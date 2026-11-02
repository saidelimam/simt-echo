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

// A hung vendor must fail the canary, not hang the release gate: the signal
// bounds the headers and the streamed body alike.
const CANARY_TIMEOUT_MS = 60_000;

const started = Date.now();
const res = await fetch(RESPONSES_URL, {
  method: "POST",
  body: JSON.stringify(body),
  headers: {
    Authorization: `Bearer ${key}`,
    "Content-Type": "application/json",
    Accept: "text/event-stream",
  },
  signal: AbortSignal.timeout(CANARY_TIMEOUT_MS),
});
if (!res.ok) {
  console.error(`canary:sam HTTP ${res.status}: ${(await res.text()).slice(0, 500)}`);
  process.exit(1);
}
if (res.body === null) {
  console.error("canary:sam response has no body");
  process.exit(1);
}

// Stream-parse line by line: the response is never buffered whole.
let text = "";
const handleLine = (chunk) => {
  const line = chunk.trim();
  if (!line.startsWith("data:")) return;
  const data = line.slice(5).trim();
  if (!data || data === "[DONE]") return;
  let j;
  try {
    j = JSON.parse(data);
  } catch {
    // Ignore non-event lines; the collector holds text only.
    return;
  }
  if (j.type === "response.output_text.delta") text += j.delta;
  else if (j.type === "response.output_text.done" && !text && typeof j.text === "string") {
    text = j.text;
  } else if (j.type === "response.failed" || j.type === "error") {
    throw new Error(data.slice(0, 500));
  }
};
const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
let buf = "";
try {
  for (let r = await reader.read(); !r.done; r = await reader.read()) {
    buf += r.value;
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      handleLine(buf.slice(0, nl));
      buf = buf.slice(nl + 1);
    }
  }
  handleLine(buf);
} catch (e) {
  // An error event or the timeout ends the call: release the connection.
  await reader.cancel().catch(() => undefined);
  console.error(`canary:sam failed: ${e instanceof Error ? e.message : String(e)}`);
  process.exit(1);
}
console.log(`canary:sam ok in ${Date.now() - started} ms; output chars: ${text.length}`);
console.log(text.slice(0, 500));
