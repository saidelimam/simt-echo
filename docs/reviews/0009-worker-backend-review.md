# Review 0009 — Worker / backend full code review (bugs, over-spending, API, performance, security)

Status: **complete** · created 2026-11-02 · Verdict: **BLOCK**

Scope: all of `worker/` at repo HEAD `b4a90b7` — every non-test source
file (`index`, `jobs`, `uploads`, `relay`, `pipeline`, `scout`, `ground`,
`groundCommon`, `samLens`, `selection`, `samParse`, `meta`, `types`),
`wrangler.jsonc`, `package.json`, both `scripts/*.mjs`, plus the deployed
Cloudflare state. The Android app was read only where it proves how the
worker's credential is distributed (`androidApp/build.gradle.kts`).
`worker/src` is byte-identical to the version deployed earlier today
(`adafa8c8`, secret-bump `35218683`); only `scripts/probe-sam.mjs` changed
since.

Method: full manual read of the files above (no sampling); targeted
greps to prove negatives (no `app.onError`, `ENV` never read, no
`console.*` in `src`); the repo's own gates (`npm run typecheck`,
`npm test`, `npm audit`); and live Cloudflare verification through the
Cloudflare MCP — worker script settings/bindings, deployment list, R2
bucket properties, R2 lifecycle rules, and a metadata-only object listing
(no object bodies were read). No live AI/SAM spend was performed, no load
test, no penetration test — see Residual risk.

Severity levels: **Critical** — exploitable leak or broken guarantee,
fix before any further rollout; **High** — real bug, privacy promise not
kept, or unbounded spend path, blocks sign-off of this subsystem;
**Medium** — bounded weakness with a second control still holding, fix
in the next slices; **Low** — hygiene. Critical/High findings are the
BLOCK. Tracker rows below are the fix slices; all start `Queued`.

## Tracker

| id | state | slice | tier | lane | depends on | evidence |
|---|---|---|---|---|---|---|
| R9-01 | Complete | F1 shared-token authorization model: design note + tests proving one install cannot read/delete another's job, upload, or relay code | High | auth | — | df3f1b8a4fc2b409193e471631fe5061482d6de3 · test: npm test |
| R9-02 | Complete | F2 indefinite retention of jobs and relay objects: lifecycle rules + cron sweep; test a terminal job's prefix and an expired relay object are gone without a GET | High | storage | — | 27a8919f1e229673cab66800221de85f34e47150 · test: npm test |
| R9-03 | Complete | F3 unbounded manifest fan-out: schema caps (checkpoints, keyframes, approach, strings) + rejection tests at the caps | High | schema | — | 83ca02a3b2ae61ab91cd6cd56eb7f844065c55dd · test: npm test |
| R9-04 | Complete | F3 scout is one Workflow step, so retries/fallbacks re-bill every checkpoint: per-checkpoint steps or persisted per-checkpoint results; test a failure at checkpoint n does not re-call 1..n-1 | High | pipeline | R9-03 | 48e55a6be96feae4b00ddafbf3a666170166a863 · test: npm test |
| R9-05 | Complete | F4 spend governance is per-job and estimate-based only: account-level daily cap/kill-switch; samFramesBilled reconciled against vendor usage; alert/runbook note | High | spend | R9-03 | f5b63f333c2272d2a5a62aab25a2081700b9f042 · test: npm test |
| R9-06 | Queued | F5 scout ignores face/quality frame flags; clips reach SAM unflagged: scout drops flagged frames like the lens; clip flag contract or recorded acceptance | Medium | scout | R9-03 |  |
| R9-07 | Complete | F6 relay allocation race, pre-cap buffering, low-entropy reusable code: conditional put or equivalent; capped streaming read; code entropy/one-shot decision + tests | Medium | relay | — | b3a530f70d3fdf8974a7c290ba48c254a1aa6b4c · test: npm test |
| R9-08 | Complete | F7 SSE event ids are not a reliable sort key: sortable, isolate-independent event ids; same-millisecond multi-isolate ordering test | Medium | events | — | 5f65868390e9b90dc9f6cb3fceaf1446f0b95a52 · test: npm test |
| R9-09 | Complete | F8 every upload-create scans all staging + all idempotency records: sweep removed from the create path (cron only) or sharded; op-count note in the slice | Medium | uploads | — | fe94dc1c84d9ac1612f7bdf875ecc820fec73e3e · test: npm test |
| R9-10 | Complete | F9 whole-job media held in memory twice (intake move + lens planning): streamed/lazy media handling; max-size job (80 MB) completes in a local Workers runtime test | Medium | memory | — | 334beabce396e56cc1452d39c53eb6c464016e12 · test: npm test |
| R9-11 | Complete | F10 idempotency not bound to payload; manifest-only creates; duplicate ids accepted: payload-hash conflict (409), required upload reference, uniqueness refinements + tests | Medium | uploads | — | 33dc6d75ad0a216d0037a2eaed744d9c4f4ac1a0 · test: npm test |
| R9-12 | Queued | F11 inconsistent error envelope; vendor error text reaches clients: app.onError JSON envelope, sanitized job errors, Retry-After, request ids; contract tests | Medium | api | — | |
| R9-13 | Queued | F12 duplicate in-flight SAM calls; image phase can starve video; legacy ground path: in-flight dedupe by cache key, video-first reservation, legacy removal or explicit keep-decision | Low | lens | — | |
| R9-14 | Queued | F13 config hygiene (ENV=dev in prod, workers_dev on, health disclosure, SAM box bounds): config diff + a bounds test in samParse | Low | config | — | |

## Live Cloudflare state (verified 2026-11-02 via MCP)

- Worker `echo-api`, usage model `standard`, compatibility
  `2026-10-01` + `nodejs_compat`; latest deployment `35218683` at 100%
  (created by the `META_API_KEY` secret put). Bindings match
  `wrangler.jsonc`: `MEDIA`→`echo-media`, `PIPELINE`→`echo-pipeline`,
  `AI`, `RL` 30/60 s, secrets `ECHO_APP_TOKEN` + `META_API_KEY`.
- Plain-text vars served in production include `ENV: "dev"`.
- R2 `echo-media` (WEUR, Standard): **no custom domains** (bucket is not
  public — good), no CORS config, and lifecycle rules contain **only**
  the default 7-day multipart-abort rule — no object expiry for
  `jobs/`, `jobs/staging/`, or `relay/` prefixes.
- The bucket currently holds one completed job prefix
  (`jobs/fe7f506f-…/`: manifest, scout evidence, ground/result, and the
  199,845-byte input keyframe) — retained after completion, confirming
  F2 in production, not just in code.
- Observability: logs enabled, persisted, 100% head sampling,
  invocation logs on.

## Bugs (correctness)

### F7 — SSE event ids do not sort in emit order (Medium)

`newEventId` (`worker/src/jobs.ts:59-65`) concatenates an unpadded
base-36 timestamp, a **per-isolate** sequence counter, and 8 hex chars
of a random UUID. `readJobEvents` (`jobs.ts:74-92`) orders events by R2
key (lexicographic) and filters with a string comparison against
`${afterId}.json`. Two emits in the same millisecond from different
isolates both start at sequence 0, so their relative order is decided
by the random suffix — and an event emitted *after* a client received
its cursor can sort *before* that cursor and be skipped forever on
resume. Relatedly, `emitStep` (`worker/src/samLens.ts:59-69`) generates
the id inside the step callback, so a step retry after a successful
`MEDIA.put` emits a duplicate event under a fresh id. Fix: sortable
ids independent of isolate state (zero-padded timestamp + random
tiebreak is not enough — use a monotonic component the store can
order, e.g. padded timestamp + full UUID and filter on the stored
event id set rather than key order), and generate the id outside the
retried callback.

### F10 — Job-create contract holes (Medium)

Three related defects in `worker/src/uploads.ts` /
`worker/src/types.ts`:

1. **Idempotency is not bound to the payload.** A replayed
   `idempotencyKey` returns the original `jobId` as soon as a manifest
   exists (`uploads.ts:255-260`), before the new request's manifest,
   uploads, or `resumeOf` are compared (`uploads.ts:262-267` runs
   after). Same key + different body silently returns the wrong job
   instead of `409`.
2. **Manifest-only creates are accepted and guaranteed to fail.**
   With neither `uploadId` nor `uploadIds`, the staged-move block is
   skipped (`uploads.ts:272-277`) and a Workflow is created whose
   scout step then throws `media_missing` — a paid Workflow instance
   burned on a request that could be `400` at intake.
3. **No uniqueness or cross-field validation.** `ManifestSchema`
   (`types.ts:76-82`) does not require unique `nodeId`s, unique
   keyframe ids, `heroKeyframeId ∈ keyframes`, or
   `clip.endMs ≥ clip.startMs` (`types.ts:43-49`); duplicate node ids
   collapse in the `Map`s in scout/ground/assemble and produce
   duplicated result checkpoints. `uploadIds` (`types.ts:250`) also
   accepts duplicates, and a file staged in two sessions is silently
   first-wins (`uploads.ts:304-315`). The v1 multipart path has the
   mirror problem: non-allowlisted file parts are silently dropped
   and duplicate part names overwrite the same R2 key
   (`jobs.ts:198-205`, `jobs.ts:219-223`), so a client typo becomes a
   scout failure after the job is created instead of a `400`.

### F13 (bug part) — SAM observations are not bounds-checked (Low)

`parseSam` (`worker/src/samParse.ts:55-60`) rejects non-integer or
degenerate boxes but accepts negative coordinates and boxes extending
past `w`/`h`; `CandidateSchema` (`types.ts:97-102`) likewise only
requires ints. A malformed SAM record yields an out-of-frame box in
the sealed result. Clamp or drop at the parse boundary.

## Over-spending

### F3 — Unbounded manifest fan-out, and the scout re-bills on every retry (High)

`ManifestSchema` caps nothing that costs money: `checkpoints` is
`.min(1)` with no max (`types.ts:81`), `keyframes` is `.min(1)` with
no max (`types.ts:65`), `approach` is unbounded (`types.ts:67`), and
`routeContext`/`draftId` are unbounded strings (`types.ts:78-80`).
The whole scout then runs as **one** Workflow step
(`worker/src/pipeline.ts:16-19`, `pipeline.ts:43`) with 4 retries and
a 4-minute timeout, inside which checkpoints are processed
**sequentially** (`worker/src/scout.ts:667-683`), each trying up to 2
models with a 90 s raced timeout (`scout.ts:240-282`). Consequences,
all reachable by any token holder:

- A manifest with *N* checkpoints costs *N* vision calls per attempt;
  nothing bounds *N*.
- If checkpoint *N*−1 fails, the step retry re-runs checkpoints
  1…*N*−2 — every one re-billed. The SAM lens solved exactly this
  with per-call steps (`pipeline.ts:157-161`); the scout never got
  the same treatment.
- If the workers-ai provider throws after some checkpoints succeeded,
  the chain falls through to meta-spark (`scout.ts:630-720`), which
  re-does the whole job in one more paid call.
- The raced timeout does not cancel the underlying call
  (`scout.ts:524-548`: "the loser keeps running in the background"),
  so a timed-out Llama call can still be billed before Gemma runs.

Fix is R9-03 (caps first — one schema change) then R9-04
(per-checkpoint steps with persisted results, mirroring the lens).

### F4 — Spend governance stops at the per-job SAM estimate (High)

The lens governor is genuinely good at its layer — synchronous
reservation, cache-first planning, per-call steps
(`worker/src/samLens.ts:142-163`, `samLens.ts:191-211`) — but it is
the *only* spend control in the system, and it is estimate-based:

- The cap is per job (`MAX_SAM_FRAMES = 240`, `types.ts:236-241`;
  default when no budget is sent, `samLens.ts:231-251`). Job creation
  itself is unlimited: the only throttle is 30 req/60 s per
  token+IP (`worker/src/index.ts:83-91`), and the token is shared by
  every install (F1). There is no daily cap, no account-level budget,
  no kill-switch short of editing vars/secrets.
- The governor reserves *estimates* (client-declared `frameCount`,
  clamped) and records `samFramesBilled` only from vendor-reported
  usage; when Meta omits it, billed is recorded as **0** for image
  calls and video calls alike (`samLens.ts:737-739`,
  `samLens.ts:777-779`). Reported cost can therefore read zero for a
  job that spent money, and `budgetExhausted` describes estimates,
  not billing.
- Workers AI scout spend has no budget concept at all
  (`max_tokens: 1500` per call, `scout.ts:275`, times the unbounded
  call count in F3).

### F12 — Duplicate SAM calls and a legacy path that re-sends the clip per candidate (Low)

Planning checks the content-hash cache before any call is sent
(`samLens.ts:197-211`), and the cache write lands only after the
fetch returns (`samLens.ts:310-317`). Two planned calls with the same
media+phrase (same hero image reused across checkpoints, or two
candidates sharing phrase and frame) therefore both fetch and both
bill; with `LENS_CONCURRENCY = 4` they run concurrently. Separately,
the image phase reserves the whole budget before the video phase is
planned (`samLens.ts:588-591` vs `samLens.ts:645-648`), so cheap
image calls at low-priority checkpoints can exhaust the budget and
refuse the destination video call the ladder exists to protect —
reservation should be video-first or priority-ordered across phases.
Finally, `GROUND_MODE=legacy` (`worker/src/ground.ts:41-53`) remains
one env var away: it sends the whole clip **per candidate**
(`ground.ts:202-220`), runs candidates with unbounded `Promise.all`
(`ground.ts:124-133`), and builds base64 by string concatenation
(`ground.ts:55-68`). It is not the deployed default (no `GROUND_MODE`
binding live), hence Low — remove it or record why it stays, as its
own header already demands (W11-10).

## API practices

### F11 — Error contract is inconsistent and leaks vendor text (Medium)

- There is no `app.onError` (grep over `worker/src` is empty), so any
  throw outside a local try — e.g. R2 failures in
  `GET /v1/uploads/:uploadId` (`uploads.ts:422-435`), both DELETEs, or
  `postRelay`'s bucket calls (`relay.ts:58-79`) — bypasses the
  `{ok:false, error}` envelope every other route uses.
- Job errors return the stored exception message verbatim
  (`jobs.ts:259-268`), and that message can contain Meta's response
  body (up to 500 chars, `worker/src/meta.ts:138-139`) or internal R2
  key layout (`pipeline.ts:59-72` persists `e.message`). Internal
  detail and vendor text should be logged server-side and replaced
  with a stable client error code.
- The `429` from the rate limiter carries no `Retry-After`
  (`index.ts:85-90`); no route emits a request id, so a client report
  cannot be correlated with the (100%-sampled) invocation logs; job
  and result GETs carry no `Cache-Control: no-store` (only relay GET
  and SSE set cache headers, `relay.ts:97-100`, `jobs.ts:295-299`)
  despite returning per-user route data.

Smaller notes, recorded not sliced: job creation answers `202` while
upload creation answers `200` for a created resource; `VERSION`
(`index.ts:32`) and the package version are both hand-maintained
`0.1.0`; the public health probe discloses provider configuration
(`index.ts:98-109`) — acceptable for a liveness probe, but it should
stay boolean-only, which it currently is.

## Performance

### F8 — Every upload-create scans the entire staging tree and idempotency tree (Medium)

`POST /v1/uploads` ends with an opportunistic `sweepExpiredUploads`
(`uploads.ts:406-416`), which lists **all** of `jobs/staging/`
(`uploads.ts:187-207`), GETs every `meta.json` (`uploads.ts:209-219`),
then lists **all** of `jobs/idem/` and HEADs every referenced job
manifest (`uploads.ts:163-179`). Cost per create therefore grows
with total system state — O(staging + idem) R2 operations on the
request path, quadratically punishing exactly the bursty
ready-on-arrival client the endpoint was built for. The hourly cron
already runs this sweep (`index.ts:133-135`); remove it from the
create path or make it incremental.

### F9 — Whole jobs are buffered in memory, twice (Medium)

The v1.1 intake move loads every staged blob into an in-memory array
of `ArrayBuffer`s before writing any of them out
(`uploads.ts:302-321`, `uploads.ts:328-330`) — up to the full 80 MB
cap held at once, plus per-object copies. The lens then does it
again: planning loads every survivor's hero bytes and every
checkpoint's full clip into `planned` and retains them for the whole
ground phase (`samLens.ts:511-539`), while each SAM call re-encodes
its media to base64 (+33%) (`samLens.ts:296-317`). On a 128 MB
Worker, a max-size job with several clips risks memory-limit
termination mid-phase — after some SAM calls have already billed.
Stream R2→R2 copies at intake (or copy by reference where the layout
allows), and load each checkpoint's media inside its own step,
releasing it after.

Secondary: SSE polling re-lists the job's whole event prefix every
second per connected client (`jobs.ts:76-92`, `jobs.ts:120-160`) —
bounded by the 25 s hold, but list-then-GET per event per poll is
the most expensive way to stream a trickle; and the v1 multipart
path buffers the entire form before the file caps are applied
(`jobs.ts:187-205`).

## Security & privacy

### F1 — One static bearer token is the entire authorization model (High)

Every non-health route accepts a single shared `ECHO_APP_TOKEN`
(`worker/src/index.ts:71-91`). That token is compiled into every
shipped app as a `BuildConfig` string
(`androidApp/build.gradle.kts:69-72`), in a release build with
minification **off** (`androidApp/build.gradle.kts:72-76`) — i.e. it
is extractable from any distributed APK with `strings`. Possession
of it authorizes everything: create jobs (spending Workers AI and,
now that `META_API_KEY` is set, paid Meta SAM frames), read any
job's result and event stream, delete any job, stage uploads, and
read/write the relay (`index.ts:111-120`, `jobs.ts:239-342`,
`uploads.ts:383-495`). Job/upload ids are unguessable UUIDs, which
limits *targeted* abuse to ids the attacker learns — but cost abuse
needs no one's id at all, and the rate limiter keys on the token
itself plus client IP (`index.ts:85-87`), so it throttles each
source IP to 30 req/min while doing nothing about the credential
being public. Unauthenticated requests are not rate-limited at all
(the limiter runs only after a valid token is presented,
`index.ts:78-91`). This needs a design decision, not a patch:
per-install credentials (e.g. anonymous per-device keys issued at
first run, or short-lived signed tokens), job ownership bound to
the creating credential, and an IP-based limit in front of auth.
Until then, treat the token as a public client identifier, not a
secret — and note it can no longer be rotated without shipping an
app update.

### F2 — Jobs and relay objects are retained indefinitely (High)

Nothing ever deletes a completed job. The hourly cron sweeps only
staging and orphaned idempotency records (`index.ts:133-135`,
`uploads.ts:187-222`); job prefixes — input photos, clips, manifests,
scout raw output, SAM evidence, results — are removed only by an
explicit `DELETE /v1/jobs/:id` (`jobs.ts:303-342`). Relay objects
expire lazily and only when someone GETs them after the TTL
(`relay.ts:92-96`); an abandoned code's ciphertext sits forever.
Live state confirms both: the R2 lifecycle has no expiry rule (see
Live state) and a completed job's 200 KB input image is still in the
bucket. This is simultaneously a privacy exposure (indoor photos of
people's homes/workplaces, retained without bound), a storage-cost
creep, and a breach-impact multiplier that compounds F1. Fix: R2
lifecycle expiry for `jobs/` and `relay/` prefixes (e.g. 24-72 h)
plus a cron job sweep so events/cache subtrees go too, and state
the retention window wherever consent copy mentions the cloud.

### F5 — The scout sends frames the SAM lens would refuse (Medium)

The lens drops a candidate whose hero frame fails the W11-04 flags
(`faceFree: false`, bad quality, bad exposure) before any SAM spend
(`samLens.ts:358-365`). The scout has no equivalent: it sends the
first 3 keyframes of every checkpoint to Workers AI — and on
fallback to Meta Spark — regardless of those flags
(`scout.ts:398-409`, `scout.ts:667-677`). The app currently withholds
flagged bytes, which is the only reason this is Medium; the worker
is the last server-side checkpoint and should enforce the same rule
so a v1 or buggy client cannot ship a face-flagged frame to two AI
vendors. Clips are the known gap (review 0008 R3): video escalation
sends staged clip bytes to Meta with no per-frame flag contract
(`samLens.ts:629-667`).

### F6 — Relay: allocation race, pre-cap buffering, reusable low-entropy codes (Medium)

- `allocateRelayCode` checks `head` and later `put`s
  (`relay.ts:43-52`, `relay.ts:67-79`) — two concurrent POSTs can
  pick the same free code and the second overwrites the first
  bundle. R2 conditional puts (`onlyIf` etag-absent) close this.
- `postRelay` buffers the whole body with `arrayBuffer()` and only
  then checks the 5 MB cap (`relay.ts:58-65`); the upload path
  already has the right pattern (`readBodyCapped`,
  `uploads.ts:88-113`) — reuse it.
- Codes are 6 chars from a 31-symbol alphabet ≈ 29.7 bits
  (`relay.ts:16-29`), are not consumed on read (`relay.ts:81-101`),
  and stay valid for 30 minutes; combined with F1's public token,
  ciphertext retrieval is a guessing game with unlimited retries
  per code (throttled only per IP). Longer codes (or one-shot
  read) are cheap insurance for a zero-knowledge store whose whole
  guarantee is that the server learns nothing.

### Low-severity security/config notes (F13/F14 remainder)

- `timingSafeEqual` returns early on length mismatch
  (`index.ts:36-43`) — leaks token length only; acceptable, noted
  for completeness.
- `ENV=dev` is served in production (live bindings; `wrangler.jsonc`
  vars) and never read by the code — remove it or set it honestly
  before some future branch trusts it.
- `workers_dev: true` (`wrangler.jsonc:10-13`) keeps the
  `*.workers.dev` hostname live alongside the custom domain; any
  future WAF/Access rule written only for `api.echo.simt.app` will
  not cover it.
- The rate-limit key embeds the raw bearer token
  (`index.ts:85-87`); hash it if keys are ever logged or exported.

## What is already good (verified, keep)

- Upload PUTs verify declared size before reading, cap the read,
  and verify SHA-256 before storing (`uploads.ts:452-481`); staging
  has a real 1 h TTL enforced lazily and by cron
  (`uploads.ts:20`, `uploads.ts:124-137`).
- The SAM lens's per-call Workflow steps, cache-before-retry, and
  synchronous governor reservation (`samLens.ts:44-48`,
  `samLens.ts:276-317`) are the right architecture — F3/F4 ask for
  the same discipline on the scout and at account level, not a
  redesign of the lens.
- R2 ids are validated before becoming key prefixes everywhere
  (`types.ts:24`, `jobs.ts:28`, `uploads.ts:227-231`), staging/idem
  trees are reserved (`jobs.ts:27`), and the bucket has no public
  domain (live check).
- Model output is zod-validated and candidates are restricted to
  images actually sent (`scout.ts:592-609`); SAM parse failures
  degrade to box-only instead of failing jobs (`samParse.ts:25-27`,
  `groundCommon.ts:99-101`).
- `npm audit` reports 0 vulnerabilities; CI runs worker typecheck +
  tests on every push (`.github/workflows/ci.yml:26-31`).

## Residual risk (not reviewed / not verified)

- No live AI call was made: Workers AI and SAM behaviour, real
  per-call billing, and Meta's `video_frames_processed` reporting
  are taken from code and prior reviews, not measured here.
  R9-05's accounting fix needs one metered live job to close.
- No load or memory test was run; F9's termination risk is inferred
  from the 80 MB caps and the Workers 128 MB limit, not reproduced.
- Cloudflare account-level settings outside this worker (WAF, API
  Shield, gateway caching/logging on the `echo` AI Gateway, billing
  alerts) were not inspected; F4's kill-switch may partly exist
  there — verify before slicing R9-05.
- App-side handling of results (how long the phone keeps job data,
  whether it ever calls `DELETE /v1/jobs/:id`) was out of scope
  except for token distribution; F2's fix should be checked against
  the app's actual delete behaviour.

## Evidence (all run 2026-11-02)

- `npm run typecheck` in `worker/` → clean.
- `npm test` in `worker/` → 11 files, 173/173 pass.
- `npm audit` in `worker/` → 0 vulnerabilities.
- Live health: `GET https://api.echo.simt.app/v1/health` → 200,
  `metaKeyConfigured: true`, `workersAi: true` (post-secret state).
- Cloudflare MCP: `get_accounts_workers_scripts_settings`,
  `…_deployments`, `…_versions` for `echo-api`;
  `get_accounts_r2_buckets_by_bucket_name`,
  `…_buckets_lifecycle`, `…_buckets_domains_custom`, and a
  metadata-only `…_buckets_objects` listing for `echo-media`
  (results summarised under Live state; no object bodies read).
- Greps: `app.onError|secureHeaders|cors` (none), `env.ENV` (never
  read), `console.` in `worker/src` (none outside tests).

Verdict: **BLOCK** — F1, F2, F3, and F4 are High and owed as
R9-01…R9-05 before this backend is signed off. The Medium and Low
rows do not block individually but should be sliced in the order
listed. No code was changed by this review.
