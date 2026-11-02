# Release engineering (W9-10)

## CI

Workflow: `.github/workflows/ci.yml`, runs on every push and pull request.
It executes exactly the gates plan 0002 §6 names as evidence:

1. `./gradlew :shared:testAndroidHostTest` — shared host tests.
2. `./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64` —
   iOS compiles (iOS itself stays postponed per W5-03; shared code must not rot).
3. Worker `npm ci && npm run typecheck && npm test` in `worker/`.
4. `./gradlew :androidApp:assembleDebug`.

CI never signs releases and never needs secrets.

**Hosted-CI status:** every CI step below was run LOCALLY green in the
W9-10 worktree (evidence §5). A push to trigger hosted CI needs separate
approval, which was not given for this slice — hosted CI has therefore not
run yet. The first push of this branch should be observed at
Actions → ci before W9-12 claims "CI green on the release commit".

## Version discipline

`androidApp/build.gradle.kts` declares three components; the rule is:

- `versionName = MAJOR.MINOR.PATCH` (e.g. `0.2.0`).
- `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH` (e.g. `0.2.0` → `200`),
  strictly increasing per release. No more `versionCode = 1` pinned while
  the name drifts.
- Bump MINOR for each release candidate, PATCH for fix-ups, MAJOR for
  store-breaking or protocol-breaking releases. The Play Console ordering
  key is always `versionCode`.

## Signing

The release keystore lives **outside git** — it is never committed, never
generated into the repo, and `.gitignore` already excludes
`*.keystore`, `*.jks`, `*.p12` plus `local.properties`.

- Production location (operator machine / release signer):
  `~/.keystores/echo-release.jks` (any path works; this is the documented
  default). Back it up offline; losing it means a new app identity.
- Env-var wiring (read only in `androidApp/build.gradle.kts`, never from
  repo files):

  | Variable | Meaning |
  |---|---|
  | `ECHO_RELEASE_STORE_FILE` | Absolute path to the release keystore |
  | `ECHO_RELEASE_STORE_PASSWORD` | Keystore password |
  | `ECHO_RELEASE_KEY_ALIAS` | Key alias |
  | `ECHO_RELEASE_KEY_PASSWORD` | Key password |

- When all four are set and the file exists, `assembleRelease` signs with
  the release key. Otherwise it falls back to Gradle debug signing so the
  release smoke run works locally with zero secrets — the artefact is then
  a smoke artefact, not a shippable signed release.
- The smoke run for this slice used debug signing (no keystore created,
  none present). No secret was generated into the repo.

## Minify decision: OFF (recorded)

`isMinifyEnabled = false` for MVP v1. Rationale:

- The app ships kotlinx.serialization models, Ktor engines, Compose
  navigation and reflective serializer lookups; an R8 pass without a
  release-tested rule set risks exactly the silent runtime breaks
  (missing serializer, stripped engine) this plan refuses to ship.
- Keep rules are committed now (`androidApp/proguard-rules.pro`) so a
  later slice can flip one line and prove it with the same
  `assembleRelease` + install smoke run used here.
- The release-build smoke run below proves the configuration that ships.

## Release artefact (smoke run, debug-signed)

Built with `./gradlew :androidApp:assembleRelease` in the W9-10 worktree:

- APK: `androidApp/build/outputs/apk/release/androidApp-release.apk`
- SHA-256: `f7676e78ec9dfdbe23ac0421eef4b295ffd61b6138a6527c06e4413bd2a2c86a`
- Built from: slice W9-10 commit (see tracker row), version `0.2.0` (code 200).

Debug-signed smoke artefact — not a shippable release. A shippable
release requires the env-wired production keystore above plus W9-12's gate.

## Production deploy (W9-01, 2026-11-02)

Remote mutations (owner-approved for this slice; every id recorded):

- R2 bucket `echo-media` created: `PUT
  /accounts/3f031459999d10bf707212469b0a2748/r2/buckets/echo-media` →
  `{name: echo-media, location: WEUR, created: 2026-11-02T00:50:50Z}`.
  Wrangler config already bound it (`MEDIA` in `worker/wrangler.jsonc`).
- AI Gateway `echo` created (the worker's `AI_GATEWAY_ID` had no
  gateway and the first live job failed `2001: configure AI Gateway`):
  `POST .../ai-gateway/gateways {id: echo}` → created 2026-11-02
  00:52:52, Workers AI postpaid billing (the metered spend this
  enables was part of the same approval).
- Worker secret `ECHO_APP_TOKEN` set via `wrangler secret put`
  (generated value; lives only in the secret store and the
  operator's gitignored `local.properties` — never in the repo).
  `META_API_KEY` deliberately unset (no value available): health
  reports `metaKeyConfigured: false`, SAM grounding stays off by
  config, Workers AI scout is the live path.
- `worker/wrangler.jsonc`: removed the `limits.cpu_ms: 300000`
  stanza — the account is on the Free plan (Cloudflare code 10028)
  and rejects deploys that set CPU limits. Worker/API contracts
  untouched. Deploy command: `wrangler deploy --var ENV:production`.

Deploy evidence (all live):

- URL: `https://echo-api.saidelimam.workers.dev`, version id
  `df4792af-aaf0-4bdb-b309-1a848a78e994`, bindings MEDIA (echo-media)
  + AI + RL + PIPELINE confirmed in deploy output.
- `GET /v1/health` (public): `{ok: true, version: 0.1.0,
  metaKeyConfigured: false, providers: {workersAi: true, metaKey:
  false}}` — true-to-config.
- Throwaway job `fe7f506f-b1e1-431e-97d7-dc82f9958d0c`: POST 202 with
  a real emulator-camera door photo → `complete` in <60 s with a
  genuine Llama 4 Scout candidate ("Door / Turn left at the door",
  box-only, `scoutMs: 4320`) → result fetched (readable) →
  `DELETE` 204 → `GET` 404 `job_not_found`. A first job errored on
  the missing gateway, was also deleted (204 → 404). Full lifecycle
  proven on production data and code.
- Release builds need no developer `local.properties` for the cloud
  path: `androidApp/build.gradle.kts` release type defaults
  `WORKERS_API_BASE` to the URL above (`ECHO_PROD_API_BASE`
  overrides) and reads `ECHO_APP_TOKEN` only from
  `ECHO_PROD_APP_TOKEN` (empty default — fail-closed per W7-07).
