// W3-05 test delta: SAM parsing, §6.6 selection/ranking, box-only path.
//
// Fixture SAM responses produce ranked candidates with `maskSvgPath`
// null-degrading to box-only, never crashing — the slice acceptance.

import { afterEach, describe, expect, it, vi } from "vitest";
import { runGround } from "./ground";
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
import type { Checkpoint } from "./types";

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
