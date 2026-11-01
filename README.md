# ECHO — simt-echo

Turn a phone video of your walk into a route that others can navigate without
GPS. The creator records a route (video + inertial sensors), AI grounds
landmarks onto the captured geometry, and the resulting **EchoRoute bundle**
navigates a friend along it — turn cues, landmark AR overlays, QR checkpoints.

Status: **scaffold complete** (slice W0-02) — module structure, build wiring,
platform seams and a runnable app shell; no feature code yet. The
authoritative spec is `SIMT_ECHO_IMPLEMENTATION_PLAN_V2.md` (tracked at the
repo root), and agent work is sequenced by `docs/plans/0001-agentic-flow.md`.

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
| `shared/.../platform` | seams are declared here; actuals land per slice |
| `shared/.../capture`, `nav` | W1-01 motion, W2-05 navigator |
| `shared/.../bundle`, `transfer` | W2-02 codec, W2-03 peer transport |
| `shared/.../ai`, `ui` | W3-02 API client, W0-03 nav shell + screens |
| `worker/src` | W3-03 jobs API (health route already live) |

## Setup

Prereqs: JDK 21, Android SDK (platform 37), Node 20+, Xcode (iOS targets).

On this machine the default `JAVA_HOME`/developer dir point elsewhere, so
prefix Gradle commands with:

```sh
export JAVA_HOME=/Users/saidelimam/.sdkman/candidates/java/21.0.12-tem
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

### Build & test (acceptance gate for the scaffold)

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

## Build notes

- AGP 9: `shared` uses `com.android.kotlin.multiplatform.library` with
  `withHostTest {}` so `commonTest` runs on the host JVM; `androidApp` uses
  `com.android.application` with built-in Kotlin (no `kotlin-android` plugin).
- `shared` depends only on what the skeleton compiles against. Slice
  libraries (CameraX, Media3, Ktor, ML Kit, QR, crypto) are pinned in
  `gradle/libs.versions.toml` and wired by the slice that first imports them.
- No secrets in git: app secrets in `local.properties`, worker secrets via
  `wrangler secret put`.
