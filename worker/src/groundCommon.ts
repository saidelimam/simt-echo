// Shared ground builders (slice W11-06).
//
// Pure/frozen pieces of the W3-05 ground step, used by the SAM lens
// (`samLens.ts`) and the box-only path it degrades to: `boxOnlyOne` builds
// the box-only fallback for one candidate, `boxOnly` maps it over a
// checkpoint, `decodeHeroMask` decodes the hero observation's mask, and
// `toGrounded` finalises an AI_GROUNDED candidate. Moved verbatim from
// — the §6.6 formulas live here exactly once.

import { decodeMaskToSVGPath, formats, frameIndexOf, recordsOfKind } from "@meta-sam/parser";
import type { SamObs } from "./samParse";
import type { Candidate, Checkpoint } from "./types";
import type { ScoutCandidate } from "./scout";

import type { Box } from "./selection";
import { interpolateDistance, rankCandidate, selectTrack, sparkBoxToPixels } from "./selection";

export const GRID = 1000;

export function boxOnlyOne(
  jobId: string,
  scoutModel: string,
  checkpoint: Checkpoint,
  c: ScoutCandidate,
  k: number,
  models: string = `${scoutModel} (box-only)`,
): Candidate {
  return {
    candidateId: `${checkpoint.nodeId}_${k}`,
    title: c.title,
    description: c.description,
    cue: c.cue,
    samPhrase: c.samPhrase,
    signText: c.signText,
    privacyRisk: c.privacyRisk,
    keyframeId: c.imageId,
    box: [...c.box] as Box,
    frameW: GRID,
    frameH: GRID,
    maskSvgPath: null,
    maskW: null,
    maskH: null,
    samConfidence: null,
    trackability: null,
    visibleFromM: null,
    distinctiveness: c.distinctiveness,
    permanence: c.permanence,
    rank: rankCandidate({
      distinctiveness: c.distinctiveness,
      permanence: c.permanence,
      trackability: null,
      samConfidence: null,
      ambiguous: c.ambiguous,
      boxMismatch: false,
    }),
    source: "AI_BOX_ONLY" as const,
    models,
    evidenceKey: `jobs/${jobId}/scout/raw/${checkpoint.nodeId}.0.json`,
  };
}

export function boxOnly(
  jobId: string,
  scoutModel: string,
  checkpoint: Checkpoint,
  candidates: ScoutCandidate[],
): Candidate[] {
  return candidates.map((c, k) => boxOnlyOne(jobId, scoutModel, checkpoint, c, k));
}

/**
 * Decode the hero observation's mask to an SVG path (plan §7.3): the hero
 * line goes to `@meta-sam/parser`'s video parser, and the mask record for
 * this object on this frame goes to its mask→SVG helper. The SVG lives in
 * mask-raster coordinates, so `w`/`h` are the raster's own dims — never
 * the frame's. The mask stays attached to the object that produced it:
 * a multi-object line cannot donate another object's mask. Any shape
 * drift or decode failure null-degrades to box-only (review 0002 R1).
 */
export function decodeHeroMask(hero: SamObs | null): { svg: string; w: number; h: number } | null {
  if (!hero) return null;
  try {
    const parser = formats.segmentation.video().createParser();
    parser.push(`${hero.line}\n`);
    const { result } = parser.finish({ status: "completed" });
    const masks = recordsOfKind(result.records, "mask");
    const rec =
      masks.find((m) => m.objectId === String(hero.id) && frameIndexOf(m) === hero.frame) ??
      (masks.length === 1 ? masks[0] : undefined);
    if (!rec) return null;
    const svg = decodeMaskToSVGPath(rec.mask);
    if (typeof svg !== "string" || svg === "") return null;
    return { svg, w: rec.mask.width, h: rec.mask.height };
  } catch {
    return null;
  }
}

export function toGrounded(
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  evidenceKey: string,
  obs: SamObs[],
  clip: { clipB64: string; fps: number; heroIdx: number; heroTMs: number },
  fallback: Candidate,
  imageFallback: boolean,
): Candidate {
  const frameW = obs[0]?.w ?? GRID;
  const frameH = obs[0]?.h ?? GRID;
  const sparkPx = sparkBoxToPixels(sc.box, frameW, frameH);
  const sel = selectTrack(obs, sparkPx, imageFallback ? 0 : clip.heroIdx);
  // No hero observation means no SAM box in this frame's pixels. Emitting
  // the scout's 0–1000-grid box against SAM frame dims would mix two
  // coordinate systems (review 0002): degrade to the box-only candidate.
  if (!sel.hero || sel.obs.length === 0) return fallback;
  const winW = sel.frameW > 0 ? sel.frameW : frameW;
  const winH = sel.frameH > 0 ? sel.frameH : frameH;
  const trackability = imageFallback ? null : sel.present;
  const samConfidence = sel.hero?.c ?? (sel.meanC || null);
  const tFirst = cp.clip ? cp.clip.startMs + (sel.first * 1000) / Math.max(1e-9, clip.fps) : cp.tMs;
  const visibleFromM = imageFallback
    ? null
    : Math.max(0, cp.atDistanceM - interpolateDistance(cp.approach, tFirst));
  const mask = decodeHeroMask(sel.hero);
  const box = [...sel.hero.box] as Box;
  return {
    candidateId: `${cp.nodeId}_${k}`,
    title: sc.title,
    description: sc.description,
    cue: sc.cue,
    samPhrase: sc.samPhrase,
    signText: sc.signText,
    privacyRisk: sc.privacyRisk,
    keyframeId: sc.imageId,
    box,
    frameW: winW,
    frameH: winH,
    maskSvgPath: mask?.svg ?? null,
    maskW: mask && mask.w > 0 ? mask.w : null,
    maskH: mask && mask.h > 0 ? mask.h : null,
    samConfidence,
    trackability,
    visibleFromM,
    distinctiveness: sc.distinctiveness,
    permanence: sc.permanence,
    rank: rankCandidate({
      distinctiveness: sc.distinctiveness,
      permanence: sc.permanence,
      trackability,
      samConfidence,
      ambiguous: sc.ambiguous,
      boxMismatch: sel.boxMismatch,
    }),
    source: "AI_GROUNDED",
    models: fallback.models,
    evidenceKey,
  };
}
