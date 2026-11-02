// SAM 3.1 live probe (slice W11-07): one real call, raw SSE archived.
//
// Settles the facts the escalation ladder depends on by sending exactly one
// request per invocation and saving the raw event stream, so every number in
// docs/measurements.md can be traced back to a file.
//
// Usage (META_API_KEY in the environment; a dry run prints the plan, sends
// nothing, spends $0):
//   node scripts/probe-sam.mjs --image a.jpg --phrase "a face" --phrase "a collar" --out probe-out/two-phrase
//   node scripts/probe-sam.mjs --video clip.mp4 --phrase "a door" --out probe-out/clip

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename } from "node:path";

const RESPONSES_URL = "https://api.meta.ai/v1/responses";
const TIMEOUT_MS = 90_000;

function parseArgs(argv) {
  const args = { phrases: [] };
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i];
    const value = argv[i + 1];
    if (value === undefined) throw new Error(`${flag} needs a value`);
    if (flag === "--phrase") args.phrases.push(value);
    else if (flag === "--image") args.image = value;
    else if (flag === "--video") args.video = value;
    else if (flag === "--out") args.out = value;
    else throw new Error(`unknown flag ${flag}`);
  }
  if (args.phrases.length === 0) throw new Error("at least one --phrase is required");
  if ((args.image === undefined) === (args.video === undefined)) {
    throw new Error("exactly one of --image or --video is required");
  }
  if (args.out === undefined) throw new Error("--out is required");
  return args;
}

function mediaPart(args) {
  if (args.image !== undefined) {
    const b64 = readFileSync(args.image).toString("base64");
    return { type: "input_image", image_url: `data:image/jpeg;base64,${b64}` };
  }
  const b64 = readFileSync(args.video).toString("base64");
  return { type: "input_video", video_url: `data:video/mp4;base64,${b64}` };
}

const args = parseArgs(process.argv.slice(2));
const media = mediaPart(args);
const body = {
  model: "sam-3.1",
  stream: true,
  metadata: { include_confidence: "true", mask_encoding: "one_bit" },
  input: [
    {
      type: "message",
      role: "user",
      content: [...args.phrases.map((text) => ({ type: "input_text", text })), media],
    },
  ],
};
const mediaFile = basename(args.image ?? args.video);
const mediaBytes = readFileSync(args.image ?? args.video).length;

const key = process.env.META_API_KEY;
if (!key) {
  console.log(`probe-sam dry run: ${args.phrases.length} phrase(s), ${mediaFile} (${mediaBytes} bytes). $0 spent.`);
  process.exit(0);
}

mkdirSync(args.out, { recursive: true });
const started = Date.now();
const res = await fetch(RESPONSES_URL, {
  method: "POST",
  body: JSON.stringify(body),
  headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", Accept: "text/event-stream" },
  signal: AbortSignal.timeout(TIMEOUT_MS),
});
const headersMs = Date.now() - started;
const raw = await res.text();
const totalMs = Date.now() - started;
writeFileSync(`${args.out}/response.sse`, raw);

const requestId = res.headers.get("x-request-id") ?? res.headers.get("request-id") ?? null;
const events = [];
let usage = null;
let outputText = "";
for (const block of raw.split(/\r?\n\r?\n/)) {
  for (const line of block.split(/\r?\n/)) {
    if (!line.startsWith("data:")) continue;
    const data = line.slice(5).trim();
    if (!data || data === "[DONE]") continue;
    try {
      const j = JSON.parse(data);
      events.push(j.type ?? "?");
      if (j.type === "response.completed") usage = j.response?.usage ?? null;
      if (j.type === "response.output_text.done" && typeof j.text === "string") outputText = j.text;
      if (j.type === "response.output_text.delta" && typeof j.delta === "string") outputText += j.delta;
    } catch {
      // not an event line
    }
  }
}
const summary = {
  status: res.status,
  requestId,
  phrases: args.phrases,
  media: { file: mediaFile, bytes: mediaBytes, kind: args.image !== undefined ? "image" : "video" },
  headersMs,
  totalMs,
  eventTypes: [...new Set(events)],
  usage,
  outputChars: outputText.length,
  outputLines: outputText === "" ? 0 : outputText.split("\n").length,
};
writeFileSync(`${args.out}/summary.json`, JSON.stringify(summary, null, 2));
writeFileSync(`${args.out}/output.txt`, outputText);
console.log(JSON.stringify(summary, null, 2));
if (!res.ok) process.exit(1);
