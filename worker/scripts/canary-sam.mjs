// SAM 3.1 canary for the W14-05 release gate (slice W11-07).
//
// Exactly one 1-frame image call — the cheapest live shape the worker
// itself uses (`groundViaImage` in `../src/ground.ts`: sam-3.1,
// `input_text` + `input_image`, streaming Responses events).
// Spends $0 without a key: with no META_API_KEY it prints the request it
// *would* send and exits 0 (dry run), so CI and keyless environments
// never bill anything.
//
// Usage:
//   npm run canary:sam            # dry run, no key needed
//   META_API_KEY=… npm run canary:sam   # one live 1-frame image call (≤$1 budget, W11-07)

const RESPONSES_URL = "https://api.meta.ai/v1/responses";

// 1x1 transparent PNG: the smallest decodable frame.
const ONE_PX_PNG_B64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

const body = {
  model: "sam-3.1",
  stream: true,
  metadata: { include_confidence: "true", mask_encoding: "one_bit" },
  input: [
    {
      type: "message",
      role: "user",
      content: [
        { type: "input_text", text: "a small square marker" },
        { type: "input_image", image_url: `data:image/png;base64,${ONE_PX_PNG_B64}` },
      ],
    },
  ],
};

const key = process.env.META_API_KEY;
if (!key) {
  console.log("canary:sam dry run (no META_API_KEY — $0 spent, no request sent).");
  console.log(`POST ${RESPONSES_URL}`);
  console.log(JSON.stringify(body).slice(0, 400));
  process.exit(0);
}

const started = Date.now();
const res = await fetch(RESPONSES_URL, {
  method: "POST",
  body: JSON.stringify(body),
  headers: {
    Authorization: `Bearer ${key}`,
    "Content-Type": "application/json",
    Accept: "text/event-stream",
  },
});
if (!res.ok) {
  console.error(`canary:sam HTTP ${res.status}: ${(await res.text()).slice(0, 500)}`);
  process.exit(1);
}
let text = "";
for (const chunk of (await res.text()).split("\n")) {
  const line = chunk.trim();
  if (!line.startsWith("data:")) continue;
  const data = line.slice(5).trim();
  if (!data || data === "[DONE]") continue;
  try {
    const j = JSON.parse(data);
    if (j.type === "response.output_text.delta") text += j.delta;
    else if (j.type === "response.output_text.done" && !text && typeof j.text === "string") {
      text = j.text;
    }
  } catch {
    // Ignore non-event lines; the collector holds text only.
  }
}
console.log(`canary:sam ok in ${Date.now() - started} ms; output chars: ${text.length}`);
console.log(text.slice(0, 500));
