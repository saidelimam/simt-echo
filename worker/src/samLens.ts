// Worker SAM Lens (plan §10 W11-06, closes G6–G9; T with S review).
//
// Per checkpoint, in node-priority order (DESTINATION > TURN >
// LEVEL_CHANGE > CHECKPOINT):
//   1. Drop candidates whose keyframe fails the W11-04 quality/face flags —
//      zero SAM spend, AI_BOX_ONLY (stable output shape, same treatment as
//      a governor refusal).
//   2. SAM image on the hero frame for the top-2 survivors (1 frame each),
//      one Workflow step `ground:<nodeId>:<k>:<phraseHash>` per call, so a retry
//      re-bills one call — and the content-hash cache makes the re-bill zero
//      frames when the first attempt already fetched.
//   3. SAM video on the staged clip only for the top candidate at
//      TURN/DESTINATION, or when image confidence is in [0.5, 0.85)
//      (step `ground:<nodeId>:<k>:<phraseHash>:video`).
// The budget governor reserves every estimate before any call is sent and
// never exceeds `budget.maxSamFrames`; `usage.samFramesBilled` sums only
// vendor-reported `response.completed` frames and `budgetExhausted` records
// a refusal. W10-06 checkpoint + usage events are emitted per checkpoint.
//
// Single-phrase-per-call: the W11-07 multi-phrase probe
// (docs/measurements.md "SAM live probe" row 1) is TBD — no META_API_KEY
// in this environment, $0 spent — so MULTI_PHRASE stays default off and
// every phrase gets its own call (see [phrasesPerCall]).

import { NonRetryableError } from "cloudflare:workflows";
import type { Env } from "./index";
import { emitJobEvent, newEventId } from "./jobs";
import { metaResponsesDetailed } from "./meta";
import { parseSam } from "./samParse";
import { boxOnly, boxOnlyOne, toGrounded } from "./groundCommon";
import {
  JobResultSchema,
  MAX_SAM_FRAMES,
  type Candidate,
  type Checkpoint,
  type JobEvent,
  type JobResult,
  type Manifest,
} from "./types";
import type { ScoutCandidate, ScoutResult } from "./scout";
import { heroIndex } from "./selection";

/** One Workflow step per SAM call: the unit of retry (plan §10 W11-06).
 * A step returns everything the lens needs from it (candidate, frames, ms)
 * and the lens aggregates only from those returned values: on a Workflow
 * replay a completed step returns its recorded output without running the
 * callback, so nothing may live in callback side effects. */
export type StepRunner = <T extends Rpc.Serializable<T>>(
  name: string,
  fn: () => Promise<T>,
) => Promise<T>;

/** Direct execution (tests, non-Workflow callers): steps run inline. */
export const inlineRunner: StepRunner = (_name, fn) => fn();

/** Emits one job event inside its own step: a replay returns the recorded
 * step instead of emitting a duplicate under a fresh event id. */
export function emitStep(
  runner: StepRunner,
  env: Env,
  jobId: string,
  name: string,
  build: (id: string) => JobEvent,
): Promise<void> {
  return runner(name, async () => {
    await emitJobEvent(env, jobId, build(newEventId()));
  });
}

/** Test/threading knobs for the Meta transport bounds (defaults: 60 s/20 s). */
export interface LensOptions {
  samTimeoutMs?: number;
  samIdleMs?: number;
}

/** Bounded concurrency for SAM calls (plan §10 W11-06 item 5). */
export const LENS_CONCURRENCY = 4;

const SAM_MODEL = "sam-3.1";
const SAM_METADATA = { include_confidence: "true", mask_encoding: "one_bit" } as const;
const IMAGE_PARAMS = JSON.stringify({ kind: "image", model: SAM_MODEL, metadata: SAM_METADATA });
const VIDEO_PARAMS = JSON.stringify({ kind: "video", model: SAM_MODEL, metadata: SAM_METADATA });

// nodejs_compat provides Buffer in workerd; Node provides it in vitest.
// A structural type keeps this precise without pulling in @types/node.
const nodeBuffer = (
  globalThis as unknown as {
    Buffer: { from(b: Uint8Array): { toString(e: "base64"): string } };
  }
).Buffer;

function toBase64(bytes: Uint8Array): string {
  return nodeBuffer.from(bytes).toString("base64");
}

async function hexSha256(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes as unknown as ArrayBuffer);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Step-name hash: first 12 hex of sha256(samPhrase), deterministic. */
export async function phraseHash(phrase: string): Promise<string> {
  return (await hexSha256(new TextEncoder().encode(phrase))).slice(0, 12);
}

/** `k` is the candidate's checkpoint-local index, so two survivors that
 * share a phrase still get distinct, replay-stable step names. */
export function imageStepName(nodeId: string, k: number, hash: string): string {
  return `ground:${nodeId}:${k}:${hash}`;
}

export function videoStepName(nodeId: string, k: number, hash: string): string {
  return `ground:${nodeId}:${k}:${hash}:video`;
}

/**
 * Phrases per video call. Always 1: the W11-07 probe row 1
 * (docs/measurements.md "Multi-phrase in one call?") is TBD, so batching
 * stays off even when MULTI_PHRASE=on. When the probe says yes, return >1
 * here and attribute per-phrase output at the video call site.
 */
function phrasesPerCall(env: Env): number {
  void (env.MULTI_PHRASE ?? "off");
  return 1;
}

/** Node priority for governor reservation (plan §10 W11-06 item 4). */
function priorityOf(cp: Checkpoint): number {
  switch (cp.type) {
    case "DESTINATION":
      return 0;
    case "TURN":
      return 1;
    case "LEVEL_CHANGE":
      return 2;
    case "CHECKPOINT":
      return 3;
  }
}

/**
 * Spend guard (S-review-grade: enforced before any call). Estimates are
 * reserved synchronously while planning — atomically in JS, so concurrent
 * pool tasks cannot double-spend. Actuals come back in the step results; vendor
 * billing above the estimate is a recorded residual risk (W11-07 measures
 * real billing; the governor caps on estimates regardless of price).
 */
class Governor {
  constructor(
    readonly max: number,
    public reserved = 0,
    public exhausted = false,
  ) {}
  reserve(estimate: number): boolean {
    if (this.reserved + estimate > this.max) {
      this.exhausted = true;
      return false;
    }
    this.reserved += estimate;
    return true;
  }
}

/** What the governor decided for one planned call. A `hit` is already in
 * the content-hash cache, so it is replayed at zero frames and reserves
 * nothing; a `refused` call keeps its box-only fallback. */
type PlanDecision = "call" | "hit" | "refused";

/** Recorded outcome of a planning step: the decisions plus the governor
 * state they leave behind, so a Workflow replay restores both instead of
 * re-deciding against a cache the first attempt has since filled. */
interface PlanResult {
  decisions: PlanDecision[];
  reserved: number;
  exhausted: boolean;
}

const cachePath = (jobId: string, key: string) => `jobs/${jobId}/sam/cache/${key}.txt`;

/** True when a non-empty cached response exists for the key (own job, or
 * the sanctioned `resumeOf` job). Metadata only: no body is read. */
async function isCached(env: Env, jobId: string, key: string, resumeOf: string | undefined): Promise<boolean> {
  for (const owner of resumeOf !== undefined ? [jobId, resumeOf] : [jobId]) {
    const head = await env.MEDIA.head(cachePath(owner, key));
    if (head !== null && head.size > 0) return true;
  }
  return false;
}

/**
 * Cache first, then reserve: a cache hit consumes no budget, so a resume or
 * retry cannot report a false `budgetExhausted`. Misses reserve in the order
 * given (node priority). Pure in the governor state it is handed, so a
 * retried step cannot double-reserve.
 */
async function planCalls(
  env: Env,
  jobId: string,
  resumeOf: string | undefined,
  start: Governor,
  items: Array<{ key: string; estimate: number }>,
): Promise<PlanResult> {
  const gov = new Governor(start.max, start.reserved, start.exhausted);
  const decisions: PlanDecision[] = [];
  for (const { key, estimate } of items) {
    if (await isCached(env, jobId, key, resumeOf)) decisions.push("hit");
    else decisions.push(gov.reserve(estimate) ? "call" : "refused");
  }
  return { decisions, reserved: gov.reserved, exhausted: gov.exhausted };
}

interface LensBudget {
  maxSamFrames: number;
  resumeOf?: string;
  /** Why the hard ceiling was applied in place of a declared budget. */
  note?: string;
}

/** A recorded reason the lens degraded (written to `sam/notes.json`). */
interface LensNote {
  scope: string;
  reason: string;
}

/**
 * The spend cap is never absent: a missing budget.json (v1 / manifest-only
 * creates) caps at the hard ceiling, and an unreadable or invalid one fails
 * closed to the same ceiling with a recorded reason, never to "uncapped".
 */
async function readLensBudget(env: Env, jobId: string): Promise<LensBudget> {
  const obj = await env.MEDIA.get(`jobs/${jobId}/budget.json`);
  if (!obj) return { maxSamFrames: MAX_SAM_FRAMES };
  try {
    const raw = (await obj.json()) as { maxSamFrames?: unknown; resumeOf?: unknown } | null;
    if (typeof raw !== "object" || raw === null) {
      return { maxSamFrames: MAX_SAM_FRAMES, note: "budget_unreadable" };
    }
    const resume =
      typeof raw.resumeOf === "string" && raw.resumeOf !== "" ? { resumeOf: raw.resumeOf } : {};
    if (raw.maxSamFrames === undefined) return { maxSamFrames: MAX_SAM_FRAMES, ...resume };
    if (typeof raw.maxSamFrames !== "number" || !(raw.maxSamFrames > 0)) {
      return { maxSamFrames: MAX_SAM_FRAMES, note: "budget_invalid", ...resume };
    }
    return { maxSamFrames: Math.min(raw.maxSamFrames, MAX_SAM_FRAMES), ...resume };
  } catch {
    return { maxSamFrames: MAX_SAM_FRAMES, note: "budget_unreadable" };
  }
}

/** Frames the governor reserves for a clip. The count is client-declared,
 * so it is clamped to [1, hard ceiling]: an absurd declaration can neither
 * overflow the accounting nor reserve more than any budget can hold. */
function clipFrameEstimate(clip: { frameCount: number }): number {
  return Math.min(Math.max(clip.frameCount, 1), MAX_SAM_FRAMES);
}

/** Cache key = sha256(media) + phrase + model + params (plan §10 W11-06). */
async function cacheKeyHex(media: Uint8Array, phrase: string, params: string): Promise<string> {
  const head = new TextEncoder().encode(`${SAM_MODEL}\n${params}\n${phrase}\n`);
  const bytes = new Uint8Array(head.length + media.length);
  bytes.set(head, 0);
  bytes.set(media, head.length);
  return hexSha256(bytes);
}

interface SamCall {
  text: string;
  reported?: number;
  cached: boolean;
  samMs: number;
}

/**
 * One SAM call with cache lookup-then-fetch. The cache write lands
 * immediately after a successful fetch — before evidence/parse — so a
 * Workflow retry of this step replays from R2 at zero frames (closes G7).
 * Empty text is never cached: it would turn one bad response into a
 * permanent fallback. `resumeOf` is the only sanctioned cross-job reuse
 * (W10-06 scoping): a hit there is adopted into this job's own prefix.
 * `mustHit` is set when planning reserved nothing because the key was
 * cached: a vanished entry then degrades to the fallback rather than
 * sending an unreserved call.
 */
async function samCall(
  env: Env,
  jobId: string,
  media: Uint8Array,
  key: string,
  buildBody: (b64: string) => object,
  resumeOf: string | undefined,
  mustHit: boolean,
  lensOpts: LensOptions,
): Promise<SamCall> {
  const ownKey = cachePath(jobId, key);
  const own = await env.MEDIA.get(ownKey);
  const ownText = own ? await own.text() : "";
  if (ownText !== "") return { text: ownText, cached: true, samMs: 0 };
  if (resumeOf !== undefined) {
    const prior = await env.MEDIA.get(cachePath(resumeOf, key));
    const text = prior ? await prior.text() : "";
    if (text !== "") {
      await env.MEDIA.put(ownKey, text);
      return { text, cached: true, samMs: 0 };
    }
  }
  if (mustHit) throw new NonRetryableError("sam cache entry vanished after planning");
  const callStart = Date.now();
  const detailed = await metaResponsesDetailed(env, buildBody(toBase64(media)), {
    ...(lensOpts.samTimeoutMs !== undefined ? { timeoutMs: lensOpts.samTimeoutMs } : {}),
    ...(lensOpts.samIdleMs !== undefined ? { idleMs: lensOpts.samIdleMs } : {}),
  });
  const samMs = Date.now() - callStart;
  if (detailed.text.trim() !== "") await env.MEDIA.put(ownKey, detailed.text);
  return { text: detailed.text, reported: detailed.videoFramesProcessed, cached: false, samMs };
}

function imageBody(phrase: string, b64: string): object {
  return {
    model: SAM_MODEL,
    metadata: { ...SAM_METADATA },
    input: [
      {
        type: "message",
        role: "user",
        content: [
          { type: "input_text", text: phrase },
          { type: "input_image", image_url: `data:image/jpeg;base64,${b64}` },
        ],
      },
    ],
  };
}

function videoBody(phrase: string, b64: string): object {
  return {
    model: SAM_MODEL,
    metadata: { ...SAM_METADATA },
    input: [
      {
        type: "message",
        role: "user",
        content: [
          { type: "input_text", text: phrase },
          { type: "input_video", video_url: `data:video/mp4;base64,${b64}` },
        ],
      },
    ],
  };
}

/**
 * W11-04 per-frame flags (carried by `KeyframeRefSchema`). Present-and-failing
 * flags drop the candidate; absent flags keep it (today's manifests).
 */
function frameFailsFlags(cp: Checkpoint, imageId: string): boolean {
  const ref = cp.keyframes.find((k) => k.id === imageId);
  if (!ref) return false;
  if (ref.faceFree === false) return true;
  if (ref.quality === "bad") return true;
  if (ref.exposureOk === false) return true;
  return false;
}

function lensFallback(jobId: string, scoutModel: string, cp: Checkpoint, sc: ScoutCandidate, k: number): Candidate {
  const base = boxOnlyOne(jobId, scoutModel, cp, sc, k, cp.tMs);
  return {
    ...base,
    models: `${scoutModel} + sam-3.1`,
    evidenceKey: `jobs/${jobId}/sam/${cp.nodeId}_${k}.txt`,
  };
}

async function runPool<T>(tasks: Array<() => Promise<T>>, limit: number): Promise<T[]> {
  const out = new Array<T>(tasks.length);
  let next = 0;
  const workers = Array.from({ length: Math.max(1, Math.min(limit, tasks.length)) }, async () => {
    while (next < tasks.length) {
      const i = next++;
      out[i] = await tasks[i]();
    }
  });
  await Promise.all(workers);
  return out;
}

/** Frames and time one SAM call consumed. Zero when the call replayed from
 * the content-hash cache or fell back. */
interface CallSpend {
  sentFrames: number;
  billedFrames: number;
  ms: number;
}

const NO_SPEND: CallSpend = { sentFrames: 0, billedFrames: 0, ms: 0 };

/** Serializable output of one image step: everything the lens aggregates
 * is read from this value, never from callback side effects. */
interface ImageStepResult extends CallSpend {
  k: number;
  candidate: Candidate;
  /** Why the call degraded to the fallback (recorded in `sam/notes.json`). */
  reason?: string;
}

/** Output of one video step; `candidate` is null when the clip added
 * nothing and the image result stands. */
interface VideoStepResult extends CallSpend {
  k: number;
  candidate: Candidate | null;
  reason?: string;
}

/**
 * Event emits and the `ground.json` write, each in its own step so a replay
 * neither re-emits under new ids nor rewrites the sealed result.
 */
async function sealLens(
  env: Env,
  jobId: string,
  runner: StepRunner,
  result: JobResult,
  emitUsage: boolean,
  notes: LensNote[] = [],
): Promise<void> {
  for (const c of result.checkpoints) {
    await emitStep(runner, env, jobId, `emit:checkpoint:${c.nodeId}`, (id) => ({
      id,
      type: "checkpoint",
      nodeId: c.nodeId,
      status: c.candidates.some((cand) => cand.source === "AI_GROUNDED") ? "grounded" : "skipped",
      candidates: c.candidates,
    }));
  }
  if (emitUsage) {
    await emitStep(runner, env, jobId, "emit:usage", (id) => ({
      id,
      type: "usage",
      usage: result.usage,
    }));
  }
  await runner("ground:seal", async () => {
    if (notes.length > 0) await env.MEDIA.put(`jobs/${jobId}/sam/notes.json`, JSON.stringify(notes));
    await env.MEDIA.put(`jobs/${jobId}/ground.json`, JSON.stringify(result));
  });
}

/**
 * The W11-06 SAM lens (see file header). Seals the same
 * `jobs/<id>/ground.json` shape as the legacy path, plus
 * `usage.samFramesBilled`/`usage.budgetExhausted` whenever SAM ran.
 */
export async function runGroundLens(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
  runner: StepRunner = inlineRunner,
  lensOpts: LensOptions = {},
): Promise<JobResult> {
  const groundOrder = (env.GROUND_ORDER ?? "sam31,none")
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
  const samOrdered = groundOrder.includes("sam31") || groundOrder.includes("sam-3.1");
  const samEnabled = samOrdered && Boolean(env.META_API_KEY);
  const scoutByNode = new Map(scout.checkpoints.map((c) => [c.nodeId, c]));

  if (!samEnabled) {
    const checkpoints: JobResult["checkpoints"] = manifest.checkpoints.map((cp) => ({
      nodeId: cp.nodeId,
      candidates: boxOnly(
        jobId,
        scout.model,
        cp,
        scoutByNode.get(cp.nodeId)?.candidates ?? [],
        new Map(cp.keyframes.map((k) => [k.id, k.tMs])),
      ).sort((a, b) => b.rank - a.rank),
    }));
    const result = JobResultSchema.parse({
      jobId,
      models: { scout: scout.model, ground: "none" },
      checkpoints,
      usage: {},
    });
    await sealLens(env, jobId, runner, result, false);
    return result;
  }

  const budget = await readLensBudget(env, jobId);
  let gov = new Governor(budget.maxSamFrames);
  const notes: LensNote[] = budget.note ? [{ scope: "budget", reason: budget.note }] : [];
  const perCall = phrasesPerCall(env);

  interface Planned {
    cp: Checkpoint;
    index: number;
    kept: ScoutCandidate[];
    /** Survivor indexes into kept (top-2, flags passing). */
    survivors: number[];
    heroBytes: Map<number, Uint8Array>;
    clipBytes: Uint8Array | null;
    clipFps: number;
    clipHeroIdx: number;
  }

  // Planning is a pure function of the manifest, the scout step output, the
  // staged media and budget.json, so a replay reproduces it exactly.
  const planned: Planned[] = [];
  for (let index = 0; index < manifest.checkpoints.length; index++) {
    const cp = manifest.checkpoints[index];
    const kept = scoutByNode.get(cp.nodeId)?.candidates ?? [];
    // Top-2 by scout signal (same ordering cleanCandidates enforces).
    const ranked = kept
      .map((sc, k) => ({ sc, k }))
      .sort((a, b) => b.sc.distinctiveness * b.sc.permanence - a.sc.distinctiveness * a.sc.permanence)
      .slice(0, 2);
    const survivors = ranked.filter(({ sc }) => !frameFailsFlags(cp, sc.imageId)).map(({ k }) => k);
    const heroBytes = new Map<number, Uint8Array>();
    for (const k of survivors) {
      const obj = await env.MEDIA.get(`jobs/${jobId}/in/${kept[k].imageId}`);
      if (obj) heroBytes.set(k, new Uint8Array(await obj.arrayBuffer()));
    }
    let clipBytes: Uint8Array | null = null;
    let clipFps = 0;
    let clipHeroIdx = 0;
    if (cp.clip) {
      const clipObj = await env.MEDIA.get(`jobs/${jobId}/in/${cp.clip.id}`);
      if (clipObj) {
        clipBytes = new Uint8Array(await clipObj.arrayBuffer());
        const heroTMs = cp.keyframes.find((k) => k.id === cp.heroKeyframeId)?.tMs ?? cp.tMs;
        clipFps = (cp.clip.frameCount * 1000) / Math.max(1, cp.clip.durationMs);
        clipHeroIdx = heroIndex(heroTMs, cp.clip.startMs, clipFps);
      }
    }
    planned.push({ cp, index, kept, survivors, heroBytes, clipBytes, clipFps, clipHeroIdx });
  }

  // Governor reservation order: node priority first, manifest order within.
  const byPriority = [...planned].sort(
    (a, b) => priorityOf(a.cp) - priorityOf(b.cp) || a.index - b.index,
  );

  // Candidates per checkpoint, starting as box-only fallbacks; step results
  // replace them below. Refused and media-less calls keep the fallback.
  const finalResults = new Map<number, Map<number, Candidate>>();
  for (const p of planned) {
    const perCp = new Map<number, Candidate>();
    finalResults.set(p.index, perCp);
    for (let k = 0; k < p.kept.length; k++) {
      perCp.set(k, lensFallback(jobId, scout.model, p.cp, p.kept[k], k));
    }
  }
  const spend = { samFrames: 0, samFramesBilled: 0, samMs: 0 };
  const tally = (r: CallSpend & { reason?: string }, scope: string) => {
    if (r.reason) notes.push({ scope, reason: r.reason });
    spend.samFrames += r.sentFrames;
    spend.samFramesBilled += r.billedFrames;
    spend.samMs += r.ms;
  };

  /** Runs one governed phase: a recorded planning step decides each call
   * (cache first, then reserve), then every non-refused call runs as its
   * own step. The governor state it leaves is restored from the recorded
   * plan, so a replay never re-decides against a cache that has since
   * been filled. */
  const planPhase = async (
    name: string,
    items: Array<{ key: string; estimate: number }>,
  ): Promise<PlanDecision[]> => {
    if (items.length === 0) return [];
    const plan = await runner(name, () => planCalls(env, jobId, budget.resumeOf, gov, items));
    gov = new Governor(gov.max, plan.reserved, plan.exhausted);
    return plan.decisions;
  };

  // Image phase: 1 frame per hero call, planned before any call is sent.
  const imageCalls: Array<{ p: Planned; k: number; media: Uint8Array; key: string }> = [];
  for (const p of byPriority) {
    for (const k of p.survivors) {
      const media = p.heroBytes.get(k);
      if (!media) continue;
      imageCalls.push({ p, k, media, key: await cacheKeyHex(media, p.kept[k].samPhrase, IMAGE_PARAMS) });
    }
  }
  const imageDecisions = await planPhase(
    "ground:plan:image",
    imageCalls.map(({ key }) => ({ key, estimate: 1 })),
  );
  const imageTasks: Array<{ name: string; perCp: Map<number, Candidate>; run: () => Promise<ImageStepResult> }> = [];
  for (const [i, { p, k, media, key }] of imageCalls.entries()) {
    const decision = imageDecisions[i];
    const perCp = finalResults.get(p.index);
    const fallback = perCp?.get(k);
    if (decision === "refused" || !perCp || !fallback) continue;
    const sc = p.kept[k];
    const name = imageStepName(p.cp.nodeId, k, await phraseHash(sc.samPhrase));
    const evidenceKey = `jobs/${jobId}/sam/${p.cp.nodeId}_${k}.txt`;
    imageTasks.push({
      name,
      perCp,
      run: () =>
        runner(name, async () => ({
          k,
          ...(await imageCall(env, jobId, p.cp, sc, k, media, key, decision === "hit", fallback, evidenceKey, budget.resumeOf, lensOpts)),
        })),
    });
  }
  const imageOut = await runPool(
    imageTasks.map((t) => t.run),
    LENS_CONCURRENCY,
  );
  imageOut.forEach((r, i) => {
    imageTasks[i].perCp.set(r.k, r.candidate);
    tally(r, imageTasks[i].name);
  });

  // Video phase: top survivor only, TURN/DESTINATION or mid confidence.
  const videoCalls: Array<{
    p: Planned;
    perCp: Map<number, Candidate>;
    topK: number;
    top: Candidate;
    clipBytes: Uint8Array;
    key: string;
  }> = [];
  for (const p of byPriority) {
    const perCp = finalResults.get(p.index);
    if (!perCp || !p.clipBytes || p.survivors.length === 0) continue;
    // Top survivor by image-phase rank (image-grounded or box-only stand-in).
    let topK = p.survivors[0];
    for (const k of p.survivors) {
      if ((perCp.get(k)?.rank ?? -Infinity) > (perCp.get(topK)?.rank ?? -Infinity)) topK = k;
    }
    const top = perCp.get(topK);
    const conf = top?.samConfidence;
    const nodeWantsVideo = p.cp.type === "TURN" || p.cp.type === "DESTINATION";
    const confWantsVideo = conf !== null && conf !== undefined && conf >= 0.5 && conf < 0.85;
    if (!top || (!nodeWantsVideo && !confWantsVideo)) continue;
    const key = await cacheKeyHex(p.clipBytes, p.kept[topK].samPhrase, VIDEO_PARAMS);
    videoCalls.push({ p, perCp, topK, top, clipBytes: p.clipBytes, key });
  }
  const videoDecisions = await planPhase(
    "ground:plan:video",
    videoCalls.map(({ p, key }) => ({ key, estimate: p.cp.clip ? clipFrameEstimate(p.cp.clip) : 0 })),
  );
  const videoTasks: Array<{ name: string; perCp: Map<number, Candidate>; run: () => Promise<VideoStepResult> }> = [];
  for (const [i, { p, perCp, topK, top, clipBytes, key }] of videoCalls.entries()) {
    const decision = videoDecisions[i];
    if (decision === "refused") continue;
    const sc = p.kept[topK];
    const name = videoStepName(p.cp.nodeId, topK, await phraseHash(sc.samPhrase));
    const evidenceKey = `jobs/${jobId}/sam/${p.cp.nodeId}_${topK}.txt`;
    const clip = { fps: p.clipFps, heroIdx: p.clipHeroIdx };
    // The plan above is keyed on the first phrase; multi-phrase batching
    // (default off, probe TBD) must plan per phrase before it is enabled.
    const phrases = [sc.samPhrase].slice(0, perCall);
    videoTasks.push({
      name,
      perCp,
      run: () =>
        runner(name, async () => {
          const out: VideoStepResult = { k: topK, candidate: null, ...NO_SPEND };
          for (const phrase of phrases) {
            const call = await videoCall(env, jobId, p.cp, sc, topK, clipBytes, phrase, key, decision === "hit", evidenceKey, clip, top, budget.resumeOf, lensOpts);
            out.sentFrames += call.sentFrames;
            out.billedFrames += call.billedFrames;
            out.ms += call.ms;
            if (call.candidate) out.candidate = call.candidate;
            if (call.reason) out.reason = call.reason;
          }
          return out;
        }),
    });
  }
  const videoOut = await runPool(
    videoTasks.map((t) => t.run),
    LENS_CONCURRENCY,
  );
  videoOut.forEach((r, i) => {
    if (r.candidate) videoTasks[i].perCp.set(r.k, r.candidate);
    tally(r, videoTasks[i].name);
  });

  const checkpoints: JobResult["checkpoints"] = planned.map((p) => {
    const perCp = finalResults.get(p.index);
    const candidates = p.kept.map((_, k) => perCp?.get(k) ?? lensFallback(jobId, scout.model, p.cp, p.kept[k], k));
    candidates.sort((a, b) => b.rank - a.rank || (a.candidateId < b.candidateId ? -1 : 1));
    return { nodeId: p.cp.nodeId, candidates };
  });

  const usage = {
    ...(spend.samFrames > 0 ? { samFrames: Math.round(spend.samFrames) } : {}),
    ...(spend.samMs > 0 ? { samMs: Math.round(spend.samMs) } : {}),
    samFramesBilled: Math.round(spend.samFramesBilled),
    budgetExhausted: gov.exhausted,
  };
  const result = JobResultSchema.parse({
    jobId,
    models: { scout: scout.model, ground: "sam-3.1" },
    checkpoints,
    usage,
  });
  await sealLens(env, jobId, runner, result, true, notes);
  return result;
}


/** Reason recorded when a non-retryable failure degrades a call to its
 * fallback (truncated: vendor error bodies can be long). */
function nonRetryableReason(e: NonRetryableError): string {
  return `non_retryable: ${e.message.slice(0, 200)}`;
}

/** One hero-frame SAM call. A non-retryable failure degrades to the
 * box-only fallback at zero recorded spend, with the reason returned. */
async function imageCall(
  env: Env,
  jobId: string,
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  media: Uint8Array,
  key: string,
  mustHit: boolean,
  fallback: Candidate,
  evidenceKey: string,
  resumeOf: string | undefined,
  lensOpts: LensOptions,
): Promise<{ candidate: Candidate; reason?: string } & CallSpend> {
  try {
    const call = await samCall(env, jobId, media, key, (b64) => imageBody(sc.samPhrase, b64), resumeOf, mustHit, lensOpts);
    // Single hero frame sent; vendor image billing is unmeasured until
    // the W11-07 probe (row 3), so sent frames stand in.
    const spend: CallSpend = call.cached
      ? NO_SPEND
      : { sentFrames: call.reported ?? 1, billedFrames: call.reported ?? 0, ms: call.samMs };
    if (call.text.trim() === "") return { candidate: fallback, reason: "empty_response", ...spend };
    await env.MEDIA.put(evidenceKey, call.text);
    const obs = parseSam(call.text);
    if (obs.length === 0) return { candidate: fallback, ...spend };
    const candidate = toGrounded(cp, sc, k, evidenceKey, obs, { clipB64: "", fps: 0, heroIdx: 0, heroTMs: cp.tMs }, fallback, true);
    return { candidate, ...spend };
  } catch (e) {
    if (e instanceof NonRetryableError) {
      return { candidate: fallback, reason: nonRetryableReason(e), ...NO_SPEND };
    }
    throw e;
  }
}

/**
 * Video escalation for the top candidate. `candidate` is the upgraded
 * candidate, or null when the clip yields nothing usable (the image result
 * stands — never a downgrade to box-only).
 */
async function videoCall(
  env: Env,
  jobId: string,
  cp: Checkpoint,
  sc: ScoutCandidate,
  k: number,
  media: Uint8Array,
  phrase: string,
  key: string,
  mustHit: boolean,
  evidenceKey: string,
  clip: { fps: number; heroIdx: number },
  standIn: Candidate,
  resumeOf: string | undefined,
  lensOpts: LensOptions,
): Promise<{ candidate: Candidate | null; reason?: string } & CallSpend> {
  try {
    const call = await samCall(env, jobId, media, key, (b64) => videoBody(phrase, b64), resumeOf, mustHit, lensOpts);
    const spend: CallSpend = call.cached
      ? NO_SPEND
      : { sentFrames: call.reported ?? (cp.clip ? clipFrameEstimate(cp.clip) : 0), billedFrames: call.reported ?? 0, ms: call.samMs };
    if (call.text.trim() === "") return { candidate: null, reason: "empty_response", ...spend };
    await env.MEDIA.put(evidenceKey, call.text);
    const obs = parseSam(call.text);
    if (obs.length === 0) return { candidate: null, ...spend };
    // Mismatch degrades to the image result, not to box-only: the video
    // disagreeing never erases grounding the hero frame already earned.
    const upgraded = toGrounded(
      cp,
      sc,
      k,
      evidenceKey,
      obs,
      { clipB64: "", fps: clip.fps, heroIdx: clip.heroIdx, heroTMs: cp.tMs },
      { ...standIn, evidenceKey, models: standIn.models },
      false,
    );
    return { candidate: upgraded.source === "AI_GROUNDED" ? upgraded : null, ...spend };
  } catch (e) {
    if (e instanceof NonRetryableError) {
      return { candidate: null, reason: nonRetryableReason(e), ...NO_SPEND };
    }
    throw e;
  }
}
