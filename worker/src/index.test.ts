import { describe, expect, it } from "vitest";
import { app } from "./index";

// Scaffold smoke test: the health route every later worker slice builds on.
// The jobs API itself lands in W3-03; keep this file free of route logic.
describe("GET /v1/health", () => {
  it("reports ok without any bindings configured", async () => {
    const res = await app.request("/v1/health");
    expect(res.status).toBe(200);
    const body = (await res.json()) as {
      ok: boolean;
      version: string;
      metaKeyConfigured: boolean;
    };
    expect(body.ok).toBe(true);
    expect(body.version).toBe("0.1.0");
    expect(body.metaKeyConfigured).toBe(false);
  });
});
