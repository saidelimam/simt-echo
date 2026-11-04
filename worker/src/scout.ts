import { z } from "zod";
import { NonRetryableError } from "cloudflare:workflows";
import type { Env } from "./index";
import { metaResponses } from "./meta";
import { frameFailsFlags, inlineRunner, type StepRunner } from "./samLens";
import { JobIdSchema, type Checkpoint, type Manifest } from "./types";

// Scout chain (plan §7, slices W3-04 + W3-09).
//
// Generic provider plumbing: `runScout` walks SCOUT_ORDER and lets the first
// registered, enabled provider propose candidates. `workers-ai` proposes per
// checkpoint (§7.2); `meta-spark` proposes for the whole job in one call
// (§7.1, W3-09). Both share SYSTEM, the SCHEMA candidate shape, zod
// validation and `cleanCandidates`, so Spark candidates are
// indistinguishable in shape from W3-04 candidates downstream. A provider id
// with no credentials is skipped without branching at the call site. The
// single model that ran is recorded on the result (plan §7: the ids that ran
// go to result.models).

/** Verbatim SYSTEM prompt shared by every scout (plan §7.1). */
export const SYSTEM = `You help pedestrians follow an indoor route recorded by another person. For each checkpoint you get 2–3 photos taken while walking toward it. The travel direction at each checkpoint is MEASURED BY SENSORS and given to you; never infer or change directions or distances. Propose up to 3 landmarks per checkpoint that a stranger can spot from several metres away. Prefer permanent structure: pillars, doors, elevators, stairs, ramps, signs, painted floor markings, pipes, fire equipment, distinctive walls. Never use vehicles, people, faces, license plates or temporary objects. samPhrase: 1–4 word concrete noun phrase with colour/material, no positions or verbs ("red pillar", "green exit sign", "yellow stair railing"). box: [x1,y1,x2,y2] on a 0–1000 grid of the named image. distinctiveness: how unique it is ALONG THIS ROUTE (a red pillar seen at three checkpoints is low). permanence: 1 = building structure, 0 = movable. Copy legible sign text exactly into signText, else null. Set privacyRisk true if the crop would show people, faces, plates or screens. cue: one short instruction combining the given direction and the landmark, e.g. "Turn left at the red pillar". If nothing suitable is visible, return an empty list.`;

/** Closed set of scout failure codes. `runScout` never surfaces raw model
 * text: every failure is one of these, carried by `ScoutError`. */
export const SCOUT_ERROR_CODES = ["no_provider", "media_missing", "all_models_failed"] as const;
export type ScoutErrorCode = (typeof SCOUT_ERROR_CODES)[number];

export class ScoutError extends Error {
  readonly code: ScoutErrorCode;
  /** Per-model failure evidence on `all_models_failed` (the raw output
   * where one exists, always the rejection reason); `runScout` persists it
   * under `scout/raw/<nodeId>.failed.json`. */
  readonly details?: Array<{ model: string; error: string; raw?: unknown }>;
  constructor(code: ScoutErrorCode, message: string, details?: ScoutError["details"]) {
    // The code rides in the message: the pipeline persists only messages to
    // error.json, so the typed code stays greppable there too.
    super(`[${code}] ${message}`);
    this.name = "ScoutError";
    this.code = code;
    this.details = details;
  }
}

/** One compact scout proposal (plan §7.1 SCHEMA candidate, verbatim fields). */
export const ScoutCandidateSchema = z.object({
  title: z.string().min(1),
  samPhrase: z.string().min(1),
  description: z.string().min(1),
  imageId: z.string().min(1),
  box: z.tuple([z.number().int(), z.number().int(), z.number().int(), z.number().int()]),
  distinctiveness: z.number().min(0).max(1),
  permanence: z.number().min(0).max(1),
  signText: z.string().nullable(),
  ambiguous: z.boolean(),
  privacyRisk: z.boolean(),
  cue: z.string().min(1),
});
export type ScoutCandidate = z.infer<typeof ScoutCandidateSchema>;

/** One item of SCHEMA.checkpoints: the unit the model returns per call.
 * The prompt instructs ≤3 candidates; the parser tolerates more and trims —
 * strictness is enforced on what we emit (top 2), never by rejecting a
 * usable answer into a typed failure. */
export const ScoutCheckpointSchema = z.object({
  nodeId: JobIdSchema,
  candidates: z.array(ScoutCandidateSchema),
});
export type ScoutCheckpoint = z.infer<typeof ScoutCheckpointSchema>;

/** Compact per-job scout output. Full `Candidate` rows (frameW/mask/rank)
 * are W3-05/W3-06's expansion; the scout only proposes, never grounds. */
export const ScoutResultSchema = z.object({
  model: z.string().min(1),
  checkpoints: z.array(ScoutCheckpointSchema),
});
export type ScoutResult = z.infer<typeof ScoutResultSchema>;

/**
 * Shared candidate JSON-schema fragment (plan §7.1 SCHEMA candidate fields).
 * `CHECKPOINT_JSON_SCHEMA` (per-checkpoint, §7.2) and `SPARK_JSON_SCHEMA`
 * (whole-job, §7.1) compose from it, so the wire shape cannot drift between
 * providers; zod validates either way.
 */
export const CANDIDATE_JSON_SCHEMA = {
  type: "object",
  properties: {
    title: { type: "string" },
    samPhrase: { type: "string" },
    description: { type: "string" },
    imageId: { type: "string" },
    box: { type: "array", items: { type: "integer" }, minItems: 4, maxItems: 4 },
    distinctiveness: { type: "number", minimum: 0, maximum: 1 },
    permanence: { type: "number", minimum: 0, maximum: 1 },
    signText: { type: ["string", "null"] },
    ambiguous: { type: "boolean" },
    privacyRisk: { type: "boolean" },
    // `minLength: 1` matches `ScoutCandidateSchema.cue` exactly: the wire
    // contract and the validator are one contract. Models that return `""`
    // are rescued by cue synthesis (`withCues`), not rejected wholesale.
    cue: { type: "string", minLength: 1 },
  },
  required: [
    "title",
    "samPhrase",
    "description",
    "imageId",
    "box",
    "distinctiveness",
    "permanence",
    "signText",
    "ambiguous",
    "privacyRisk",
    "cue",
  ],
  additionalProperties: false,
} as const;

/**
 * JSON Schema sent as `response_format` (plan §7.2). This is the schema form
 * of `ScoutCheckpointSchema` above: zod validates either way, so a model that
 * rejects `response_format` is retried once without it and still validated.
 */
export const CHECKPOINT_JSON_SCHEMA = {
  type: "object",
  properties: {
    nodeId: { type: "string" },
    candidates: {
      type: "array",
      maxItems: 3,
      items: CANDIDATE_JSON_SCHEMA,
    },
  },
  required: ["nodeId", "candidates"],
  additionalProperties: false,
} as const;

/** Whole-job JSON Schema (plan §7.1 SCHEMA): `checkpoints[]` of the shared
 * item above, all properties required, `additionalProperties: false` at
 * every level. */
export const SPARK_JSON_SCHEMA = {
  type: "object",
  properties: {
    checkpoints: { type: "array", items: CHECKPOINT_JSON_SCHEMA },
  },
  required: ["checkpoints"],
  additionalProperties: false,
} as const;

/** Loosened whole-job schema for the plan's schema-rejection fallback: no
 * `minItems`/`maxItems`, no nullable type arrays. Zod enforces the dropped
 * constraints after parsing. */
function loosenedSparkSchema(): object {
  const candidate = {
    ...CANDIDATE_JSON_SCHEMA,
    properties: {
      ...CANDIDATE_JSON_SCHEMA.properties,
      box: { type: "array", items: { type: "integer" } },
      signText: { type: "string" },
    },
  };
  const item = {
    type: "object",
    properties: {
      nodeId: { type: "string" },
      candidates: { type: "array", items: candidate },
    },
    required: ["nodeId", "candidates"],
    additionalProperties: false,
  };
  return {
    type: "object",
    properties: { checkpoints: { type: "array", items: item } },
    required: ["checkpoints"],
    additionalProperties: false,
  };
}

/** Standard Spark model id (plan §7.1). */
export const SPARK_MODEL = "muse-spark-1.3";

/** Cap on inline images per Spark call (plan §7.1: ≤ 48). */
export const SPARK_MAX_IMAGES = 48;

// R2 layout (plan §5.3, owned by the W3-03 freeze): inputs under
// `jobs/<id>/in/`, scout evidence under `jobs/<id>/scout/`.
const inputKey = (jobId: string, id: string) => `jobs/${jobId}/in/${id}`;
const scoutKey = (jobId: string) => `jobs/${jobId}/scout.json`;
// `attempt` is the success-sequence number, or `"failed"` for the
// every-model-failed evidence object.
const rawKey = (jobId: string, nodeId: string, attempt: number | string) =>
  `jobs/${jobId}/scout/raw/${nodeId}.${attempt}.json`;

const DEFAULT_WORKERS_AI_MODELS =
  "@cf/meta/llama-4-scout-17b-16e-instruct,@cf/google/gemma-4-26b-a4b-it";

/** Minimal structural view of the Workers AI binding (plan §7.2 call shape). */
interface WorkersAiBinding {
  run(
    model: string,
    input: Record<string, unknown>,
    options?: { gateway?: { id: string } },
  ): Promise<unknown>;
}

interface ImageInput {
  id: string;
  b64: string;
}

/** A scout provider: one entry of SCOUT_ORDER. Per-checkpoint providers
 * (`workers-ai`) propose one checkpoint per call; whole-job providers
 * (`meta-spark`) propose the whole manifest in one call via
 * `BatchScoutProvider` below. The chain dispatches on registration, so
 * adding a provider never branches the call site. */
interface ScoutProvider {
  readonly id: string;
  enabled(env: Env): boolean;
  propose(
    env: Env,
    checkpoint: Checkpoint,
    images: ImageInput[],
    context: string,
  ): Promise<{ model: string; raw: unknown; result: ScoutCheckpoint }>;
}

/** A whole-job scout provider: one call for all keyframes (plan §7.1). */
interface BatchScoutProvider {
  readonly id: string;
  enabled(env: Env): boolean;
  proposeAll(env: Env, jobId: string, manifest: Manifest): Promise<ScoutResult>;
}

/** `workers-ai` provider: Llama 4 Scout first, then Gemma fallback
 * (plan §7.2 `for (const model of ...)` loop, via AI Gateway). */
const workersAiProvider: ScoutProvider = {
  id: "workers-ai",

  enabled(env: Env): boolean {
    return Boolean(env.AI);
  },

  async propose(env, checkpoint, images, context) {
    const ai = env.AI as unknown as WorkersAiBinding;
    const models = (env.WORKERS_AI_MODELS ?? DEFAULT_WORKERS_AI_MODELS)
      .split(",")
      .map((m) => m.trim())
      .filter((m) => m !== "");
    const gateway = env.AI_GATEWAY_ID;
    const timeoutMs = modelTimeoutMs(env);
    let lastError: unknown = null;
    // Per-model evidence for the `all_models_failed` typed failure: the raw
    // output where one exists (validation failures) plus always the
    // rejection reason. `runScout` persists it under
    // `scout/raw/<nodeId>.failed.json` so a failed job leaves a trace.
    const attempts: Array<{ model: string; error: string; raw?: unknown }> = [];
    for (const model of models) {
      let raw: unknown;
      for (let attempt = 0; attempt < 2; attempt++) {
        const withSchema = attempt === 0;
        let out: unknown;
        try {
          out = await withModelTimeout(
            ai.run(
              model,
              {
                messages: [
                  { role: "system", content: SYSTEM },
                  {
                    role: "user",
                    content: [
                      {
                        type: "text",
                        text: withSchema
                          ? context
                          : `${context}\nReturn only JSON matching this schema: ${JSON.stringify(CHECKPOINT_JSON_SCHEMA)}`,
                      },
                      ...images.map((img) => ({
                        type: "image_url",
                        image_url: { url: `data:image/jpeg;base64,${img.b64}` },
                      })),
                    ],
                  },
                ],
                ...(withSchema
                  ? {
                      response_format: {
                        type: "json_schema",
                        json_schema: CHECKPOINT_JSON_SCHEMA,
                      },
                    }
                  : {}),
                max_tokens: 1500,
                temperature: 0.1,
                ...thinkingOffKwargs(model),
              },
              { gateway: { id: gateway } },
            ),
            timeoutMs,
            model,
          );
        } catch (e) {
          lastError = e;
          attempts.push({ model, error: errorMessage(e) });
          // Only a binding-level rejection of `response_format` earns the
          // no-schema retry (plan §7.2). Parse/validation failures never
          // reach this catch — they are handled below and move straight
          // to the next model.
          if (!(withSchema && mentionsFormat(e))) break;
          continue;
        }
        try {
          raw = extractResponse(out);
          const result = ScoutCheckpointSchema.parse(withCues(raw, checkpoint));
          // An answer for a different checkpoint is invalid output from
          // this model, not a chain failure: fall through to the next one.
          if (result.nodeId !== checkpoint.nodeId) {
            const err = new Error(
              `workers-ai: model answered for ${result.nodeId}, asked for ${checkpoint.nodeId}`,
            );
            lastError = err;
            attempts.push({ model, error: err.message, raw });
            break;
          }
          return { model, raw, result };
        } catch (e) {
          lastError = e;
          attempts.push({ model, error: errorMessage(e), ...(raw !== undefined ? { raw } : {}) });
          break;
        }
      }
    }
    throw new ScoutError(
      "all_models_failed",
      `workers-ai: every model failed (${models.join(", ") || "no models configured"}): ${errorMessage(lastError)}`,
      attempts,
    );
  },
};

/** `chat_template_kwargs` that turns reasoning off, keyed to the model
 * family that needs it: gemma-4 spends `max_tokens` on thinking before
 * emitting `content` (live-verified on the failed run: `content: null`,
 * `finish_reason: "length"` at 1500 tokens). The key is per-model — K2.6
 * uses `thinking`, llama takes neither — so it is not applied to the whole
 * chain. */
function thinkingOffKwargs(model: string): Record<string, unknown> {
  return /gemma-4/i.test(model) ? { chat_template_kwargs: { enable_thinking: false } } : {};
}

const PROVIDERS: Record<string, ScoutProvider> = {
  "workers-ai": workersAiProvider,
};

/** Whole-job `checkpoints[]` response (plan §7.1 SCHEMA). Same validation
 * as W3-04: the item schema is shared, so Spark output is held to the same
 * bar as Workers AI output. */
const SparkResponseSchema = z.object({
  checkpoints: z.array(ScoutCheckpointSchema),
});

/** Relative timing of one image against its checkpoint (shared by the
 * single- and whole-job CONTEXT forms so the phrasing cannot drift). */
function describeImage(id: string, tMs: number, refTMs: number): string {
  const relMs = tMs - refTMs;
  const when =
    relMs === 0 ? "at the checkpoint" : `${Math.abs(relMs / 1000).toFixed(1)} s ${relMs < 0 ? "before" : "after"}`;
  return `${id} (${when})`;
}

/** Whole-job user context (plan §7.1 CONTEXT verbatim). Only loaded images
 * are named: the model must never reference a photo it was not sent. */
export function sparkContext(
  routeContext: string,
  checkpoints: Array<{ cp: Checkpoint; loaded: Array<{ id: string; tMs: number }> }>,
): string {
  const lines = checkpoints.map(
    ({ cp, loaded }) =>
      `- ${cp.nodeId}: ${turnLabel(cp)} at ${cp.atDistanceM} m (sensor-measured). Images: ${loaded.map((l) => describeImage(l.id, l.tMs, cp.tMs)).join(", ")}.`,
  );
  return `Route: ${routeContext}. Checkpoints in walking order:\n${lines.join("\n")}`;
}

/** One Spark request body (plan §7.1 verbatim). `reasoning.effort` and the
 * schema are parameters so the low→medium and strict→loosened retries share
 * the shape instead of rebuilding it. */
function sparkBody(
  context: string,
  images: Array<{ id: string; b64: string }>,
  opts: { effort: "low" | "medium"; schema: object; instructions?: string },
): object {
  const content: unknown[] = [
    { type: "input_text", text: context },
    ...images.flatMap((img) => [
      { type: "input_text", text: `imageId=${img.id}` },
      { type: "input_image", image_url: `data:image/jpeg;base64,${img.b64}` },
    ]),
  ];
  const system = { type: "message", role: "system", content: [{ type: "input_text", text: SYSTEM }] };
  const user = { type: "message", role: "user", content };
  return {
    model: SPARK_MODEL,
    reasoning: { effort: opts.effort },
    ...(opts.instructions !== undefined ? { instructions: opts.instructions } : {}),
    input: opts.instructions !== undefined ? [user] : [system, user],
    text: { format: { type: "json_schema", name: "echo_landmarks", strict: true, schema: opts.schema } },
  };
}

function mentionsRole(e: unknown): boolean {
  return /\brole\b|instructions/i.test(errorMessage(e));
}

function mentionsSchema(e: unknown): boolean {
  return /json_schema|additionalProperties|minItems|maxItems|nullable/i.test(errorMessage(e));
}

/**
 * Muse Spark scout (plan §10 W3-09): one call for all keyframes (≤ 48
 * images), SYSTEM/SCHEMA verbatim, `reasoning.effort = "low"`, through the
 * generic `metaResponses` SSE collector. Same validation and
 * `cleanCandidates` shaping as W3-04, so candidates are indistinguishable
 * downstream. On JSON parse failure retries once with `"medium"`; on a 400
 * that mentions the role moves SYSTEM to top-level `instructions`; on a
 * schema rejection drops `minItems`/`maxItems` and nullable arrays and
 * enforces them with zod. Evidence mirrors the per-checkpoint path:
 * `scout.json` plus the single raw output under `scout/raw/spark.0.json`.
 * Throws `ScoutError` with the closed code set — never raw model text.
 */
export async function metaSparkScout(env: Env, jobId: string, manifest: Manifest): Promise<ScoutResult> {
  const images: Array<{ id: string; b64: string }> = [];
  const loadedByCp = new Map<string, Array<{ id: string; tMs: number }>>();
  for (const cp of manifest.checkpoints) {
    const loaded: Array<{ id: string; tMs: number }> = [];
    // R12-07 (review 0012, F7.1): a keyframe the on-device filter refused —
    // a face, bad quality, failed exposure — is never read, let alone sent to
    // a vendor. The lens already applied this filter; the scout paths did not.
    // ≤ 3 eligible images per checkpoint (mirrors §7.2); the 48-image cap
    // below is the plan's per-request bound.
    for (const ref of cp.keyframes.filter((r) => !frameFailsFlags(cp, r.id)).slice(0, 3)) {
      if (images.length >= SPARK_MAX_IMAGES) break;
      const obj = await env.MEDIA.get(inputKey(jobId, ref.id));
      if (!obj) continue;
      images.push({ id: ref.id, b64: toBase64(new Uint8Array(await obj.arrayBuffer())) });
      loaded.push({ id: ref.id, tMs: ref.tMs });
    }
    loadedByCp.set(cp.nodeId, loaded);
  }
  if (images.length === 0) {
    throw new ScoutError(
      "media_missing",
      `no keyframe bytes for job ${jobId} under jobs/${jobId}/in/`,
    );
  }
  const context = sparkContext(
    manifest.routeContext,
    manifest.checkpoints.map((cp) => ({ cp, loaded: loadedByCp.get(cp.nodeId) ?? [] })),
  );

  let text: string;
  try {
    try {
      text = await metaResponses(env, sparkBody(context, images, { effort: "low", schema: SPARK_JSON_SCHEMA }));
    } catch (e) {
      // Plan §7.1 fallbacks, in order: role rejection moves SYSTEM to
      // `instructions`; schema rejection loosens the schema. Anything else
      // (including retryable 429/5xx, which the Workflow would retry)
      // falls through to the next provider via the chain.
      if (e instanceof NonRetryableError && mentionsRole(e) && !mentionsSchema(e)) {
        text = await metaResponses(
          env,
          sparkBody(context, images, { effort: "low", schema: SPARK_JSON_SCHEMA, instructions: SYSTEM }),
        );
      } else if (e instanceof NonRetryableError && mentionsSchema(e)) {
        text = await metaResponses(
          env,
          sparkBody(context, images, { effort: "low", schema: loosenedSparkSchema() }),
        );
      } else {
        throw e;
      }
    }
  } catch (e) {
    if (e instanceof ScoutError) throw e;
    // A retryable Meta 429/5xx must reach the Workflow's step retry as
    // itself — wrapping it in a typed ScoutError would be converted to
    // NonRetryableError by the pipeline and kill the job (review 0004).
    if (isRetryableMetaError(e)) throw e;
    throw new ScoutError("all_models_failed", `meta-spark: request failed: ${errorMessage(e)}`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    // Plan §10 W3-09: on JSON parse failure retry once with "medium".
    try {
      text = await metaResponses(env, sparkBody(context, images, { effort: "medium", schema: SPARK_JSON_SCHEMA }));
      parsed = JSON.parse(text);
    } catch (e) {
      if (isRetryableMetaError(e)) throw e;
      throw new ScoutError("all_models_failed", `meta-spark: unparseable output: ${errorMessage(e)}`);
    }
  }
  parsed = withSparkCues(parsed, manifest);

  let response: z.infer<typeof SparkResponseSchema>;
  try {
    response = SparkResponseSchema.parse(parsed);
  } catch (e) {
    throw new ScoutError(
      "all_models_failed",
      `meta-spark: schema-invalid output: ${e instanceof Error ? e.message : String(e)}`,
    );
  }
  const knownNodes = new Set(manifest.checkpoints.map((c) => c.nodeId));
  if (!response.checkpoints.some((c) => knownNodes.has(c.nodeId))) {
    throw new ScoutError(
      "all_models_failed",
      "meta-spark: answered for unknown checkpoints; trying next provider",
    );
  }
  const byNode = new Map(response.checkpoints.map((c) => [c.nodeId, c]));
  const checkpoints = manifest.checkpoints.map((cp) => ({
    nodeId: cp.nodeId,
    // Same shaping as W3-04, per checkpoint: only images loaded AND sent
    // for THIS checkpoint may be boxed — never another checkpoint's
    // keyframes (review 0004 R2, the review-0001 invariant).
    candidates: cleanCandidates(
      byNode.get(cp.nodeId) ?? { nodeId: cp.nodeId, candidates: [] },
      new Set((loadedByCp.get(cp.nodeId) ?? []).map((l) => l.id)),
    ),
  }));
  const result = ScoutResultSchema.parse({ model: SPARK_MODEL, checkpoints });
  await env.MEDIA.put(scoutKey(jobId), JSON.stringify(result));
  await env.MEDIA.put(rawKey(jobId, "spark", 0), JSON.stringify({ model: SPARK_MODEL, raw: parsed }));
  return result;
}

const BATCH_PROVIDERS: Record<string, BatchScoutProvider> = {
  "meta-spark": {
    id: "meta-spark",
    enabled: (env) => Boolean(env.META_API_KEY),
    proposeAll: metaSparkScout,
  },
};

function extractResponse(out: unknown): unknown {
  if (typeof out === "object" && out !== null && "response" in out) {
    const response = (out as { response: unknown }).response;
    return typeof response === "string" ? JSON.parse(response) : response;
  }
  throw new ScoutError(
    "all_models_failed",
    "workers-ai: model output has no `response` field; trying next model",
  );
}

function mentionsFormat(e: unknown): boolean {
  return /response_format|json_schema/i.test(errorMessage(e));
}

/** Per-model call bound: a hung first model must not starve the fallback.
 * The binding takes no abort signal, so the call is raced; the loser keeps
 * running in the background but the chain moves on. */
export const DEFAULT_MODEL_TIMEOUT_MS = 90_000;

function modelTimeoutMs(env: Env): number {
  const parsed = Number(env.SCOUT_MODEL_TIMEOUT_MS);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_MODEL_TIMEOUT_MS;
}

async function withModelTimeout<T>(call: Promise<T>, timeoutMs: number, model: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      call,
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new Error(`workers-ai: ${model} timed out after ${timeoutMs} ms`)),
          timeoutMs,
        );
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Portable base64 (no Buffer/btoa dependency): works in workerd and Node. */
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

function turnLabel(cp: Checkpoint): string {
  if (cp.type === "TURN") return `TURN ${cp.turn ?? "UNKNOWN"}`;
  if (cp.type === "LEVEL_CHANGE") return "LEVEL CHANGE";
  return cp.type;
}

/** Per-checkpoint user context (plan §7.1 CONTEXT, single-checkpoint form
 * for the §7.2 one-call-per-checkpoint shape). Only loaded images are named:
 * the model must never reference a photo it was not sent. */
export function checkpointContext(
  routeContext: string,
  cp: Checkpoint,
  loaded: Array<{ id: string; tMs: number }>,
): string {
  const imgs = loaded.map(({ id, tMs }) => describeImage(id, tMs, cp.tMs)).join(", ");
  return (
    `Route: ${routeContext}. Checkpoint in walking order: ` +
    `- ${cp.nodeId}: ${turnLabel(cp)} at ${cp.atDistanceM} m (sensor-measured). Images: ${imgs}.`
  );
}

const clampBox = (box: readonly [number, number, number, number]): [number, number, number, number] => {
  const [x1, y1, x2, y2] = box.map((v) => Math.min(1000, Math.max(0, v)));
  return [Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)];
};

/** Drop candidates Naming images the checkpoint never had, clamp boxes to
 * the 0–1000 grid (fixing inverted edges), keep top 2 by
 * distinctiveness·permanence (plan §10 W3-04; W3-05 grounds this pair). */
export function cleanCandidates(
  raw: ScoutCheckpoint,
  knownImageIds: Set<string>,
): ScoutCheckpoint["candidates"] {
  return raw.candidates
    .filter((c) => knownImageIds.has(c.imageId))
    .map((c) => ({ ...c, box: clampBox(c.box) }))
    .sort((a, b) => b.distinctiveness * b.permanence - a.distinctiveness * a.permanence)
    .slice(0, 2);
}

/** Fallback cue for a candidate the model returned with an empty `cue`:
 * the sensor-measured direction + the landmark the model named — the
 * SYSTEM prompt's own definition of a cue. Synthesizing preserves the rest
 * of a usable answer instead of rejecting the whole checkpoint into a
 * typed failure (the wire contract asks for non-empty; models return ""). */
function synthesizeCue(cp: Pick<Checkpoint, "type" | "turn">, samPhrase: string): string {
  if (cp.type === "TURN" && cp.turn) return `Turn ${cp.turn.toLowerCase()} at the ${samPhrase}`;
  return `Look for the ${samPhrase}`;
}

function fillCue(cand: { cue?: unknown; samPhrase?: unknown }, cp: Pick<Checkpoint, "type" | "turn">): void {
  const phrase = typeof cand.samPhrase === "string" ? cand.samPhrase.trim() : "";
  if (phrase !== "" && (typeof cand.cue !== "string" || cand.cue.trim() === "")) {
    cand.cue = synthesizeCue(cp, phrase);
  }
}

/** Fill empty `cue` values in a per-checkpoint raw answer before zod
 * validation (a no-op for anything that is not the expected shape). */
function withCues(raw: unknown, cp: Pick<Checkpoint, "type" | "turn">): unknown {
  if (typeof raw === "object" && raw !== null && Array.isArray((raw as { candidates?: unknown }).candidates)) {
    for (const cand of (raw as { candidates: unknown[] }).candidates) {
      if (typeof cand === "object" && cand !== null) fillCue(cand as { cue?: unknown; samPhrase?: unknown }, cp);
    }
  }
  return raw;
}

/** The same fill for a whole-job raw answer (`{ checkpoints: [...] }`):
 * each item is matched to its manifest checkpoint for the direction. */
function withSparkCues(raw: unknown, manifest: Manifest): unknown {
  if (typeof raw === "object" && raw !== null && Array.isArray((raw as { checkpoints?: unknown }).checkpoints)) {
    const byNode = new Map(manifest.checkpoints.map((cp) => [cp.nodeId, cp] as const));
    for (const item of (raw as { checkpoints: unknown[] }).checkpoints) {
      if (typeof item !== "object" || item === null || !Array.isArray((item as { candidates?: unknown }).candidates)) {
        continue;
      }
      const nodeId = (item as { nodeId?: unknown }).nodeId;
      const cp = typeof nodeId === "string" ? byNode.get(nodeId) : undefined;
      if (!cp) continue;
      for (const cand of (item as { candidates: unknown[] }).candidates) {
        if (typeof cand === "object" && cand !== null) fillCue(cand as { cue?: unknown; samPhrase?: unknown }, cp);
      }
    }
  }
  return raw;
}

/** Raw evidence for a checkpoint whose every model failed: the per-model
 * raw outputs and rejection reasons under `scout/raw/<nodeId>.failed.json`.
 * Raw writes exist only on the success path, so without this a failed job
 * leaves no scout trace in R2. Best-effort: an evidence write must never
 * mask the typed failure. */
async function persistFailedRaw(env: Env, jobId: string, nodeId: string, e: ScoutError): Promise<void> {
  try {
    await env.MEDIA.put(
      rawKey(jobId, nodeId, "failed"),
      JSON.stringify({ nodeId, error: e.message, attempts: e.details ?? [] }),
    );
  } catch {
    // Diagnostic only; the typed failure stands on its own.
  }
}

/** True for the Meta transport's retryable (429/5xx) signal: a plain
 * Error the Workflow step is supposed to retry, not a typed failure. */
function isRetryableMetaError(e: unknown): boolean {
  return (
    !(e instanceof ScoutError) &&
    !(e instanceof NonRetryableError) &&
    errorMessage(e).startsWith("retryable meta")
  );
}

/**
 * Walk SCOUT_ORDER and propose landmarks for every checkpoint.
 *
 * `jobId` scopes R2: keyframes read from `jobs/<id>/in/`, evidence written
 * to `jobs/<id>/scout.json` plus per-attempt raw outputs under
 * `jobs/<id>/scout/raw/`. Returns the compact validated list; the full
 * `Candidate` rows are W3-05/W3-06's expansion. Throws `ScoutError` with a
 * closed code set — never raw model text.
 *
 * R10-04 (review 0010, F3): every paid call runs inside a Workflow step —
 * one `scout:<provider>:<nodeId>` per checkpoint, one `scout:<provider>`
 * for whole-job providers — so on a replay a completed step returns its
 * recorded output and a failed checkpoint re-bills itself, never
 * checkpoints 1..n-1. Mirrors the lens (`samLens.ts`); `runner` defaults
 * to `inlineRunner` for callers without a Workflow step.
 */
export async function runScout(
  env: Env,
  jobId: string,
  manifest: Manifest,
  runner: StepRunner = inlineRunner,
): Promise<ScoutResult> {
  const order = (env.SCOUT_ORDER ?? "workers-ai")
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
  const skipped: string[] = [];
  let lastError: unknown = null;
  for (const id of order) {
    // Whole-job providers (meta-spark) run the manifest in one call;
    // per-checkpoint providers (workers-ai) loop below. Registration
    // decides the shape, so the chain never branches on provider names.
    const batch = BATCH_PROVIDERS[id];
    if (batch) {
      if (!batch.enabled(env)) {
        skipped.push(id);
        continue;
      }
      try {
        // One step for the whole job: a replay returns the recorded
        // ScoutResult, so the single paid call is never re-billed (R10-04).
        return await runner(`scout:${id}`, () => batch.proposeAll(env, jobId, manifest));
      } catch (e) {
        lastError = e;
        if (e instanceof ScoutError && e.code === "media_missing") throw e;
        // Retryable transport errors go back to the Workflow step retry,
        // not silently to the next provider (review 0004 R2).
        if (isRetryableMetaError(e)) throw e;
        continue;
      }
    }
    const provider = PROVIDERS[id];
    if (!provider || !provider.enabled(env)) {
      skipped.push(id);
      continue;
    }
    try {
      const checkpoints: ScoutCheckpoint[] = [];
      const ranModels: string[] = [];
      const rawAttempts = new Map<string, number>();
      for (const cp of manifest.checkpoints) {
        // R12-07 (review 0012, F7.1): keyframes the on-device filter refused
        // — a face, bad quality, failed exposure — are never read, let alone
        // sent to a vendor. The lens already applied this rule; the scout did
        // not. A checkpoint with nothing admissible gets no model call and no
        // candidates: the honest answer, not a guess over refused pixels.
        const refs = cp.keyframes.filter((r) => !frameFailsFlags(cp, r.id)).slice(0, 3);
        if (refs.length === 0) {
          checkpoints.push({ nodeId: cp.nodeId, candidates: [] });
          continue;
        }
        // One step per checkpoint (R10-04), mirroring the lens: a replay
        // returns the recorded output, so a failed checkpoint re-bills
        // itself, never checkpoints 1..n-1. The step returns what the
        // continuation needs; the raw model output is written inside the
        // step, so a replay skips the write (already persisted).
        const { model, result, loaded } = await runner(
          `scout:${id}:${cp.nodeId}`,
          async () => {
            // ≤ 3 eligible images per call (plan §7.2).
            const images: ImageInput[] = [];
            const loaded: Array<{ id: string; tMs: number }> = [];
            for (const ref of refs) {
              const obj = await env.MEDIA.get(inputKey(jobId, ref.id));
              if (!obj) continue;
              images.push({ id: ref.id, b64: toBase64(new Uint8Array(await obj.arrayBuffer())) });
              loaded.push({ id: ref.id, tMs: ref.tMs });
            }
            if (images.length === 0) {
              throw new ScoutError(
                "media_missing",
                `no keyframe bytes for checkpoint ${cp.nodeId} under jobs/${jobId}/in/`,
              );
            }
            const context = checkpointContext(manifest.routeContext, cp, loaded);
            try {
              const attempt = await provider.propose(env, cp, images, context);
              const n = rawAttempts.get(cp.nodeId) ?? 0;
              rawAttempts.set(cp.nodeId, n + 1);
              await env.MEDIA.put(
                rawKey(jobId, cp.nodeId, n),
                JSON.stringify({ model: attempt.model, raw: attempt.raw }),
              );
              return { model: attempt.model, result: attempt.result, loaded };
            } catch (e) {
              // all_models_failed is deterministic: every model for this
              // checkpoint already failed. Persist the raw evidence, then
              // make the step non-retryable so a replay re-bills nothing
              // (R10-04).
              if (e instanceof ScoutError && e.code === "all_models_failed") {
                await persistFailedRaw(env, jobId, cp.nodeId, e);
                throw new NonRetryableError(e.message);
              }
              throw e;
            }
          },
        );
        ranModels.push(model);
        checkpoints.push({
          nodeId: cp.nodeId,
          // Only images actually loaded and sent may be boxed: a candidate
          // naming a manifest ref whose bytes never loaded is invented.
          candidates: cleanCandidates(result, new Set(loaded.map((l) => l.id))),
        });
      }
      // The model(s) that ran, in checkpoint order (plan §7: the ids that ran
      // go to result.models; W3-05 extends this with " + sam-3.1").
      const result = ScoutResultSchema.parse({
        model: [...new Set(ranModels)].join(" + "),
        checkpoints,
      });
      await env.MEDIA.put(scoutKey(jobId), JSON.stringify(result));
      return result;
    } catch (e) {
      lastError = e;
      if (e instanceof ScoutError && e.code === "media_missing") throw e;
      // Provider threw (typed ScoutError or exhausted retries): try next.
    }
  }
  if (lastError instanceof ScoutError) throw lastError;
  const tried = order.length === 0 ? "(empty SCOUT_ORDER)" : order.join(",");
  throw new ScoutError(
    "no_provider",
    `no scout provider ran (order: ${tried}; skipped without credentials: ${skipped.join(", ") || "none"}): ${errorMessage(lastError)}`,
  );
}
