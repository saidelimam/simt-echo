# ECHO — simt-echo

Turn a phone video of your walk into a route that others can navigate without
GPS. The creator records a route (video + inertial sensors), AI grounds
landmarks onto the captured geometry, and the resulting **EchoRoute bundle**
navigates a friend along it — turn cues, landmark AR overlays, QR checkpoints.

Status: **in development — feature code throughout, not yet production-true.**
Plan `docs/plans/0001-agentic-flow.md` stands at 34/36 Complete (W5-03
Accepted/postponed, W4-06 Queued); the active plan is
`docs/plans/0002-mvp-cosmos-design.md` (W7-01, W7-02 Complete), whose
review verdict on the app as it stands is **BLOCK**: the core loop dead-ends
from the UI, the worker has never been deployed, and device proofs are open.
W6-02's draw-box extraction landed (`1c51afa`, recorded Complete in
`docs/reviews/0006-residual-risks.md`). Measured cost/latency evidence lives
in `docs/measurements.md` — the deployed 60 m envelope is still unmeasured
and unclaimed.

## Legal

- [Privacy Policy](docs/legal/privacy-policy.md)
- [Terms of Use](docs/legal/terms-of-use.md)

## Layout

```
.
├── shared/            KMP library (package app.simt.echo): model, capture,
│                      nav, bundle, transfer, ai, data, platform, ui
│                      — Android + iOS (device + Apple Silicon simulator)
├── androidApp/        Android entry point (MainActivity, EchoApplication);
│                      hosts the shared Compose UI via AppGraph
├── worker/            Cloudflare Worker (echo-api): Hono + zod, R2 bucket
│                      echo-media, Workers AI / Meta Model API pipeline
└── gradle/            Version catalog (Kotlin 2.4.21, AGP 9.5.0, CMP 1.12.0)
```

Where new code goes (owning slice in `docs/plans/0001-agentic-flow.md`):

| Package / area | First slice |
|---|---|
| `shared/.../model`, `data` | W0-04 domain model, `FileStore`, `RouteRepository` |
| `shared/.../ui`, nav shell | W0-03 theme, components, `EchoNavHost`, placeholder screens |
| `shared/.../platform` | seams are declared here; actuals land per slice |
| `shared/.../capture`, `nav` | W1-01 motion, W2-05 navigator |
| `shared/.../bundle`, `transfer` | W2-02 codec, W2-03 peer transport |
| `shared/.../ai`, `ui` | W3-02 API client, W0-03 nav shell + screens |
| `worker/src` | W3-03 jobs API (contract implemented + vitest-green, never deployed — production deploy is W9-01) |

## Setup

Prereqs: JDK 21, Android SDK (platform 37), Node 20+, Xcode (iOS targets).

On this machine the default `JAVA_HOME`/developer dir point elsewhere, so
prefix Gradle commands with:

```sh
export JAVA_HOME=/Users/saidelimam/.sdkman/candidates/java/21.0.12-tem
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

### Build & test (current gates)

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64
./gradlew :androidApp:installDebug     # build + install on a device
```

iOS test *execution* (`:shared:iosSimulatorArm64Test`, part of
`:shared:allTests`) additionally needs an iOS simulator runtime installed
(Xcode → Settings → Components); iOS compilation above does not. There is no
`iosX64` target: Kotlin 2.4 / CMP 1.12 no longer publish it.

App secrets live in `local.properties` (gitignored): `ECHO_APP_TOKEN`,
`META_API_KEY` (optional — enables SAM grounding), `WORKERS_API_BASE`.

### Worker

```sh
cd worker
npm install
npm run typecheck && npm test
npm run dev        # local wrangler dev
npm run deploy
curl https://echo-api.<acct>.workers.dev/v1/health
# → {"ok":true,"version":"0.1.0","metaKeyConfigured":<bool>}
```

One-time setup: `npm exec -- wrangler r2 bucket create echo-media`,
`npm exec -- wrangler secret put ECHO_APP_TOKEN` (and `META_API_KEY`,
optional). Also: R2 lifecycle rule expiring prefixes `jobs/` and `relay/`
after 1 day, and an AI Gateway named `echo` (dashboard → AI → AI Gateway).

`npm run deploy` also registers an hourly cron trigger
(`triggers.crons` in `worker/wrangler.jsonc`) that runs the worker's
`scheduled()` handler: it sweeps staged uploads older than 1 h and
orphaned `jobs/idem/*` records, so expiry no longer depends on a later
upload request arriving.

## Build notes

- AGP 9: `shared` uses `com.android.kotlin.multiplatform.library` with
  `withHostTest {}` so `commonTest` runs on the host JVM; `androidApp` uses
  `com.android.application` with built-in Kotlin (no `kotlin-android` plugin).
- `shared` depends only on what the skeleton compiles against. Slice
  libraries (CameraX, Media3, Ktor, ML Kit, QR, crypto) are pinned in
  `gradle/libs.versions.toml` and wired by the slice that first imports them.
- No secrets in git: app secrets in `local.properties`, worker secrets via
  `wrangler secret put`.
- Local AI is not in v1: on-phone (`.litertlm`) and LAN-server inference was
  never verified on a device here, so Settings exposes no local-model
  section. The provider seam (`EchoConfig`/`AppGraph`) stays for a future
  slice.
