// Test-only stand-in for the `cloudflare:workers` runtime module, aliased
// in vitest.config.ts. Wrangler bundles the real module on deploy; unit
// tests only need the class shape so imports resolve.

export class WorkflowEntrypoint<Env = unknown, Params = unknown> {
  protected env: Env;
  protected ctx: ExecutionContext;

  constructor(ctx: ExecutionContext, env: Env) {
    this.ctx = ctx;
    this.env = env;
  }

  async run(_event?: Readonly<WorkflowEvent<Params>>): Promise<unknown> {
    return undefined;
  }
}

export interface WorkflowEvent<Params = unknown> {
  payload: Params;
  timestamp: Date;
  instanceId: string;
}
