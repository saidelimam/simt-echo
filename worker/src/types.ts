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

export const KeyframeRefSchema = z.object({
  id: JobIdSchema,
  tMs: z.number().int().nonnegative(),
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

/** One non-START route node with its media refs (plan §5.5 `checkpoints[]`). */
export const CheckpointSchema = z
  .object({
    nodeId: JobIdSchema,
    type: z.enum(["TURN", "CHECKPOINT", "LEVEL_CHANGE", "DESTINATION"]),
    turn: z.enum(["LEFT", "RIGHT", "U_TURN"]).optional(),
    atDistanceM: z.number().nonnegative(),
    tMs: z.number().int().nonnegative(),
    heroKeyframeId: JobIdSchema,
    keyframes: z.array(KeyframeRefSchema).min(1),
    clip: ClipRefSchema.optional(),
    approach: z.array(ApproachSampleSchema),
  })
  .superRefine((cp, ctx) => {
    if (cp.type === "TURN" && cp.turn === undefined) {
      ctx.addIssue({ code: "custom", message: "TURN checkpoints require turn" });
    }
  });
export type Checkpoint = z.infer<typeof CheckpointSchema>;

export const ManifestSchema = z.object({
  schema: z.literal(1),
  draftId: z.string().min(1),
  consent: ConsentSchema,
  routeContext: z.string().min(1),
  checkpoints: z.array(CheckpointSchema).min(1),
});
export type Manifest = z.infer<typeof ManifestSchema>;

/** One proposed landmark (plan §5.5 `candidates[]`). Nullables are the
 * documented null-degrades: box-only candidates carry no mask, confidence,
 * trackability or visibility distance. */
export const CandidateSchema = z.object({
  candidateId: z.string().min(1),
  title: z.string().min(1),
  description: z.string().min(1),
  cue: z.string().min(1),
  samPhrase: z.string().min(1),
  signText: z.string().nullable(),
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
  maskSvgPath: z.string().nullable(),
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
 * results stay Rpc.Serializable across the Workflow boundary. */
export const JobUsageSchema = z.object({
  samFrames: z.number().int().nonnegative().optional(),
  scoutMs: z.number().nonnegative().optional(),
  samMs: z.number().nonnegative().optional(),
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
