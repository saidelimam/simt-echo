// The Muse Spark whole-job scout (W3-09) and its SSE fetch helpers, split
// from scout.test.ts to stay under the fileLines ceiling. The setup block
// duplicates scout.test.ts (per-file fakes, the established pattern).
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SPARK_JSON_SCHEMA,
  SPARK_MODEL,
  SYSTEM,
  ScoutError,
  metaSparkScout,
  runScout,
  sparkContext,
} from "./scout";

// W3-04 test delta: the Workers AI scout chain. Fakes mirror the repo's
// per-file precedent (jobs.test.ts): in-memory R2 plus a scripted AI binding.

// In-memory R2 stand-in: the surface scout.ts touches (get/put).
class FakeR2 {
  store = new Map<string, ArrayBuffer>();
  /** Every key read, so a test can prove refused bytes are never touched. */
  gets: string[] = [];

  async put(key: string, value: ArrayBuffer | string): Promise<null> {
    const body =
      typeof value === "string" ? new TextEncoder().encode(value).buffer as ArrayBuffer : value;
    this.store.set(key, body);
    return null;
  }

  async get(key: string) {
    this.gets.push(key);
    const entry = this.store.get(key);
    if (!entry) return null;
    return {
      arrayBuffer: async () => entry.slice(0),
      json: async <T,>() => JSON.parse(new TextDecoder().decode(entry)) as T,
      text: async () => new TextDecoder().decode(entry),
    };
  }
}

interface RunCall {
  model: string;
  input: Record<string, unknown>;
  options: unknown;
}

// Scripted Workers AI binding: one behavior per model id.
class FakeAI {
  calls: RunCall[] = [];
  constructor(private readonly behaviors: Record<string, (call: RunCall) => Promise<unknown> | unknown>) {}

  async run(model: string, input: Record<string, unknown>, options?: unknown): Promise<unknown> {
    const call = { model, input, options };
    this.calls.push(call);
    const behavior = this.behaviors[model];
    if (!behavior) throw new Error(`model not found ${model}`);
    return behavior(call);
  }
}

const LLAMA = "@cf/meta/llama-4-scout-17b-16e-instruct";
const GEMMA = "@cf/google/gemma-4-26b-a4b-it";

function validManifest() {
  return {
    schema: 1,
    draftId: "draft-1",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1762000000000 },
    routeContext: "Indoor walk, 62 m, 3 turns, 1 level change",
    checkpoints: [
      {
        nodeId: "n2",
        type: "TURN",
        turn: "LEFT",
        atDistanceM: 23.5,
        tMs: 41250,
        heroKeyframeId: "kf_n2_1",
        keyframes: [
          { id: "kf_n2_0", tMs: 39250 },
          { id: "kf_n2_1", tMs: 40750 },
        ],
        approach: [{ tMs: 37250, d: 18.9 }],
      },
    ],
  } as const;
}

function envFor(media: FakeR2, ai?: FakeAI, extra: Record<string, unknown> = {}) {
  return {
    ECHO_APP_TOKEN: "test-token-123",
    MEDIA: media as never,
    SCOUT_ORDER: "workers-ai,meta-spark",
    AI_GATEWAY_ID: "echo-test",
    WORKERS_AI_MODELS: `${LLAMA},${GEMMA}`,
    ...(ai ? { AI: ai as never } : {}),
    ...extra,
  } as never;
}

function seedMedia(media: FakeR2, jobId: string) {
  for (const id of ["kf_n2_0", "kf_n2_1"]) {
    void media.put(`jobs/${jobId}/in/${id}`, new Uint8Array([id.length, 2, 3]).buffer as ArrayBuffer);
  }
}

function candidate(overrides: Record<string, unknown> = {}) {
  return {
    title: "Red pillar",
    samPhrase: "red pillar",
    description: "A red structural pillar beside the corridor.",
    imageId: "kf_n2_1",
    box: [100, 100, 400, 500],
    distinctiveness: 0.9,
    permanence: 1,
    signText: null,
    ambiguous: false,
    privacyRisk: false,
    cue: "Turn left at the red pillar",
    ...overrides,
  };
}

function checkpointResult(nodeId = "n2", candidates: unknown[] = [candidate()]) {
  return { nodeId, candidates };
}

// W3-09 test delta: the Muse Spark whole-job scout. The fetch stub mirrors
// ground.test.ts's SSE shape; bodies are asserted against the §7.1 verbatim
// request instead of re-stating it here.
function sseSpark(text: string): Response {
  const events = `data: ${JSON.stringify({ type: "response.output_text.delta", delta: text })}\n\ndata: [DONE]\n\n`;
  return new Response(events, { status: 200 });
}

function stubSparkFetch(handler: (body: Record<string, unknown>) => Response | Promise<Response>) {
  const calls: Record<string, unknown>[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: { body: string }) => {
    const body = JSON.parse(init.body) as Record<string, unknown>;
    calls.push(body);
    return handler(body);
  });
  return calls;
}

function sparkAnswer(nodeId = "n2", candidates: unknown[] = [candidate()]) {
  return { checkpoints: [{ nodeId, candidates }] };
}

describe("metaSparkScout (W3-09)", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("proposes the whole job in one call with SYSTEM/SCHEMA verbatim", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const calls = stubSparkFetch(() => sseSpark(JSON.stringify(sparkAnswer())));

    const result = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    );

    expect(calls).toHaveLength(1);
    expect(result.model).toBe(SPARK_MODEL);
    expect(result.checkpoints).toHaveLength(1);
    expect(result.checkpoints[0].candidates).toHaveLength(1);
    const body = calls[0] as {
      model: string;
      reasoning: { effort: string };
      input: Array<{ role: string; content: unknown[] }>;
      text: { format: { name: string; schema: unknown } };
    };
    expect(body.model).toBe("muse-spark-1.3");
    expect(body.reasoning).toEqual({ effort: "low" });
    expect(body.input[0].content).toEqual([{ type: "input_text", text: SYSTEM }]);
    expect(body.text.format.name).toBe("echo_landmarks");
    expect(body.text.format.schema).toEqual(SPARK_JSON_SCHEMA);
    // One CONTEXT text plus an imageId marker per sent image.
    const userContent = body.input[1].content as Array<{ type: string; text?: string }>;
    expect(userContent[0].type).toBe("input_text");
    expect(userContent.filter((c) => c.type === "input_image")).toHaveLength(2);
    expect(userContent.filter((c) => c.text === "imageId=kf_n2_0")).toHaveLength(1);

    // Compact evidence sealed like the per-checkpoint path.
    const sealed = JSON.parse(new TextDecoder().decode(media.store.get(`jobs/${jobId}/scout.json`)!));
    expect(sealed.model).toBe(SPARK_MODEL);
    const raw = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/scout/raw/spark.0.json`)!),
    );
    expect(raw.model).toBe(SPARK_MODEL);
  });

  it("cleans Spark output like W3-04: drops unknown images, clamps, keeps top 2", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    stubSparkFetch(() =>
      sseSpark(
        JSON.stringify(
          sparkAnswer("n2", [
            candidate({ imageId: "kf_evil", distinctiveness: 1, permanence: 1 }),
            candidate({ imageId: "kf_n2_0", distinctiveness: 0.5, permanence: 0.5, box: [900, 900, 100, 100] }),
            candidate({ imageId: "kf_n2_1", distinctiveness: 0.9, permanence: 1, box: [-50, -10, 1200, 2000] }),
            candidate({ imageId: "kf_n2_1", distinctiveness: 0.1, permanence: 0.1 }),
          ]),
        ),
      ),
    );

    const result = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    );

    const kept = result.checkpoints[0].candidates;
    expect(kept).toHaveLength(2);
    expect(kept[0].imageId).toBe("kf_n2_1");
    expect(kept[0].box).toEqual([0, 0, 1000, 1000]);
    expect(kept[1].box).toEqual([100, 100, 900, 900]);
  });

  it("collects done-only output — the shape the real API sends", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    // No delta events at all: SAM/Spark answer in response.output_text.done
    // (live-verified, review 0003 R5). The old delta-only stub hid this.
    vi.stubGlobal("fetch", async () => {
      const events =
        `data: ${JSON.stringify({ type: "response.output_text.done", text: JSON.stringify(sparkAnswer()) })}\n\n` +
        "data: [DONE]\n\n";
      return new Response(events, { status: 200 });
    });

    const result = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    );

    expect(result.model).toBe(SPARK_MODEL);
    expect(result.checkpoints[0].candidates).toHaveLength(1);
  });

  it("never lets one checkpoint box another checkpoint's keyframe", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    await media.put(`jobs/${jobId}/in/kf_n3_0`, new Uint8Array([4, 2, 3]).buffer as ArrayBuffer);
    const manifest = structuredClone(validManifest()) as unknown as { checkpoints: unknown[] };
    manifest.checkpoints.push({
      nodeId: "n3",
      type: "DESTINATION",
      atDistanceM: 45,
      tMs: 60000,
      heroKeyframeId: "kf_n3_0",
      keyframes: [{ id: "kf_n3_0", tMs: 59500 }],
      approach: [],
    });
    stubSparkFetch(() =>
      sseSpark(
        JSON.stringify({
          checkpoints: [
            // kf_n3_0 was sent, but for n3 — it must not survive on n2.
            { nodeId: "n2", candidates: [candidate({ imageId: "kf_n3_0", distinctiveness: 1, permanence: 1 })] },
            { nodeId: "n3", candidates: [candidate({ imageId: "kf_n3_0" })] },
          ],
        }),
      ),
    );

    const result = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      manifest as never,
    );

    expect(result.checkpoints[0].candidates).toEqual([]);
    expect(result.checkpoints[1].candidates).toHaveLength(1);
  });

  it("a retryable Meta 503 reaches the caller retryable, not as a typed failure", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    vi.stubGlobal("fetch", async () => new Response("busy", { status: 503 }));
    const env = envFor(media, undefined, { META_API_KEY: "k", SCOUT_ORDER: "meta-spark" });

    const err = await runScout(env, jobId, structuredClone(validManifest()) as never).catch((e) => e);
    expect(err).not.toBeInstanceOf(ScoutError);
    expect(String((err as Error).message)).toContain("retryable meta 503");
  });

  it("retries once with medium effort on JSON parse failure", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    let n = 0;
    const calls = stubSparkFetch(() => {
      n++;
      return sseSpark(n === 1 ? "not json at all" : JSON.stringify(sparkAnswer()));
    });

    const result = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    );

    expect(result.checkpoints[0].candidates).toHaveLength(1);
    expect(calls).toHaveLength(2);
    expect((calls[1] as { reasoning: { effort: string } }).reasoning).toEqual({ effort: "medium" });
  });

  it("fails typed when Spark output is schema-invalid", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    stubSparkFetch(() => sseSpark(JSON.stringify(sparkAnswer("n2", [candidate({ distinctiveness: 2 })]))));

    const err = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    ).catch((e) => e);
    expect(err).toBeInstanceOf(ScoutError);
    expect((err as ScoutError).code).toBe("all_models_failed");
    expect(media.store.has(`jobs/${jobId}/scout.json`)).toBe(false);
  });

  it("fails with media_missing when no keyframe bytes exist", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    stubSparkFetch(() => sseSpark(JSON.stringify(sparkAnswer())));

    const err = await metaSparkScout(
      envFor(media, undefined, { META_API_KEY: "k" }),
      jobId,
      structuredClone(validManifest()) as never,
    ).catch((e) => e);
    expect(err).toBeInstanceOf(ScoutError);
    expect((err as ScoutError).code).toBe("media_missing");
  });

  it("runs first with a key and SCOUT_ORDER=meta-spark,workers-ai", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult("n2", []) }) });
    const fetches = stubSparkFetch(() => sseSpark(JSON.stringify(sparkAnswer())));
    const env = envFor(media, ai, { SCOUT_ORDER: "meta-spark,workers-ai", META_API_KEY: "k" });

    const result = await runScout(env, jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(SPARK_MODEL);
    expect(fetches).toHaveLength(1);
    expect(ai.calls).toHaveLength(0);
  });

  it("falls back to Workers AI when Spark fails but the key is set", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult() }) });
    stubSparkFetch(() => sseSpark("garbage {{{"));
    const env = envFor(media, ai, { SCOUT_ORDER: "meta-spark,workers-ai", META_API_KEY: "k" });

    const result = await runScout(env, jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(LLAMA);
  });

  it("without a key nothing changes: meta-spark is skipped", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult() }) });
    const fetches = stubSparkFetch(() => sseSpark(JSON.stringify(sparkAnswer())));
    const env = envFor(media, ai, { SCOUT_ORDER: "meta-spark,workers-ai" });

    const result = await runScout(env, jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(LLAMA);
    expect(fetches).toHaveLength(0);
  });

  it("builds the whole-job CONTEXT from sensor-measured directions", () => {
    const cp = validManifest().checkpoints[0] as never;
    const text = sparkContext("Indoor walk", [
      { cp, loaded: [{ id: "kf_n2_0", tMs: 39250 }] },
    ]);
    expect(text).toContain("Route: Indoor walk. Checkpoints in walking order:");
    expect(text).toContain("TURN LEFT at 23.5 m (sensor-measured)");
    expect(text).toContain("kf_n2_0 (2.0 s before)");
  });
});
