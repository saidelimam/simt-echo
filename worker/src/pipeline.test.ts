// W3-06 test delta: assemble sorts by §6.6 rank, seals result.json with
// measured usage, and marks progress complete.
//
// Fakes mirror the repo's per-file precedent (jobs.test.ts): in-memory R2.
// The chain underneath (scout → ground) is covered by scout.test.ts and
// ground.test.ts; here assemble is fed a hand-built ground result plus the
// box-only chain output, so the slice's owned step is proven on its own.

import { describe, expect, it } from "vitest";
import { assembleFromGround } from "./pipeline";
import { runGround } from "./ground";
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
