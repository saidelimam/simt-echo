import { WorkflowEntrypoint } from "cloudflare:workers";
import { NonRetryableError } from "cloudflare:workflows";
import type { WorkflowEvent, WorkflowStep } from "cloudflare:workers";
import type { Env } from "./index";
import { JobResultSchema, ManifestSchema, type JobResult, type Manifest } from "./types";
import { putError, putProgress } from "./jobs";
import { runScout, ScoutError, type ScoutResult } from "./scout";
import { runGround } from "./ground";

export interface PipelineParams {
  jobId: string;
}

// Step policy, plan §10 W3-03 verbatim.
const STEP_OPTS = {
  retries: { limit: 4, delay: "5 seconds" as const, backoff: "exponential" as const },
  timeout: "4 minutes" as const,
};

/** Load → scout (W3-04, plus W3-09 meta-spark) → ground (W3-05) → assemble.
 * Ground seals ranked candidates to `jobs/<id>/ground.json`; assemble (this
 * slice, W3-06) re-sorts by §6.6 rank, attaches measured usage and seals
 * `jobs/<id>/result.json` for the app to poll. */
export class EchoPipeline extends WorkflowEntrypoint<Env, PipelineParams> {
  override async run(
    event: Readonly<WorkflowEvent<PipelineParams>>,
    step: WorkflowStep,
  ): Promise<unknown> {
    const { jobId } = event.payload;
    try {
      const manifest = await step.do("load", STEP_OPTS, () => loadManifest(this.env, jobId));
      // Wall-clock usage for result.json (plan §10 W3-06: timings). Measured
      // around the steps so the numbers are real even though the Workflow
      // scheduler owns the gaps between them.
      const t0 = Date.now();
      const scout = await step.do("scout", STEP_OPTS, () => runScoutStep(this.env, jobId, manifest));
      const scoutMs = Date.now() - t0;
      const ground = await step.do("ground", STEP_OPTS, () => runGroundStep(this.env, jobId, manifest, scout));
      const samMs = Date.now() - t0 - scoutMs;
      return await step.do("assemble", STEP_OPTS, () =>
        assembleFromGround(this.env, jobId, manifest, ground, { scoutMs, samMs }),
      );
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
  try {
    return await runScout(env, jobId, manifest);
  } catch (e) {
    // Scout failures are deterministic (no provider, missing media, every
    // model already fell back and failed): retrying re-bills the same calls.
    if (e instanceof ScoutError) throw new NonRetryableError(e.message);
    throw e;
  }
}

/**
 * Assemble the public result (plan §7.1 step 4, slice W3-06): re-sort every
 * checkpoint's candidates by §6.6 rank (ground already ranks; the re-sort is
 * the cheap invariant the Review screen's preselect depends on), attach
 * measured usage, and seal `jobs/<id>/result.json`. `samFrames` counts clip
 * frames sent to SAM — a billing proxy, since the text-only SSE collector
 * does not surface `response.completed` usage; it is absent on the box-only
 * path. Exported for the slice's vitest.
 */
export async function assembleFromGround(
  env: Env,
  jobId: string,
  manifest: Manifest,
  ground: JobResult,
  timings: { scoutMs: number; samMs: number },
): Promise<JobResult> {
  const samRan = ground.models.ground !== "none";
  const samFrames = samRan
    ? manifest.checkpoints.reduce((sum, cp) => sum + (cp.clip?.frameCount ?? 0), 0)
    : undefined;
  const result = JobResultSchema.parse({
    ...ground,
    jobId,
    checkpoints: ground.checkpoints.map((c) => ({
      nodeId: c.nodeId,
      candidates: [...c.candidates].sort((a, b) => b.rank - a.rank || (a.candidateId < b.candidateId ? -1 : 1)),
    })),
    usage: {
      ...(samFrames !== undefined && samFrames > 0 ? { samFrames } : {}),
      scoutMs: Math.max(0, timings.scoutMs),
      samMs: Math.max(0, timings.samMs),
    },
  });
  await env.MEDIA.put(`jobs/${jobId}/result.json`, JSON.stringify(result));
  await putProgress(env, jobId, "complete");
  return result;
}

async function runGroundStep(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
): Promise<JobResult> {
  await putProgress(env, jobId, "grounding");
  return runGround(env, jobId, manifest, scout);
}
