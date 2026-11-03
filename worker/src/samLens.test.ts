// W11-06 test delta: SAM lens — per-call steps, cache, ladder, governor,
// aborts, events, replay safety. Split from ground.test.ts to keep both
// files under the repo line limit; the fixtures below mirror its per-file
// FakeR2/stubMeta precedent.

import { afterEach, describe, expect, it, vi } from "vitest";
import { inlineRunner, runGround, type StepRunner } from "./ground";
import { phraseHash } from "./samLens";
import { readJobEvents } from "./jobs";
import { ManifestSchema } from "./types";

class FakeR2 {
  store = new Map<string, ArrayBuffer | string>();
  async put(key: string, value: ArrayBuffer | string) {
    this.store.set(key, value);
    return null;
  }
  async get(key: string) {
    const entry = this.store.get(key);
    if (entry === undefined) return null;
    const text = typeof entry === "string" ? entry : new TextDecoder().decode(entry);
    // R10-10: the lens reloads each step's media inside its callback — serve
    // the stored bytes as a body stream, mirroring R2.
    const bytes = typeof entry === "string" ? new TextEncoder().encode(entry) : new Uint8Array(entry);
    return {
      body: new Response(bytes).body,
      arrayBuffer: async () => bytes.buffer as ArrayBuffer,
      json: async <T,>() => JSON.parse(text) as T,
      text: async () => text,
    };
  }
  async head(key: string) {
    const entry = this.store.get(key);
    if (entry === undefined) return null;
    return { size: typeof entry === "string" ? new TextEncoder().encode(entry).length : entry.byteLength };
  }
  async list({ prefix }: { prefix?: string; cursor?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const, delimitedPrefixes: [] };
  }
  async delete(keys: string | string[]): Promise<void> {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.store.delete(k);
  }
}

// A one_bit mask over a 4x3 raster (2x2 block), encoded with the parser's
// own conformance encoder; the hero line carries it in the real wire shape.
const MASK_TOKEN = `<|mask;x=0;y=0;data=3,4,!!!!!'IUK/e!!|>`;

function sseResponse(text: string, status = 200): Response {
  const events = text
    ? `data: ${JSON.stringify({ type: "response.output_text.delta", delta: text })}\n\n`
    : "";
  return new Response(`${events}data: [DONE]\n\n`, { status });
}

/** Route stubbed Meta calls by input kind; `video`/`image` are SAM texts. */
function stubMeta(video: string | Response, image: string | Response) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: { body: string }) => {
    const body = JSON.parse(init.body) as {
      input: Array<{ content: Array<{ type: string }> }>;
    };
    const kind = body.input[0].content.some((c) => c.type === "input_video") ? "video" : "image";
    calls.push(kind);
    const out = kind === "video" ? video : image;
    return typeof out === "string" ? sseResponse(out) : out;
  });
  return calls;
}

function scoutCandidate(overrides: Record<string, unknown> = {}) {
  return {
    title: "Red pillar",
    samPhrase: "red pillar",
    description: "d",
    imageId: "kf_n2_1",
    box: [412, 230, 590, 900],
    distinctiveness: 0.9,
    permanence: 0.95,
    signText: null,
    ambiguous: false,
    privacyRisk: false,
    cue: "Turn left at the red pillar",
    ...overrides,
  };
}

// W11-06 test delta: SAM lens — per-call steps, cache, ladder, governor,
// aborts, events. The W11-07 probe (docs/measurements.md) is TBD, so every
// scenario below is single-phrase-per-call with the fake SAM transport.

const STRIP_CLIP = {
  id: "clip_n2",
  startMs: 37250,
  endMs: 40250,
  frameCount: 12,
  durationMs: 3000,
};

function lensSetup(
  opts: {
    type?: "TURN" | "CHECKPOINT" | "DESTINATION" | "LEVEL_CHANGE";
    clip?: boolean;
    keyframeFlags?: Record<string, unknown>;
    clipFrames?: number;
  } = {},
) {
  const { type = "CHECKPOINT", clip = true, keyframeFlags = {}, clipFrames = STRIP_CLIP.frameCount } = opts;
  const r2 = new FakeR2();
  void r2.put("jobs/job1/in/clip_n2", "clip-bytes");
  void r2.put("jobs/job1/in/kf_n2_1", "hero-bytes");
  const cp = {
    nodeId: "n2",
    type,
    ...(type === "TURN" ? { turn: "LEFT" } : {}),
    atDistanceM: 23.5,
    tMs: 41250,
    heroKeyframeId: "kf_n2_1",
    keyframes: [
      { id: "kf_n2_0", tMs: 39250 },
      { id: "kf_n2_1", tMs: 40750, ...keyframeFlags },
    ],
    ...(clip ? { clip: { ...STRIP_CLIP, frameCount: clipFrames } } : {}),
    approach: [{ tMs: 37250, d: 18.9 }],
  };
  // Parsed like job intake does, so a schema that strips a field fails here.
  const manifest = ManifestSchema.parse({
    schema: 1,
    draftId: "d1",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
    routeContext: "ctx",
    checkpoints: [cp],
  });
  // No GROUND_MODE: the lens is the default path under test here.
  const env = { MEDIA: r2, GROUND_ORDER: "sam31,none", META_API_KEY: "k" } as never;
  return { r2, manifest, env };
}

function lensScout(cands = [scoutCandidate()]) {
  return {
    model: "scout-model",
    checkpoints: [{ nodeId: "n2", candidates: cands }],
  } as never;
}

const imgLine = (c: number) =>
  `<0f>1<|box;x1=300;y1=300;x2=420;y2=1100;w=720;h=1280;c=${c}|>${MASK_TOKEN}`;
const STRIP_VIDEO =
  `<12f>1<|box;x1=300;y1=300;x2=420;y2=1100;w=720;h=1280;c=0.88|>\n` +
  `<14f>1<|box;x1=300;y1=300;x2=420;y2=1100;w=720;h=1280;c=0.9|>${MASK_TOKEN}\n`;

function stepRecorder(names: string[]): StepRunner {
  return async (name, fn) => {
    names.push(name);
    return fn();
  };
}

/** SAM-call steps only: event emits and the ground.json seal are separate steps. */
const isCallStep = (name: string) => /^ground:[a-z0-9_]+:\d+:/.test(name);
const callSteps = (names: string[]) => names.filter(isCallStep);

describe("runGround SAM lens (W11-06)", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("hashes phrases deterministically for per-call step names", async () => {
    expect(await phraseHash("red pillar")).toMatch(/^[0-9a-f]{12}$/);
    expect(await phraseHash("red pillar")).toBe(await phraseHash("red pillar"));
    expect(await phraseHash("red pillar")).not.toBe(await phraseHash("green exit sign"));
  });

  it("runs one per-call step and skips video when image confidence is high", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    const calls = stubMeta("", imgLine(0.92));
    const names: string[] = [];
    const result = await runGround(env, "job1", manifest, lensScout(), stepRecorder(names));

    expect(calls).toEqual(["image"]);
    const steps = callSteps(names);
    expect(steps).toHaveLength(1);
    expect(steps[0]).toMatch(/^ground:n2:0:[0-9a-f]{12}$/);
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_GROUNDED");
    expect(c.samConfidence).toBeCloseTo(0.92);
    // Image-first ladder: single frame, no track, no visibility range.
    expect(c.trackability).toBeNull();
    expect(c.visibleFromM).toBeNull();
    expect(result.usage.samFrames).toBe(1);
    // R10-05: the vendor omitted usage, so the one sent frame stands in
    // for billed — a call that went out never reports zero.
    expect(result.usage.samFramesBilled).toBe(1);
    expect(result.usage.budgetExhausted).toBe(false);
  });

  it("escalates to strip video when image confidence is mid", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.6));
    const names: string[] = [];
    const result = await runGround(env, "job1", manifest, lensScout(), stepRecorder(names));

    expect(calls).toEqual(["image", "video"]);
    const steps = callSteps(names);
    expect(steps).toHaveLength(2);
    expect(steps[1]).toBe(`${steps[0]}:video`);
    const top = result.checkpoints[0].candidates[0];
    expect(top.source).toBe("AI_GROUNDED");
    // The strip call earned a real track and mask.
    expect(top.trackability).not.toBeNull();
    expect(top.maskSvgPath).toMatch(/^M/);
  });

  it("escalates TURN to video even at high image confidence", async () => {
    const { manifest, env } = lensSetup({ type: "TURN" });
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.95));
    const result = await runGround(env, "job1", manifest, lensScout());

    expect(calls).toEqual(["image", "video"]);
    expect(result.checkpoints[0].candidates[0].trackability).not.toBeNull();
  });

  it("keeps the image result when confidence is too low for video to help", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.3));
    const result = await runGround(env, "job1", manifest, lensScout());

    expect(calls).toEqual(["image"]);
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_GROUNDED");
    expect(c.samConfidence).toBeCloseTo(0.3);
  });

  it("records billed frames from fixture response.completed", async () => {
    const { manifest, env } = lensSetup({ type: "TURN" });
    vi.stubGlobal("fetch", async (_url: string, init: { body: string }) => {
      // Real elapsed time so the measured samMs is > 0 deterministically.
      await new Promise((r) => setTimeout(r, 5));
      const body = JSON.parse(init.body) as {
        input: Array<{ content: Array<{ type: string }> }>;
      };
      const video = body.input[0].content.some((c) => c.type === "input_video");
      const text = video ? STRIP_VIDEO : imgLine(0.95);
      const completed = video
        ? `data: ${JSON.stringify({ type: "response.completed", response: { usage: { video_frames_processed: 12 } } })}\n\n`
        : "";
      const events =
        `data: ${JSON.stringify({ type: "response.output_text.delta", delta: text })}\n\n` +
        `${completed}data: [DONE]\n\n`;
      return new Response(events, { status: 200 });
    });
    const result = await runGround(env, "job1", manifest, lensScout());

    // 12 vendor-billed strip frames + the 1 sent hero frame (the image
    // call's vendor omits usage, so its sent frame stands in — R10-05).
    expect(result.usage.samFramesBilled).toBe(13);
    expect(result.usage.samFrames).toBe(13);
    expect(result.usage.budgetExhausted).toBe(false);
    expect(typeof result.usage.samMs).toBe("number");
  });

  it("refuses video past the budget and records exhaustion", async () => {
    const { r2, manifest, env } = lensSetup({ type: "TURN" });
    await r2.put("jobs/job1/budget.json", JSON.stringify({ maxSamFrames: 1 }));
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    const result = await runGround(env, "job1", manifest, lensScout());

    // The 1-frame image call fits; the 12-frame strip does not.
    expect(calls).toEqual(["image"]);
    expect(result.usage.budgetExhausted).toBe(true);
    expect(result.usage.samFrames).toBe(1);
    // The image grounding stands: the governor refuses spend, never quality.
    expect(result.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");
  });

  it("caps a job with no budget.json at the 240-frame hard ceiling", async () => {
    // 1 image frame + a 240-frame clip is 241: over the ceiling, refused.
    const over = lensSetup({ type: "TURN", clipFrames: 240 });
    const overCalls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    const refused = await runGround(over.env, "job1", over.manifest, lensScout());
    expect(overCalls).toEqual(["image"]);
    expect(refused.usage.budgetExhausted).toBe(true);

    // 1 + 239 lands exactly on the ceiling and is allowed.
    const exact = lensSetup({ type: "TURN", clipFrames: 239 });
    const exactCalls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    const allowed = await runGround(exact.env, "job1", exact.manifest, lensScout());
    expect(exactCalls).toEqual(["image", "video"]);
    expect(allowed.usage.budgetExhausted).toBe(false);
  });

  it("fails closed to the ceiling on a corrupt budget.json and records why", async () => {
    for (const [body, reason] of [
      ["{not json", "budget_unreadable"],
      [JSON.stringify({ maxSamFrames: "lots" }), "budget_invalid"],
    ]) {
      const { r2, manifest, env } = lensSetup({ type: "TURN", clipFrames: 240 });
      await r2.put("jobs/job1/budget.json", body);
      const calls = stubMeta(STRIP_VIDEO, imgLine(0.9));
      const result = await runGround(env, "job1", manifest, lensScout());

      expect(calls).toEqual(["image"]);
      expect(result.usage.budgetExhausted).toBe(true);
      const notes = await r2.get("jobs/job1/sam/notes.json");
      expect(await notes?.json()).toEqual([{ scope: "budget", reason }]);
    }
  });

  it("clamps an oversize declared budget to the ceiling", async () => {
    const { r2, manifest, env } = lensSetup({ type: "TURN", clipFrames: 240 });
    await r2.put("jobs/job1/budget.json", JSON.stringify({ maxSamFrames: 100000 }));
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    await runGround(env, "job1", manifest, lensScout());
    expect(calls).toEqual(["image"]);
  });

  it("lets cache hits through without consuming budget on a retry", async () => {
    const { r2, manifest, env } = lensSetup({ type: "CHECKPOINT", clip: false });
    await r2.put("jobs/job1/budget.json", JSON.stringify({ maxSamFrames: 1 }));
    const twins = lensScout([
      scoutCandidate({ title: "Red pillar" }),
      scoutCandidate({ title: "Green exit", samPhrase: "green exit sign" }),
    ]);
    const calls = stubMeta("", imgLine(0.92));
    const first = await runGround(env, "job1", manifest, twins);
    // One frame of budget: the first call fits, the second is refused.
    expect(calls).toEqual(["image"]);
    expect(first.usage.budgetExhausted).toBe(true);

    // The retry re-plans: the cached call is free, so the refused one now
    // fits the same one-frame budget and nothing reports exhaustion.
    const retried = await runGround(env, "job1", manifest, twins);
    expect(calls).toEqual(["image", "image"]);
    expect(retried.usage.budgetExhausted).toBe(false);
    expect(retried.checkpoints[0].candidates.every((c) => c.source === "AI_GROUNDED")).toBe(true);
  });

  it("restores the recorded plan on replay instead of re-deciding against a filled cache", async () => {
    // Budget 12: the 1-frame image fits, the 12-frame strip then does not.
    const { r2, manifest, env } = lensSetup({ type: "TURN" });
    await r2.put("jobs/job1/budget.json", JSON.stringify({ maxSamFrames: 12 }));
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    const recorded = new Map<string, unknown>();
    const workflow: StepRunner = async (name, fn) => {
      if (recorded.has(name)) return structuredClone(recorded.get(name)) as Awaited<ReturnType<typeof fn>>;
      const out = await fn();
      recorded.set(name, structuredClone(out));
      return out;
    };
    const first = await runGround(env, "job1", manifest, lensScout(), workflow);
    expect(calls).toEqual(["image"]);
    expect(first.usage.budgetExhausted).toBe(true);

    // Re-planned live, the now-cached image would reserve nothing and let the
    // strip through: spend the first run never authorised.
    const replay = await runGround(env, "job1", manifest, lensScout(), workflow);
    expect(calls).toEqual(["image"]);
    expect(replay).toEqual(first);
  });

  it("never caches an empty SAM response", async () => {
    const { r2, manifest, env } = lensSetup({ type: "CHECKPOINT", clip: false });
    stubMeta("", "");
    const result = await runGround(env, "job1", manifest, lensScout());

    expect(result.checkpoints[0].candidates[0].source).toBe("AI_BOX_ONLY");
    expect([...r2.store.keys()].filter((k) => k.includes("/sam/cache/"))).toEqual([]);
    const notes = await r2.get("jobs/job1/sam/notes.json");
    expect(await notes?.json()).toEqual([
      { scope: expect.stringMatching(/^ground:n2:0:/), reason: "empty_response" },
    ]);
  });

  it("records the reason when a non-retryable SAM failure falls back", async () => {
    const { r2, manifest, env } = lensSetup({ type: "CHECKPOINT", clip: false });
    stubMeta("", new Response("bad request body", { status: 400 }));
    const result = await runGround(env, "job1", manifest, lensScout());

    expect(result.checkpoints[0].candidates[0].source).toBe("AI_BOX_ONLY");
    const notes = (await (await r2.get("jobs/job1/sam/notes.json"))?.json()) as Array<{
      scope: string;
      reason: string;
    }>;
    expect(notes).toHaveLength(1);
    expect(notes[0].reason).toMatch(/^non_retryable: meta 400/);
  });

  it("reserves by node priority: DESTINATION before CHECKPOINT", async () => {
    const r2 = new FakeR2();
    void r2.put("jobs/job1/in/kf_n9_1", "hero-bytes");
    void r2.put("jobs/job1/in/kf_n2_1", "hero-bytes");
    await r2.put("jobs/job1/budget.json", JSON.stringify({ maxSamFrames: 1 }));
    const manifest = {
      schema: 1,
      draftId: "d1",
      consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
      routeContext: "ctx",
      checkpoints: [
        {
          nodeId: "n2",
          type: "CHECKPOINT",
          atDistanceM: 10,
          tMs: 20000,
          heroKeyframeId: "kf_n2_1",
          keyframes: [{ id: "kf_n2_1", tMs: 20000 }],
          approach: [],
        },
        {
          nodeId: "n9",
          type: "DESTINATION",
          atDistanceM: 60,
          tMs: 60000,
          heroKeyframeId: "kf_n9_1",
          keyframes: [{ id: "kf_n9_1", tMs: 60000 }],
          approach: [],
        },
      ],
    } as never;
    const env = { MEDIA: r2, GROUND_ORDER: "sam31,none", META_API_KEY: "k" } as never;
    const scout = {
      model: "scout-model",
      checkpoints: [
        { nodeId: "n2", candidates: [scoutCandidate({ imageId: "kf_n2_1" })] },
        { nodeId: "n9", candidates: [scoutCandidate({ imageId: "kf_n9_1", title: "Doors" })] },
      ],
    } as never;
    const calls = stubMeta("", imgLine(0.92));
    const result = await runGround(env, "job1", manifest, scout);

    // One frame of budget: DESTINATION spends it, CHECKPOINT is refused.
    expect(calls).toEqual(["image"]);
    const byNode = new Map(result.checkpoints.map((c) => [c.nodeId, c]));
    expect(byNode.get("n9")?.candidates[0].source).toBe("AI_GROUNDED");
    expect(byNode.get("n2")?.candidates[0].source).toBe("AI_BOX_ONLY");
    expect(result.usage.budgetExhausted).toBe(true);
  });

  it("serves a repeat run from cache at zero frames", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    stubMeta("", imgLine(0.92));
    const first = await runGround(env, "job1", manifest, lensScout());
    expect(first.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");

    let fetches = 0;
    vi.stubGlobal("fetch", async () => {
      fetches++;
      return new Response("must not be called", { status: 500 });
    });
    const second = await runGround(env, "job1", manifest, lensScout());

    expect(fetches).toBe(0);
    expect(second.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");
    expect(second.usage.samFrames ?? 0).toBe(0);
    expect(second.usage.samFramesBilled).toBe(0);
  });

  it("retries a single billed call from cache at zero re-billed frames", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    const calls = stubMeta("", imgLine(0.92));
    const target = `ground:n2:0:${await phraseHash("red pillar")}`;
    const recorded = new Map<string, unknown>();
    const ran: string[] = [];
    let lost = false;
    // Workflow stand-in: completed steps replay from their recorded output.
    const workflow: StepRunner = async (name, fn) => {
      if (recorded.has(name)) return structuredClone(recorded.get(name)) as Awaited<ReturnType<typeof fn>>;
      ran.push(name);
      const out = await fn();
      // The first attempt of the SAM step bills, then the step is lost.
      if (name === target && !lost) {
        lost = true;
        throw new Error("simulated step loss after bill");
      }
      recorded.set(name, structuredClone(out));
      return out;
    };
    await expect(runGround(env, "job1", manifest, lensScout(), workflow)).rejects.toThrow(
      "simulated step loss after bill",
    );
    expect(ran.filter((n) => n === target)).toHaveLength(1);
    expect(calls).toEqual(["image"]);

    // The Workflow retries only the lost step; every other step replays from
    // its record and the lost one replays from the SAM cache.
    ran.length = 0;
    const retried = await runGround(env, "job1", manifest, lensScout(), workflow);
    expect(callSteps(ran)).toEqual([target]);
    expect(calls).toEqual(["image"]);
    expect(retried.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");
    expect(retried.usage.samFrames ?? 0).toBe(0);
    expect(retried.usage.samFramesBilled).toBe(0);
  });

  it("replays a cached-step run to identical aggregates with no duplicate events", async () => {
    const { manifest, env } = lensSetup({ type: "TURN" });
    vi.stubGlobal("fetch", async (_url: string, init: { body: string }) => {
      await new Promise((r) => setTimeout(r, 5));
      const video = init.body.includes("input_video");
      const completed = video
        ? `data: ${JSON.stringify({ type: "response.completed", response: { usage: { video_frames_processed: 12 } } })}\n\n`
        : "";
      const text = video ? STRIP_VIDEO : imgLine(0.95);
      return new Response(
        `data: ${JSON.stringify({ type: "response.output_text.delta", delta: text })}\n\n${completed}data: [DONE]\n\n`,
        { status: 200 },
      );
    });
    // A Workflow stand-in: a completed step returns its recorded output
    // (structured-cloned, like the real serialization) without running.
    const recorded = new Map<string, unknown>();
    const replaying: StepRunner = async (name, fn) => {
      if (recorded.has(name)) return structuredClone(recorded.get(name)) as Awaited<ReturnType<typeof fn>>;
      const out = await fn();
      recorded.set(name, structuredClone(out));
      return out;
    };
    const first = await runGround(env, "job1", manifest, lensScout(), replaying);
    const eventsAfterFirst = await readJobEvents(env, "job1");
    expect(first.usage.samFramesBilled).toBe(13); // 12 vendor-reported + 1 sent hero frame (R10-05 fallback)
    expect(first.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");

    vi.stubGlobal("fetch", async () => {
      throw new Error("a replay must not reach SAM");
    });
    const second = await runGround(env, "job1", manifest, lensScout(), replaying);

    expect(second).toEqual(first);
    expect(await readJobEvents(env, "job1")).toEqual(eventsAfterFirst);
  });

  it("gives two survivors that share a phrase distinct step names", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT", clip: false });
    stubMeta("", imgLine(0.92));
    const names: string[] = [];
    const twins = lensScout([
      scoutCandidate({ title: "Red pillar A" }),
      scoutCandidate({ title: "Red pillar B" }),
    ]);
    await runGround(env, "job1", manifest, twins, stepRecorder(names));

    const steps = callSteps(names);
    expect(steps).toHaveLength(2);
    expect(new Set(steps).size).toBe(2);
    expect(steps.map((n) => n.split(":")[2]).sort()).toEqual(["0", "1"]);
  });

  it("aborts a hanging SAM call on idle silence", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT", clip: false });
    vi.stubGlobal(
      "fetch",
      (_url: string, init: { signal?: AbortSignal }) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener("abort", () => {
            reject(init.signal?.reason ?? new Error("aborted"));
          });
        }),
    );
    await expect(
      runGround(env, "job1", manifest, lensScout(), inlineRunner, { samIdleMs: 25 }),
    ).rejects.toThrow();
  });

  it("drops candidates on failing face flags at zero spend", async () => {
    // The fixture goes through ManifestSchema.parse, so this also proves the
    // flags survive intake instead of being stripped.
    const { manifest, env } = lensSetup({ type: "CHECKPOINT", keyframeFlags: { faceFree: false } });
    const calls = stubMeta(STRIP_VIDEO, imgLine(0.9));
    const result = await runGround(env, "job1", manifest, lensScout());

    expect(calls).toEqual([]);
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_BOX_ONLY");
    expect(result.usage.samFrames ?? 0).toBe(0);
    expect(result.usage.samFramesBilled).toBe(0);
  });

  it("emits checkpoint and usage events", async () => {
    const { manifest, env } = lensSetup({ type: "CHECKPOINT" });
    stubMeta("", imgLine(0.92));
    await runGround(env, "job1", manifest, lensScout());

    const events = await readJobEvents(env, "job1");
    // Same-millisecond order is not contract (R10-08); assert the set.
    expect(events.map((e) => e.type).sort()).toEqual(["checkpoint", "usage"]);
    const checkpoint = events.find((e) => e.type === "checkpoint");
    expect(checkpoint).toMatchObject({ nodeId: "n2", status: "grounded" });
  });
});
