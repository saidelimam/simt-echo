// W3-06 test delta: assemble sorts by §6.6 rank, seals result.json with
// measured usage, and marks progress complete.
//
// Fakes mirror the repo's per-file precedent (jobs.test.ts): in-memory R2.
// The chain underneath (scout → ground) is covered by scout.test.ts and
// ground.test.ts; here assemble is fed a hand-built ground result plus the
// box-only chain output, so the slice's owned step is proven on its own.

import { afterEach, describe, expect, it, vi } from "vitest";
import { assembleFromGround, EchoPipeline } from "./pipeline";
import { runGround } from "./ground";
import { readJobEvents } from "./jobs";
import { JobResultSchema, type Candidate, type JobResult, type Manifest } from "./types";

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

  async list({ prefix }: { prefix?: string; cursor?: string }) {
    const keys = [...this.store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
    return { objects: keys.map((key) => ({ key })), truncated: false as const, delimitedPrefixes: [] };
  }
}

function envFor(media: FakeR2, extra: Record<string, unknown> = {}) {
  return { MEDIA: media as never, ...extra } as never;
}

function manifestWithClip(): Manifest {
  return {
    schema: 1,
    draftId: "d1",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
    routeContext: "ctx",
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
        clip: { id: "clip_n2", startMs: 37250, endMs: 42250, frameCount: 50, durationMs: 5000 },
        approach: [{ tMs: 37250, d: 18.9 }],
      },
    ],
  };
}

function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    candidateId: "n2_0",
    title: "Red pillar",
    description: "d",
    cue: "Turn left at the red pillar",
    samPhrase: "red pillar",
    signText: null,
    privacyRisk: false,
    keyframeId: "kf_n2_1",
    box: [412, 230, 590, 900],
    frameW: 1000,
    frameH: 1000,
    maskSvgPath: null,
    maskW: null,
    maskH: null,
    samConfidence: null,
    trackability: null,
    visibleFromM: null,
    distinctiveness: 0.9,
    permanence: 0.95,
    rank: 0.5,
    source: "AI_BOX_ONLY",
    models: "scout-model (box-only)",
    evidenceKey: "jobs/job1/scout/raw/n2.0.json",
    ...overrides,
  };
}

function groundResult(candidates: Candidate[]): JobResult {
  return JobResultSchema.parse({
    jobId: "job1",
    models: { scout: "scout-model", ground: "none" },
    checkpoints: [{ nodeId: "n2", candidates }],
    usage: {},
  });
}

describe("assembleFromGround", () => {
  it("sorts candidates per checkpoint by rank desc and seals result.json", async () => {
    const media = new FakeR2();
    const ground = groundResult([
      candidate({ candidateId: "n2_0", title: "Weak", rank: 0.2 }),
      candidate({ candidateId: "n2_1", title: "Strong", rank: 0.8 }),
    ]);

    const result = await assembleFromGround(
      envFor(media),
      "job1",
      manifestWithClip(),
      ground,
      { scoutMs: 21000 },
    );

    expect(result.checkpoints[0].candidates.map((c) => c.title)).toEqual(["Strong", "Weak"]);
    // Ground measured no SAM usage: samMs is absent, never invented.
    expect(result.usage).toEqual({ scoutMs: 21000 });
    expect(result.usage.samMs).toBeUndefined();
    // Box-only path sends no frames to SAM: no samFrames claim.
    expect(result.usage.samFrames).toBeUndefined();
    const sealed = JSON.parse(new TextDecoder().decode(media.store.get("jobs/job1/result.json")!));
    expect(JobResultSchema.parse(sealed).checkpoints[0].candidates[0].title).toBe("Strong");
    const progress = JSON.parse(new TextDecoder().decode(media.store.get("jobs/job1/progress.json")!));
    expect(progress.stage).toBe("complete");
  });

  it("carries ground's measured SAM usage through, never a proxy", async () => {
    const media = new FakeR2();
    const ground = JobResultSchema.parse({
      ...groundResult([candidate({ candidateId: "n2_0", source: "AI_GROUNDED", models: "m + sam-3.1" })]),
      models: { scout: "muse-spark-1.3", ground: "sam-3.1" },
      usage: { samFrames: 240, samMs: 1800 },
    });

    const result = await assembleFromGround(envFor(media), "job1", manifestWithClip(), ground, {
      scoutMs: 1000,
    });

    // Two video sends reported 240 vendor frames — not the manifest's
    // single-clip 50 the old proxy claimed.
    expect(result.usage).toEqual({ samFrames: 240, scoutMs: 1000, samMs: 1800 });
  });

  it("keeps manifest checkpoints ground never produced, as empty", async () => {
    const media = new FakeR2();
    const manifest = manifestWithClip();
    (manifest.checkpoints as unknown[]).push({
      nodeId: "n3",
      type: "DESTINATION",
      atDistanceM: 45,
      tMs: 60000,
      heroKeyframeId: "kf_n2_1",
      keyframes: [{ id: "kf_n2_1", tMs: 1000 }],
      approach: [],
    });
    const ground = groundResult([candidate({ candidateId: "n2_0" })]);

    const result = await assembleFromGround(envFor(media), "job1", manifest, ground, {
      scoutMs: 10,
    });

    expect(result.checkpoints.map((c) => c.nodeId)).toEqual(["n2", "n3"]);
    expect(result.checkpoints[1].candidates).toEqual([]);
  });

  it("assembles the box-only ground chain end to end", async () => {
    const media = new FakeR2();
    const manifest = manifestWithClip();
    const env = envFor(media, { GROUND_ORDER: "none" });
    const scout = {
      model: "scout-model",
      checkpoints: [
        {
          nodeId: "n2",
          candidates: [
            {
              title: "Weak",
              samPhrase: "dim pillar",
              description: "d",
              imageId: "kf_n2_1",
              box: [10, 10, 50, 50],
              distinctiveness: 0.2,
              permanence: 0.2,
              signText: null,
              ambiguous: false,
              privacyRisk: false,
              cue: "Turn left at the dim pillar",
            },
            {
              title: "Strong",
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
            },
          ],
        },
      ],
    } as never;

    const ground = await runGround(env, "job1", manifest, scout);
    const result = await assembleFromGround(env, "job1", manifest, ground, { scoutMs: 5 });

    expect(result.models).toEqual({ scout: "scout-model", ground: "none" });
    expect(result.checkpoints[0].candidates.map((c) => c.title)).toEqual(["Strong", "Weak"]);
    expect(result.usage.scoutMs).toBe(5);
    expect(result.checkpoints[0].candidates[0].source).toBe("AI_BOX_ONLY");
  });
});

describe("assembleFromGround lens accounting (W11-06)", () => {
  it("carries billed frames and exhaustion through, never a proxy", async () => {
    const media = new FakeR2();
    const ground = JobResultSchema.parse({
      ...groundResult([candidate({ candidateId: "n2_0", source: "AI_GROUNDED", models: "m + sam-3.1" })]),
      models: { scout: "muse-spark-1.3", ground: "sam-3.1" },
      usage: { samFrames: 13, samMs: 900, samFramesBilled: 12, budgetExhausted: true },
    });

    const result = await assembleFromGround(envFor(media), "job1", manifestWithClip(), ground, {
      scoutMs: 1000,
    });

    expect(result.usage).toEqual({
      samFrames: 13,
      scoutMs: 1000,
      samMs: 900,
      samFramesBilled: 12,
      budgetExhausted: true,
    });
  });
});

// A one_bit mask over a 4x3 raster, carried on the hero image line.
const LENS_MASK = `<|mask;x=0;y=0;data=3,4,!!!!!'IUK/e!!|>`;
const LENS_IMAGE =
  `<0f>1<|box;x1=300;y1=300;x2=420;y2=1100;w=720;h=1280;c=0.92|>${LENS_MASK}\n`;

function sse(text: string): Response {
  return new Response(
    `data: ${JSON.stringify({ type: "response.output_text.delta", delta: text })}\n\n` +
      "data: [DONE]\n\n",
    { status: 200 },
  );
}

describe("EchoPipeline lens phase (W11-06)", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("grounds with one step per SAM call and emits W10-06 events", async () => {
    const media = new FakeR2();
    const manifest = {
      schema: 1,
      draftId: "d1",
      consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
      routeContext: "ctx",
      checkpoints: [
        {
          nodeId: "n2",
          type: "CHECKPOINT",
          atDistanceM: 23.5,
          tMs: 41250,
          heroKeyframeId: "kf_n2_1",
          keyframes: [{ id: "kf_n2_1", tMs: 40750 }],
          approach: [],
        },
      ],
    };
    await media.put("jobs/job1/manifest.json", JSON.stringify(manifest));
    await media.put("jobs/job1/in/kf_n2_1", "hero-bytes");
    const ai = {
      run: async () => ({
        response: JSON.stringify({
          nodeId: "n2",
          candidates: [
            {
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
            },
          ],
        }),
      }),
    };
    const env = envFor(media, {
      AI: ai,
      AI_GATEWAY_ID: "test",
      SCOUT_ORDER: "workers-ai",
      GROUND_ORDER: "sam31,none",
      META_API_KEY: "k",
    });
    vi.stubGlobal("fetch", async () => sse(LENS_IMAGE));

    const names: string[] = [];
    const step = {
      do: async (name: string, _cfg: unknown, fn: () => Promise<unknown>) => {
        names.push(name);
        return fn();
      },
    };
    const pipeline = new EchoPipeline({} as never, env);
    const out = (await pipeline.run({ payload: { jobId: "job1" } } as never, step as never)) as JobResult;

    // One step per SAM call — a retry re-bills one call, never all.
    expect(names).toHaveLength(4);
    expect(names.slice(0, 2)).toEqual(["load", "scout"]);
    expect(names[2]).toMatch(/^ground:n2:[0-9a-f]{12}$/);
    expect(names[3]).toBe("assemble");
    expect(out.models).toEqual({ scout: expect.any(String), ground: "sam-3.1" });
    expect(out.checkpoints[0].candidates[0].source).toBe("AI_GROUNDED");
    expect(out.usage.samFrames).toBe(1);
    expect(out.usage.samFramesBilled).toBe(0);
    expect(out.usage.budgetExhausted).toBe(false);

    const events = await readJobEvents(env, "job1");
    expect(events.map((e) => e.type)).toEqual(["stage", "checkpoint", "usage", "complete"]);
    const checkpoint = events[1];
    expect(checkpoint).toMatchObject({ type: "checkpoint", nodeId: "n2", status: "grounded" });
  });
});
