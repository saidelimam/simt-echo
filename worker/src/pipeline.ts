import { WorkflowEntrypoint } from "cloudflare:workers";
import type { WorkflowEvent, WorkflowStep } from "cloudflare:workers";
import type { Env } from "./index";
import { JobResultSchema, ManifestSchema, type JobResult, type Manifest } from "./types";
import { putError, putProgress } from "./jobs";
import { runScout, type ScoutResult } from "./scout";

export interface PipelineParams {
  jobId: string;
}

// Step policy, plan §10 W3-03 verbatim.
const STEP_OPTS = {
  retries: { limit: 4, delay: "5 seconds" as const, backoff: "exponential" as const },
  timeout: "4 minutes" as const,
};

/** Load → scout (Workers AI, W3-04) → ground → assemble. W3-05 owns
 * ground-*, W3-06 owns assemble's content; the scout step already seals its
 * compact result + raw evidence to R2 for them to consume. */
export class EchoPipeline extends WorkflowEntrypoint<Env, PipelineParams> {
  override async run(
    event: Readonly<WorkflowEvent<PipelineParams>>,
    step: WorkflowStep,
  ): Promise<unknown> {
    const { jobId } = event.payload;
    try {
      const manifest = await step.do("load", STEP_OPTS, () => loadManifest(this.env, jobId));
      await step.do("scout", STEP_OPTS, () => runScoutStep(this.env, jobId, manifest));
      await step.do("ground", STEP_OPTS, () => putProgress(this.env, jobId, "grounding"));
      return await step.do("assemble", STEP_OPTS, () => assembleEmpty(this.env, jobId, manifest));
    } catch (e) {
      await putError(this.env, jobId, e instanceof Error ? e.message : String(e));
      throw e;
    }
  }
}

async function loadManifest(env: Env, jobId: string): Promise<Manifest> {
  const obj = await env.MEDIA.get(`jobs/${jobId}/manifest.json`);
  if (!obj) throw new Error(`manifest missing for job ${jobId}`);
  const manifest = ManifestSchema.parse(await obj.json());
  await putProgress(env, jobId, "reading");
  return manifest;
}

async function runScoutStep(env: Env, jobId: string, manifest: Manifest): Promise<ScoutResult> {
  await putProgress(env, jobId, "scouting");
  return runScout(env, jobId, manifest);
}

async function assembleEmpty(env: Env, jobId: string, manifest: Manifest): Promise<JobResult> {
  const result = JobResultSchema.parse({
    jobId,
    models: { scout: "none", ground: "none" },
    checkpoints: manifest.checkpoints.map((c) => ({ nodeId: c.nodeId, candidates: [] })),
    usage: {},
  });
  await env.MEDIA.put(`jobs/${jobId}/result.json`, JSON.stringify(result));
  await putProgress(env, jobId, "complete");
  return result;
}
