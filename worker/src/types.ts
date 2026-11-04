import { z } from "zod";

// W3-03 contract freeze (plan §5 owns these shapes; later slices fit them,
// never widen them). Every bound below traces to §5.4/§5.5: id charset and
// the 60-file / 80 MB caps come from §10 W3-03, the checkpoint/result field
// lists are §5.5 verbatim, and the node-type/turn vocabularies mirror §5.2.

/** Job lifecycle states. `terminated` maps to `errored` at the API boundary. */
export const JobStatusSchema = z.enum(["queued", "running", "complete", "errored"]);
export type JobStatus = z.infer<typeof JobStatusSchema>;

/** Progress stages the app's Processing screen renders (plan §8.3). */
export const JobStageSchema = z.enum([
  "queued",
  "reading",
  "scouting",
  "grounding",
  "complete",
  "errored",
]);
export type JobStage = z.infer<typeof JobStageSchema>;

/** File/key ids: lowercase, digits, underscore, 1–40 chars (plan §10 W3-03). */
export const JobIdSchema = z.string().regex(/^[a-z0-9_]{1,40}$/);

export const ConsentSchema = z.object({
  sendFramesToCloudAi: z.boolean(),
  atEpochMs: z.number().int().nonnegative(),
});
export type Consent = z.infer<typeof ConsentSchema>;

/** W11-04 per-frame flags the client emits. Optional: absent flags keep the
 * frame (video fallbacks, v1 clients); the SAM lens drops a candidate whose
 * hero frame has `faceFree: false`, `quality: "bad"` or `exposureOk: false`. */
// Keyframe times are video-relative and may be negative: the W3-01 reference
// window schedules a keyframe at `t − 4000`, which goes below video 0 when the
// checkpoint lands in the first seconds of the walk (the extracted frame is
// clamped to 0; only the manifest timestamp is negative). The floor admits
// those legitimate early-walk manifests while still bounding hostile input.
export const MIN_KEYFRAME_T_MS = -600_000;
export const KeyframeRefSchema = z.object({
  id: JobIdSchema,
  tMs: z.number().int().min(MIN_KEYFRAME_T_MS),
  faceFree: z.boolean().optional(),
  quality: z.string().optional(),
  exposureOk: z.boolean().optional(),
});

export const ClipRefSchema = z.object({
  id: JobIdSchema,
  startMs: z.number().int().nonnegative(),
  endMs: z.number().int().nonnegative(),
  frameCount: z.number().int().positive(),
  durationMs: z.number().int().positive(),
});

export const ApproachSampleSchema = z.object({
  tMs: z.number().int().nonnegative(),
  d: z.number().nonnegative(),
});

/**
 * R10-03 (review 0010, F3): fan-out caps. Every checkpoint costs paid vision
 * calls, so the schema bounds what one job can fan out to. Each value sits
 * well above what the app produces today — ≤3 keyframes per checkpoint
 * (`keyframeTimes`), ≤12 approach samples (`APPROACH_MAX_FRAMES`), one
 * checkpoint per route node — so a legitimate walk is never rejected; a
 * hostile or buggy client is.
 */
export const MAX_CHECKPOINTS = 40;
export const MAX_KEYFRAMES_PER_CHECKPOINT = 8;
export const MAX_APPROACH_SAMPLES = 16;
export const MAX_DRAFT_ID_LENGTH = 128;
export const MAX_ROUTE_CONTEXT_LENGTH = 200;
/** R12-12 (review 0012, F12): bounds on the model-facing text a manifest or a
 * model reply can carry. A prompt is assembled from these strings, so an
 * unbounded one is an injection surface and a token bill. */
export const MAX_CANDIDATE_TITLE_CHARS = 80;
export const MAX_CANDIDATE_TEXT_CHARS = 300;
export const MAX_MASK_SVG_PATH_CHARS = 65_536;

/** One non-START route node with its media refs (plan §5.5 `checkpoints[]`). */
export const CheckpointSchema = z
  .object({
    nodeId: JobIdSchema,
    type: z.enum(["TURN", "CHECKPOINT", "LEVEL_CHANGE", "DESTINATION"]),
    turn: z.enum(["LEFT", "RIGHT", "U_TURN"]).optional(),
    atDistanceM: z.number().nonnegative(),
    tMs: z.number().int().nonnegative(),
    heroKeyframeId: JobIdSchema,
    keyframes: z.array(KeyframeRefSchema).min(1).max(MAX_KEYFRAMES_PER_CHECKPOINT),
    clip: ClipRefSchema.optional(),
    approach: z.array(ApproachSampleSchema).max(MAX_APPROACH_SAMPLES),
  })
  .superRefine((cp, ctx) => {
    if (cp.type === "TURN" && cp.turn === undefined) {
      ctx.addIssue({ code: "custom", message: "TURN checkpoints require turn" });
    }
  });
export type Checkpoint = z.infer<typeof CheckpointSchema>;

export const ManifestSchema = z
  .object({
    schema: z.literal(1),
    draftId: z.string().min(1).max(MAX_DRAFT_ID_LENGTH),
    consent: ConsentSchema,
    // R12-12: one line only. A newline is how a manifest smuggles new
    // instructions into `checkpointContext`; the client builder emits a
    // single line, so nothing legitimate is refused.
    routeContext: z
      .string()
      .min(1)
      .max(MAX_ROUTE_CONTEXT_LENGTH)
      .refine((v) => !/[\r\n]/.test(v), { message: "routeContext must be one line" }),
    checkpoints: z.array(CheckpointSchema).min(1).max(MAX_CHECKPOINTS),
  })
  .superRefine((m, ctx) => {
    // R10-11 (review 0010, F10): uniqueness and cross-field checks. Duplicate
    // nodeIds collapse in the Maps scout/ground/assemble build and yield
    // duplicated result checkpoints; duplicate media ids collide in the
    // staging move; a hero pointing at no keyframe of its checkpoint breaks
    // the lens step.
    const nodeIds = new Set<string>();
    const mediaIds = new Set<string>();
    m.checkpoints.forEach((cp, i) => {
      if (nodeIds.has(cp.nodeId)) {
        ctx.addIssue({
          code: "custom",
          path: ["checkpoints", i, "nodeId"],
          message: "duplicate nodeId",
        });
      }
      nodeIds.add(cp.nodeId);
      if (!cp.keyframes.some((k) => k.id === cp.heroKeyframeId)) {
        ctx.addIssue({
          code: "custom",
          path: ["checkpoints", i, "heroKeyframeId"],
          message: "heroKeyframeId must reference one of this checkpoint's keyframes",
        });
      }
      for (const k of cp.keyframes) {
        if (mediaIds.has(k.id)) {
          ctx.addIssue({
            code: "custom",
            path: ["checkpoints", i, "keyframes"],
            message: `duplicate keyframe id ${k.id}`,
          });
        }
        mediaIds.add(k.id);
      }
      if (cp.clip !== undefined) {
        if (mediaIds.has(cp.clip.id)) {
          ctx.addIssue({
            code: "custom",
            path: ["checkpoints", i, "clip"],
            message: `duplicate clip id ${cp.clip.id}`,
          });
        }
        mediaIds.add(cp.clip.id);
        if (cp.clip.endMs < cp.clip.startMs) {
          ctx.addIssue({
            code: "custom",
            path: ["checkpoints", i, "clip", "endMs"],
            message: "clip endMs must be >= startMs",
          });
        }
      }
    });
  });
export type Manifest = z.infer<typeof ManifestSchema>;

/** One proposed landmark (plan §5.5 `candidates[]`). Nullables are the
 * documented null-degrades: box-only candidates carry no mask, confidence,
 * trackability or visibility distance. */
export const CandidateSchema = z.object({
  candidateId: z.string().min(1),
  title: z.string().min(1).max(MAX_CANDIDATE_TITLE_CHARS),
  description: z.string().min(1).max(MAX_CANDIDATE_TEXT_CHARS),
  cue: z.string().min(1).max(MAX_CANDIDATE_TEXT_CHARS),
  samPhrase: z.string().min(1).max(MAX_CANDIDATE_TITLE_CHARS),
  signText: z.string().max(MAX_CANDIDATE_TEXT_CHARS).nullable(),
  privacyRisk: z.boolean(),
  keyframeId: JobIdSchema,
  box: z.tuple([
    z.number().int(),
    z.number().int(),
    z.number().int(),
    z.number().int(),
  ]),
  frameW: z.number().int().positive(),
  frameH: z.number().int().positive(),
  maskSvgPath: z.string().max(MAX_MASK_SVG_PATH_CHARS).nullable(),
  maskW: z.number().int().positive().nullable(),
  maskH: z.number().int().positive().nullable(),
  samConfidence: z.number().min(0).max(1).nullable(),
  trackability: z.number().min(0).max(1).nullable(),
  visibleFromM: z.number().nonnegative().nullable(),
  distinctiveness: z.number().min(0).max(1),
  permanence: z.number().min(0).max(1),
  rank: z.number(),
  source: z.enum(["AI_GROUNDED", "AI_BOX_ONLY"]),
  models: z.string().min(1),
  evidenceKey: z.string().min(1),
});
export type Candidate = z.infer<typeof CandidateSchema>;

export const JobResultCheckpointSchema = z.object({
  nodeId: JobIdSchema,
  candidates: z.array(CandidateSchema),
});

/** Measured cost/latency. The three fields are the complete §5.5 set;
 * W3-06 fills them. The object is closed (zod strips extras) so step
 * results stay Rpc.Serializable across the Workflow boundary.
 * W11-06 adds two optional fields (still closed, still serializable):
 * `samFramesBilled` sums vendor-reported `response.completed` frames,
 * falling back to the sent frames when the vendor omits usage (R10-05;
 * cache hits bill zero), and `budgetExhausted` records the governor
 * refusing a call. Both are absent when SAM never ran. */
export const JobUsageSchema = z.object({
  samFrames: z.number().int().nonnegative().optional(),
  scoutMs: z.number().nonnegative().optional(),
  samMs: z.number().nonnegative().optional(),
  samFramesBilled: z.number().int().nonnegative().optional(),
  budgetExhausted: z.boolean().optional(),
});
export type JobUsage = z.infer<typeof JobUsageSchema>;

export const JobResultSchema = z.object({
  jobId: z.string().min(1),
  models: z.object({
    scout: z.string().min(1),
    ground: z.string().min(1),
  }),
  checkpoints: z.array(JobResultCheckpointSchema),
  usage: JobUsageSchema,
});
export type JobResult = z.infer<typeof JobResultSchema>;

/** Durable per-job progress the app polls (plan §10 W3-03). */
export const JobProgressSchema = z.object({
  stage: JobStageSchema,
  updatedAt: z.number().int().nonnegative(),
});
export type JobProgress = z.infer<typeof JobProgressSchema>;

// Frozen HTTP shapes (repo-consistent envelope: {ok:true,...} success,
// {ok:false,error} failure — chosen over the V2 sketch's bare bodies).

export const CreateJobResponseSchema = z.object({
  ok: z.literal(true),
  jobId: z.string().min(1),
});

export const JobStatusResponseSchema = z.object({
  ok: z.literal(true),
  status: JobStatusSchema,
  stage: JobStageSchema,
  result: JobResultSchema.optional(),
  error: z.string().optional(),
});

export const ApiErrorSchema = z.object({
  ok: z.literal(false),
  error: z.string().min(1),
});

// W10-06 worker API v1.1 freeze (additive to the W3-03 freeze above; the
// W3-03 shapes are untouched). Full intake lands in W11-05, the SAM ladder
// in W11-06, SSE hardening in W11-07 — this section is the producer
// contract those slices implement against, never widened without a new S slice.

// Content hash declared per file at upload creation (plan W10-06).
export const Sha256Schema = z.string().regex(/^[0-9a-f]{64}$/);

/** Upload sessions and jobs are keyed by server-issued UUIDs. */
export const UuidSchema = z.uuid();

/** Total upload cap (80 MB, W3-03 §10); no single file can exceed it. */
export const MAX_UPLOAD_BYTES = 80 * 1024 * 1024;

export const UploadFileRefSchema = z.object({
  name: JobIdSchema,
  sha256: Sha256Schema,
  bytes: z.number().int().nonnegative().max(MAX_UPLOAD_BYTES),
});
export type UploadFileRef = z.infer<typeof UploadFileRefSchema>;

// POST /v1/uploads {files:[{name,sha256,bytes}]} (caps mirror W3-03 §10:
// at most 60 files, unique names; the 80 MB total is checked on declared
// sizes here and on actual PUT bytes in W11-05).
export const CreateUploadRequestSchema = z
  .object({
    files: z.array(UploadFileRefSchema).min(1).max(60),
  })
  .superRefine((req, ctx) => {
    const names = req.files.map((f) => f.name);
    if (new Set(names).size !== names.length) {
      ctx.addIssue({ code: "custom", message: "file names must be unique", path: ["files"] });
    }
  });
export type CreateUploadRequest = z.infer<typeof CreateUploadRequestSchema>;

// POST /v1/uploads → {uploadId, missing[]}; missing[] lets clients resume
// by PUTing only what the server does not already hold (W11-05).
export const CreateUploadResponseSchema = z.object({
  ok: z.literal(true),
  uploadId: UuidSchema,
  missing: z.array(JobIdSchema),
});
export type CreateUploadResponse = z.infer<typeof CreateUploadResponseSchema>;

// PUT /v1/uploads/:uploadId/:name carries raw file bytes; W11-05 verifies
// sha256(bytes) against the declared sha and answers 400 on mismatch.
export const UploadPutParamsSchema = z.object({
  uploadId: UuidSchema,
  name: JobIdSchema,
});
export type UploadPutParams = z.infer<typeof UploadPutParamsSchema>;

// POST /v1/jobs gains {uploadId, budget, idempotencyKey} next to the frozen
// v1 multipart body (kept for v1 clients). resumeOf is the only sanctioned
// cross-job SAM-cache reuse; default scoping is per job, deleted with it.
/** Hard SAM frame ceiling: the schema maximum, and the cap the lens applies
 * when a job carries no readable budget. */
export const MAX_SAM_FRAMES = 240;
export const JobBudgetSchema = z.object({
  maxSamFrames: z.number().int().positive().max(MAX_SAM_FRAMES),
});
export type JobBudget = z.infer<typeof JobBudgetSchema>;

export const CreateJobV11RequestSchema = z.object({
  manifest: ManifestSchema,
  uploadId: UuidSchema.optional(),
  // W11-09 ready-on-arrival: per-window upload sessions staged during the
  // walk; Finish creates the job against all of them. Never alongside
  // uploadId — one addressing mode per create. R10-11 (F10): duplicates
  // would double-list one session in the move.
  uploadIds: z
    .array(UuidSchema)
    .min(1)
    .max(12)
    .refine((ids) => new Set(ids).size === ids.length, { message: "duplicate uploadIds" })
    .optional(),
  budget: JobBudgetSchema.optional(),
  idempotencyKey: z.string().min(1).max(128).optional(),
  // The id of an earlier job; the server checks it exists before it is
  // ever used as an R2 key prefix.
  resumeOf: UuidSchema.optional(),
});
export type CreateJobV11Request = z.infer<typeof CreateJobV11RequestSchema>;

// GET /v1/jobs/:id/events (SSE, backed by append-only R2 event records in
// W11-05; server holds ≤25 s then the client reconnects with Last-Event-ID).
// Every event carries a string id so resume replays exactly what follows it.
export const CheckpointEventStatusSchema = z.enum(["scouted", "grounded", "skipped"]);
export type CheckpointEventStatus = z.infer<typeof CheckpointEventStatusSchema>;

export const StageEventSchema = z.object({
  id: z.string().min(1),
  type: z.literal("stage"),
  stage: JobStageSchema,
  updatedAt: z.number().int().nonnegative(),
});

export const CheckpointEventSchema = z.object({
  id: z.string().min(1),
  type: z.literal("checkpoint"),
  nodeId: JobIdSchema,
  status: CheckpointEventStatusSchema,
  candidates: z.array(CandidateSchema),
});

export const UsageEventSchema = z.object({
  id: z.string().min(1),
  type: z.literal("usage"),
  usage: JobUsageSchema,
});

export const CompleteEventSchema = z.object({
  id: z.string().min(1),
  type: z.literal("complete"),
  result: JobResultSchema,
});

export const ErrorEventSchema = z.object({
  id: z.string().min(1),
  type: z.literal("error"),
  error: z.string().min(1),
});

export const JobEventSchema = z.discriminatedUnion("type", [
  StageEventSchema,
  CheckpointEventSchema,
  UsageEventSchema,
  CompleteEventSchema,
  ErrorEventSchema,
]);
export type JobEvent = z.infer<typeof JobEventSchema>;
