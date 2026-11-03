// Ground step entry (plan §6.6 + §10 W3-05/W11-06).
//
// The W11-06 SAM lens is the sole ground path. R10-13 (review 0010, F12)
// removes the frozen W3-05 legacy (`GROUND_MODE=legacy`): per-candidate
// whole-clip video, unbounded `Promise.all`, string-concat base64. It was
// never the deployed default (no `GROUND_MODE` binding live) and its
// behaviors are covered by the lens's own tests (samLens.test.ts).
//
// Callers never branch: `runGround` hands straight to the lens, which owns
// per-call Workflow steps, the content-hash cache, the escalation ladder,
// and the budget governor. Generic core lives in `groundCommon.ts`.

import type { Env } from "./index";
import {
  inlineRunner,
  runGroundLens,
  type LensOptions,
  type StepRunner,
} from "./samLens";
import type { JobResult, Manifest } from "./types";
import type { ScoutResult } from "./scout";

export type { StepRunner };
export { inlineRunner };
export { runGroundLens } from "./samLens";
export type { LensOptions } from "./samLens";

/**
 * Ground dispatch: the W11-06 SAM lens (per-call Workflow steps,
 * content-hash cache, escalation ladder, budget governor). `runner` and
 * `lensOpts` only affect the lens.
 */
export async function runGround(
  env: Env,
  jobId: string,
  manifest: Manifest,
  scout: ScoutResult,
  runner: StepRunner = inlineRunner,
  lensOpts: LensOptions = {},
): Promise<JobResult> {
  return runGroundLens(env, jobId, manifest, scout, runner, lensOpts);
}
