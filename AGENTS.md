# Fluence Android — Agent Notes

Single-module Gradle project (`:app`; wrapper 8.4, AGP 8.3.2, Kotlin 1.9.23, Compose, JDK 17). Namespace/applicationId `com.groq.voicetyper`. Keep this file to non-obvious rules only — canonical details stay in the linked docs.

## Build & verify

- CI gate: `./gradlew testDebugUnitTest lintDebug`; debug APK: `./gradlew assembleDebug` (id `com.groq.voicetyper.debug.ui`).
- One test class: `./gradlew testDebugUnitTest --tests "com.groq.voicetyper.autolearn.DecisionCoreTest"`.
- Local `assembleRelease` intentionally FAILS unless `release.keystore.path`, `release.keystore.password`, `release.key.alias`, `release.key.password` are in `local.properties` (CI uses `CI=true` + secrets). Do not remove this guard in `app/build.gradle.kts`.
- Environment is preconfigured: `GRADLE_USER_HOME=E:\App dev\gradle`, `JAVA_HOME=E:\App dev\Android kit\java\jdk-17.0.12+7`, SDK path in `local.properties`. Keep Gradle caches under `E:\App dev`; don't redirect to system dirs.

## Architecture gotchas

- Runtime entry points (see manifest): `MainActivity`, `VoiceInputIME`, `FluenceAccessibilityService`, `FloatingBubbleService`, `FluenceApplication`.
- Offline STT uses local file AARs in `app/libs/` (`sherpa-onnx-1.13.2.aar`, `moonshine-voice-0.1.5-isolated.aar`), not Maven — a missing AAR breaks the build.
- Room schema output via KAPT → `app/schemas` (gitignored). No ktlint/detekt task; CI runs Android lint + JUnit only. `app/src/androidTest` (Room migration tests) is device-only and never run in CI.
- Unit tests run with `isReturnDefaultValues = true`; `org.json:json:20231013` is a test dep because android.jar's org.json stubs throw "Stub!".

## Frozen fixtures

- `examples/sync/v1/` is the canonical, frozen v1.2.1 wire contract, shared byte-for-byte with the Windows client. `app/src/test/resources/sync/v1/*.json` and `app/src/test/resources/convergence/scenario-01..31.json` are test copies; scenario-31 is the full-tie case. If production merge disagrees with a reference scenario, that is a finding — leave the test failing, don't edit the fixture. See `examples/sync/v1/README.md`.

## Do not change casually

- Release/signing pipeline is FROZEN — never touch `.github/workflows/release.yml`, signing config, or secrets. Release = bump versionCode (must exceed the latest published code) + versionName, push `main`, push an annotated `v*` tag; CI signs and verifies the production cert. See `docs/RELEASE_PIPELINE.md`.
- Visual language is FROZEN — read `DESIGN_SYSTEM.md` before any UI change; semantic tokens only, no raw values, closed accent list; floating bubble and IME are frozen. If a design decision isn't covered, stop and ask.
- Follow `CONTRIBUTING.md`: no wildcard imports, `rememberUpdatedState` in gesture coroutines, delete temp audio in `finally`, Conventional Commits.
- Consult `docs/AUTOLEARN.md` for the auto-learn/suggestion pipeline and `docs/decisions/` for ADRs. `docs/ARCHITECTURE.md`, `docs/SYNC.md`, and `docs/DESIGN_SYSTEM.md` do not exist — don't link them.
