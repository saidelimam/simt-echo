import { describe, expect, it } from "vitest";
import { app } from "./index";
import { emitJobEvent, jobEventStream, readJobEvents } from "./jobs";
import type { JobEvent } from "./types";

// GET /v1/jobs/:id/events (SSE). FakeR2/FakeInstance mirror jobs.test.ts
// (duplicated per file, the established pattern).

const TOKEN = "test-token-123";
const auth = { Authorization: `Bearer ${TOKEN}` };

class FakeR2 {
  store = new Map<string, string>();
  async put(key: string, value: string): Promise<null> {
    this.store.set(key, value);
    return null;
  }
  async get(key: string) {
    const body = this.store.get(key);
    if (body === undefined) return null;
    return { json: async () => JSON.parse(body) as unknown };
  }
  async list({ prefix }: { prefix?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const };
  }
}

type Status = "queued" | "running" | "complete" | "errored" | "terminated";

class FakeInstance {
  constructor(public statusValue: Status) {}
  async status() {
    return { status: this.statusValue };
  }
}

class FakePipeline {
  instances = new Map<string, FakeInstance>();
  async get(id: string) {
    const inst = this.instances.get(id);
    if (!inst) throw new Error("instance.not_found");
    return inst;
  }
}

function setup(status: Status) {
  const media = new FakeR2();
  const pipeline = new FakePipeline();
  const instance = new FakeInstance(status);
  pipeline.instances.set("job1", instance);
  const env = { ECHO_APP_TOKEN: TOKEN, MEDIA: media, PIPELINE: pipeline } as never;
  return { media, instance, env, store: { MEDIA: media } as never };
}

const stage = (id: string): JobEvent => ({ id, type: "stage", stage: "grounding", updatedAt: 1 });
const usage = (id: string): JobEvent => ({ id, type: "usage", usage: { samFrames: 2 } });

function frameIds(body: string): string[] {
  return [...body.matchAll(/^id: (.+)$/gm)].map((m) => m[1]);
}

describe("GET /v1/jobs/:id/events", () => {
  it("requires auth", async () => {
    const { env } = setup("complete");
    const res = await app.request("/v1/jobs/job1/events", {}, env);
    expect(res.status).toBe(401);
  });

  it("404s job_not_found only for a job that does not exist", async () => {
    const { env } = setup("running");
    const missing = await app.request("/v1/jobs/nope/events", { headers: auth }, env);
    expect(missing.status).toBe(404);
    expect(await missing.json()).toEqual({ ok: false, error: "job_not_found" });
  });

  it("streams a known job with no events yet instead of 404ing", async () => {
    const { env } = setup("complete");
    const res = await app.request("/v1/jobs/job1/events", { headers: auth }, env);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("");
  });

  it("streams every event then closes when the job is already terminal", async () => {
    const { env, store } = setup("complete");
    await emitJobEvent(store, "job1", stage("a-1"));
    await emitJobEvent(store, "job1", usage("a-2"));
    const res = await app.request("/v1/jobs/job1/events", { headers: auth }, env);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/event-stream");
    const body = await res.text();
    expect(frameIds(body)).toEqual(["a-1", "a-2"]);
    expect(body).toContain(`data: ${JSON.stringify(stage("a-1"))}\n\n`);
  });

  it("replays only events after Last-Event-ID (header) or ?after=", async () => {
    const { env, store } = setup("complete");
    for (const id of ["a-1", "a-2", "a-3"]) await emitJobEvent(store, "job1", stage(id));
    const byHeader = await app.request(
      "/v1/jobs/job1/events",
      { headers: { ...auth, "Last-Event-ID": "a-1" } },
      env,
    );
    expect(frameIds(await byHeader.text())).toEqual(["a-2", "a-3"]);
    const byQuery = await app.request("/v1/jobs/job1/events?after=a-2", { headers: auth }, env);
    expect(frameIds(await byQuery.text())).toEqual(["a-3"]);
  });
});

describe("jobEventStream", () => {
  const timing = { pollMs: 2, heartbeatMs: 10, maxMs: 60 };

  it("emits heartbeat comments and closes at the hold limit on a running job", async () => {
    const { instance, store } = setup("running");
    const started = Date.now();
    const body = await new Response(
      jobEventStream(store, "job1", instance as never, undefined, timing),
    ).text();
    expect(body).toContain(": heartbeat\n\n");
    expect(Date.now() - started).toBeGreaterThanOrEqual(timing.maxMs - 5);
  });

  it("follows new events and stops once the job turns terminal", async () => {
    const { instance, store } = setup("running");
    const done = new Response(
      jobEventStream(store, "job1", instance as never, undefined, { ...timing, maxMs: 5_000 }),
    ).text();
    await new Promise((r) => setTimeout(r, 10));
    await emitJobEvent(store, "job1", stage("b-1"));
    await new Promise((r) => setTimeout(r, 10));
    await emitJobEvent(store, "job1", usage("b-2"));
    instance.statusValue = "complete";
    expect(frameIds(await done)).toEqual(["b-1", "b-2"]);
    expect((await readJobEvents(store, "job1")).length).toBe(2);
  });
});
