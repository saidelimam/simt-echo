import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkflowStep as RuntimeWorkflowStep } from "cloudflare:workers";
import { app } from "./index";
import { EchoPipeline } from "./pipeline";
import {
  CHECKPOINT_JSON_SCHEMA,
  SPARK_JSON_SCHEMA,
  SPARK_MODEL,
  SYSTEM,
  ScoutError,
  checkpointContext,
  cleanCandidates,
  metaSparkScout,
  runScout,
  sparkContext,
  type ScoutCheckpoint,
} from "./scout";
import type { Manifest } from "./types";

// W3-04 test delta: the Workers AI scout chain. Fakes mirror the repo's
// per-file precedent (jobs.test.ts): in-memory R2 plus a scripted AI binding.

// In-memory R2 stand-in: the surface scout.ts touches (get/put).
class FakeR2 {
  store = new Map<string, ArrayBuffer>();

  async put(key: string, value: ArrayBuffer | string): Promise<null> {
    const body =
      typeof value === "string" ? new TextEncoder().encode(value).buffer as ArrayBuffer : value;
    this.store.set(key, body);
    return null;
  }

  async get(key: string) {
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

describe("runScout validation", () => {
  it("cleans model output: drops unknown images, clamps boxes, keeps top 2 by d·p", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      [LLAMA]: () => ({
        response: checkpointResult("n2", [
          candidate({ imageId: "kf_n2_0", distinctiveness: 0.5, permanence: 0.5, box: [900, 900, 100, 100] }),
          candidate({ imageId: "kf_evil", distinctiveness: 1, permanence: 1 }),
          candidate({ imageId: "kf_n2_1", distinctiveness: 0.9, permanence: 1, box: [-50, -10, 1200, 2000] }),
          candidate({ imageId: "kf_n2_1", distinctiveness: 0.1, permanence: 0.1 }),
        ]),
      }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(LLAMA);
    expect(result.checkpoints).toHaveLength(1);
    const kept = result.checkpoints[0].candidates;
    // Unknown image dropped, lowest score trimmed: top 2 of the 3 known remain.
    expect(kept).toHaveLength(2);
    expect(kept.map((c) => c.title)).toEqual(["Red pillar", "Red pillar"]);
    expect(kept[0].imageId).toBe("kf_n2_1");
    expect(kept[0].box).toEqual([0, 0, 1000, 1000]);
    expect(kept[1].imageId).toBe("kf_n2_0");
    // Inverted edges fixed to x1<x2, y1<y2.
    expect(kept[1].box).toEqual([100, 100, 900, 900]);

    // Compact evidence sealed for W3-06; raw output kept for audit.
    const sealed = JSON.parse(new TextDecoder().decode(media.store.get(`jobs/${jobId}/scout.json`)!));
    expect(sealed.model).toBe(LLAMA);
    expect(sealed.checkpoints[0].candidates).toHaveLength(2);
    const raw = JSON.parse(
      new TextDecoder().decode(media.store.get(`jobs/${jobId}/scout/raw/n2.0.json`)!),
    );
    expect(raw.model).toBe(LLAMA);
    expect(raw.raw.candidates).toHaveLength(4);
  });

  it("accepts a JSON-string response as well as an object response", async () => {
    for (const response of [
      JSON.stringify(checkpointResult()),
      checkpointResult(),
    ]) {
      const media = new FakeR2();
      const jobId = "job-1";
      seedMedia(media, jobId);
      const ai = new FakeAI({ [LLAMA]: () => ({ response }) });
      const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);
      expect(result.checkpoints[0].candidates).toHaveLength(1);
    }
  });

  it("falls back to Gemma when Llama throws, recording the model that ran", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      [LLAMA]: () => {
        throw new Error("retryable 500");
      },
      [GEMMA]: () => ({ response: checkpointResult() }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(GEMMA);
    expect(ai.calls.filter((c) => c.model === LLAMA)).toHaveLength(1);
    expect(ai.calls.filter((c) => c.model === GEMMA)).toHaveLength(1);
  });

  it("falls back to Gemma when Llama returns schema-invalid output", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      // distinctiveness out of the 0–1 range: Zod rejects this answer.
      [LLAMA]: () => ({ response: checkpointResult("n2", [candidate({ distinctiveness: 2 })]) }),
      [GEMMA]: () => ({ response: checkpointResult() }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(GEMMA);
    expect(ai.calls.filter((c) => c.model === LLAMA)).toHaveLength(1);
    expect(ai.calls.filter((c) => c.model === GEMMA)).toHaveLength(1);
  });

  it("falls back to Gemma when Llama answers for the wrong checkpoint", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      [LLAMA]: () => ({ response: checkpointResult("n99") }),
      [GEMMA]: () => ({ response: checkpointResult("n2") }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(GEMMA);
    expect(result.checkpoints[0].nodeId).toBe("n2");
    expect(ai.calls.filter((c) => c.model === GEMMA)).toHaveLength(1);
  });

  it("does not retry the same model without schema after a parse failure", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      // Free text mentioning "schema": a parse failure, not a binding
      // rejection of response_format — no same-model retry is owed.
      [LLAMA]: () => ({ response: "this is not the schema you wanted" }),
      [GEMMA]: () => ({ response: checkpointResult() }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(GEMMA);
    expect(ai.calls.filter((c) => c.model === LLAMA)).toHaveLength(1);
  });

  it("falls back to Gemma when Llama hangs past the model timeout", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      [LLAMA]: () => new Promise(() => {}),
      [GEMMA]: () => ({ response: checkpointResult() }),
    });
    const env = envFor(media, ai, { SCOUT_MODEL_TIMEOUT_MS: "20" });

    const result = await runScout(env, jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(GEMMA);
  });

  it("drops candidates naming a keyframe whose bytes never loaded", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    // Only kf_n2_0 is uploaded; kf_n2_1 is a manifest ref the model was
    // never sent and must not be able to box.
    await media.put(`jobs/${jobId}/in/kf_n2_0`, new Uint8Array([1, 2, 3]).buffer as ArrayBuffer);
    const ai = new FakeAI({
      [LLAMA]: () => ({
        response: checkpointResult("n2", [
          candidate({ imageId: "kf_n2_1", distinctiveness: 1, permanence: 1 }),
          candidate({ imageId: "kf_n2_0", distinctiveness: 0.5, permanence: 0.5 }),
        ]),
      }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.checkpoints[0].candidates).toHaveLength(1);
    expect(result.checkpoints[0].candidates[0].imageId).toBe("kf_n2_0");
  });

  it("retries once without response_format when the model rejects it", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    let llamaCalls = 0;
    const ai = new FakeAI({
      [LLAMA]: (call) => {
        llamaCalls++;
        const input = call.input as { response_format?: unknown };
        if (input.response_format) throw new Error("400: response_format json_schema unsupported");
        return { response: checkpointResult() };
      },
      [GEMMA]: () => ({ response: checkpointResult() }),
    });

    const result = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(result.model).toBe(LLAMA);
    expect(llamaCalls).toBe(2);
    const retry = ai.calls[1].input as { messages: Array<{ role: string; content: unknown }> };
    expect("response_format" in ai.calls[1].input).toBe(false);
    const userContent = retry.messages[1].content as Array<{ text?: string }>;
    expect(userContent[0].text).toContain("Return only JSON");
    // The first message still carries the shared SYSTEM prompt.
    expect((retry.messages[0] as { content: unknown }).content).toBe(SYSTEM);
  });

  it("sends ≤ 3 images through the AI Gateway with the strict schema", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult() }) });

    await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never);

    expect(ai.calls).toHaveLength(1);
    const call = ai.calls[0];
    expect(call.options).toEqual({ gateway: { id: "echo-test" } });
    const input = call.input as {
      messages: Array<{ role: string; content: unknown[] }>;
      response_format: { type: string; json_schema: unknown };
      max_tokens: number;
    };
    expect(input.response_format).toEqual({ type: "json_schema", json_schema: CHECKPOINT_JSON_SCHEMA });
    expect(input.max_tokens).toBe(1500);
    const userContent = input.messages[1].content;
    const images = userContent.filter(
      (c) => typeof c === "object" && c !== null && "type" in c && c.type === "image_url",
    );
    expect(images).toHaveLength(2);
  });
});

describe("runScout typed failures", () => {
  it("fails closed with no_provider when the order has no enabled provider", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    // meta-spark without a key is skipped (W3-09: key-gated).
    const env = envFor(media, undefined, { SCOUT_ORDER: "meta-spark" });

    const err = await runScout(env, jobId, structuredClone(validManifest()) as never).catch((e) => e);
    expect(err).toBeInstanceOf(ScoutError);
    expect((err as ScoutError).code).toBe("no_provider");
  });

  it("fails closed with no_provider when the AI binding is absent", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);

    const err = await runScout(envFor(media), jobId, structuredClone(validManifest()) as never).catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(ScoutError);
    expect((err as ScoutError).code).toBe("no_provider");
  });

  it("returns a typed failure — never raw model text — when every model fails", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({
      // Free text with no `response` field: must not leak through.
      [LLAMA]: () => ({ response: "Here are some landmarks I noticed while walking…" }),
      [GEMMA]: () => {
        throw new Error("retryable 429");
      },
    });

    const err = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never).catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(ScoutError);
    // R9-04: the checkpoint step wraps the deterministic all_models_failed
    // in a NonRetryableError, so the chain's final error is a no_provider
    // ScoutError carrying the all_models_failed message. Raw model text
    // still never leaks.
    expect((err as ScoutError).code).toBe("no_provider");
    expect((err as ScoutError).message).toContain("all_models_failed");
    expect((err as ScoutError).message).not.toContain("landmarks I noticed");
    expect(media.store.has(`jobs/${jobId}/scout.json`)).toBe(false);
  });

  it("fails with media_missing when no keyframe bytes exist in R2", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult() }) });

    const err = await runScout(envFor(media, ai), jobId, structuredClone(validManifest()) as never).catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(ScoutError);
    expect((err as ScoutError).code).toBe("media_missing");
  });

  it("skips unregistered providers in SCOUT_ORDER and uses the next one", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedMedia(media, jobId);
    const ai = new FakeAI({ [LLAMA]: () => ({ response: checkpointResult() }) });
    const env = envFor(media, ai, { SCOUT_ORDER: "meta-spark,workers-ai" });

    const result = await runScout(env, jobId, structuredClone(validManifest()) as never);
    expect(result.model).toBe(LLAMA);
  });
});

// R9-04 test delta (review 0009, F3): per-checkpoint steps with persisted
// results. A PersistentStep stands in for the Workflow's durable step
// state: a step that completed once returns its recorded output on every
// later call; a failed step is re-run — the Workflow persists only
// successful step outputs. Two sequential runScout calls on the same
// instance therefore model a run and its replay.
class PersistentStep {
  names: string[] = [];
  private readonly state = new Map<string, unknown>();

  async do<T>(name: string, fn: () => Promise<T>): Promise<T> {
    this.names.push(name);
    const recorded = this.state.get(name);
    if (recorded !== undefined) {
      return recorded as T;
    }
    const result = await fn();
    this.state.set(name, result);
    return result;
  }
}

/** The checkpoint a scripted model call was made for, from its prompt's
 * images line (`Images: kf_n2_0 (...), ...`): one call, one checkpoint. */
function promptNodeId(call: RunCall): string {
  const content = (call.input as { messages: Array<{ content: Array<{ text?: string }> }> }).messages[
    1
  ].content as Array<{ text?: string }>;
  const text = content.find((c) => c.text?.includes("Images:"))?.text ?? "";
  return text.match(/kf_(n\d+)/)?.[1] ?? "";
}

function checkpointResultFor(nodeId: string, imageId: string) {
  return checkpointResult(nodeId, [candidate({ imageId })]);
}

function threeCheckpointManifest(): Manifest {
  const m = structuredClone(validManifest()) as never as Manifest;
  for (const [i, node] of ["n3", "n4"].entries()) {
    const cp = structuredClone(m.checkpoints[0]);
    cp.nodeId = node;
    cp.heroKeyframeId = `kf_${node}_1`;
    cp.keyframes = [
      { id: `kf_${node}_0`, tMs: 41250 + i * 20000 },
      { id: `kf_${node}_1`, tMs: 42750 + i * 20000 },
    ];
    m.checkpoints.push(cp);
  }
  return m;
}

function seedThree(media: FakeR2, jobId: string) {
  for (const node of ["n2", "n3", "n4"]) {
    for (const k of [0, 1]) {
      void media.put(
        `jobs/${jobId}/in/kf_${node}_${k}`,
        new Uint8Array([1, 2, 3]).buffer as ArrayBuffer,
      );
    }
  }
}

describe("runScout per-checkpoint steps (R9-04)", () => {
  it("re-bills only the failed checkpoint on replay; completed ones come from recorded output", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedThree(media, jobId);
    const step = new PersistentStep();
    const ai = new FakeAI({
      [LLAMA]: (call) => {
        const nodeId = promptNodeId(call);
        // n4's every model fails: a deterministic all_models_failed the
        // step wraps non-retryable, so the step itself is not retried.
        if (nodeId === "n4") throw new Error("boom");
        return { response: checkpointResultFor(nodeId, `kf_${nodeId}_1`) };
      },
    });
    const env = envFor(media, ai, { SCOUT_ORDER: "workers-ai" });

    // Run: n2 and n3 succeed; n4 fails and the scout fails with it.
    const err = await runScout(env, jobId, threeCheckpointManifest(), step.do.bind(step)).catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(ScoutError);
    // R9-04: the step's NonRetryableError is swallowed by the chain into a
    // no_provider ScoutError carrying the all_models_failed message.
    expect((err as ScoutError).code).toBe("no_provider");
    expect((err as ScoutError).message).toContain("all_models_failed");

    // Replay: n2/n3 return their recorded outputs (no model calls), n4
    // re-runs its step and fails again.
    const err2 = await runScout(env, jobId, threeCheckpointManifest(), step.do.bind(step)).catch(
      (e) => e,
    );
    expect(err2).toBeInstanceOf(ScoutError);
    expect((err2 as ScoutError).code).toBe("no_provider");

    // n4's step ran twice; n2/n3 exactly once each — their paid calls were
    // not re-billed on the replay.
    expect(step.names.filter((n) => n === "scout:workers-ai:n4")).toHaveLength(2);
    const byNode = new Map<string, number>([["n2", 0], ["n3", 0], ["n4", 0]]);
    for (const c of ai.calls.filter((c) => c.model === LLAMA)) {
      const n = promptNodeId(c);
      byNode.set(n, (byNode.get(n) ?? 0) + 1);
    }
    expect(byNode.get("n2")).toBe(1);
    expect(byNode.get("n3")).toBe(1);
    expect(byNode.get("n4")).toBe(2);
  });

  it("replays a successful run with zero model calls and the same result", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    seedThree(media, jobId);
    const step = new PersistentStep();
    const ai = new FakeAI({
      [LLAMA]: (call) => {
        const nodeId = promptNodeId(call);
        return { response: checkpointResultFor(nodeId, `kf_${nodeId}_1`) };
      },
    });
    const env = envFor(media, ai, { SCOUT_ORDER: "workers-ai" });

    const first = await runScout(env, jobId, threeCheckpointManifest(), step.do.bind(step));
    const second = await runScout(env, jobId, threeCheckpointManifest(), step.do.bind(step));

    // The replay re-runs no paid call and returns the same result. The
    // same three step names are entered again (replay) — each returning its
    // recorded output.
    expect(ai.calls).toHaveLength(3);
    expect(second).toEqual(first);
    expect(step.names).toEqual([
      "scout:workers-ai:n2",
      "scout:workers-ai:n3",
      "scout:workers-ai:n4",
      "scout:workers-ai:n2",
      "scout:workers-ai:n3",
      "scout:workers-ai:n4",
    ]);
  });
});

describe("scout helpers", () => {
  it("builds checkpoint context from sensor-measured direction and image timing", () => {
    const cp = validManifest().checkpoints[0] as never;
    const text = checkpointContext("Indoor walk", cp, [
      { id: "kf_n2_0", tMs: 39250 },
      { id: "kf_n2_1", tMs: 40750 },
    ]);
    expect(text).toContain("TURN LEFT at 23.5 m (sensor-measured)");
    expect(text).toContain("kf_n2_0 (2.0 s before)");
    expect(text).toContain("kf_n2_1 (0.5 s before)");
  });

  it("cleanCandidates keeps the top pair and never invents image ids", () => {
    const raw = {
      nodeId: "n2",
      candidates: [
        candidate({ imageId: "kf_n2_0", distinctiveness: 0.2, permanence: 0.2 }),
        candidate({ imageId: "kf_n2_1", distinctiveness: 0.9, permanence: 0.9 }),
        candidate({ imageId: "kf_ghost", distinctiveness: 1, permanence: 1 }),
      ],
    } as unknown as ScoutCheckpoint;
    const kept = cleanCandidates(raw, new Set(["kf_n2_0", "kf_n2_1"]));
    expect(kept.map((c) => c.imageId)).toEqual(["kf_n2_1", "kf_n2_0"]);
  });
});

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

describe("GET /v1/health providers", () => {
  it("reports workersAi true with the AI binding, metaKey from the key", async () => {
    const withAi = await app.request(
      "/v1/health",
      {},
      envFor(new FakeR2(), new FakeAI({}), { META_API_KEY: "k" }) as never,
    );
    expect(await withAi.json()).toEqual({
      ok: true,
      version: "0.1.0",
      metaKeyConfigured: true,
      providers: { workersAi: true, metaKey: true },
    });

    const bare = await app.request("/v1/health");
    expect(await bare.json()).toEqual({
      ok: true,
      version: "0.1.0",
      metaKeyConfigured: false,
      providers: { workersAi: false, metaKey: false },
    });
  });
});

describe("EchoPipeline scout step", () => {
  it("records a typed scout failure to error.json instead of free text", async () => {
    const media = new FakeR2();
    const jobId = "job-1";
    await media.put(`jobs/${jobId}/manifest.json`, JSON.stringify(validManifest()));
    // No AI binding: no provider can run, so the run fails typed.
    const env = envFor(media) as import("./index").Env;
    const pipeline = new EchoPipeline({} as ExecutionContext, env);
    const step = {
      do: async (_name: string, _config: unknown, fn: () => Promise<unknown>) => fn(),
    } as unknown as RuntimeWorkflowStep;

    await expect(
      pipeline.run(
        { payload: { jobId }, timestamp: new Date(), instanceId: jobId, workflowName: "echo-pipeline" },
        step,
      ),
    ).rejects.toThrow(/no_provider/);
    const progress = JSON.parse(new TextDecoder().decode(media.store.get(`jobs/${jobId}/progress.json`)!));
    expect(progress.stage).toBe("errored");
    const detail = JSON.parse(new TextDecoder().decode(media.store.get(`jobs/${jobId}/error.json`)!));
    expect(detail.error).toMatch(/no_provider/);
  });
});
