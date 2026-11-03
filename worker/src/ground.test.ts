// W3-05 test delta: SAM parsing, §6.6 selection/ranking, box-only path.
//
// Fixture SAM responses produce ranked candidates with `maskSvgPath`
// null-degrading to box-only, never crashing — the slice acceptance.

import { describe, expect, it } from "vitest";
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

  it("drops out-of-frame boxes (R10-14): negative coords and coords past w/h", () => {
    const junk =
      `<0f>1<|box;x1=-10;y1=20;x2=60;y2=80;w=720;h=1280|>\n` + // negative x1
      `<0f>2<|box;x1=10;y1=20;x2=60;y2=-5;w=720;h=1280|>\n` + // negative y2
      `<0f>3<|box;x1=10;y1=20;x2=900;y2=80;w=720;h=1280|>\n` + // x2 past w
      `<0f>4<|box;x1=10;y1=20;x2=60;y2=1500;w=720;h=1280|>\n` + // y2 past h
      `<0f>5<|box;x1=0;y1=0;x2=720;y2=1280;w=720;h=1280|>\n`; // touches the edge: kept
    const obs = parseSam(junk);
    // The four out-of-frame records are dropped; only the edge-touching one
    // survives. Edge-touching (x2==w, y2==h) is in frame, so the bound is
    // strict > not >=.
    expect(obs).toHaveLength(1);
    expect(obs[0].id).toBe(5);
    expect(obs[0].box).toEqual([0, 0, 720, 1280]);
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
