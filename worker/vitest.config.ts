import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

// Unit tests run in plain Node: the Workers runtime module is stubbed
// (deploy still bundles the real one via wrangler).
export default defineConfig({
  resolve: {
    alias: {
      "cloudflare:workers": fileURLToPath(
        new URL("./src/test/cloudflare-workers-stub.ts", import.meta.url),
      ),
    },
  },
  test: {
    include: ["src/**/*.test.ts"],
  },
});
