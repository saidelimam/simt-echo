// Ground step (plan §6.6 + §10 W3-05, slice W3-05).
//
// Two paths, one output shape. Without a Meta key (or with GROUND_ORDER
// resolving to `none`) scout boxes pass through as AI_BOX_ONLY. With a key,
// each kept candidate is grounded against SAM 3.1 on its checkpoint clip and
// selected/ranked by the §6.6 formulas; the mask null-degrades to box-only
// whenever SAM yields nothing usable — never a crash.
//
// Generic core: `bestBy` picks any item by a numeric score, `iou` works on
// any `[x1,y1,x2,y2]` tuple, and `rankCandidate` is pure over scalars, so
// W3-06's assemble reuses all three without re-implementing §6.6.

import { NonRetryableError } from "cloudflare:workflows";
import { decodeMaskToSVGPath, formats, frameIndexOf, recordsOfKind } from "@meta-sam/parser";
import type { Env } from "./index";
import { metaResponses, metaResponsesDetailed } from "./meta";
import { parseSam, type SamObs } from "./samParse";
import {
  JobResultSchema,
  type Candidate,
  type Checkpoint,
  type JobResult,
  type Manifest,
} from "./types";
import type { ScoutCandidate, ScoutResult } from "./scout";

import type { Box } from "./selection";
import {
  heroIndex,
  interpolateDistance,
  iou,
  rankCandidate,
  selectTrack,
  sparkBoxToPixels,
} from "./selection";

const GRID = 1000;

function boxOnly(
  jobId: string,
  scoutModel: string,
  checkpoint: Checkpoint,
  candidates: ScoutCandidate[],
  keyframeTMs: Map<string, number>,
): Candidate[] {
  return candidates.map((c, k) => {
    const tMs = keyframeTMs.get(c.imageId) ?? checkpoint.tMs;
    void tMs;
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
      models: `${scoutModel} (box-only)`,
      evidenceKey: `jobs/${jobId}/scout/raw/${checkpoint.nodeId}.0.json`,
    };
  });
}

function toBase64(bytes: Uint8Array): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i];
    const b = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const c = i + 2 < bytes.length ? bytes[i + 2] : 0;
    const n = (a << 16) | (b << 8) | c;
    out += chars[(n >> 18) & 63] + chars[(n >> 12) & 63];
    out += i + 1 < bytes.length ? chars[(n >> 6) & 63] : "=";
    out += i + 2 < bytes.length ? chars[n & 63] : "=";
  }
  return out;
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
function decodeHeroMask(hero: SamObs | null): { svg: string; w: number; h: number } | null {
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

/**
 * Ground every checkpoint: SAM 3.1 when ordered and keyed, box-only
 * otherwise. Reads the scout result + media from R2, writes raw SAM text to
 * `jobs/<id>/sam/<node>_<k>.txt` and the ranked candidates to
 * `jobs/<id>/ground.json`. Total: zero observations, missing clips, and a
 * missing key all degrade to AI_BOX_ONLY instead of throwing.
 */
export async function runGround(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
): Promise<JobResult> {
  const groundOrder = (env.GROUND_ORDER ?? "sam31,none")
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
  const samOrdered = groundOrder.includes("sam31") || groundOrder.includes("sam-3.1");
  const samEnabled = samOrdered && Boolean(env.META_API_KEY);
  const scoutByNode = new Map(scout.checkpoints.map((c) => [c.nodeId, c]));
  const checkpoints: JobResult["checkpoints"] = [];
  // Honest usage (review 0004 R1): video frames actually sent to SAM
  // (vendor-reported when response.completed carries usage, else the
  // sent clip's frame count per send) and Meta wall time actually spent.
  const usage = { videoFrames: 0, samMs: 0 };

  for (const cp of manifest.checkpoints) {
    const kept = scoutByNode.get(cp.nodeId)?.candidates ?? [];
    const keyframeTMs = new Map(cp.keyframes.map((k) => [k.id, k.tMs]));
    if (!samEnabled || kept.length === 0) {
      // Box-only is the default path, and its consumers preselect
      // candidates[0]: it gets the same §6.6 rank order as the SAM path.
      const ranked = boxOnly(jobId, scout.model, cp, kept, keyframeTMs).sort(
        (a, b) => b.rank - a.rank,
      );
      checkpoints.push({ nodeId: cp.nodeId, candidates: ranked });
      continue;
    }
    const heroTMs = keyframeTMs.get(cp.heroKeyframeId) ?? cp.tMs;
    // Fallback a (plan §10 W3-05): no clip — or no clip bytes — grounds on
    // the hero keyframe image instead of short-circuiting to box-only.
    let clip: { clipB64: string; fps: number; heroIdx: number; heroTMs: number } | null = null;
    if (cp.clip) {
      const clipObj = await env.MEDIA.get(`jobs/${jobId}/in/${cp.clip.id}`);
      if (clipObj) {
        const fps = (cp.clip.frameCount * 1000) / Math.max(1, cp.clip.durationMs);
        clip = {
          clipB64: toBase64(new Uint8Array(await clipObj.arrayBuffer())),
          fps,
          heroIdx: heroIndex(heroTMs, cp.clip.startMs, fps),
          heroTMs,
        };
      }
    }
    const candidates: Candidate[] = [];
    const jobs: Array<Promise<void>> = [];
    kept.forEach((sc, k) => {
      jobs.push(
        (async () => {
          candidates.push(await groundOne(env, jobId, scout.model, cp, sc, k, clip, usage));
        })(),
      );
    });
    await Promise.all(jobs);
    candidates.sort((a, b) => b.rank - a.rank);
    checkpoints.push({ nodeId: cp.nodeId, candidates });
  }

  const result = JobResultSchema.parse({
    jobId,
    models: {
      scout: scout.model,
      ground: samEnabled ? "sam-3.1" : "none",
    },
    checkpoints,
    usage: {
      ...(usage.videoFrames > 0 ? { samFrames: Math.round(usage.videoFrames) } : {}),
      ...(samEnabled && usage.samMs > 0 ? { samMs: Math.round(usage.samMs) } : {}),
    },
  });
  await env.MEDIA.put(`jobs/${jobId}/ground.json`, JSON.stringify(result));
  return result;
}

async function groundOne(
  env: Env,
  jobId: string,
  scoutModel: string,
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  clip: { clipB64: string; fps: number; heroIdx: number; heroTMs: number } | null,
  usage: { videoFrames: number; samMs: number },
): Promise<Candidate> {
  const evidenceKey = `jobs/${jobId}/sam/${cp.nodeId}_${k}.txt`;
  const fallback: Candidate = {
    candidateId: `${cp.nodeId}_${k}`,
    title: sc.title,
    description: sc.description,
    cue: sc.cue,
    samPhrase: sc.samPhrase,
    signText: sc.signText,
    privacyRisk: sc.privacyRisk,
    keyframeId: sc.imageId,
    box: [...sc.box] as Box,
    frameW: GRID,
    frameH: GRID,
    maskSvgPath: null,
    maskW: null,
    maskH: null,
    samConfidence: null,
    trackability: null,
    visibleFromM: null,
    distinctiveness: sc.distinctiveness,
    permanence: sc.permanence,
    rank: rankCandidate({
      distinctiveness: sc.distinctiveness,
      permanence: sc.permanence,
      trackability: null,
      samConfidence: null,
      ambiguous: sc.ambiguous,
      boxMismatch: false,
    }),
    source: "AI_BOX_ONLY",
    models: `${scoutModel} + sam-3.1`,
    evidenceKey,
  };
  try {
    if (!clip) {
      // Fallback a from the start: no clip to ground against.
      return await groundViaImage(env, jobId, cp, sc, k, evidenceKey, fallback, usage);
    }
    const callStart = Date.now();
    const detailed = await metaResponsesDetailed(env, {
      model: "sam-3.1",
      metadata: { include_confidence: "true", mask_encoding: "one_bit" },
      input: [
        {
          type: "message",
          role: "user",
          content: [
            { type: "input_text", text: sc.samPhrase },
            { type: "input_video", video_url: `data:video/mp4;base64,${clip.clipB64}` },
          ],
        },
      ],
    });
    usage.samMs += Date.now() - callStart;
    // Vendor-reported frames when present; otherwise the frames of the
    // clip this call actually sent (per candidate, not per checkpoint).
    usage.videoFrames += detailed.videoFramesProcessed ?? (cp.clip?.frameCount ?? 0);
    const text = detailed.text;
    await env.MEDIA.put(evidenceKey, text);
    const obs = parseSam(text);
    if (obs.length === 0) {
      // Fallback a (plan §10 W3-05): same phrase on the hero keyframe.
      return await groundViaImage(env, jobId, cp, sc, k, evidenceKey, fallback, usage);
    }
    return toGrounded(cp, sc, k, evidenceKey, obs, clip, fallback, false);
  } catch (e) {
    if (e instanceof NonRetryableError) return fallback;
    throw e;
  }
}

/**
 * Fallback a (plan §10 W3-05): ground the phrase on the candidate's hero
 * keyframe via `input_image`. Single-frame grounding has no track, so
 * `trackability` and `visibleFromM` are null by spec — never the real
 * clip-derived values.
 */
async function groundViaImage(
  env: Env,
  jobId: string,
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  evidenceKey: string,
  fallback: Candidate,
  usage: { videoFrames: number; samMs: number },
): Promise<Candidate> {
  const heroObj = await env.MEDIA.get(`jobs/${jobId}/in/${sc.imageId}`);
  if (!heroObj) return fallback;
  const heroB64 = toBase64(new Uint8Array(await heroObj.arrayBuffer()));
  const callStart = Date.now();
  const heroText = await metaResponses(env, {
    model: "sam-3.1",
    metadata: { include_confidence: "true", mask_encoding: "one_bit" },
    input: [
      {
        type: "message",
        role: "user",
        content: [
          { type: "input_text", text: sc.samPhrase },
          { type: "input_image", image_url: `data:image/jpeg;base64,${heroB64}` },
        ],
      },
    ],
  });
  usage.samMs += Date.now() - callStart;
  await env.MEDIA.put(evidenceKey, heroText);
  const heroObs = parseSam(heroText);
  if (heroObs.length === 0) return fallback;
  return toGrounded(
    cp,
    sc,
    k,
    evidenceKey,
    heroObs,
    { clipB64: "", fps: 0, heroIdx: 0, heroTMs: cp.tMs },
    fallback,
    true,
  );
}

function toGrounded(
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
  const sel = selectTrack(obs, sparkPx, imageFallback ? 0 : clip.heroIdx, clip.fps);
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
