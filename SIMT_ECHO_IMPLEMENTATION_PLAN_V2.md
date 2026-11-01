# SIMT ECHO — MVP Implementation Plan

> **Walk it once. Share the way.** Android first (Compose Multiplatform, iOS-ready) · Cloudflare backend + Cloudflare AI (Meta Llama 4 Scout on Workers AI; Muse Spark / SAM 3.1 optional)

| | |
|---|---|
| Plan version | 1.2 · 1 Nov 2026 · AI through Cloudflare by default; Meta Model API and local models optional · derived from `SIMT_ECHO_HACKATHON_PROPOSAL.md` v1.0 |
| Deadline | Wed 4 Nov 2026 21:00 ET (Thu 5 Nov 03:00 Paris) — submit early |
| Audience | An AI coding agent (e.g. Muse Spark 1.3) or developer implementing slice by slice |
| Tracker | §9 (one table per wave) · details in §10 |
| Status legend | ⬜ todo · 🟨 in progress · ✅ done · ⛔ blocked · ✂️ cut |

---

## 0. Rules for the implementing agent

1. Pick the first ⬜ slice in §9 whose **Deps are all ✅**. Finish P0 slices of a wave before its P1 slices.
2. One branch + commit per slice: `echo/<ID>-<slug>`. Update the slice's Status cell in §9 in the same commit.
3. Use only the APIs and contracts written here. Where the plan says *verify*, check **local** sources (IDE autocompletion, `node_modules/**/*.d.ts`, Gradle sources), not the web.
4. **Geometry comes from sensors only.** AI output may attach landmarks and text to existing nodes; it never adds, moves or deletes nodes, legs or path points.
5. Packages `nav/`, `bundle/`, `data/`, `capture/` must not import any HTTP client. Replay must work in airplane mode.
6. No secrets in git: app secrets in `local.properties`, worker secrets via `wrangler secret put`.
7. A slice is ✅ only when its "Done when" list passes and `./gradlew :echo:shared:allTests` (app) / `npm test` (worker) are green.
8. If blocked > 45 min, apply the slice's **Fallback**, note it in the Status cell (e.g. `✅ fallback`), move on.

## 1. Scope

**MVP loop (must work end-to-end on two Android phones):**
1. Phone A records a 30–80 m indoor walk (sensors + silent video) and marks optional spots.
2. Sensor path is segmented into legs, turns and checkpoints (deterministic).
3. With consent, keyframes (+ clips) go to the Worker; **Cloudflare Workers AI** (Meta's Llama 4 Scout) proposes landmarks. If a Meta
   Model API key is configured, Muse Spark 1.3 / SAM 3.1 add proposals, masks and tracking. An optional local model is used only if configured.
4. Creator reviews: accept / swap / edit / draw own landmark, then saves the ECHO.
5. Phone A shows a QR; Phone B scans it and downloads the encrypted bundle over LAN/hotspot.
6. Phone B goes airplane mode, aligns at the start photo, follows arrow + haptics + landmark cards.
7. Turns and confirmed landmarks re-anchor progress; uncertainty is shown, never hidden.
8. Trials are logged for honest sensor-only vs ECHO comparison.

**Out of scope:** 3D reconstruction, multi-route maps, arbitrary routing, public route library, accounts/login,
automatic re-localization without confirmation, on-device AI as a feature, safety/evacuation use, social features. Keep the UI to the
screens in §8.

## 2. Ground truth and assumptions

- **The `~/code/simt/simt-app` repo (track + link modules) was not readable while writing this plan.** Reuse goes
  through two seams — `MotionSource` (Track+) and `PeerTransport` (link) — each with a fully specified built-in
  fallback, so nothing blocks on reuse. Slice W0-01 inspects the modules and fills §2.1.
- Public SIMT facts: Track+ fuses step detection, camera motion, barometric altitude and device sensors for
  GPS-weak places; the app is offline-first for Android and Wear OS.
- **Meta Model API — optional provider** (dev.meta.ai docs as summarised on 27 Oct 2026; re-check only if a call fails):
  - One OpenAI-compatible endpoint `POST https://api.meta.ai/v1/responses`, `Authorization: Bearer <MODEL_API_KEY>`.
    Use `stream: true` (non-streaming requests can 504 on slow responses). `GET /v1/models` lists enabled models.
  - `sam-3.1`: images or video (MP4/MOV, H.264/H.265/MPEG-4; not VP9/WebM/AV1). **One noun phrase + one media part
    per request**; ≤16 tracked objects; ≤15,000 frames. Video via `input_video` with `video_url` (public or base64
    data URL) or `file_id`. Output = one text line per frame: `<Nf>id<|box;x1=..;y1=..;x2=..;y2=..;w=W;h=H[;c=..]|><|mask;...|>,...`
    (frames with no object emit no line; ids stable within one response; masks base85). Request metadata
    `{"include_confidence":"true","mask_encoding":"one_bit"}`. Price $0.20 / 1k video frames (min 50 billed), $2.50 / 1k images.
  - `muse-spark-1.3`: text+image+video in, JSON-Schema output (`text.format`), reasoning `minimal…max`, image coordinates on a
    **0–1000 normalized grid**, ≤50 images/request, 50 MB inline. **Do not use `muse-spark-1.3-contributor`**: its inputs may be used to train Meta models (privacy) and it is capped at 150 RPM.
  - Official helper parsers: npm `@meta-sam/parser`, pip `meta-sam-parser`.
- **AI positioning:** all AI calls go through the Cloudflare Worker. Default = **Workers AI** with Meta's
  `@cf/meta/llama-4-scout-17b-16e-instruct` (vision, JSON-schema `response_format`, 131k context, $0.27 / $0.85 per M tokens), then
  `@cf/google/gemma-4-26b-a4b-it`, optionally through AI Gateway — so the product truthfully runs Meta AI on Cloudflare. The Meta Model API
  (Muse Spark 1.3, SAM 3.1) is an **optional provider**, enabled by setting `META_API_KEY`. The UI names the models that actually ran.
- **Local models are optional configuration, not a feature:** Muse Glimmer (Meta, 30B, needs a 24–32 GB GPU/Mac — does **not** fit on a
  phone) can be reached as an OpenAI-compatible LAN server; Google Gemma 4 E2B (image + text, < 1.5 GB RAM) runs on a phone via LiteRT-LM.
  Either is used only if configured in Settings (W5-01); nothing in the MVP depends on them.
- Known-good version pins (checked 1 Nov 2026): Kotlin **2.4.20**, Compose Multiplatform **1.12.0**, AGP **9.2.0**
  (AGP 9: KMP modules must use `com.android.kotlin.multiplatform.library`; the app module is a separate
  `com.android.application` module without the kotlin-android plugin), CameraX **1.6.2**, Media3 **≥ 1.10.1**.

### 2.1 Adapter mapping (filled by W0-01)

| Need | Found in track/link? (class, API) | ECHO adapter | Fallback (spec'd here) |
|---|---|---|---|
| Per-step distance + relative heading stream | _tbd_ | `TrackPlusMotionSource` | `BuiltInPdrMotionSource` (§6.1–6.2) |
| Barometric pressure / altitude | _tbd_ | same | `TYPE_PRESSURE` in `AndroidImuSource` |
| Camera-motion fusion (VO) | _tbd_ | bonus, only if exposed | none |
| Peer discovery / pairing | _tbd_ | `LinkPeerTransport` | QR with IP:port (§5.4) |
| Send arbitrary bytes to a peer | _tbd_ | same | `KtorLanTransport` (W2-03) |
| Wear OS haptic channel | _tbd_ | W5-04 | phone haptics |
| SIMT design tokens / fonts | _tbd_ | map into `EchoTheme` | tokens in §8.1 |
| Build setup (AGP/Kotlin versions of root) | _tbd_ | decides Option A/B in W0-02 | standalone build |

## 3. Architecture

```text
 Phone A — creator                       Cloudflare                          Meta Model API
 ┌────────────────────────────┐  HTTPS  ┌───────────────────────────┐  HTTPS ┌─────────────────┐
 │ Record: MotionSource+video │────────▶│ echo-api Worker (Hono)    │───────▶│ muse-spark-1.3  │
 │ Segmenter → draft route    │ kf+clips│ R2 echo-media (1-day TTL) │        │ sam-3.1         │
 │ Review → EchoRoute         │◀────────│ Workflow echo-pipeline    │◀───────│ /v1/responses   │
 │ Share: .echo + QR          │ result  │ Workers AI (default):     │        └─────────────────┘
 └─────────────┬──────────────┘         │ Llama 4 Scout → Gemma 4   │          (optional, if key set)
               │                        └───────────────────────────┘
               │ QR {ip, port, token, AES key} → HTTP GET over Wi-Fi/hotspot (no internet needed)
 ┌─────────────▼──────────────┐
 │ Phone B — navigator        │  airplane mode: MotionSource + Navigator + stored crops only
 └────────────────────────────┘
```

**Module layout** (inside `simt-app/` as `echo/`):

```text
echo/
  shared/                       KMP library (commonMain/androidMain/iosMain), package app.simt.echo
    commonMain/kotlin/app/simt/echo/
      platform/  MotionSource, CameraRecorder, MediaTools, PeerTransport, Haptics, Platform (interfaces)
      model/     EchoRoute, RouteNode, RouteLeg, Landmark, CaptureLog, AiModels (kotlinx.serialization)
      capture/   PdrEngine, HeadingMath, CaptureSession, Segmenter, ReplayMotionSource
      nav/       Navigator, TurnDetector, NavModels
      bundle/    BundleCodec (container, SHA-256, AES-GCM)
      transfer/  QrPayload, TransferService
      ai/        EchoApiClient, ManifestBuilder, CandidateMerger, AiRouter, OpenAiCompatVision (LAN server)
      data/      FileStore, RouteRepository, TrialRepository
      ui/        theme/, components/, screens/<feature>/{Screen,ViewModel}.kt, EchoNavHost.kt, App.kt
    androidMain/  AndroidImuSource, CameraXRecorder, AndroidMediaTools, KtorLanTransport, AndroidHaptics, AndroidPlatform,
                  LiteRtGemmaVision (optional, W5-01)
    iosMain/      TODO stubs (W5-03)
    commonTest/   unit tests
  androidApp/                   com.android.application: MainActivity, EchoApplication (builds AppGraph), adapters to track/link
  iosApp/                       Xcode shell (later)
  worker/                       Cloudflare Worker (TypeScript)
```

**Proposal module → package:** EchoCapture → `capture/`; EchoAIAdapter → `ai/` + `worker/`; EchoGraphBuilder → `capture/Segmenter` +
`ai/CandidateMerger`; EchoBundle → `bundle/`; EchoTransfer → `transfer/`; EchoNavigator → `nav/`; EchoLandmarkMatcher → roadmap (not in MVP); EchoIrisOverlay → W5-02.

**DI:** manual. `EchoApplication` builds `AppGraph(platform, motionSourceFactory, camera, media, transport, haptics, api, localVision?)`
and `MainActivity` provides it via `CompositionLocalProvider(LocalAppGraph provides graph) { App() }`.

| Operation | Where | Network |
|---|---|---|
| Record, segment, review, save | Phone A | offline |
| AI landmark proposals | Worker → Workers AI (Meta Model API optional); local model only if configured | online (Phone A only) |
| Transfer | A ⇄ B over LAN/hotspot (relay optional) | local network |
| Navigate | Phone B | **offline** (enforced by package rule §0.5) |

## 4. Tech stack

| Area | Choice | Version |
|---|---|---|
| Language / UI | Kotlin, Compose Multiplatform + Material 3 | Kotlin 2.4.20, CMP 1.12.0 (use wizard-generated Compose deps) |
| Build | Gradle (wizard default), AGP | AGP 9.2.0, compileSdk/targetSdk 36, minSdk 29 |
| Navigation / VM | `org.jetbrains.androidx.navigation:navigation-compose`, `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | latest stable (≥2.9) |
| Async / JSON | kotlinx-coroutines, kotlinx-serialization-json | ≥1.10 / ≥1.9 |
| Files | okio (`FileSystem.SYSTEM`) | ≥3.10 |
| HTTP | Ktor client (OkHttp on Android, Darwin on iOS) + `ktor-server-cio` (androidMain) | Ktor ≥3.2 |
| Crypto | `dev.whyoleg.cryptography:cryptography-core` + `cryptography-provider-optimal` | ≥0.5 |
| QR | generate: `io.github.alexzhirkevich:qrose`; scan: ML Kit `barcode-scanning` + `camera-mlkit-vision` | latest stable |
| Camera / video | CameraX core, camera2, lifecycle, video, compose; Media3 transformer | 1.6.2 / 1.10.1 |
| Backend | Cloudflare Workers + Workflows + R2 + Rate Limiting + Workers AI + AI Gateway; Hono, zod, `@meta-sam/parser` | wrangler ≥4 |
| Optional local model | LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android` + Gemma 4 E2B `.litertlm`, or any OpenAI-compatible LAN server | only if configured |

## 5. Core contracts

### 5.1 Platform seams (commonMain)

```kotlin
data class MotionUpdate(val tNs: Long /* monotonic; Android elapsedRealtimeNanos base */, val distanceM: Double /* since start() */,
    val yawRad: Double /* relative to start(), clockwise-positive, (-π, π] */, val stepCount: Int, val isStep: Boolean /* new step */,
    val pressureHpa: Double? /* null without barometer */, val source: String /* "simt_track_plus" | "echo_builtin_pdr" | "replay" */)
interface MotionSource { val updates: Flow<MotionUpdate>; fun start(); fun stop() }        // ~20 Hz + every step
interface CameraRecorder { @Composable fun Preview(modifier: Modifier); suspend fun takePhoto(out: Path): Path
    suspend fun startVideo(out: Path): Long /* tNs of recording start, MotionUpdate clock */; suspend fun stopVideo(): Path }
interface MediaTools {
    suspend fun extractFrame(video: Path, atMs: Long, out: Path, maxSide: Int = 1280): FrameInfo
    suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo
    suspend fun cropJpeg(src: Path, box: IntRect, out: Path, maxSide: Int = 384): Path }
data class FrameInfo(val path: Path, val width: Int, val height: Int)          // displayed orientation
data class ClipInfo(val path: Path, val frameCount: Int, val durationMs: Long)  // fps = frameCount*1000/durationMs
interface PeerTransport { suspend fun offer(payload: ByteArray, token: String): Endpoint
    suspend fun fetch(endpoint: Endpoint, token: String): ByteArray; fun stopOffer() }
data class Endpoint(val host: String, val port: Int)
enum class HapticPattern { TICK, CONFIRM, LEFT, RIGHT, U_TURN, WARN, ARRIVE }; interface Haptics { fun play(p: HapticPattern) }
interface Platform { val dataDir: Path; fun nowMs(): Long; fun isOnline(): Boolean; fun deviceLabel(): String
    fun shareFile(path: Path, mime: String); @Composable fun KeepScreenOn() }
// Optional local model seam (ai/): returns the same AiCandidate as §5.5 candidates[] (boxes in keyframe pixels)
data class ScoutCheckpoint(val nodeId: String, val type: NodeType, val turn: TurnDir?, val atDistanceM: Double,
    val keyframes: List<FrameInfo>, val heroIndex: Int, val routeContext: String)
interface LocalVision { val modelId: String; suspend fun proposeLandmarks(cp: ScoutCheckpoint): List<AiCandidate> }  // LiteRtGemmaVision | OpenAiCompatVision
```

### 5.2 Domain model (commonMain, `@Serializable`)

```kotlin
data class CaptureLog(val draftId: String, val startedAtEpochMs: Long, val motionSource: String,
    val videoStartNs: Long, val samples: List<CaptureSample>, val marks: List<Mark>,
    val startPhoto: String, val routeName: String = "", val destinationLabel: String = "")
data class CaptureSample(val tNs: Long, val d: Double, val yaw: Double, val p: Double? = null)  // every step + 2 Hz
data class Mark(val tNs: Long, val d: Double, val label: String)

data class EchoRoute(
    val schemaVersion: Int = 1, val routeId: String, val name: String, val createdAtEpochMs: Long,
    val creatorLabel: String, val coordinateSystem: String = "relative_to_confirmed_start",
    val motionSource: String, val totalLengthM: Double, val start: StartAnchor,
    val nodes: List<RouteNode>, val legs: List<RouteLeg>, val path: List<PathPoint>,
    val landmarks: List<Landmark> = emptyList(), val privacy: PrivacyInfo = PrivacyInfo(), val ai: AiProvenance? = null)
data class StartAnchor(val description: String, val photoAssetId: String?, val alignmentHint: String)
data class RouteNode(val id: String, val type: NodeType, val atDistanceM: Double, val label: String,
    val turn: TurnDir? = null, val cue: String = "", val levelDelta: Int = 0,
    val landmarkIds: List<String> = emptyList(), val captureTMs: Long? = null /* creator only; null on export */)
enum class NodeType { START, TURN, CHECKPOINT, LEVEL_CHANGE, DESTINATION }
enum class TurnDir { LEFT, RIGHT, U_TURN }
data class RouteLeg(val fromNode: String, val toNode: String, val lengthM: Double, val headingRelDeg: Double, val levelDelta: Int = 0)
data class PathPoint(val d: Double, val x: Double, val y: Double)   // every 0.5 m; x east-ish, y forward at start
data class Landmark(
    val id: String, val nodeId: String, val title: String, val description: String, val cue: String,
    val samPhrase: String? = null, val signText: String? = null, val cropAssetId: String? = null,
    val visibleFromM: Double? = null, val trackability: Double? = null, val samConfidence: Double? = null,
    val source: LandmarkSource, val models: String? = null /* "muse-spark-1.3 + sam-3.1" */, val confirmation: Confirmation)
enum class LandmarkSource { AI_GROUNDED, AI_BOX_ONLY, MANUAL }
enum class Confirmation { CREATOR_VERIFIED, AI_SUGGESTED }
data class PrivacyInfo(val rawVideoIncluded: Boolean = false, val expiresAtEpochMs: Long? = null)
data class AiProvenance(val jobId: String, val scoutModel: String, val groundModel: String, val processedAtEpochMs: Long)
data class Asset(val id: String, val mime: String, val b64: String)
data class BundleBody(val route: EchoRoute, val assets: List<Asset>)   // assets sorted by id
```

`EchoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }`.
Ids: routes/drafts = random UUID string; nodes `n0..nN`; landmarks `lm_<nodeId>_<k>`; assets `a_<name>`.

### 5.3 Storage layout (`Platform.dataDir`)

```text
drafts/<draftId>/capture.json | video.mp4 | start.jpg | kf/<nodeId>_<i>.jpg | clips/<nodeId>.mp4 | job.json | route.draft.json
routes/<routeId>/route.json | assets/<assetId>.jpg            (imported and own routes alike)
trials/<trialId>.json
```

### 5.4 Wire formats

- **Container** `C = "ECHO"(4 B) ‖ 0x01 ‖ SHA-256(body)(32 B) ‖ body` where `body` = UTF-8 `EchoJson` of `BundleBody`.
  The hash is verified over received bytes (never re-encoded, so it is stable across Android/iOS).
- **Encrypted file** `E = "ECX1"(4 B) ‖ AES-256-GCM(key, C)` (cipher output = 12 B nonce ‖ ciphertext ‖ 16 B tag).
- **QR URI** (≤ 200 chars): `simt-echo://r?v=1&h=<ipv4>&p=<port>&t=<token>&k=<key>&s=<sha8>&n=<name>[&c=<relayCode>]`
  — `t` 16 random bytes and `k` the 32-byte AES key, both base64url without padding; `s` = first 8 bytes of SHA-256(body) as hex;
  `n` URL-encoded ≤ 40 chars.
- File fallback (W2-07) shares the **plain container** `C` as `<name>.echo` after an explicit privacy warning.

### 5.5 Worker HTTP API (`Authorization: Bearer <ECHO_APP_TOKEN>`)

| Method & path | Body / response |
|---|---|
| `GET /v1/health` | `{ok, version, metaKeyConfigured}` (no auth) |
| `POST /v1/jobs` | multipart: `manifest` (JSON) + files named by manifest ids (`kf_*` JPEG, `clip_*` MP4) → `202 {jobId}` |
| `GET /v1/jobs/:id` | `{status: queued, running, complete or errored, stage, result?, error?}` |
| `DELETE /v1/jobs/:id` | deletes `jobs/<id>/*` in R2 → `204` |
| `POST /v1/relay` (P1) | raw `E` bytes ≤ 5 MB → `{code, expiresAt}` |
| `GET /v1/relay/:code` (P1) | raw `E` bytes or 404 |

```jsonc
// manifest (zod-validated in worker, built by ManifestBuilder in app)
{ "schema": 1, "draftId": "…", "consent": { "sendFramesToCloudAi": true, "atEpochMs": 1762000000000 },
  "routeContext": "Indoor walk, 62 m, 3 turns, 1 level change",
  "checkpoints": [ { "nodeId": "n2", "type": "TURN", "turn": "LEFT", "atDistanceM": 23.5, "tMs": 41250, "heroKeyframeId": "kf_n2_1",
    "keyframes": [ { "id": "kf_n2_0", "tMs": 39250 }, { "id": "kf_n2_1", "tMs": 40750 }, { "id": "kf_n2_2", "tMs": 42750 } ],
    "clip": { "id": "clip_n2", "startMs": 37250, "endMs": 42250, "frameCount": 50, "durationMs": 5000 },   // used only by SAM 3.1
    "approach": [ { "tMs": 37250, "d": 18.9 }, { "tMs": 37750, "d": 19.4 } ] } ] }                          // every 500 ms over the clip
// result (JobResult)
{ "jobId": "…", "models": { "scout": "@cf/meta/llama-4-scout-17b-16e-instruct", "ground": "sam-3.1 | none" },
  "checkpoints": [ { "nodeId": "n2", "candidates": [ {
      "candidateId": "n2_0", "title": "Red pillar", "description": "…", "cue": "Turn left at the red pillar",
      "samPhrase": "red pillar", "signText": null, "privacyRisk": false, "keyframeId": "kf_n2_1",
      "box": [412, 230, 590, 900], "frameW": 720, "frameH": 1280,                    // pixels in reported frame size
      "maskSvgPath": "M…Z", "maskW": 180, "maskH": 320, "samConfidence": 0.86,        // null without SAM
      "trackability": 0.78, "visibleFromM": 9.5, "distinctiveness": 0.9, "permanence": 0.95, "rank": 0.83,
      "source": "AI_GROUNDED", "models": "llama-4-scout + sam-3.1", "evidenceKey": "jobs/<id>/sam/n2_0.txt" } ] } ],
  "usage": { "samFrames": 400, "scoutMs": 21000, "samMs": 9000 } }
```

## 6. Algorithms (pure Kotlin in commonMain, unit-tested)

Conventions: heading is **clockwise-positive** (right turn ⇒ positive Δyaw). `wrapPi(a)` maps to (-π, π].
Circular mean = `atan2(Σ sin θ, Σ cos θ)`. All thresholds live in `EchoConfig` (data class) for W4-02 tuning.

### 6.1 Heading from `TYPE_GAME_ROTATION_VECTOR` (gyro+accel, no magnetometer → immune to garage steel)

```kotlin
// r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, event.values)  (row-major, device→world)
fun headingRad(r: FloatArray, prevCamera: Boolean): Pair<Double, Boolean> {
    val flatness = abs(r[8])                                    // |R22|: 1 = flat on table, 0 = upright
    val camera = if (prevCamera) flatness < 0.8f else flatness < 0.6f   // hysteresis
    val h = if (camera) atan2(-r[2].toDouble(), -r[5].toDouble())      // back-camera axis (-Z) projected
            else atan2(r[1].toDouble(), r[4].toDouble())                // device-top axis (+Y) projected
    return h to camera
}
// yaw0 = heading of first sample after start();  yawRad = wrapPi(heading - yaw0)
```

### 6.2 Built-in PDR (`PdrEngine`) — accelerometer at `SENSOR_DELAY_GAME` (~50 Hz)

1. `mag = |a|`; gravity `g ← g + 0.02 (mag − g)` (init 9.81); signal `s ← s + 0.25 ((mag − g) − s)`.
2. Track `amax`/`amin` since last step. A **step** fires when `s` crosses 0 downward after `amax > 0.9 m/s²`,
   and 280 ms ≤ Δt since last step ≤ 2000 ms (if > 2000 ms, just reset the window: stationary).
3. Step length (Weinberg) `L = K · (amax − amin)^0.25`, `K = 0.48` (calibratable, W4-02), clamp to [0.35, 0.95] m.
4. Position: `x += L·sin(yaw)`, `y += L·cos(yaw)`; `distanceM += L`. Emit `MotionUpdate(isStep=true)` per step and
   throttled updates at 20 Hz from rotation samples. Pressure (`TYPE_PRESSURE`, if present) is attached as latest value.
5. Sanity: if `|event.timestamp − elapsedRealtimeNanos()| > 1 s` on the first event, store the offset and correct all timestamps.

### 6.3 Segmenter (`CaptureLog` → draft `EchoRoute`)

1. Resample samples on distance every 0.5 m (yaw by circular interpolation) → `PathPoint` list (x, y from 6.2).
2. Smooth yaw with a circular moving average over ±1 m.
3. Walk the series keeping `legHeading` = circular mean of the last 3 m. When `|wrapPi(yaw − legHeading)| > 35°`
   for ≥ 1.5 m, open a turn; close it when yaw changes < 10° over 1.5 m. Turn angle `α = wrapPi(newLeg − oldLeg)`.
   Keep if `|α| ≥ 45°`: `45–150° → RIGHT (α>0) / LEFT (α<0)`, `≥150° → U_TURN`. Node distance = middle of the turn.
4. Level change: `h(p) = 44330 (1 − (p/p0)^(1/5.255))`; if a leg's |Δh| > 2.2 m, set `levelDelta = round(Δh/3.0)` and
   insert a `LEVEL_CHANGE` node at the point where half the change has occurred.
5. Nodes: `START` (d=0), turns, user marks (a mark within 3 m of a turn merges into the turn node and supplies its label),
   `LEVEL_CHANGE`, `DESTINATION` (end, label = destinationLabel). Drop CHECKPOINT/LEVEL nodes closer than 4 m to the previous node (never START/DESTINATION).
6. Legs between consecutive nodes: `lengthM` = Δd, `headingRelDeg` = circular mean of smoothed yaw on the leg.
7. Each node gets `captureTMs` (interpolate tNs at its distance, minus `videoStartNs`) and a default cue (§8.4).

### 6.4 Navigator (state machine, `nav/Navigator.kt`)

State: `phase`, `legIndex`, `legStartDist` (B's odometer at leg start), `lastAnchorDist`, `yawOffset`.
Derived: `progress = dist − legStartDist`, `remaining = leg.lengthM − progress`,
`arrow = wrapPi(rad(leg.headingRelDeg) − (yaw + yawOffset))`, `σ = 0.5 + 0.08·(dist − lastAnchorDist)` m.

| Phase | Enter when | Shows / does | Exit |
|---|---|---|---|
| `ALIGN` | navigation opened | start photo + live preview, "Stand here. Match this view." | `Aligned` → yaw0 = current, dist = 0 → `WALK` |
| `WALK` | leg started | arrow, "Walk straight · 12 m", next landmark card when `remaining ≤ max(visibleFromM ?: 8, 6)` | see rows below |
| `APPROACH` | `remaining ≤ max(4, 0.15·length)` and a turn is next | TICK haptic once, "In 3 m turn left at the red pillar" | turn event |
| `TURNING` | landmark confirmed at a turn node | "Turn left now", arrow previews next leg | turn event |
| `CONFIRM` | non-turn node with landmark reached | card + "I see it" / "Not yet"; auto-dismiss after +8 m | advance leg |
| `UNCERTAIN` | `progress > length + max(5, 0.3·length)` without expected turn, or `σ > 6` | WARN haptic, "Location uncertain — confirm landmark" | `SeeLandmark` / `Lost` |
| `OFF_ROUTE` | turn mismatched, or first leg ≥ 6 m with heading error > 50° (wrong start) | "Off route — go back to <last landmark>" / "This doesn't match the start — re-align" | `RestartFromLast` / `RestartFromStart` |
| `ARRIVED` | last leg `remaining ≤ 2 m` then "I'm here" or +3 m | ARRIVE haptic, summary | — |

Rules: **turn event** (6.5) matching expected direction with `progress ≥ 0.5·length` ⇒ advance leg, `legStartDist = dist`,
`lastAnchorDist = dist`, CONFIRM haptic. Early matching turn (< 50 %) ⇒ `UNCERTAIN` with copy "Turned early? Expected in 9 m".
`SeeLandmark` ⇒ snap `legStartDist = dist − leg.lengthM` (we are at the node), anchor, then `TURNING` or advance.
Non-turn nodes without a landmark advance automatically when `remaining ≤ 0` (LEVEL_CHANGE shows its cue, e.g. "Go down 1 level").
**Corridor snapping:** in `WALK`, if yaw std over the last 4 m < 8° and `|arrow| < 25°`, move
`yawOffset += 0.05·arrow` per metre walked (compensates gyro drift and imperfect alignment).
`Lost` opens a sheet: "Go back to <last landmark>" (`RestartFromLast` → leg of last anchor) or "Restart from start".

### 6.5 Online turn detector (`TurnDetector`)

Keep 6 s of `(tNs, yaw)`. `ref` = circular mean over [t−4 s, t−2.5 s]. Fire when `|wrapPi(yaw − ref)| ≥ 45°` **and** yaw std
over the last 1.0 s < 12°. Angle classified as in 6.3 step 3. After firing: clear history, 2 s cooldown.

### 6.6 Landmark track selection and ranking (worker, `ground.ts`)

- Convert Spark box (0–1000) to pixels of the SAM frame: `x·w/1000`, `y·h/1000`. `heroIdx = round((heroTMs − clip.startMs)·fps/1000)`.
- For each SAM track id: `first` = first frame seen; `hero` = observation nearest `heroIdx` (only if within ±5 frames);
  `iou = IoU(sparkBox, hero.box)`; `present = count / (heroIdx − first + 1)` capped at 1; `meanC` = mean confidence.
  `score = 0.6·iou + 0.25·present + 0.15·meanC`. Choose max score with `iou ≥ 0.1`; else best `present·meanC` and set `boxMismatch`.
- `visibleFromM = node.atDistanceM − d(t_first)` with `t_first = clip.startMs + first·1000/fps`, `d()` interpolated from `approach` (≥ 0).
- `trackability = present`. `rank = 0.35·distinctiveness + 0.25·permanence + 0.2·(trackability ?: 0.3) + 0.2·(samConfidence ?: 0.3) − 0.2·[ambiguous] − 0.1·[boxMismatch]`.

## 7. AI pipeline (through Cloudflare; Meta models, provider-agnostic)

### 7.0 Providers and routing

| Role | Provider id — model | Runs on | Enabled when | Pri |
|---|---|---|---|---|
| Scout (propose) | `workers-ai` — Llama 4 Scout, then Gemma 4 26B A4B | Worker → Workers AI (AI Gateway) | always (default) | P0 |
| Scout | `meta-spark` — Muse Spark 1.3 | Worker → Meta Model API | `META_API_KEY` set | P1 |
| Ground (verify/track) | `sam31` — SAM 3.1 video | Worker → Meta Model API | `META_API_KEY` set | P0 if key |
| Ground | `none` — keep the scout's box | Worker | always (fallback) | P0 |
| Scout (local) | Gemma 4 E2B on the phone, or LAN server (e.g. Muse Glimmer) | Phone A / laptop | configured in Settings | P2 |

**Routing:** the app always calls the Worker. The Worker walks `SCOUT_ORDER` (default `workers-ai,meta-spark`) and `GROUND_ORDER`
(default `sam31,none`), skipping providers without credentials and moving on after `NonRetryableError` or exhausted retries; the ids
that ran go to `result.models`. `ai/AiRouter.kt` uses a **local model only if one is configured** and (the Worker is unreachable or
"Prefer local model" is on); otherwise the creator picks landmarks manually. All paths emit `AiCandidate`, so Review and `CandidateMerger`
never branch on provider. The Review footnote names the models that ran ("Llama 4 Scout on Cloudflare", "Muse Spark 1.3 + SAM 3.1").

### 7.1 Cloud pipeline (Workflow)

```text
app ── POST /v1/jobs (manifest + keyframes + clips) ──▶ Worker ── R2 jobs/<id>/in/*
Workflow echo-pipeline (id = jobId):
  1 load      read + validate manifest                                    → progress "queued→reading"
  2 scout     SCOUT_ORDER: workers-ai = one call per checkpoint (≤ 3 images); meta-spark (if key) = ONE call for all
              keyframes (≤ 48 images) → ≤3 candidates/checkpoint (JSON Schema); raw output saved to R2 as evidence
  3 ground    GROUND_ORDER (sam31 only with key): per checkpoint, top-2 candidates in parallel: sam-3.1 video on the clip with candidate.samPhrase
              → parse → select track (6.6) → hero mask → SVG path; raw output saved as evidence
              fallback a: no observations → sam-3.1 image on hero keyframe; fallback b: box-only candidate
  4 assemble  rank, write jobs/<id>/result.json                           → progress "complete"
```

**Why this split:** the vision LLM reads the scene, signs and route context; with a Meta key, SAM verifies that the proposed object is real, segmentable and stays trackable while approaching — which yields the
`visibleFromM` cue distance. Sensors still own the geometry.

**Optional Spark request (`meta-spark`)** — the SYSTEM prompt and SCHEMA below are shared by every scout:

```jsonc
{ "model": "muse-spark-1.3", "stream": true, "reasoning": { "effort": "low" },
  "input": [ { "type": "message", "role": "system", "content": [ { "type": "input_text", "text": "<SYSTEM>" } ] },
    { "type": "message", "role": "user", "content": [ { "type": "input_text", "text": "<CONTEXT>" },
      { "type": "input_text", "text": "imageId=kf_n2_0" }, { "type": "input_image", "image_url": "data:image/jpeg;base64,…" } /* …per keyframe */ ] } ],
  "text": { "format": { "type": "json_schema", "name": "echo_landmarks", "strict": true, "schema": "<SCHEMA>" } } }
```
If a 400 mentions the role, move SYSTEM to top-level `"instructions"`. If the schema is rejected, drop `minItems/maxItems`
and nullable type arrays from it and enforce them with zod after parsing.

**SYSTEM:**
```text
You help pedestrians follow an indoor route recorded by another person. For each checkpoint you get 2–3 photos
taken while walking toward it. The travel direction at each checkpoint is MEASURED BY SENSORS and given to you;
never infer or change directions or distances. Propose up to 3 landmarks per checkpoint that a stranger can spot
from several metres away. Prefer permanent structure: pillars, doors, elevators, stairs, ramps, signs, painted
floor markings, pipes, fire equipment, distinctive walls. Never use vehicles, people, faces, license plates or
temporary objects. samPhrase: 1–4 word concrete noun phrase with colour/material, no positions or verbs
("red pillar", "green exit sign", "yellow stair railing"). box: [x1,y1,x2,y2] on a 0–1000 grid of the named image.
distinctiveness: how unique it is ALONG THIS ROUTE (a red pillar seen at three checkpoints is low). permanence:
1 = building structure, 0 = movable. Copy legible sign text exactly into signText, else null. Set privacyRisk true
if the crop would show people, faces, plates or screens. cue: one short instruction combining the given direction
and the landmark, e.g. "Turn left at the red pillar". If nothing suitable is visible, return an empty list.
```
**CONTEXT** (built in worker): `Route: <routeContext>. Checkpoints in walking order:` then per checkpoint
`- n2: TURN LEFT at 23.5 m (sensor-measured). Images: kf_n2_0 (2.0 s before), kf_n2_1 (at the turn), kf_n2_2 (after).`

**SCHEMA:** object `{checkpoints: [{nodeId: string, candidates: [≤3 × {title, samPhrase, description, imageId: string;
box: integer[4]; distinctiveness, permanence: number 0–1; signText: string|null; ambiguous, privacyRisk: boolean; cue: string}]}]}`,
all properties required, `additionalProperties: false` at every level.

### 7.2 Workers AI scout (`workers-ai`, default)

```ts
for (const model of env.WORKERS_AI_MODELS.split(",")) {          // Llama 4 Scout first, Gemma 4 26B A4B second
  try {
    const out: any = await env.AI.run(model, {
      messages: [{ role: "system", content: SYSTEM }, { role: "user", content: [{ type: "text", text: contextFor(cp) },
        ...cp.keyframes.map(k => ({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${k.b64}` } }))] }],
      response_format: { type: "json_schema", json_schema: CHECKPOINT_SCHEMA },   // = one item of SCHEMA.checkpoints
      max_tokens: 1500, temperature: 0.1 }, { gateway: { id: env.AI_GATEWAY_ID } });
    return { model, json: typeof out.response === "string" ? JSON.parse(out.response) : out.response };
  } catch (e) { last = e; }                                       // try next model
}
```
Same SYSTEM text as §7.1; ≤ 3 images per call. If a model rejects `response_format`, retry once without it and append
"Return only JSON matching this schema: <schema>"; zod validates either way. Boxes are on the same 0–1000 grid.

### 7.3 SAM 3.1 ground (only when `META_API_KEY` is set) and parsing

```jsonc
{ "model": "sam-3.1", "stream": true,
  "metadata": { "include_confidence": "true", "mask_encoding": "one_bit" },
  "input": [ { "type": "message", "role": "user", "content": [
      { "type": "input_text", "text": "red pillar" },                                 // exactly one noun phrase
      { "type": "input_video", "video_url": "data:video/mp4;base64,…" } ] } ] }      // image fallback: input_image + image_url
```

```ts
// samParse.ts — boxes/ids/confidence; the raw line is kept so the hero frame's mask can be decoded later
export type SamObs = { frame: number; id: number; box: [number, number, number, number]; w: number; h: number; c?: number; line: string };
const LINE = /^<(\d+)f>(.*)$/, REC = /(\d+)<\|box;([^|]*)\|>(?:<\|mask;([^|]*)\|>)?/g;   // base85 never contains '|'
export function parseSam(text: string): SamObs[] {
  const out: SamObs[] = [];
  for (const raw of text.split("\n")) {
    const m = LINE.exec(raw.trim()); if (!m) continue;
    for (const r of m[2].matchAll(REC)) {
      const kv = Object.fromEntries(r[2].split(";").map(p => p.split("=") as [string, string]));
      out.push({ frame: +m[1], id: +r[1], box: [+kv.x1, +kv.y1, +kv.x2, +kv.y2], w: +kv.w, h: +kv.h, c: kv.c !== undefined ? +kv.c : undefined, line: raw.trim() });
    }
  }
  return out;
}
```
Mask → SVG: feed the hero frame's `line + "\n"` to `@meta-sam/parser`'s video parser and call its mask→SVG helper (TS names mirror
Python `video_segmentation_format()` / `decode_mask_to_svg_path()`; confirm exports in `node_modules/@meta-sam/parser/dist/*.d.ts`); SVG is in
mask-raster coordinates (`maskW×maskH` covering the frame). **Fallback:** `maskSvgPath = null` (box-only UI). Store raw SAM text in R2.

**SSE collector** for Meta calls (`stream: true`; `NonRetryableError` from `cloudflare:workflows`):
```ts
export async function metaResponses(env: Env, body: object): Promise<string> {
  const res = await fetch("https://api.meta.ai/v1/responses", { method: "POST", body: JSON.stringify({ ...body, stream: true }),
    headers: { Authorization: `Bearer ${env.META_API_KEY}`, "Content-Type": "application/json", Accept: "text/event-stream" } });
  if (res.status === 429 || res.status >= 500) throw new Error(`retryable ${res.status}`);          // Workflow retries
  if (!res.ok) throw new NonRetryableError(`meta ${res.status}: ${(await res.text()).slice(0, 500)}`);
  const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader(); let buf = "", out = "";
  for (let r = await reader.read(); !r.done; r = await reader.read()) {
    buf += r.value; let i: number;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      for (const line of buf.slice(0, i).split("\n")) {
        const data = line.startsWith("data:") ? line.slice(5).trim() : ""; if (!data || data === "[DONE]") continue;
        const j = JSON.parse(data);
        if (j.type === "response.output_text.delta") out += j.delta;
        else if (j.type === "response.failed" || j.type === "error") throw new Error(data.slice(0, 500));
      }
      buf = buf.slice(i + 2);
    }
  }
  return out;
}
```
Base64 via `Buffer.from(bytes).toString("base64")` (`nodejs_compat`).

### 7.4 Envelope (per 60 m route, 6–8 checkpoints)

Workers AI: 6–8 calls, cents. Optional: Spark 1 call (~24 images); SAM ≤ 16 clip calls × ≥ 50 billed frames ≈ $0.16. Target end-to-end < 2 min
(measure in W3-06 and show real numbers in the demo). Workflow step results must stay < 1 MiB: return compact
candidates only; large text goes to R2.

### 7.5 Optional local model (only if configured, W5-01)

- **LAN server:** `OpenAiCompatVision(baseUrl, model)` → `POST {baseUrl}/v1/chat/completions`, same messages / `response_format` as §7.2.
  Muse Glimmer on a 24–32 GB Mac/GPU laptop: `llama-server -m <Glimmer Q4_K_M>.gguf --mmproj <perception-encoder>.gguf --host 0.0.0.0
  --port 8080` (files from the `Muse-Glimmer-30B-GGUF` repo) or Ollama.
- **On phone:** `LiteRtGemmaVision` with `gemma-4-E2B-it.litertlm` (LiteRT community build; Gemma terms) in `getExternalFilesDir("models")`
  (`adb push`). `Engine(EngineConfig(modelPath, backend = Backend.GPU(), visionBackend = Backend.GPU(), cacheDir)).initialize()` once
  off the main thread; per checkpoint `createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM + " Answer with JSON
  only."))).use { it.sendMessage(Contents.of(Content.ImageFile(hero), Content.Text(contextFor(cp)))) }`; parse the first `{…}` block;
  `close()` the engine afterwards. Constructor shapes vary by LiteRT-LM release — follow autocompletion. Needs ≥ 8 GB RAM.

## 8. UX / UI specification

### 8.1 Principles and tokens

Calm, high-contrast, one action per screen, numbers big, words few. **Dark-first** (dim garages) with a light variant.
Every instruction is redundant: text + arrow + haptic. Uncertainty is always visible as a chip.

| Token | Dark | Light | | Token | Value |
|---|---|---|---|---|---|
| `bg` | `#0B0D10` | `#F7F8FA` | | radius card / sheet / pill | 28 / 24 / 999 dp |
| `surface` | `#15181D` | `#FFFFFF` | | spacing | 4, 8, 12, 16, 24, 32 dp |
| `surfaceHigh` | `#1E2228` | `#EEF0F3` | | touch target | ≥ 56 dp primary, ≥ 48 dp other |
| `onBg` / `muted` | `#F2F4F7` / `#9AA3AE` | `#0B0D10` / `#5B6470` | | type: display / title / body / label | 64 / 22 / 16 / 13 sp |
| `accent` (Echo Lime) | `#C6F432` | `#4E7A00` | | numbers | `fontFeatureSettings = "tnum"` |
| `warn` / `danger` / `ok` | `#FFB020` / `#FF5A5F` / `#3DDC97` | same | | motion | `spring(0.8f, 300f)`; `AnimatedContent` for text |

Font: SIMT font if found in W0-01, else `FontFamily.Default`. Icons: Material Symbols (rounded) via `material-icons-extended` or drawn vectors.

### 8.2 Screen map

`Home → Record → Finish sheet → Review(manual / AI) → RouteDetail → Share` · `Home → Receive → RouteDetail → Align → Navigate → Arrived` · `Settings` (gear on Home).

### 8.3 Screens

| Screen | Layout (top → bottom) | Primary action |
|---|---|---|
| **Home** | small "ECHO" wordmark + gear · route cards (name, length, turns, "Draft"/"Own"/"Received", offline badge) · empty state "Walk it once. Share the way." | pill **Record** (accent) + pill **Receive** (outline), bottom-fixed |
| **Record** | full-bleed camera preview · pre-start overlay "Stand at the start, face where you'll walk" · while recording: top chips (time, steps), centre-bottom giant distance `23.4 m`, small heading tick | 88 dp round **Start**; then **Mark spot** (pill) + long-press **Finish** |
| **Finish sheet** | route name, destination label ("Elevator, level −2") | **Continue** |
| **Review** | minimap (path + numbered nodes) · vertical timeline: node chip (Turn left · 23 m) + landmark card (keyframe with mask overlay, title, cue, stat chips "SAM 0.86 · tracked 78% · visible ~9 m") · per card: Use / Next option / Draw my own / Skip · provenance footnote with the models that ran ("Muse Spark 1.3 + SAM 3.1", "Llama 4 Scout on Cloudflare", "Gemma 4 E2B on this phone") | **Find landmarks** (consent) then **Save ECHO** |
| **Processing** | 4-step progress list: Uploading · Reading scenes (<scout model>) · Grounding (<ground model>) · Ready | Cancel |
| **RouteDetail** | name, length, nodes, landmark strip, privacy/expiry line | **Navigate**, **Share**; debug **Simulate** |
| **Share** | white QR card (max width), name, size, status "Waiting for the other phone…" → "Sent ✓" | "Share as file" text button |
| **Receive** | camera scanner with rounded cutout, hint "Scan the QR on the other phone", "Import file" | auto on scan → import confirmation sheet |
| **Align** | start photo (top 60 %) + live preview PiP · hint text | **I'm aligned** |
| **Navigate** | top: Offline chip + σ chip ("±3 m") · centre: 200 dp arrow (Canvas chevron, animated rotation) · instruction (title) + distance (display) · landmark card (crop, title, "in ~6 m") · route progress bar with node dots | contextual: **I see it** / **Lost** |
| **Arrived** | check mark, destination, time, distance, confirmations | **Done** (saves trial) |
| **Settings** | step-length K + calibrate, mode ECHO / sensor-only (baseline), API base, export trials CSV, delete all; *Advanced:* optional local model (file or LAN URL) | — |

### 8.4 Haptics and copy

Haptics (`VibrationEffect.createWaveform`, timings ms): TICK `[0,30]`, CONFIRM `[0,40,80,40]`, LEFT `[0,60,120,60]`,
RIGHT `[0,60,120,60,120,60]`, U_TURN `[0,300,150,300]`, WARN `[0,400]`, ARRIVE `[0,200,120,60,120,200]`.
Default cues: TURN `"Turn left"` / `"Turn left at the <title>"`, CHECKPOINT `"Pass the <title>"`, LEVEL `"Go down 1 level"`,
DESTINATION `"Arrive at <label>"`. Copy style: sentence case, ≤ 6 words for instructions, never claim certainty.

## 9. Tracker

Order follows the proposal: **prove record → transfer → offline replay with a manual route (Waves 0–2) before Meta AI (Wave 3).**
Est = focused hours. Pri: P0 = needed for the end-to-end demo, P1 = expendable, P2 = after submission.

### Wave 0 — Foundations (Sun 1 Nov, morning)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W0-01 | Repo recon (track, link) + fill §2.1 | P0 | 1 | — | §2.1 filled; Track+/link reuse decided | ⬜ |
| W0-02 | KMP scaffold `:echo:shared` + `:echo:androidApp` (+ iOS shell) | P0 | 1.5 | W0-01 | builds & installs; AGP 9 module split; manifest + secrets | ⬜ |
| W0-03 | Design system, components, nav shell | P0 | 1.5 | W0-02 | EchoTheme, 10 components, all routes navigable | ⬜ |
| W0-04 | Domain model, FileStore, RouteRepository, fixtures | P0 | 1 | W0-02 | §5.2 types, okio store, L_ROUTE fixture, tests | ⬜ |
| W0-05 | Worker scaffold (Hono, auth, rate limit, R2, deploy) | P0 | 1 | — | `/v1/health` live, R2 bucket + TTL, secrets set | ⬜ |

### Wave 1 — Capture (Sun 1 Nov, afternoon)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W1-01 | MotionSource: AndroidImuSource + PdrEngine (+ Track+ adapter) | P0 | 2 | W0-04 | `MotionUpdate` stream; heading + PDR tests green | ⬜ |
| W1-02 | CameraRecorder (CameraX preview, silent video, start photo, clock sync) | P0 | 1.5 | W0-03 | silent HD MP4 + start.jpg + `videoStartNs` | ⬜ |
| W1-03 | Record screen + CaptureSession + Finish sheet | P0 | 1.5 | W1-01, W1-02 | draft dir with capture.json, video, start photo | ⬜ |
| W1-04 | Segmenter → draft EchoRoute (+ minimap) | P0 | 1.5 | W1-03 | `route.draft.json` with nodes/legs/path; segmenter tests | ⬜ |
| W1-05 | MediaTools: keyframes + crop (Android) | P0 | 0.75 | W1-02 | upright keyframes per node; `cropJpeg` | ⬜ |

### Wave 2 — Core loop without AI (Mon 2 Nov)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W2-01 | Review screen, manual landmark editor, Save ECHO | P0 | 2 | W1-04, W1-05 | saved `routes/<id>` with manual landmarks | ⬜ |
| W2-02 | BundleCodec (container, SHA-256, AES-GCM) | P0 | 1 | W0-04 | `.echo` container + AES-GCM seal/open; tests | ⬜ |
| W2-03 | PeerTransport: KtorLanTransport (+ link adapter) + QrPayload | P0 | 2 | W2-02 | LAN offer/fetch in < 3 s; QR URI build/parse | ⬜ |
| W2-04 | Share + Receive screens (QR show/scan, import sheet) | P0 | 1.5 | W2-03 | QR shown on A, scanned + imported on B | ⬜ |
| W2-05 | Navigator engine + TurnDetector + ReplayMotionSource + tests | P0 | 2.5 | W0-04 | `NavUi`/`NavEvent` engine, replay, 7 scenario tests | ⬜ |
| W2-06 | Align, Navigate, Arrived screens + haptics + offline chip | P0 | 2 | W2-05, W2-01 | real course followed in airplane mode | ⬜ |
| W2-07 | File fallback (share/import plain `.echo`) | P0 | 0.75 | W2-02 | plain `.echo` via share sheet + import | ⬜ |

### Wave 3 — AI landmarks, cloud, Meta-first (Mon 2 – Tue 3 Nov)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W3-01 | Clips (Media3, ≤10 fps H.264) + ManifestBuilder | P0 | 1 | W1-05 | 5 s ≤10 fps clips; §5.5 manifest | ⬜ |
| W3-02 | Consent sheet, EchoApiClient, Processing screen | P0 | 1.5 | W3-01, W0-05 | consent, upload, polling, staged progress UI | ⬜ |
| W3-03 | Worker jobs API + Workflow skeleton + progress | P0 | 1.5 | W0-05 | jobs API; Workflow runs queued → complete | ⬜ |
| W3-04 | Worker scout: Workers AI (Llama 4 Scout → Gemma 4) via AI Gateway + provider chain | P0 | 1.5 | W3-03 | candidates (JSON Schema) per checkpoint | ⬜ |
| W3-05 | Worker ground step: box-only default + SAM 3.1 when `META_API_KEY` set | P0 | 2 | W3-04 | grounded or box-only candidates + evidence in R2 | ⬜ |
| W3-06 | Worker assemble + vitest fixtures + live latency/cost check | P0 | 1 | W3-05 | `result.json`, vitest green, measured cost/latency | ⬜ |
| W3-07 | App: merge candidates into Review (mask overlay, alternatives) | P0 | 1.5 | W3-02, W3-06, W2-01 | accepted AI landmarks with mask/box in bundle | ⬜ |
| W3-08 | Privacy: export preview, privacyRisk default-off, expiry, delete job | P0 | 0.75 | W3-07 | export toggles, expiry, delete uploaded media | ⬜ |
| W3-09 | Optional Meta Model API scout (Muse Spark 1.3) | P1 | 1 | W3-05 | Spark candidates when the key is set | ⬜ |

### Wave 4 — Hardening, evidence, submission (Tue 3 – Wed 4 Nov)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W4-01 | Trial logger, baseline mode, drift markers, CSV export | P0 | 1.5 | W2-06 | trials JSON + CSV; baseline mode; drift markers | ⬜ |
| W4-02 | Calibration (step K walk) + EchoConfig settings | P0 | 1 | W2-06 | calibrated K; tunable `EchoConfig` | ⬜ |
| W4-03 | Robustness: permissions, keep-screen-on, process death, errors | P0 | 1 | W2-06 | no crash on kill/deny/offline paths | ⬜ |
| W4-04 | UX polish: motion, empty/error states, a11y, icon, light theme | P0 | 1.5 | W3-07 | polished motion, states, a11y, icon | ⬜ |
| W4-05 | Relay fallback via R2 (zero-knowledge) | P1 | 1 | W2-04, W0-05 | ciphertext-only relay, QR `c=` code | ⬜ |
| W4-06 | README, limitations, code-boundary disclosure, demo script, release APK | P0 | 1.5 | all P0 | README, boundary disclosure, measurements, APK | ⬜ |

### Wave 5 — Optional extras (only after a recorded end-to-end run)

| ID | Slice | Pri | Est | Deps | Key output | Status |
|---|---|---|---|---|---|---|
| W5-01 | Optional local model provider (LAN server e.g. Muse Glimmer, or Gemma 4 E2B on phone) — used only if configured | P2 | 2 | W3-07 | candidates without the Worker when configured | ⬜ |
| W5-02 | Iris-lite navigate overlay | P2 | 1.5 | W2-06 | camera-backed navigate view | ⬜ |
| W5-03 | iOS actuals (CoreMotion, AVFoundation, LAN, haptics) | P2 | 6 | all P0 | iOS app running shared code | ⬜ |
| W5-04 | Wear OS haptic bridge via link module | P2 | 2 | W0-01 | watch haptics mirror phone | ⬜ |

## 10. Slice details

### Wave 0

#### W0-01 · Repo recon + adapter decision
**Steps:** 1) `cd ~/code/simt/simt-app && ls; cat settings.gradle* gradle/libs.versions.toml` (use `grep -rn` if `rg` is missing). 2) In `track`:
`rg -n "step|heading|azimuth|yaw|Pdr|pressure|Flow<|StateFlow|interface" track/src --type kotlin | head -200`; find the
public entry point that starts tracking and the type it emits. 3) In `link`: `rg -n "send|receive|socket|nsd|discover|pair|ByteArray" link/src`.
4) Fill every row of §2.1 with class names/APIs; record AGP/Kotlin versions of the root build.
**Decision rules:** use `TrackPlusMotionSource` only if Track+ emits per-step (or ≥5 Hz) distance and a heading usable as
relative yaw, adaptable in ≤ 2 h, and does not need exclusive camera access (ECHO records video; if Track+ camera-motion needs the camera, run Track+
without that input or use the built-in PDR); use `LinkPeerTransport` only if link can send ≥ 1 MB of arbitrary bytes to a peer found
without internet. **Both phones must run the same MotionSource.**
**Done when:** §2.1 has no `_tbd_`; decisions committed in this file. **Fallback:** built-in implementations.

#### W0-02 · KMP scaffold
**Steps:** 1) Generate a project with the official KMP wizard (Android + iOS, *Share UI*), name `echo`, package `app.simt.echo`.
2) Option A (root already AGP 9.x / Kotlin 2.4): copy as `echo/shared` (rename `composeApp`→`shared`) and `echo/androidApp`;
`include(":echo:shared", ":echo:androidApp")` in root settings. Option B (otherwise): keep `echo/` as a standalone build with its own
`settings.gradle.kts`; reuse track/link only via the adapters if `includeBuild("..")` works, else fallbacks.
3) Versions per §4 in `libs.versions.toml`; `shared` applies `kotlinMultiplatform`, `com.android.kotlin.multiplatform.library`,
`composeMultiplatform`, `composeCompiler`, `kotlinxSerialization`; `androidApp` applies only `com.android.application` +
`composeCompiler` (no kotlin-android under AGP 9), `applicationId = "app.simt.echo"`, distinct namespaces.
4) Manifest: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `VIBRATE`; `MainActivity`
`android:screenOrientation="portrait"`; `networkSecurityConfig` with `<base-config cleartextTrafficPermitted="true"/>`
(LAN payloads are AES-GCM encrypted). 5) `local.properties` → `buildConfigField` `ECHO_API_BASE`, `ECHO_APP_TOKEN`.
**Done when:** `./gradlew :echo:androidApp:installDebug` shows the wizard screen; `:echo:shared:allTests` runs.

#### W0-03 · Design system + nav shell
**Files:** `ui/theme/{EchoColors,EchoType,EchoTheme}.kt`, `ui/components/{EchoButton,PillButton,RoundActionButton,StatusChip,
DistanceReadout,DirectionArrow,LandmarkCard,RouteTimeline,Minimap,QrCard}.kt`, `ui/EchoNavHost.kt`, `ui/App.kt`.
**Steps:** tokens from §8.1 as `darkColorScheme/lightColorScheme` + `CompositionLocal` for extra tokens (warn, ok, muted);
`DirectionArrow(angleRad)` = Canvas chevron rotated with `animateFloatAsState(spring)` (unwrap angle deltas to avoid 359°→0° spins);
`Minimap(path, nodes, highlightIndex)` scales path to fit with 16 dp padding. Routes are `@Serializable` objects:
`Home, Record, Review(draftId), Processing(draftId), RouteDetail(routeId), Share(routeId), Receive, Align(routeId), Navigate(routeId, simulate: Boolean), Arrived(trialId), Settings`.
Each screen: `XScreen(vm)` + `XViewModel` exposing `StateFlow<XUi>`; placeholders allowed.
**Done when:** all routes reachable from Home via buttons; dark/light previews render.

#### W0-04 · Domain model + storage
**Files:** `model/*.kt` (§5.2), `data/FileStore.kt` (okio; `readText/writeText/writeBytes/list/deleteRecursively` under `dataDir`),
`data/RouteRepository.kt` (`list(): List<EchoRoute>`, `get(id)`, `save(route, assets: Map<String, Path>)`, `delete(id)`,
`purgeExpired(nowMs)`), `data/DraftRepository.kt`, `commonTest/Fixtures.kt` with `L_ROUTE` (start → 20 m → right → 15 m → left → 10 m → destination).
**Done when:** JSON round-trip test for `EchoRoute` and `CaptureLog`; repository save/list/delete test on `FakeFileSystem` (okio-fakefilesystem).

#### W0-05 · Worker scaffold
**Steps:** `npm create cloudflare@latest worker -- --type=hello-world --ts` inside `echo/`; `npm i hono zod @meta-sam/parser`;
`npm i -D vitest`. `npx wrangler r2 bucket create echo-media`; add an R2 lifecycle rule expiring prefixes `jobs/` and `relay/` after 1 day.
Secrets: `npx wrangler secret put META_API_KEY`, `npx wrangler secret put ECHO_APP_TOKEN`. Create an AI Gateway named `echo` (dashboard → AI → AI Gateway). Auth middleware: constant-time compare of
the bearer token; then `const { success } = await env.RL.limit({ key: token + ip })` → 429 on failure.
```jsonc
// wrangler.jsonc
{ "$schema": "node_modules/wrangler/config-schema.json", "name": "echo-api", "main": "src/index.ts",
  "compatibility_date": "2026-10-01", "compatibility_flags": ["nodejs_compat"],
  "r2_buckets": [{ "binding": "MEDIA", "bucket_name": "echo-media" }],
  "workflows": [{ "name": "echo-pipeline", "binding": "PIPELINE", "class_name": "EchoPipeline" }],
  "ratelimits": [{ "name": "RL", "namespace_id": "1001", "simple": { "limit": 30, "period": 60 } }],
  "ai": { "binding": "AI" },
  "vars": { "SCOUT_ORDER": "workers-ai,meta-spark", "GROUND_ORDER": "sam31,none", "AI_GATEWAY_ID": "echo",
            "WORKERS_AI_MODELS": "@cf/meta/llama-4-scout-17b-16e-instruct,@cf/google/gemma-4-26b-a4b-it" },
  "limits": { "cpu_ms": 300000 }, "observability": { "enabled": true } }
```
Files: `src/index.ts` (Hono routes), `src/pipeline.ts` (stub `EchoPipeline extends WorkflowEntrypoint`), `src/meta.ts`, `src/scout.ts`,
`src/ground.ts`, `src/samParse.ts`, `src/types.ts` (zod `Manifest`, `JobResult`). Run `npx wrangler types` for `Env`.
**Done when:** deployed; `curl https://echo-api.<acct>.workers.dev/v1/health` → `{"ok":true,…,"metaKeyConfigured":true}`; a temporary route calling
`env.AI.run("@cf/meta/llama-4-scout-17b-16e-instruct", { messages: [{ role: "user", content: "ping" }] })` answers (remove after). If a
Meta key is set, also probe `GET https://api.meta.ai/v1/models` for `sam-3.1` / `muse-spark-1.3`.

### Wave 1

#### W1-01 · MotionSource
**Files:** `capture/HeadingMath.kt` (6.1, `wrapPi`, `circularMean`), `capture/PdrEngine.kt` (6.2; input
`ImuSample(tNs, ax, ay, az)`, `RotSample(tNs, r: FloatArray)`, `PressureSample`), `androidMain/AndroidImuSource.kt`
(SensorManager listeners for `TYPE_ACCELEROMETER` + `TYPE_GAME_ROTATION_VECTOR` at `SENSOR_DELAY_GAME`, `TYPE_PRESSURE` at
`SENSOR_DELAY_NORMAL`; `callbackFlow` into a single-thread dispatcher feeding `PdrEngine`), `BuiltInPdrMotionSource`
(`MotionSource`, `source = "echo_builtin_pdr"`); optional `androidApp/TrackPlusMotionSource.kt` mapping Track+ output → `MotionUpdate`.
**Tests:** heading of identity matrix (flat, top→north) = 0; upright portrait facing east (camera axis) = +π/2; PDR on a synthetic
2 Hz sine of amplitude 2 m/s² for 10 s → 20 ± 1 steps, distance 13.6 ± 1.5 m; yaw wraps correctly across ±π.
**Done when:** debug overlay on Record shows steps/distance/yaw live and walking 10 counted steps yields ≈ 10.

#### W1-02 · CameraRecorder (Android)
**Steps:** `ProcessCameraProvider.awaitInstance(context)`; use cases: `Preview`, `ImageCapture`, `VideoCapture(Recorder)` with
`Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
.setTargetVideoEncodingBitRate(3_000_000)`; bind to `DEFAULT_BACK_CAMERA`. If binding 3 use cases throws, bind Preview+Video only and
use the video frame at t = 500 ms (extracted after `stopVideo`) as the start photo (flag `photoFromVideo`). Preview: `preview.setSurfaceProvider { req -> surfaceRequest.value = req }`
and `CameraXViewfinder(surfaceRequest = it)` (camera-compose). Video: `recorder.prepareRecording(ctx, FileOutputOptions.Builder(file).build())`
**without `withAudioEnabled()`** (silent, no mic permission) `.start(mainExecutor) { e -> if (e is VideoRecordEvent.Start) startNs = SystemClock.elapsedRealtimeNanos() }`.
`stopVideo` awaits `VideoRecordEvent.Finalize` (check `hasError()`).
**Done when:** 60 s recording plays back in a gallery app, portrait, no audio track; `startNs` logged.

#### W1-03 · Record screen + CaptureSession
**Steps:** states `Idle → Ready(preview) → Recording → Finishing → Saved(draftId)`. On **Start**: create draft dir, `takePhoto(start.jpg)`,
`motion.start()`, `videoStartNs = camera.startVideo(video.mp4)`. Collect updates: store every step update + one sample per 500 ms as
`CaptureSample`; HUD shows distance, steps, elapsed. **Mark spot** appends `Mark(tNs, d, "Spot n")` + CONFIRM haptic. **Finish**
(long-press 600 ms) → stop motion + video → Finish sheet (route name, destination label) → write `capture.json` (also autosave every 10 s).
**Done when:** a walk produces `capture.json` + `video.mp4` + `start.jpg`; reopening the app lists the draft on Home ("Draft" badge).

#### W1-04 · Segmenter
**Steps:** implement 6.3 as `fun segment(log: CaptureLog, cfg: EchoConfig, routeId: String, nowMs: Long): EchoRoute`
(start anchor: `description = "Start"`, `photoAssetId = "a_start"`, `alignmentHint = "Stand where the photo was taken and face the same way"`).
Save as `route.draft.json`. Show the `Minimap` + node list on Review (read-only here).
**Tests:** synthetic L path (20 m straight, 90° right, 15 m) → nodes START, TURN(RIGHT≈20 m ±1.5), DESTINATION; legs 20/15 m ±1;
U-turn case; 30° wiggle → no turn; pressure drop of 0.4 hPa on one leg (≈ +3.3 m, going up) → `LEVEL_CHANGE` with `levelDelta = +1`.
**Done when:** tests green; a real L-shaped corridor walk shows the correct turn on the minimap.

#### W1-05 · MediaTools (Android)
**Steps:** `extractFrame`: `MediaMetadataRetriever.getScaledFrameAtTime(atMs*1000, OPTION_CLOSEST, maxSide, maxSide)`; read
`METADATA_KEY_VIDEO_ROTATION`; if rotation is 90/270 and the bitmap is landscape, rotate it so output is in **displayed** orientation;
JPEG quality 82. Clamp `atMs` to `[0, duration − 100]`. `cropJpeg`: decode, crop `box` (clamped, padded 8 %), scale to `maxSide`, JPEG 85.
Keyframe times per node: START → `start.jpg`; TURN → `t−2000, t−500, t+1500`; CHECKPOINT/LEVEL/DESTINATION → `t−1500, t`.
Files `kf/<nodeId>_<i>.jpg`; hero = index 1 (TURN) or last (others).
**Done when:** keyframes for every node exist, upright, ≤ 1280 px, ~100–200 KB.

### Wave 2

#### W2-01 · Review + manual landmarks + Save
**Steps:** Review lists nodes in a `RouteTimeline`; each node card shows the hero keyframe, editable `label` and `cue` (default §8.4),
and **Draw my own**: full-screen keyframe, drag a rectangle (`detectDragGestures`), confirm → `cropJpeg` → `Landmark(source = MANUAL,
confirmation = CREATOR_VERIFIED, title = user text, cue = §8.4 default for the node type)`. Start node: shows `start.jpg`, editable description.
**Save ECHO:** copy start photo + crops into `routes/<routeId>/assets/`, set `captureTMs = null` on export copies, write `route.json`,
navigate to RouteDetail. Rule §0.4: node/leg/path lists are copied untouched from the draft.
**Done when:** a route with ≥ 2 manual landmarks is saved and visible on Home with an "Own" badge.

#### W2-02 · BundleCodec
**API:** `encode(route, assets): ByteArray` (container C), `seal(c): Pair<ByteArray /*E*/, ByteArray /*key*/>`,
`open(bytes, key?): Result<BundleBody>` with errors `BadMagic, NeedsKey, DecryptFailed, HashMismatch, UnsupportedVersion, Expired`.
Crypto (cryptography-kotlin; suspend APIs, `…Blocking` variants exist):
`val aes = CryptographyProvider.Default.get(AES.GCM)`; `key = aes.keyGenerator(AES.Key.Size.B256).generateKey()`;
`raw = key.encodeToByteArray(AES.Key.Format.RAW)`; `sealed = key.cipher().encrypt(c)`;
open: `aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, raw).cipher().decrypt(sealed)`;
SHA-256: `CryptographyProvider.Default.get(SHA256).hasher().hash(body)`.
**Tests:** round-trip; flipped byte → `DecryptFailed`/`HashMismatch`; wrong key; expired route; 500 KB bundle < 100 ms.
**Done when:** tests green; typical bundle (6 crops) < 500 KB.

#### W2-03 · PeerTransport + QR payload
**Files:** `transfer/QrPayload.kt` (build/parse §5.4, base64url helpers), `transfer/TransferService.kt` (offer: seal → token →
`transport.offer` → QR string; receive: parse → fetch → open → verify `s` prefix → save), `androidMain/KtorLanTransport.kt`:
```kotlin
override suspend fun offer(payload: ByteArray, token: String): Endpoint {
    val s = embeddedServer(CIO, port = 0, host = "0.0.0.0") { routing { get("/echo/{t}") {
        if (call.parameters["t"] != token) return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(payload, ContentType.Application.OctetStream); served.emit(Unit) } } }.start(wait = false)
    server = s; return Endpoint(localIpv4() ?: error("Connect to Wi-Fi or turn on hotspot"), s.engine.resolvedConnectors().first().port)
}
override suspend fun fetch(e: Endpoint, token: String): ByteArray =
    client.get("http://${e.host}:${e.port}/echo/$token") { timeout { connectTimeoutMillis = 4_000; requestTimeoutMillis = 20_000 } }.body()
```
`localIpv4()`: `NetworkInterface.getNetworkInterfaces()` → up, non-loopback, `Inet4Address.isSiteLocalAddress`; prefer names
`wlan*`, then `ap*`/`swlan*` (hotspot). Server stops 3 s after the first successful download or after 10 min. Install Ktor `HttpTimeout`.
If W0-01 chose `LinkPeerTransport`, implement the same interface in `androidApp` and select it in `AppGraph`.
**Done when:** two phones on the same Wi-Fi **and** on Phone A's hotspot transfer a 500 KB bundle in < 3 s.

#### W2-04 · Share + Receive screens
**Share:** on enter → `TransferService.offer(routeId)`; `QrCard` = `Image(rememberQrCodePainter(qr))` on white, ≥ 260 dp; observe `served`
→ "Sent ✓" + CONFIRM haptic. **Receive:** CameraX `ImageAnalysis` + `MlKitAnalyzer(listOf(scanner), COORDINATE_SYSTEM_ORIGINAL, executor)`
with `BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())`; first value
starting with `simt-echo://` → stop analysis → fetch with spinner → import sheet (name, length, landmarks count, expiry, creator label)
→ **Save**. Error copy: "Can't reach the other phone. Join its hotspot and try again." (venue Wi-Fi often isolates clients).
**Done when:** scan → saved route on B in < 10 s; B shows a "Received" badge.

#### W2-05 · Navigator engine
**Files:** `nav/NavModels.kt`:
`data class NavUi(phase, legIndex, instruction, distanceToNextM, arrowRad, nextTurn, landmark, uncertaintyM, progress01, offline)`,
`sealed interface NavInput { Motion(u), Aligned, SeeLandmark, NotYet, Lost, RestartFromLast, RestartFromStart, ImHere }`,
`sealed interface NavEvent { Haptic(p), Anchor(kind, nodeId, dist), Phase(from, to), Arrived }`;
`nav/TurnDetector.kt` (6.5); `nav/Navigator.kt` (6.4; `val ui: StateFlow<NavUi>`, `val events: SharedFlow<NavEvent>`, `fun onInput(i)`);
`capture/ReplayMotionSource.kt` (replays a `CaptureLog` at 20 Hz with linear interpolation and `speed` factor; `source = "replay"`).
**Tests (table-driven, synthetic updates on `L_ROUTE`):** happy path arrives with 2 anchors; wrong-direction turn → `OFF_ROUTE`; early turn
→ `UNCERTAIN`; overshoot 30 % → `UNCERTAIN`; `SeeLandmark` snaps progress; first leg walked at 70° error → wrong-start `OFF_ROUTE`;
corridor snapping reduces a 15° offset to < 5° within 10 m.
**Done when:** tests green; **Simulate** on RouteDetail replays the creator's own capture through the Navigator to `ARRIVED`.

#### W2-06 · Align, Navigate, Arrived
**Steps:** Align screen (§8.3) → `Aligned`. Navigate collects `MotionSource.updates` → `NavInput.Motion`; renders `NavUi`; maps
`NavEvent.Haptic` to `Haptics`; `Platform.KeepScreenOn()`; Offline chip from `Platform.isOnline()` polled every 5 s (shows **Offline ✓**
in airplane mode). Landmark card loads the crop from `routes/<id>/assets`. Buttons are contextual (**I see it** when a landmark card is
visible or phase is `UNCERTAIN`; **Lost** always in overflow). Arrived shows the summary and writes the trial (W4-01 extends it).
`AndroidHaptics`: `VibratorManager.defaultVibrator.vibrate(VibrationEffect.createWaveform(timings, -1))` with §8.4 timings.
**Done when:** on Phone B in airplane mode, a received route can be followed to ARRIVED on the real course.

#### W2-07 · File fallback
**Steps:** RouteDetail overflow → "Share as file": warning dialog ("Not encrypted. Share only with people you trust.") → write container C
to cache `<name>.echo` → `Platform.shareFile` (Android `FileProvider` + `ACTION_SEND`, mime `application/octet-stream`). Receive → "Import file"
→ `ActivityResultContracts.OpenDocument(arrayOf("*/*"))` → bytes → `BundleCodec.open(bytes, key = null)`.
**Done when:** a route moves A → B via Quick Share / messaging app and navigates identically.

### Wave 3

#### W3-01 · Clips + ManifestBuilder
**Steps:** `AndroidMediaTools.cutClip` with Media3 Transformer (must run on the main looper; wrap in `suspendCancellableCoroutine`):
```kotlin
val item = MediaItem.Builder().setUri(Uri.fromFile(video.toFile()))
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
val edited = EditedMediaItem.Builder(item).setRemoveAudio(true).setFrameRate(maxFps).build()   // setFrameRate = max output fps (Media3 1.10)
Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
    override fun onCompleted(c: Composition, r: ExportResult) = cont.resume(Unit)
    override fun onError(c: Composition, r: ExportResult, e: ExportException) = cont.resumeWithException(e) }).build().start(edited, out.toString())
```
Then read `METADATA_KEY_VIDEO_FRAME_COUNT` and duration → `ClipInfo`. Clip window per node: `[t−4000, t+1000]` (clamped), `maxFps = 10`.
Cut clips only when `/v1/health` reports `providers.metaKey = true` (SAM enabled); otherwise send keyframes only (`clip` omitted).
`ManifestBuilder` builds §5.5 manifest: checkpoints = all non-START nodes; `approach` samples every 500 ms by interpolating `CaptureLog`
distance at `videoStartNs + tMs`; `routeContext` = "Indoor walk, <len> m, <turns> turns, <levels> level changes".
**Fallback:** if Transformer fails on a device, send no clip for that node (worker uses SAM image on the hero keyframe).
**Done when:** clips play, are H.264, ≈ 50 frames, < 2 MB each.

#### W3-02 · Consent, EchoApiClient, Processing
**Consent sheet** (shown before any upload; stored in manifest): "Send <n> photos (and <m> five-second silent clips if SAM is enabled) to cloud AI on Cloudflare (Meta's Llama 4 Scout on Workers AI;
Meta Model API if enabled) via SIMT ECHO's server to suggest landmarks. Files are deleted within 24 h (or now, from this screen). If the Meta Model API is enabled, its
standard tier is used, not the contributor tier whose inputs may be used for training. Your route shape stays on this phone." Buttons: **Send** / **Not now** (manual review stays available).
**EchoApiClient** (commonMain, Ktor, `Authorization: Bearer ECHO_APP_TOKEN`): `submit(manifest, files)` via
`submitFormWithBinaryData(url, formData { append("manifest", json, Headers.build { append(HttpHeaders.ContentType, "application/json") })
files.forEach { append(it.id, it.bytes, Headers.build { append(HttpHeaders.ContentType, it.mime); append(HttpHeaders.ContentDisposition, "filename=\"${it.id}\"") }) } })`;
`get(jobId)`, `delete(jobId)`. Poll every 2 s, max 5 min; persist `job.json` (jobId, status) so a restart resumes polling.
**Processing screen:** the four stages of §8.3 driven by `stage`; errors show **Retry** and **Continue without AI**.
**Done when:** a real draft submits, the job id persists, stage changes are visible.

#### W3-03 · Worker jobs API + Workflow skeleton
```ts
app.post("/v1/jobs", async c => {
  const form = await c.req.formData(); const manifest = Manifest.parse(JSON.parse(String(form.get("manifest"))));
  if (!manifest.consent.sendFramesToCloudAi) return c.json({ error: "consent_required" }, 400);
  const allowed = new Set(manifest.checkpoints.flatMap(k => [...k.keyframes.map(f => f.id), ...(k.clip ? [k.clip.id] : [])]));
  const jobId = crypto.randomUUID();
  for (const [name, v] of form.entries()) if (v instanceof File && allowed.has(name))
    await c.env.MEDIA.put(`jobs/${jobId}/in/${name}`, await v.arrayBuffer(), { httpMetadata: { contentType: v.type } });
  await c.env.MEDIA.put(`jobs/${jobId}/manifest.json`, JSON.stringify(manifest)); await putProgress(c.env, jobId, "queued");
  await c.env.PIPELINE.create({ id: jobId, params: { jobId } }); return c.json({ jobId }, 202);
});
```
Limits: ids match `^[a-z0-9_]{1,40}$`, ≤ 60 files, ≤ 80 MB total (413 otherwise). `GET /v1/jobs/:id`: `(await env.PIPELINE.get(id)).status()`
→ map `complete` (read `result.json`), `errored`/`terminated` → `errored`, others → `running`/`queued`; include `stage` from
`progress.json`. `DELETE`: `MEDIA.list({ prefix })` + `MEDIA.delete(keys)` (also terminate the instance if running).
`EchoPipeline.run(event, step)`: `load` → `scout` → `ground-*` → `assemble`; each `step.do(name, { retries: { limit: 4, delay: "5 seconds",
backoff: "exponential" }, timeout: "4 minutes" }, fn)`; every step writes `progress.json` (`reading`, `scouting`, `grounding`, `complete`).
**Done when:** a dummy pipeline (no AI calls) goes queued → complete and the app shows all stages.

#### W3-04 · Workers AI scout + provider chain
**Steps:** `scout.ts` exports `runScout(env, manifest) → { model, checkpoints }`, walking `SCOUT_ORDER` and skipping providers without
credentials (`meta-spark` needs `META_API_KEY`; added in W3-09). Implement `workersAiScout` per §7.2: per checkpoint, keyframes from R2 as
base64 data URLs, SYSTEM text of §7.1, `CHECKPOINT_SCHEMA`, models from `WORKERS_AI_MODELS`, AI Gateway id `AI_GATEWAY_ID`. Validate with
zod (`ScoutResult`); drop candidates whose `imageId` is not in that checkpoint, clamp boxes to 0–1000, fix `x1<x2, y1<y2`; keep top 2 per
checkpoint by `distinctiveness·permanence`. Save raw outputs + `jobs/<id>/scout.json` (evidence); return the compact list. A provider that
throws `NonRetryableError` or exhausts retries is skipped; record the model that ran. `GET /v1/health` adds `providers: {workersAi, metaKey}`.
**Done when:** for a real walk, ≥ 2 checkpoints get sensible candidates from Llama 4 Scout (spot-check against keyframes) in < 60 s.

#### W3-05 · Ground step (box-only default, SAM 3.1 with key)
**Steps:** without `META_API_KEY` (or `GROUND_ORDER` = `none`): pass scout boxes through as `AI_BOX_ONLY`. With a key: `meta.ts` = §7.3
SSE collector; for each kept candidate (parallel `Promise.all` of `step.do("ground-<nodeId>-<k>")`): clip → data URL → §7.3 request with
`samPhrase` → save raw text to `jobs/<id>/sam/<nodeId>_<k>.txt` → `parseSam` → 6.6 selection with `fps = frameCount·1000/durationMs` →
mask SVG for the hero observation → candidate (§5.5 result shape, `source = "AI_GROUNDED"`, `models = "<scout> + sam-3.1"`). **Fallback a:** zero observations or no clip →
same phrase on the hero keyframe via `input_image` (`heroIdx = 0`, `trackability = null`, `visibleFromM = null`). **Fallback b:** still
nothing, or `GROUND_ORDER` resolves to `none` → keep the scout box, `source = "AI_BOX_ONLY"`, `samConfidence = null`.
**Done when:** box-only path works without a key; with a key, fixture tests pass (W3-06) and a real job yields ≥ 2 `AI_GROUNDED` candidates.

#### W3-06 · Assemble + tests + live check
**Steps:** `assemble` computes `rank` (6.6), sorts candidates per checkpoint, writes `result.json` with `usage` (sum of
`usage.video_frames_processed` if present in `response.completed`, timings). **vitest:** `samParse` (two-object frame, skipped frames,
missing `c`), `selectTrack` (IoU pick, mismatch flag, visibleFrom interpolation), schema validation of a scout sample, provider skipping without credentials. **Live check:** run
one real 60 m job; record latency and cost in `docs/measurements.md`.
**Done when:** `npm test` green; measurements recorded.

#### W3-07 · Merge candidates into Review
**Files:** `ai/CandidateMerger.kt`, Review UI updates. Each node card preselects the top candidate: keyframe image with box (2 dp accent
stroke) and mask (`PathParser().parsePathString(svg).toPath()` → scale by `kfW/maskW`, `kfH/maskH`; 35 % accent fill). Convert boxes with
`kfW/frameW`, `kfH/frameH` (never assume equal sizes). Actions: **Use** (→ `cropJpeg` → `Landmark(source, confirmation = CREATOR_VERIFIED)`),
**Next option**, **Draw my own** (W2-01), **Skip**. Unreviewed AI landmarks are never exported. Set `Landmark.models` from the candidate and fill `EchoRoute.ai` provenance.
Stat chips (only when SAM ran): `SAM 0.86 · tracked 78% · visible ~9 m`; footnote with model names. Merging must not touch nodes/legs/path (assert in a test).
**Done when:** accepted AI landmarks appear on Phone B's Navigate cards with correct crops.

#### W3-08 · Privacy controls
**Steps:** before Share: export preview sheet listing start photo + each landmark crop with toggles (crops with `privacyRisk = true` default
OFF), expiry selector (none / 24 h / 7 days → `PrivacyInfo.expiresAtEpochMs`), "Raw video is never shared" note. After Save ECHO: offer
"Delete uploaded media now" → `DELETE /v1/jobs/:id`. Receiver runs `purgeExpired` on app start. RouteDetail: **Delete** route.
**Done when:** toggled-off crops are absent from the received bundle; expired route disappears on B after restart.

#### W3-09 · Optional Muse Spark scout (P1)
**Steps:** `metaSparkScout(env, manifest)` = §7.1 request (one call, all keyframes, SYSTEM/SCHEMA verbatim, `reasoning.effort = "low"`,
standard model id) through `metaResponses`; same validation as W3-04; on JSON parse failure retry once with `"medium"`. Registered as
`meta-spark`; runs only when `META_API_KEY` is set and it comes first in `SCOUT_ORDER` or Workers AI failed.
**Done when:** with a key and `SCOUT_ORDER=meta-spark,workers-ai`, a job returns Spark candidates; without a key nothing changes.

### Wave 4

#### W4-01 · Trials + baseline + drift markers
`TrialRepository` writes `trials/<id>.json`: routeId, mode (`ECHO` or `SENSOR_ONLY`), device, startedAt, durationS, distanceM, arrived,
anchors (turn/landmark), confirmations, uncertainEpisodes, offRouteEpisodes, markers. **Baseline mode** (Settings) hides landmark cards
and disables `SeeLandmark` (turns and distance only). Debug button **At marker** logs `(expectedNodeDistance, estimatedDistance)` for drift
at taped markers. Settings → **Export CSV** via `shareFile`. **Done when:** 2 trials of each mode export to one CSV.

#### W4-02 · Calibration + config
Settings → **Calibrate step**: walk exactly 10 m (tape) → `K_new = K · 10 / measuredDistance`, stored in `EchoConfig` (persisted JSON).
Expose turn thresholds (35° open / 45° keep+fire), approach distance, uncertainty limit for field tuning. **Done when:** after calibration, a 20 m walk
measures 20 ± 1.5 m on both demo phones.

#### W4-03 · Robustness
Runtime permission flows (camera) with rationale; `KeepScreenOn` on Record/Navigate; ViewModels survive rotation (portrait lock) and
process death (draft autosave, job resume); sensor unregistration on stop; graceful errors: no barometer, no Wi-Fi IP, camera bind
failure, Meta 4xx (→ manual path). **Done when:** a scripted "abuse" pass (kill app mid-record, deny camera, airplane during upload) never crashes.

#### W4-04 · UX polish
Motion (arrow spring, `AnimatedContent` instruction swaps, card enter/exit), empty and error states with one clear action, TalkBack labels
on all controls, light theme check, adaptive app icon (lime echo rings on near-black), splash. **Done when:** a non-developer completes
record → share → navigate without help.

#### W4-05 · Relay fallback (P1)
Worker: `POST /v1/relay` stores `E` (≤ 5 MB) at `relay/<code>` (6 chars, base32 without ambiguous letters, retry on collision) with
`customMetadata.expiresAt` (+30 min); `GET` checks expiry. App: Share offers **Use internet relay** → adds `c=<code>` to the QR; Receive tries
LAN for 4 s, then relay. The server only ever sees ciphertext (key stays in the QR). **Done when:** transfer works with LAN blocked.

#### W4-06 · Submission kit
`echo/README.md`: what it is, setup (keys, deploy, build), permissions, connectivity table, **limitations**, and an explicit
**pre-existing SIMT vs hackathon code** boundary (list modules/files reused via adapters vs created in `echo/`). Commit a sanitized
sample bundle, `docs/measurements.md` (trials, latency, cost, bundle size, devices, lighting). Demo script = proposal §9.1 timings.
Signed release APK installed on both devices. **Done when:** a fresh clone builds following the README only.

### Wave 5 (optional extras)

- **W5-01** Optional local model (§7.5), *only if configured*: Settings → Advanced → "Local model" (LAN URL + model name, or a `.litertlm`
  file found in `getExternalFilesDir("models")`). `AiRouter` uses it when the Worker is unreachable or "Prefer local model" is on;
  candidates get `source = AI_BOX_ONLY`, `models = "<model id> (local)"`. No UI elsewhere changes. **Done when:** with a configured model and
  no internet, Review shows candidates; with nothing configured, the app behaves exactly as before.
- **W5-02** Navigate overlay: live preview background, translucent arrow and landmark crop; Iris integration only if W0-01 found an API.
- **W5-03** iOS: see §13. **W5-04** forward `HapticPattern` to a paired Wear OS watch through the link module (if supported).

## 11. Testing and evidence

**Unit (commonTest):** HeadingMath, PdrEngine, Segmenter, TurnDetector, Navigator (table-driven), BundleCodec, QrPayload, CandidateMerger
(geometry immutability), JSON round-trips. **Worker (vitest):** samParse, selectTrack, schema validation, provider fallback. **Device matrix:** the two
pinned demo phones (record + navigate on each), one extra Android if available.

**Field protocol (Tue/Wed):** one 40–80 m course, 2–3 turns, tape markers at 3 nodes. Per phone pair: 5 runs `SENSOR_ONLY`, 5 runs `ECHO`,
alternating; B always in airplane mode; note lighting and holding style. Report arrivals/attempts, time, confirmations, drift at markers,
mistaken landmark confirmations, AI processing time, transfer time, bundle size — exactly as measured, including failures.

| Acceptance (proposal §7.2) | Proven by |
|---|---|
| A1 Meta perception, traceable frames, editable text | W3-05/W3-07 (keyframe + box/mask + raw model output in R2, editable title/cue, models named) |
| A2 Path follows recorded movement | W1-04 + merger immutability test (W3-07) |
| A3 Import/validate without internet | W2-02/W2-03/W2-07 in airplane mode (after LAN connect) |
| A4 Offline replay reaches destination | W2-06 + field protocol |
| A5 Wrong start detected / needs confirmation | W2-05 wrong-start rule + Align step |
| A6 Uncertainty → confirm/restart | W2-05 `UNCERTAIN`/`OFF_ROUTE` + Lost sheet |
| A7 Creator chooses what to export | W3-08 |
| A8 Setup guide + recorded demo | W4-06 |

## 12. Risks and cut line

| Risk | Mitigation / fallback |
|---|---|
| Meta latency, 429 or outage | Workflow retries; **Continue without AI** (manual landmarks); keep one cached real job result for the demo, disclosed as such |
| SAM finds nothing for a phrase | image fallback → box-only candidate → Draw my own |
| No Meta Model API key | default path needs none (Workers AI, Llama 4 Scout — a Meta model); SAM masks/tracking only with a key |
| Workers AI model rejects images or schema | next model in `WORKERS_AI_MODELS`; retry without `response_format` (§7.2); manual landmarks |
| PDR drift / step-length mismatch A vs B | calibration (W4-02), turn anchors, corridor snapping, landmark snaps, short route |
| Venue Wi-Fi blocks peers | Phone A hotspot; file fallback (W2-07); relay (W4-05) |
| Camera use-case combo unsupported | Preview+Video only, start photo from video (W1-02) |
| Time squeeze | Cut in this order: Wave 5 → W3-09 → W4-05 → masks (box only) → SAM video (image only) → AI entirely. **Never cut:** capture, segmenter, bundle, transfer, navigator, alignment, uncertainty, trials. |

## 13. iOS port checklist (W5-03, after the hackathon)

CoreMotion `CMDeviceMotion` (`xArbitraryZVertical` attitude + `userAcceleration`) into the same `PdrEngine`, `CMAltimeter` for pressure;
AVFoundation capture (`AVCaptureMovieFileOutput`, no audio) in `UIKitView`; `AVAssetImageGenerator` frames, `AVAssetExportSession` clips;
LAN server via Ktor CIO (if Kotlin/Native-supported in the pinned Ktor) else `NWListener` + `NSLocalNetworkUsageDescription`; QR via
`AVCaptureMetadataOutput`; Core Haptics mirroring §8.4; `NSDocumentDirectory` as `dataDir`. All of `commonMain` is reused as is.

## Appendix — sources checked (1 Nov 2026)

- Meta Model API docs (dev.meta.ai) as summarised in github.com/kleongf/winnow research/meta-api.md (27 Oct 2026); SAM 3.1 on Meta Model API.
- Kotlin 2.4.20; Compose Multiplatform 1.12.0; Kotlin docs on AGP 9 for KMP; CameraX / viewfinder 1.6.2; Media3 1.10 (Transformer `setFrameRate`).
- Cloudflare Workflows (step API, retries, 1 MiB step result); Workers AI model pages (Llama 4 Scout vision + `response_format`; Gemma 4 26B A4B).
- Muse Glimmer coverage (InfoQ, PyTorch blog, Aug 2026: 30B, 24–32 GB, not phone-sized); Gemma 4 + LiteRT-LM docs (optional local model).
- SIMT public listing (Google Play): Track+ components; offline-first positioning.
