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
import type { Env } from "./index";
import { metaResponses } from "./meta";
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
 * Decode the hero frame's mask to an SVG path (plan §7.3). The hero line's
 * `<|mask;…|>` section carries the one-bit raster; it is rebuilt as a
 * `SegmentationMask` over the frame and converted with the parser's
 * mask→SVG helper. Any shape drift or decode failure null-degrades to
 * box-only — the caller never throws for a mask.
 */
async function decodeHeroMask(
  heroLine: string | null,
  frameW: number,
  frameH: number,
): Promise<{ svg: string; w: number; h: number } | null> {
  if (!heroLine || frameW <= 0 || frameH <= 0) return null;
  const section = /<\|mask;([^|]*)\|>/.exec(heroLine)?.[1];
  if (!section) return null;
  // Section shape is `<encoding>;<payload>`; the encoding token varies by
  // API version, so the payload is everything after the first `;`.
  const payload = section.includes(";") ? section.slice(section.indexOf(";") + 1) : section;
  if (!payload) return null;
  try {
    const { decodeMaskToSVGPath } = await import("@meta-sam/parser");
    for (const encoding of ["one_bit", "lossless"] as const) {
      try {
        const svg = decodeMaskToSVGPath({ encoding, payload, width: frameW, height: frameH });
        if (typeof svg === "string" && svg !== "") {
          return { svg, w: frameW, h: frameH };
        }
      } catch {
        // Try the next encoding; a final failure degrades below.
      }
    }
    return null;
  } catch {
    // Any parser shape drift null-degrades to box-only (plan §7.3 fallback).
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

  for (const cp of manifest.checkpoints) {
    const kept = scoutByNode.get(cp.nodeId)?.candidates ?? [];
    const keyframeTMs = new Map(cp.keyframes.map((k) => [k.id, k.tMs]));
    if (!samEnabled || !cp.clip || kept.length === 0) {
      checkpoints.push({ nodeId: cp.nodeId, candidates: boxOnly(jobId, scout.model, cp, kept, keyframeTMs) });
      continue;
    }
    const clipObj = await env.MEDIA.get(`jobs/${jobId}/in/${cp.clip.id}`);
    const fps = (cp.clip.frameCount * 1000) / Math.max(1, cp.clip.durationMs);
    if (!clipObj) {
      checkpoints.push({ nodeId: cp.nodeId, candidates: boxOnly(jobId, scout.model, cp, kept, keyframeTMs) });
      continue;
    }
    const clipB64 = toBase64(new Uint8Array(await clipObj.arrayBuffer()));
    const heroTMs = keyframeTMs.get(cp.heroKeyframeId) ?? cp.tMs;
    const heroIdx = heroIndex(heroTMs, cp.clip.startMs, fps);
    const candidates: Candidate[] = [];
    const jobs: Array<Promise<void>> = [];
    kept.forEach((sc, k) => {
      jobs.push(
        (async () => {
          const candidate = await groundOne(env, jobId, scout.model, cp, sc, k, {
            clipB64,
            fps,
            heroIdx,
            heroTMs,
          });
          candidates.push(candidate);
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
    usage: {},
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
  clip: { clipB64: string; fps: number; heroIdx: number; heroTMs: number },
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
    const text = await metaResponses(env, {
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
    await env.MEDIA.put(evidenceKey, text);
    const obs = parseSam(text);
    if (obs.length === 0) {
      // Fallback a (plan §10 W3-05): same phrase on the hero keyframe.
      const heroObj = await env.MEDIA.get(`jobs/${jobId}/in/${sc.imageId}`);
      if (!heroObj) return fallback;
      const heroB64 = toBase64(new Uint8Array(await heroObj.arrayBuffer()));
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
      await env.MEDIA.put(evidenceKey, heroText);
      const heroObs = parseSam(heroText);
      if (heroObs.length === 0) return fallback;
      return toGrounded(jobId, scoutModel, cp, sc, k, evidenceKey, heroObs, clip, null);
    }
    return await toGrounded(jobId, scoutModel, cp, sc, k, evidenceKey, obs, clip, null);
  } catch (e) {
    if (e instanceof NonRetryableError) return fallback;
    throw e;
  }
}

async function toGrounded(
  jobId: string,
  scoutModel: string,
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  evidenceKey: string,
  obs: SamObs[],
  clip: { clipB64: string; fps: number; heroIdx: number; heroTMs: number },
  _unused: null,
): Promise<Candidate> {
  const frameW = obs[0]?.w ?? GRID;
  const frameH = obs[0]?.h ?? GRID;
  const sparkPx = sparkBoxToPixels(sc.box, frameW, frameH);
  const sel = selectTrack(obs, sparkPx, clip.heroIdx, clip.fps);
  const trackability = sel.obs.length === 0 ? null : sel.present;
  const samConfidence = sel.hero?.c ?? (sel.obs.length === 0 ? null : sel.meanC || null);
  const tFirst = cp.clip ? cp.clip.startMs + (sel.first * 1000) / Math.max(1e-9, clip.fps) : cp.tMs;
  const visibleFromM =
    sel.obs.length === 0 ? null : Math.max(0, cp.atDistanceM - interpolateDistance(cp.approach, tFirst));
  const mask = sel.hero ? await decodeHeroMask(sel.hero.line, frameW, frameH) : null;
  const box = sel.hero ? [...sel.hero.box] as Box : ([...sc.box] as Box);
  void jobId;
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
    frameW,
    frameH,
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
    source: sel.obs.length === 0 ? "AI_BOX_ONLY" : "AI_GROUNDED",
    models: `${scoutModel} + sam-3.1`,
    evidenceKey,
  };
}
