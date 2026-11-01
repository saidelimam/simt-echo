# ECHO measurements (plan §7.4 envelope, slice W3-06)

## Envelope (per 60 m route, 6–8 checkpoints)

| Budget | Target |
|---|---|
| AI cost | ~$0.16 / route (SAM ≤ 16 clip calls × ≥ 50 billed frames; Workers AI scout calls are cents) |
| End-to-end latency | < 2 min |

## How cost/latency is recorded

`jobs/<id>/result.json` carries `usage` (frozen §5.5 shape):

- `scoutMs` / `samMs` — wall-clock milliseconds measured around the
  Workflow `scout` / `ground` steps in `EchoPipeline.run`.
- `samFrames` — clip frames sent to SAM 3.1 (sum of checkpoint
  `clip.frameCount` when `models.ground` is `sam-3.1`; absent on the
  box-only path). This is a billing proxy: the text-only SSE collector
  (`meta.ts`) does not surface `response.completed` usage, so billed
  frames are counted at send time. Vendor-metered cost = scout calls
  (cents) + `samFrames` × per-frame price.

## Runs

### Local box-only chain (2026-11-01, no keys)

- Path: manifest → Workers-AI-shaped scout fixture → `runGround` box-only
  (`GROUND_ORDER=none`) → `assembleFromGround`.
- Result: 2 candidates ranked §6.6 (`Strong` before `Weak`),
  `result.json` sealed, progress `complete`. No frames sent, no AI cost.
- Evidence: `worker/src/pipeline.test.ts` ("assembles the box-only ground
  chain end to end"), full suite `npm test` green in `worker/`.

### Live 60 m keyed run — blocked

Not yet executed in this environment: no `META_API_KEY` is configured and
the production deploy is blocked on R2 bucket-create permission
(`echo-media` absent) — the same residuals recorded by W3-04 (Workers AI
real-clip check deferred) and W3-05 (live SAM check blocked). To record it:
set `META_API_KEY`, deploy the worker, POST one 60 m job (6–8
checkpoints), then append the measured `scoutMs`/`samMs`, `samFrames`,
vendor-billed cost, and total latency to this file.
