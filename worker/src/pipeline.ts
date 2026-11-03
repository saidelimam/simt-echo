import { WorkflowEntrypoint } from "cloudflare:workers";
import { NonRetryableError } from "cloudflare:workflows";
import type { WorkflowEvent, WorkflowStep } from "cloudflare:workers";
import type { Env } from "./index";
import { JobResultSchema, ManifestSchema, type JobResult, type JobStage, type Manifest } from "./types";
import { emitJobEvent, newEventId, putError, putProgress } from "./jobs";
import { runScout, ScoutError, type ScoutResult } from "./scout";
import { runGroundLens } from "./ground";
import { emitStep, type StepRunner } from "./samLens";

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
 * `jobs/<id>/result.json` for the app to poll.
 * W11-06: the ground phase runs the SAM lens — one Workflow step
 * `ground:<nodeId>:<k>:<phraseHash>` per SAM call, so a retry re-bills one
 * call, never all. R10-13 (review 0010, F12) removed the `GROUND_MODE=legacy`
 * W3-05 path; the lens is the sole ground.
 * R10-04: the scout phase is per-checkpoint steps too — one
 * `scout:<provider>:<nodeId>` per checkpoint (one `scout:<provider>` for
 * whole-job providers) — so a replay re-bills the failed checkpoint,
 * never 1..n-1. */
export class EchoPipeline extends WorkflowEntrypoint<Env, PipelineParams> {
  override async run(
    event: Readonly<WorkflowEvent<PipelineParams>>,
    step: WorkflowStep,
  ): Promise<unknown> {
    const { jobId } = event.payload;
    const runner: StepRunner = (name, fn) => step.do(name, STEP_OPTS, fn);
    try {
      const manifest = await step.do("load", STEP_OPTS, () => loadManifest(this.env, jobId));
      // Wall-clock usage for result.json (plan §10 W3-06: timings). Measured
      // around the steps so the numbers are real even though the Workflow
      // scheduler owns the gaps between them.
      const t0 = Date.now();
      const scout = await runScoutPhase(this.env, jobId, manifest, runner);
      const scoutMs = Date.now() - t0;
      const ground = await runGroundLensPhase(this.env, jobId, manifest, scout, runner);
      const assembled = await step.do("assemble", STEP_OPTS, () =>
        assembleFromGround(this.env, jobId, manifest, ground, { scoutMs }),
      );
      await emitStep(runner, this.env, jobId, "emit:complete", (id) => ({
        id,
        type: "complete",
        result: assembled,
      }));
      return assembled;
    } catch (e) {
      // R10-12 (review 0010, F11): the raw message can carry the vendor
      // response body and internal key layout — it is the server-side log
      // site and stays in error.json for ops. The SSE event carries the
      // stable code the app maps to fixed copy.
      const raw = e instanceof Error ? e.message : String(e);
      console.error(`job ${jobId} failed: ${raw}`);
      try {
        await emitJobEvent(this.env, jobId, {
          id: newEventId(),
          type: "error",
          error: "job_failed",
        });
      } catch {
        // Event emission never masks the pipeline failure.
      }
      await putError(this.env, jobId, raw);
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

async function runScoutPhase(
  env: Env,
  jobId: string,
  manifest: Manifest,
  runner: StepRunner,
): Promise<ScoutResult> {
  await putProgress(env, jobId, "scouting");
  try {
    // R10-04: per-checkpoint steps — a replay re-bills the failed
    // checkpoint, never 1..n-1.
    return await runScout(env, jobId, manifest, runner);
  } catch (e) {
    // Scout failures are deterministic (no provider, missing media, every
    // model already fell back and failed): retrying re-bills the same calls.
    if (e instanceof ScoutError) throw new NonRetryableError(e.message);
    throw e;
  }
}

/**
 * Assemble the public result (plan §7.1 step 4, slice W3-06): re-sort every
 * checkpoint's candidates by §6.6 rank, attach measured usage, and seal
 * `jobs/<id>/result.json`. Rank amendment (review 0004): ground computes
 * §6.6 rank; assemble only sorts (deterministically) — recomputing here
 * is impossible anyway, since Candidate no longer carries the §6.6
 * inputs. Usage: `samFrames`/`samMs` are ground's measured Meta usage
 * (vendor frames when reported, actual sends otherwise); `samMs` is
 * absent when SAM never ran, never a step wall-time proxy. Every
 * manifest checkpoint appears, empty when ground produced none.
 * Exported for the slice's vitest.
 */
export async function assembleFromGround(
  env: Env,
  jobId: string,
  manifest: Manifest,
  ground: JobResult,
  timings: { scoutMs: number },
): Promise<JobResult> {
  const groundByNode = new Map(ground.checkpoints.map((c) => [c.nodeId, c]));
  const result = JobResultSchema.parse({
    ...ground,
    jobId,
    checkpoints: manifest.checkpoints.map((cp) => {
      const c = groundByNode.get(cp.nodeId);
      return {
        nodeId: cp.nodeId,
        candidates: [...(c?.candidates ?? [])].sort(
          (a, b) => b.rank - a.rank || (a.candidateId < b.candidateId ? -1 : 1),
        ),
      };
    }),
    usage: {
      ...(ground.usage.samFrames ? { samFrames: ground.usage.samFrames } : {}),
      scoutMs: Math.max(0, timings.scoutMs),
      ...(ground.usage.samMs !== undefined ? { samMs: ground.usage.samMs } : {}),
      // W11-06 lens accounting passes through verbatim.
      ...(ground.usage.samFramesBilled !== undefined
        ? { samFramesBilled: ground.usage.samFramesBilled }
        : {}),
      ...(ground.usage.budgetExhausted !== undefined
        ? { budgetExhausted: ground.usage.budgetExhausted }
        : {}),
    },
  });
  await env.MEDIA.put(`jobs/${jobId}/result.json`, JSON.stringify(result));
  await putProgress(env, jobId, "complete");
  return result;
}

/**
 * W11-06 lens ground phase: per-call steps, not one `ground` step. Each
 * SAM call runs as `step.do("ground:<nodeId>:<k>:<phraseHash>", …)` inside the
 * lens, so the Workflow retries a failed call — never the whole phase.
 * Emits the W10-06 `stage` event for grounding alongside progress.json.
 */
async function runGroundLensPhase(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
  runner: StepRunner,
): Promise<JobResult> {
  await putProgress(env, jobId, "grounding");
  const stage: JobStage = "grounding";
  await emitStep(runner, env, jobId, "emit:stage:grounding", (id) => ({
    id,
    type: "stage",
    stage,
    updatedAt: Date.now(),
  }));
  return runGroundLens(env, jobId, manifest, scout, runner);
}
