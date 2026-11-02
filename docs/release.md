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
