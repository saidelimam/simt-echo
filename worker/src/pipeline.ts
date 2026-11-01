import { WorkflowEntrypoint } from "cloudflare:workers";
import type { Env } from "./index";

interface PipelineParams {
  jobId: string;
}

// Stub for the W3-03 jobs Workflow. Declared here so the wrangler
// `workflows` binding resolves on deploy; the queued → complete
// state machine itself lands in W3-03.
export class EchoPipeline extends WorkflowEntrypoint<Env, PipelineParams> {
  override async run(): Promise<unknown> {
    return { ok: true, stub: "echo-pipeline" };
  }
}
