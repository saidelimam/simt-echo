// Ground step (plan §6.6 + §10 W3-05, slice W3-05; W11-06 adds the lens).
//
// Two modes, one output shape. `GROUND_MODE=legacy` runs the frozen W3-05
// path below verbatim — per-candidate whole-clip video first, hero-image
// fallback, string-concat base64 — until W11-10 removes it or records why
// it stays. Any other value (or unset) runs the SAM lens in `samLens.ts`
// (per-call Workflow steps, content-hash cache, escalation ladder, budget
// governor) through the same `runGround` entry, so callers never branch.
//
// Generic core lives in `groundCommon.ts`, shared by both modes without
// re-implementing §6.6.

import { NonRetryableError } from "cloudflare:workflows";
import type { Env } from "./index";
import { metaResponses, metaResponsesDetailed } from "./meta";
import { parseSam } from "./samParse";
import { boxOnly, toGrounded } from "./groundCommon";
import { inlineRunner, runGroundLens, type LensOptions, type StepRunner } from "./samLens";
import {
  JobResultSchema,
  type Candidate,
  type Checkpoint,
  type JobResult,
  type Manifest,
} from "./types";
import type { ScoutCandidate, ScoutResult } from "./scout";

import type { Box } from "./selection";
import { heroIndex, rankCandidate } from "./selection";

export type { StepRunner };
export { inlineRunner };
export { runGroundLens } from "./samLens";
export type { LensOptions } from "./samLens";

/**
 * Dispatch: the frozen W3-05 path vs the W11-06 SAM lens. `runner` and
 * `lensOpts` only affect the lens; legacy ignores both, keeping public
 * behavior for `GROUND_MODE=legacy` identical.
 */
export async function runGround(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
  runner: StepRunner = inlineRunner,
  lensOpts: LensOptions = {},
): Promise<JobResult> {
  if ((env.GROUND_MODE ?? "lens") === "legacy") {
    return runGroundLegacy(env, jobId, manifest, scout);
  }
  return runGroundLens(env, jobId, manifest, scout, runner, lensOpts);
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
 * Frozen W3-05 ground (see file header): SAM 3.1 when ordered and keyed,
 * box-only otherwise. Reads the scout result + media from R2, writes raw
 * SAM text to `jobs/<id>/sam/<node>_<k>.txt` and the ranked candidates to
 * `jobs/<id>/ground.json`. Total: zero observations, missing clips, and a
 * missing key all degrade to AI_BOX_ONLY instead of throwing.
 */
async function runGroundLegacy(
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
    frameW: 1000,
    frameH: 1000,
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
