import { describe, expect, it } from "vitest";
import { app } from "./index";
import { emitJobEvent, jobEventStream, readJobEvents } from "./jobs";
import type { JobEvent } from "./types";
import { bearer, seedDevice } from "./testkit";

// GET /v1/jobs/:id/events (SSE). FakeR2/FakeInstance mirror jobs.test.ts
// (duplicated per file, the established pattern). R9-01: the stream is bound
// to the creating install — setup writes the job's owner record.

const KEY = "dk_test_install_one";
const OTHER = "dk_test_install_two";
const auth = bearer(KEY);

class FakeR2 {
  store = new Map<string, string>();
  async put(key: string, value: string | Uint8Array | ArrayBuffer): Promise<null> {
    this.store.set(
      key,
      typeof value === "string" ? value : new TextDecoder().decode(new Uint8Array(value)),
    );
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

async function setup(status: Status) {
  const media = new FakeR2();
  const pipeline = new FakePipeline();
  const instance = new FakeInstance(status);
  pipeline.instances.set("job1", instance);
  // R9-01: seed the test install and bind job1 to it.
  const id = await seedDevice(media, KEY);
  await media.put("jobs/job1/owner.json", JSON.stringify({ owner: id }));
  const env = { ECHO_APP_TOKEN: "retired", MEDIA: media, PIPELINE: pipeline } as never;
  return { media, instance, env, store: { MEDIA: media } as never };
}

const stage = (id: string): JobEvent => ({ id, type: "stage", stage: "grounding", updatedAt: 1 });
const usage = (id: string): JobEvent => ({ id, type: "usage", usage: { samFrames: 2 } });

function frameIds(body: string): string[] {
  return [...body.matchAll(/^id: (.+)$/gm)].map((m) => m[1]);
}

describe("GET /v1/jobs/:id/events", () => {
  it("requires auth", async () => {
    const { env } = await setup("complete");
    const res = await app.request("/v1/jobs/job1/events", {}, env);
    expect(res.status).toBe(401);
  });

  it("404s job_not_found only for a job that does not exist", async () => {
    const { env } = await setup("running");
    const missing = await app.request("/v1/jobs/nope/events", { headers: auth }, env);
    expect(missing.status).toBe(404);
    expect(await missing.json()).toEqual({ ok: false, error: "job_not_found" });
  });

  it("streams a known job with no events yet instead of 404ing", async () => {
    const { env } = await setup("complete");
    const res = await app.request("/v1/jobs/job1/events", { headers: auth }, env);
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("");
  });

  it("streams every event then closes when the job is already terminal", async () => {
    const { env, store } = await setup("complete");
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
    const { env, store } = await setup("complete");
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

  it("404s a job owned by another install (R9-01)", async () => {
    const { media, env } = await setup("running");
    await seedDevice(media, OTHER);
    const res = await app.request("/v1/jobs/job1/events", { headers: bearer(OTHER) }, env);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ ok: false, error: "job_not_found" });
  });
});

describe("jobEventStream", () => {
  const timing = { pollMs: 2, heartbeatMs: 10, maxMs: 60 };

  it("emits heartbeat comments and closes at the hold limit on a running job", async () => {
    const { instance, store } = await setup("running");
    const started = Date.now();
    const body = await new Response(
      jobEventStream(store, "job1", instance as never, undefined, timing),
    ).text();
    expect(body).toContain(": heartbeat\n\n");
    expect(Date.now() - started).toBeGreaterThanOrEqual(timing.maxMs - 5);
  });

  it("follows new events and stops once the job turns terminal", async () => {
    const { instance, store } = await setup("running");
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
