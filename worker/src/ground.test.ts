// W3-05 test delta: SAM parsing, §6.6 selection/ranking, box-only path.
//
// Fixture SAM responses produce ranked candidates with `maskSvgPath`
// null-degrading to box-only, never crashing — the slice acceptance.

import { afterEach, describe, expect, it, vi } from "vitest";
import { inlineRunner, runGround, type StepRunner } from "./ground";
import { phraseHash } from "./samLens";
import { readJobEvents } from "./jobs";
import {
  bestBy,
  heroIndex,
  interpolateDistance,
  iou,
  rankCandidate,
  selectTrack,
  sparkBoxToPixels,
} from "./selection";
import { parseSam } from "./samParse";
import { ManifestSchema, type Checkpoint } from "./types";

const TWO_OBJECT =
  `<0f>1<|box;x1=412;y1=230;x2=590;y2=900;w=720;h=1280;c=0.86|><|mask;onebit;AAAA|>2<|box;x1=10;y1=20;x2=60;y2=80;w=720;h=1280;c=0.41|>\n` +
  `<1f>1<|box;x1=415;y1=235;x2=592;y2=905;w=720;h=1280;c=0.88|><|mask;onebit;BBBB|>\n` +
  `<3f>1<|box;x1=420;y1=240;x2=595;y2=910;w=720;h=1280|>\n`;

describe("parseSam", () => {
  it("parses a two-object frame with confidences and keeps the raw line", () => {
    const obs = parseSam(TWO_OBJECT);
    expect(obs).toHaveLength(4);
    expect(obs[0]).toMatchObject({ frame: 0, id: 1, w: 720, h: 1280, c: 0.86 });
    expect(obs[0].box).toEqual([412, 230, 590, 900]);
    expect(obs[0].line).toContain("<0f>");
  });

  it("skips non-frame lines and records without confidence as c=undefined", () => {
    const obs = parseSam(`not a sam line\n${TWO_OBJECT}trailer junk`);
    const noConf = obs.find((o) => o.frame === 3);
    expect(noConf?.c).toBeUndefined();
    expect(obs).toHaveLength(4);
  });

  it("returns empty on missing `c` and on empty input, never throws", () => {
    expect(parseSam("")).toEqual([]);
    expect(parseSam("<2f>oops no records\n")).toEqual([]);
  });

  it("drops degenerate records and ignores out-of-range confidence", () => {
    const junk =
      `<0f>1<|box;x1=1.5;y1=2;x2=30;y2=40;w=720;h=1280|>\n` + // float box
      `<0f>2<|box;x1=30;y1=40;x2=30;y2=90;w=720;h=1280|>\n` + // zero-area
      `<0f>3<|box;x1=90;y1=40;x2=30;y2=90;w=720;h=1280|>\n` + // inverted
      `<0f>4<|box;x1=10;y1=10;x2=30;y2=40;w=0;h=1280|>\n` + // zero frame width
      `<0f>5<|box;x1=10;y1=10;x2=30;y2=40;w=720;h=1280;c=1.7|>\n`; // c out of range
    const obs = parseSam(junk);
    expect(obs).toHaveLength(1);
    expect(obs[0].id).toBe(5);
    expect(obs[0].c).toBeUndefined();
  });
});

describe("geometry generics", () => {
  it("iou scores identity 1 and disjoint 0", () => {
    expect(iou([0, 0, 10, 10], [0, 0, 10, 10])).toBeCloseTo(1);
    expect(iou([0, 0, 10, 10], [20, 20, 30, 30])).toBe(0);
  });

  it("bestBy picks the max and null on empty", () => {
    expect(bestBy([{ v: 1 }, { v: 3 }, { v: 2 }], (x) => x.v)?.v).toBe(3);
    expect(bestBy([], () => 1)).toBeNull();
  });

  it("sparkBoxToPixels scales the 0-1000 grid", () => {
    expect(sparkBoxToPixels([0, 0, 1000, 1000], 720, 1280)).toEqual([0, 0, 720, 1280]);
  });

  it("interpolateDistance clamps outside the approach range", () => {
    const approach = [
      { tMs: 37250, d: 18.9 },
      { tMs: 37750, d: 19.4 },
    ];
    expect(interpolateDistance(approach, 37000)).toBeCloseTo(18.9);
    expect(interpolateDistance(approach, 37500)).toBeCloseTo(19.15);
    expect(interpolateDistance(approach, 39000)).toBeCloseTo(19.4);
    expect(interpolateDistance([], 37500)).toBe(0);
  });

  it("heroIndex rounds the hero frame", () => {
    expect(heroIndex(40750, 37250, 10)).toBe(35);
  });
});

describe("selectTrack (§6.6)", () => {
  const sparkPx = sparkBoxToPixels([573, 180, 819, 703], 720, 1280);

  it("picks the overlapping track and clears the mismatch flag", () => {
    const sel = selectTrack(parseSam(TWO_OBJECT), sparkPx, 1, 10);
    expect(sel.hero?.id).toBe(1);
    expect(sel.boxMismatch).toBe(false);
    expect(sel.present).toBeGreaterThan(0);
  });

  it("sets boxMismatch when nothing overlaps the scout box", () => {
    const far: [number, number, number, number] = [0, 0, 5, 5];
    const sel = selectTrack(parseSam(TWO_OBJECT), far, 1, 10);
    expect(sel.boxMismatch).toBe(true);
  });

  it("never throws on empty observations", () => {
    const sel = selectTrack([], sparkPx, 1, 10);
    expect(sel.obs).toEqual([]);
    expect(sel.boxMismatch).toBe(true);
  });
});

describe("rankCandidate (§6.6)", () => {
  it("weights quality and penalizes ambiguity + mismatch", () => {
    const base = rankCandidate({
      distinctiveness: 0.9,
      permanence: 0.95,
      trackability: 0.78,
      samConfidence: 0.86,
      ambiguous: false,
      boxMismatch: false,
    });
    expect(base).toBeCloseTo(0.35 * 0.9 + 0.25 * 0.95 + 0.2 * 0.78 + 0.2 * 0.86, 9);
    const penalized = rankCandidate({
      distinctiveness: 0.9,
      permanence: 0.95,
      trackability: 0.78,
      samConfidence: 0.86,
      ambiguous: true,
      boxMismatch: true,
    });
    expect(penalized).toBeCloseTo(base - 0.3, 9);
  });

  it("null-degrades missing trackability/confidence to 0.3", () => {
    expect(
      rankCandidate({
        distinctiveness: 1,
        permanence: 1,
        trackability: null,
        samConfidence: null,
        ambiguous: false,
        boxMismatch: false,
      }),
    ).toBeCloseTo(0.35 + 0.25 + 0.06 + 0.06, 9);
  });
});

function checkpoint(): Checkpoint {
  return {
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
    approach: [
      { tMs: 37250, d: 18.9 },
      { tMs: 37750, d: 19.4 },
    ],
  };
}

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
    return {
      arrayBuffer: async () => new TextEncoder().encode(text).buffer as ArrayBuffer,
      json: async <T,>() => JSON.parse(text) as T,
      text: async () => text,
    };
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
const samLine = (frame: number, withMask = false, c = 0.9) =>
  `<${frame}f>1<|box;x1=300;y1=300;x2=420;y2=1100;w=720;h=1280;c=${c}|>${withMask ? MASK_TOKEN : ""}`;
const SAM_VIDEO = `${samLine(33)}\n${samLine(35, true)}\n`;
const SAM_IMAGE = `${samLine(0, true)}\n`;

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

function groundedSetup(withClip = true) {
  const r2 = new FakeR2();
  void r2.put("jobs/job1/in/clip_n2", "clip-bytes");
  void r2.put("jobs/job1/in/kf_n2_1", "hero-bytes");
  const cp = checkpoint();
  if (withClip) {
    (cp as { clip?: unknown }).clip = {
      id: "clip_n2",
      startMs: 37250,
      endMs: 42250,
      frameCount: 50,
      durationMs: 5000,
    };
  }
  const manifest = {
    schema: 1,
    draftId: "d1",
    consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
    routeContext: "ctx",
    checkpoints: [cp],
  } as never;
  const env = { MEDIA: r2, GROUND_MODE: "legacy", GROUND_ORDER: "sam31,none", META_API_KEY: "k" } as never;
  return { r2, manifest, env };
}

describe("runGround SAM path", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("grounds candidates with a decoded mask, ranked §6.6 order", async () => {
    const { manifest, env } = groundedSetup();
    stubMeta(SAM_VIDEO, SAM_IMAGE);
    const scout = {
      model: "scout-model",
      checkpoints: [
        {
          nodeId: "n2",
          candidates: [
            scoutCandidate({ distinctiveness: 0.4, title: "Dim pillar" }),
            scoutCandidate({ distinctiveness: 0.9, title: "Red pillar" }),
          ],
        },
      ],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    expect(result.models.ground).toBe("sam-3.1");
    const candidates = result.checkpoints[0].candidates;
    expect(candidates).toHaveLength(2);
    // Ranked: the stronger scout signal wins despite input order.
    expect(candidates[0].title).toBe("Red pillar");
    const top = candidates[0];
    expect(top.source).toBe("AI_GROUNDED");
    expect(top.maskSvgPath).toMatch(/^M/);
    // Raster dims from the mask itself (4x3), not the 720x1280 frame.
    expect(top.maskW).toBe(4);
    expect(top.maskH).toBe(3);
    expect(top.samConfidence).toBeCloseTo(0.9);
    expect(top.trackability).not.toBeNull();
    expect(top.box).toEqual([300, 300, 420, 1100]);
    expect(top.frameW).toBe(720);
  });

  it("reports vendor frames per video send and real SAM time in usage", async () => {
    const { manifest, env } = groundedSetup();
    vi.stubGlobal("fetch", async () => {
      // Real elapsed time so the measured samMs is > 0 deterministically.
      await new Promise((r) => setTimeout(r, 5));
      const events =
        `data: ${JSON.stringify({ type: "response.output_text.done", text: SAM_VIDEO })}\n\n` +
        `data: ${JSON.stringify({ type: "response.completed", response: { usage: { video_frames_processed: 120 } } })}\n\n` +
        "data: [DONE]\n\n";
      return new Response(events, { status: 200 });
    });
    const scout = {
      model: "scout-model",
      checkpoints: [
        {
          nodeId: "n2",
          candidates: [scoutCandidate(), scoutCandidate({ title: "Second", distinctiveness: 0.5 })],
        },
      ],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    // Two candidates = two video sends = 2 x 120 vendor frames (the old
    // per-checkpoint proxy would have claimed a single clip's 50).
    expect(result.usage.samFrames).toBe(240);
    expect(typeof result.usage.samMs).toBe("number");
  });

  it("degrades to box-only when Meta refuses, never crashes", async () => {
    const { manifest, env } = groundedSetup();
    stubMeta(new Response("forbidden", { status: 403 }), SAM_IMAGE);
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [scoutCandidate()] }],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_BOX_ONLY");
    expect(c.maskSvgPath).toBeNull();
  });

  it("retryable Meta failures propagate instead of silently degrading", async () => {
    const { manifest, env } = groundedSetup();
    stubMeta(new Response("busy", { status: 503 }), SAM_IMAGE);
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [scoutCandidate()] }],
    } as never;

    // 5xx is the Workflow's retry signal: the job must not pretend the
    // candidates were grounded or quietly fall back to box-only.
    await expect(runGround(env, "job1", manifest, scout)).rejects.toThrow(/retryable meta 503/);
  });

  it("zero video observations fall back to the hero image with null track fields", async () => {
    const { manifest, env } = groundedSetup();
    const calls = stubMeta("", SAM_IMAGE);
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [scoutCandidate()] }],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    expect(calls).toEqual(["video", "image"]);
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_GROUNDED");
    // §10 fallback a: single frame has no track and no visibility range.
    expect(c.trackability).toBeNull();
    expect(c.visibleFromM).toBeNull();
    expect(c.maskSvgPath).toMatch(/^M/);
  });

  it("a checkpoint without a clip grounds on the hero image directly", async () => {
    const { manifest, env } = groundedSetup(false);
    const calls = stubMeta(SAM_VIDEO, SAM_IMAGE);
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [scoutCandidate()] }],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    expect(calls).toEqual(["image"]);
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_GROUNDED");
    expect(c.trackability).toBeNull();
    expect(c.visibleFromM).toBeNull();
  });

  it("degenerate SAM output degrades to box-only instead of crashing the job", async () => {
    const { manifest, env } = groundedSetup();
    const junk =
      `<35f>1<|box;x1=1.5;y1=2;x2=30;y2=40;w=720;h=1280;c=9|>\n` +
      `<35f>2<|box;x1=50;y1=50;x2=50;y2=90;w=720;h=1280|>\n`;
    stubMeta(junk, junk);
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [scoutCandidate()] }],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_BOX_ONLY");
    expect(c.maskSvgPath).toBeNull();
  });
});

describe("runGround box-only path", () => {
  it("passes scout boxes through with null mask fields when no key is set", async () => {
    const r2 = new FakeR2();
    const env = { MEDIA: r2, GROUND_ORDER: "sam31,none" } as never;
    const cp = checkpoint();
    const manifest = {
      schema: 1,
      draftId: "d1",
      consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
      routeContext: "ctx",
      checkpoints: [cp],
    } as never;
    const scout = {
      model: "scout-model",
      checkpoints: [
        {
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
        },
      ],
    } as never;
    const result = await runGround(env, "job1", manifest, scout);
    expect(result.models.ground).toBe("none");
    const c = result.checkpoints[0].candidates[0];
    expect(c.source).toBe("AI_BOX_ONLY");
    expect(c.maskSvgPath).toBeNull();
    expect(c.samConfidence).toBeNull();
    expect(c.trackability).toBeNull();
    expect(c.visibleFromM).toBeNull();
    expect(c.rank).toBeCloseTo(0.35 * 0.9 + 0.25 * 0.95 + 0.06 + 0.06, 9);
    const ground = await r2.get("jobs/job1/ground.json");
    expect(ground).not.toBeNull();
  });

  it("GROUND_ORDER=none degrades even with a key configured", async () => {
    const r2 = new FakeR2();
    const env = { MEDIA: r2, GROUND_ORDER: "none", META_API_KEY: "k" } as never;
    const cp = checkpoint();
    const manifest = {
      schema: 1,
      draftId: "d1",
      consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
      routeContext: "ctx",
      checkpoints: [cp],
    } as never;
    const scout = {
      model: "scout-model",
      checkpoints: [{ nodeId: "n2", candidates: [] }],
    } as never;
    const result = await runGround(env, "job1", manifest, scout);
    expect(result.models.ground).toBe("none");
    expect(result.checkpoints[0].candidates).toEqual([]);
  });

  it("emits box-only candidates in §6.6 rank order, not scout order", async () => {
    const r2 = new FakeR2();
    const env = { MEDIA: r2, GROUND_ORDER: "sam31,none" } as never;
    const manifest = {
      schema: 1,
      draftId: "d1",
      consent: { sendFramesToCloudAi: true, atEpochMs: 1 },
      routeContext: "ctx",
      checkpoints: [checkpoint()],
    } as never;
    const scout = {
      model: "scout-model",
      checkpoints: [
        {
          nodeId: "n2",
          candidates: [
            scoutCandidate({ distinctiveness: 0.2, permanence: 0.2, title: "Weak" }),
            scoutCandidate({ distinctiveness: 0.9, permanence: 0.95, title: "Strong" }),
          ],
        },
      ],
    } as never;

    const result = await runGround(env, "job1", manifest, scout);

    expect(result.checkpoints[0].candidates.map((c) => c.title)).toEqual(["Strong", "Weak"]);
  });
});

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
  } = {},
) {
  const { type = "CHECKPOINT", clip = true, keyframeFlags = {} } = opts;
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
    ...(clip ? { clip: STRIP_CLIP } : {}),
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
    expect(result.usage.samFramesBilled).toBe(0);
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

    // 12 vendor-billed strip frames + the 1 sent hero frame.
    expect(result.usage.samFramesBilled).toBe(12);
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
    const seen = new Map<string, number>();
    const lossy: StepRunner = async (name, fn) => {
      seen.set(name, (seen.get(name) ?? 0) + 1);
      const out = await fn();
      // The first attempt bills, then the step is lost and retried.
      if (seen.get(name) === 1) throw new Error("simulated step loss after bill");
      return out;
    };
    await expect(runGround(env, "job1", manifest, lensScout(), lossy)).rejects.toThrow(
      "simulated step loss after bill",
    );
    expect(seen.get(target)).toBe(1);
    expect(calls).toEqual(["image"]);

    // The Workflow retries only the lost step; every other step is untouched
    // and the lost one replays from cache.
    const strict: StepRunner = async (name, fn) => {
      // Only the lost SAM step may re-run; emits and the seal follow it.
      if (isCallStep(name) && name !== target) throw new Error(`must not re-run ${name}`);
      return fn();
    };
    const retried = await runGround(env, "job1", manifest, lensScout(), strict);
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
    expect(first.usage.samFramesBilled).toBe(12);
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
    expect(events.map((e) => e.type)).toEqual(["checkpoint", "usage"]);
    const checkpoint = events[0];
    expect(checkpoint).toMatchObject({ nodeId: "n2", status: "grounded" });
  });
});
