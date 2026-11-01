import { z } from "zod";
import type { Env } from "./index";
import { JobIdSchema, type Checkpoint, type Manifest } from "./types";

// Workers AI scout chain (plan §7.2, slice W3-04).
//
// Generic provider plumbing: `runScout` walks SCOUT_ORDER and lets the first
// registered, enabled provider propose candidates for every checkpoint. Only
// `workers-ai` is registered here; `meta-spark` stays unregistered until W3-09
// implements it, so the default `"workers-ai,meta-spark"` order degrades to
// Workers AI alone without branching at the call site. The single model that
// ran is recorded on the result (plan §7: the ids that ran go to result.models).

/** Verbatim SYSTEM prompt shared by every scout (plan §7.1). */
export const SYSTEM = `You help pedestrians follow an indoor route recorded by another person. For each checkpoint you get 2–3 photos taken while walking toward it. The travel direction at each checkpoint is MEASURED BY SENSORS and given to you; never infer or change directions or distances. Propose up to 3 landmarks per checkpoint that a stranger can spot from several metres away. Prefer permanent structure: pillars, doors, elevators, stairs, ramps, signs, painted floor markings, pipes, fire equipment, distinctive walls. Never use vehicles, people, faces, license plates or temporary objects. samPhrase: 1–4 word concrete noun phrase with colour/material, no positions or verbs ("red pillar", "green exit sign", "yellow stair railing"). box: [x1,y1,x2,y2] on a 0–1000 grid of the named image. distinctiveness: how unique it is ALONG THIS ROUTE (a red pillar seen at three checkpoints is low). permanence: 1 = building structure, 0 = movable. Copy legible sign text exactly into signText, else null. Set privacyRisk true if the crop would show people, faces, plates or screens. cue: one short instruction combining the given direction and the landmark, e.g. "Turn left at the red pillar". If nothing suitable is visible, return an empty list.`;

/** Closed set of scout failure codes. `runScout` never surfaces raw model
 * text: every failure is one of these, carried by `ScoutError`. */
export const SCOUT_ERROR_CODES = ["no_provider", "media_missing", "all_models_failed"] as const;
export type ScoutErrorCode = (typeof SCOUT_ERROR_CODES)[number];

export class ScoutError extends Error {
  readonly code: ScoutErrorCode;
  constructor(code: ScoutErrorCode, message: string) {
    // The code rides in the message: the pipeline persists only messages to
    // error.json, so the typed code stays greppable there too.
    super(`[${code}] ${message}`);
    this.name = "ScoutError";
    this.code = code;
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
      items: {
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
          cue: { type: "string" },
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
      },
    },
  },
  required: ["nodeId", "candidates"],
  additionalProperties: false,
} as const;

// R2 layout (plan §5.3, owned by the W3-03 freeze): inputs under
// `jobs/<id>/in/`, scout evidence under `jobs/<id>/scout/`.
const inputKey = (jobId: string, id: string) => `jobs/${jobId}/in/${id}`;
const scoutKey = (jobId: string) => `jobs/${jobId}/scout.json`;
const rawKey = (jobId: string, nodeId: string, attempt: number) =>
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

/** A scout provider: one entry of SCOUT_ORDER. W3-09 adds `meta-spark`
 * by registering here; the chain below needs no change. */
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
    for (const model of models) {
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
              },
              { gateway: { id: gateway } },
            ),
            timeoutMs,
            model,
          );
        } catch (e) {
          lastError = e;
          // Only a binding-level rejection of `response_format` earns the
          // no-schema retry (plan §7.2). Parse/validation failures never
          // reach this catch — they are handled below and move straight
          // to the next model.
          if (!(withSchema && mentionsFormat(e))) break;
          continue;
        }
        try {
          const raw = extractResponse(out);
          const result = ScoutCheckpointSchema.parse(raw);
          // An answer for a different checkpoint is invalid output from
          // this model, not a chain failure: fall through to the next one.
          if (result.nodeId !== checkpoint.nodeId) {
            throw new Error(
              `workers-ai: model answered for ${result.nodeId}, asked for ${checkpoint.nodeId}`,
            );
          }
          return { model, raw, result };
        } catch (e) {
          lastError = e;
          break;
        }
      }
    }
    throw new ScoutError(
      "all_models_failed",
      `workers-ai: every model failed (${models.join(", ") || "no models configured"}): ${errorMessage(lastError)}`,
    );
  },
};

const PROVIDERS: Record<string, ScoutProvider> = {
  "workers-ai": workersAiProvider,
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
  const imgs = loaded
    .map(({ id, tMs }) => {
      const relMs = tMs - cp.tMs;
      const when =
        relMs === 0 ? "at the checkpoint" : `${Math.abs(relMs / 1000).toFixed(1)} s ${relMs < 0 ? "before" : "after"}`;
      return `${id} (${when})`;
    })
    .join(", ");
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

/**
 * Walk SCOUT_ORDER and propose landmarks for every checkpoint.
 *
 * `jobId` scopes R2: keyframes read from `jobs/<id>/in/`, evidence written
 * to `jobs/<id>/scout.json` plus per-attempt raw outputs under
 * `jobs/<id>/scout/raw/`. Returns the compact validated list; the full
 * `Candidate` rows are W3-05/W3-06's expansion. Throws `ScoutError` with a
 * closed code set — never raw model text.
 */
export async function runScout(env: Env, jobId: string, manifest: Manifest): Promise<ScoutResult> {
  const order = (env.SCOUT_ORDER ?? "workers-ai")
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
  const skipped: string[] = [];
  let lastError: unknown = null;
  for (const id of order) {
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
        // ≤ 3 images per call (plan §7.2).
        const refs = cp.keyframes.slice(0, 3);
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
        const attempt = await provider.propose(env, cp, images, context);
        const n = rawAttempts.get(cp.nodeId) ?? 0;
        rawAttempts.set(cp.nodeId, n + 1);
        await env.MEDIA.put(
          rawKey(jobId, cp.nodeId, n),
          JSON.stringify({ model: attempt.model, raw: attempt.raw }),
        );
        ranModels.push(attempt.model);
        checkpoints.push({
          nodeId: cp.nodeId,
          // Only images actually loaded and sent may be boxed: a candidate
          // naming a manifest ref whose bytes never loaded is invented.
          candidates: cleanCandidates(attempt.result, new Set(loaded.map((l) => l.id))),
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
