// Generic §6.6 track selection and ranking (slice W3-05).
//
// Pure geometry shared by the ground step (`samLens.ts`) and W3-06's assemble:
// `bestBy` picks any item by score, `iou` works on any box tuple, and
// `selectTrack`/`rankCandidate` implement the plan formulas without I/O so
// both callers reuse them instead of re-implementing §6.6.

import type { SamObs } from "./samParse";

export type Box = [number, number, number, number];

/** Generic argmax: the highest-scoring item, or null when empty. Ties keep
 * the earliest item, so selection is deterministic on fixture input. */
export function bestBy<T>(items: readonly T[], score: (item: T) => number): T | null {
  let best: T | null = null;
  let bestScore = -Infinity;
  for (const item of items) {
    const s = score(item);
    if (s > bestScore) {
      bestScore = s;
      best = item;
    }
  }
  return best;
}

/** Intersection-over-union of two pixel boxes. Degenerate boxes score 0. */
export function iou(a: Box, b: Box): number {
  const x1 = Math.max(a[0], b[0]);
  const y1 = Math.max(a[1], b[1]);
  const x2 = Math.min(a[2], b[2]);
  const y2 = Math.min(a[3], b[3]);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  if (inter <= 0) return 0;
  const areaA = Math.max(0, a[2] - a[0]) * Math.max(0, a[3] - a[1]);
  const areaB = Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  const union = areaA + areaB - inter;
  return union <= 0 ? 0 : inter / union;
}

/** Scout box (0–1000 grid) → pixels of a `w×h` SAM frame (plan §6.6). */
export function sparkBoxToPixels(box: readonly [number, number, number, number], w: number, h: number): Box {
  return [(box[0] * w) / 1000, (box[1] * h) / 1000, (box[2] * w) / 1000, (box[3] * h) / 1000];
}

/** Linear interpolation of `d` over `approach` samples at time `tMs`.
 * Clamps to the sample range; empty approach yields 0. */
export function interpolateDistance(
  approach: ReadonlyArray<{ tMs: number; d: number }>,
  tMs: number,
): number {
  if (approach.length === 0) return 0;
  const sorted = [...approach].sort((a, b) => a.tMs - b.tMs);
  if (tMs <= sorted[0].tMs) return sorted[0].d;
  const last = sorted[sorted.length - 1];
  if (tMs >= last.tMs) return last.d;
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].tMs >= tMs) {
      const prev = sorted[i - 1];
      const next = sorted[i];
      const span = next.tMs - prev.tMs;
      const f = span <= 0 ? 0 : (tMs - prev.tMs) / span;
      return prev.d + f * (next.d - prev.d);
    }
  }
  return last.d;
}

/** §6.6 rank: weighted quality minus ambiguity/mismatch penalties. */
export function rankCandidate(input: {
  distinctiveness: number;
  permanence: number;
  trackability: number | null;
  samConfidence: number | null;
  ambiguous: boolean;
  boxMismatch: boolean;
}): number {
  return (
    0.35 * input.distinctiveness +
    0.25 * input.permanence +
    0.2 * (input.trackability ?? 0.3) +
    0.2 * (input.samConfidence ?? 0.3) -
    (input.ambiguous ? 0.2 : 0) -
    (input.boxMismatch ? 0.1 : 0)
  );
}

export interface TrackSelection {
  /** Observations of the winning track id (possibly empty on mismatch). */
  obs: SamObs[];
  /** Fraction of frames present from first sighting to the hero frame. */
  present: number;
  /** Mean SAM confidence over the track (0 when records carry none). */
  meanC: number;
  /** Hero observation (nearest to heroIdx within ±5), if any. */
  hero: SamObs | null;
  /** IoU of the scout box against the hero observation (0 when no hero). */
  heroIou: number;
  /** True when no track met `iou ≥ 0.1` and the present·meanC fallback won. */
  boxMismatch: boolean;
  /** First frame the winning track was seen (0 when nothing was seen). */
  first: number;
  /** Frame dimensions of the winning track (0×0 when nothing was seen). */
  frameW: number;
  frameH: number;
}

/**
 * §6.6 track selection. Groups observations by track id, scores each by
 * `0.6·iou + 0.25·present + 0.15·meanC`, and picks the max with `iou ≥ 0.1`;
 * otherwise the best `present·meanC` wins with `boxMismatch` set. Pure and
 * total: empty input yields a zeroed mismatch selection, never a throw.
 */
export function selectTrack(
  obs: readonly SamObs[],
  sparkBoxPx: Box,
  heroIdx: number,
): TrackSelection {
  const empty: TrackSelection = {
    obs: [],
    present: 0,
    meanC: 0,
    hero: null,
    heroIou: 0,
    boxMismatch: true,
    first: 0,
    frameW: 0,
    frameH: 0,
  };
  if (obs.length === 0) return empty;
  const byId = new Map<number, SamObs[]>();
  for (const o of obs) {
    const list = byId.get(o.id) ?? [];
    list.push(o);
    byId.set(o.id, list);
  }
  interface Scored {
    id: number;
    list: SamObs[];
    first: number;
    present: number;
    meanC: number;
    hero: SamObs | null;
    heroIou: number;
    score: number;
  }
  const scored: Scored[] = [];
  for (const [id, list] of byId) {
    const frames = list.map((o) => o.frame).sort((a, b) => a - b);
    const first = frames[0];
    const span = Math.max(1, heroIdx - first + 1);
    const present = Math.min(1, list.length / span);
    const meanC =
      list.reduce((sum, o) => sum + (o.c ?? 0), 0) / Math.max(1, list.length);
    let hero: SamObs | null = null;
    for (const o of list) {
      if (Math.abs(o.frame - heroIdx) <= 5 && (!hero || Math.abs(o.frame - heroIdx) < Math.abs(hero.frame - heroIdx))) {
        hero = o;
      }
    }
    const heroIou = hero ? iou(sparkBoxPx, hero.box) : 0;
    scored.push({ id, list, first, present, meanC, hero, heroIou, score: 0.6 * heroIou + 0.25 * present + 0.15 * meanC });
  }
  const aligned = scored.filter((s) => s.heroIou >= 0.1);
  const winner =
    (aligned.length > 0 ? bestBy(aligned, (s) => s.score) : null) ??
    bestBy(scored, (s) => s.present * s.meanC);
  if (!winner) return empty;
  return {
    obs: winner.list,
    present: winner.present,
    meanC: winner.meanC,
    hero: winner.hero,
    heroIou: winner.heroIou,
    boxMismatch: aligned.length === 0,
    first: winner.first,
    frameW: winner.list[0]?.w ?? 0,
    frameH: winner.list[0]?.h ?? 0,
  };
}

/** Hero frame index for a checkpoint clip (plan §6.6). */
export function heroIndex(heroTMs: number, clipStartMs: number, fps: number): number {
  return Math.round(((heroTMs - clipStartMs) * fps) / 1000);
}
