import { z } from "zod";

// Scaffold contract placeholders for the jobs API frozen in W3-03.
// Kept permissive on purpose: W3-03 owns the freeze and may extend these
// without breaking the W0-05 scaffold gates.

export const JobStatusSchema = z.enum([
  "queued",
  "running",
  "complete",
  "failed",
]);
export type JobStatus = z.infer<typeof JobStatusSchema>;

export const ManifestSchema = z.object({
  jobId: z.string().min(1),
  createdAt: z.string().min(1),
  keyframes: z.array(z.string()).default([]),
  clips: z.array(z.string()).default([]),
});
export type Manifest = z.infer<typeof ManifestSchema>;

export const JobResultSchema = z.object({
  jobId: z.string().min(1),
  status: JobStatusSchema,
});
export type JobResult = z.infer<typeof JobResultSchema>;
