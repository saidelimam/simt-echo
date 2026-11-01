import { describe, expect, it } from "vitest";
import { app } from "./index";

const TOKEN = "test-token-123";

// Scaffold gate: every non-health /v1/* route requires auth + rate limit.
// No jobs routes exist yet (W3-03); unknown paths exercise the same gate.
describe("W0-05 auth + rate limit gate", () => {
  it("leaves /v1/health public without credentials", async () => {
    const res = await app.request("/v1/health");
    expect(res.status).toBe(200);
  });

  it("rejects protected routes without a token (401)", async () => {
    const res = await app.request(
      "/v1/jobs",
      {},
      { ECHO_APP_TOKEN: TOKEN } as never,
    );
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ ok: false, error: "unauthorized" });
  });

  it("rejects a wrong bearer token (401)", async () => {
    const res = await app.request(
      "/v1/jobs",
      { headers: { Authorization: "Bearer wrong" } },
      { ECHO_APP_TOKEN: TOKEN } as never,
    );
    expect(res.status).toBe(401);
  });

  it("fails closed when no server token is configured (500)", async () => {
    const res = await app.request(
      "/v1/jobs",
      { headers: { Authorization: `Bearer ${TOKEN}` } },
      {} as never,
    );
    expect(res.status).toBe(500);
  });

  it("returns 429 when the rate limiter refuses", async () => {
    const limited = {
      limit: async () => ({ success: false }),
    };
    const res = await app.request(
      "/v1/jobs",
      { headers: { Authorization: `Bearer ${TOKEN}` } },
      { ECHO_APP_TOKEN: TOKEN, RL: limited } as never,
    );
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ ok: false, error: "rate_limited" });
  });

  it("passes valid credentials through the gate (falls to 404, no routes yet)", async () => {
    const allowed = {
      limit: async () => ({ success: true }),
    };
    const res = await app.request(
      "/v1/jobs",
      { headers: { Authorization: `Bearer ${TOKEN}` } },
      { ECHO_APP_TOKEN: TOKEN, RL: allowed } as never,
    );
    // No jobs routes in the scaffold: reaching notFound proves the gate passed.
    expect(res.status).toBe(404);
  });
});
