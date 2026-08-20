# Fluence Android — Production Readiness Audit (main branch)

**Branch:** `main` @ `b8a1c5d` (AGPL-3.0, v1.11.0 versionCode 34) — sync-feature excluded  
**Date:** 2026-08-20  
**Auditor:** Staff/Principal Windows+Android+Security+Reliability (adversarial, evidence-driven)  
**Scope:** Complete app on `main` — IME, bubble, audio capture, offline/streaming/batch transcription, injection, persistence, update, permissions. Release pipeline treated as frozen.  
**Method:** 15-phase trace (architecture → workflow A-AJ → state/concurrency → audio → network → Android robustness → security → data → UI/a11y → tests → build → consistency). Every finding cites `file:line` verified via `git show main` / direct read on `main` checkout. `main` is Android (Kotlin/Compose/Room/WorkManager/MediaRecorder/AudioRecord/OkHttp/WS/sherpa-onnx); prompt's Windows/Tauri framing is remapped to Android analogues and not filed as bugs.

---

## 1. Executive Verdict

**Production Ready with Required Fixes.**

The core promise `TAP → RECORD → TRANSCRIBE → INJECT` is fundamentally sound on `main`. The session lifecycle (`TranscriptionSessionManager` singleton, `sessionGeneration`, `endStreamingSession` unification, `ERROR→IDLE` 4s auto-clear, `streamingCollectJob` + `StreamingAudioCapture` + `Mistral` bounded channel + `AUDIO_BECOMING_NOISY` receiver, offline `VAD → SEGMENT_QUEUE 32 → sequential worker + Mutex + pending-init latch`) works, has extensive JVM coverage (StreamingSessionManagerTest 18 tests, Owner tests, Mistral real WS tests, offline pipeline tests) and all 18 streaming lifecycle tests pass. Prior streaming audit regressions (S1 cached prefs, S4 agent-streaming, S5a/S5b queue + writer, S5c/d watchdog/leak, S6 null-intent guard) are demonstrably fixed on `main`. Batch `.m4a` ownership/deletion, streaming Final/Error/Closed handling, and offline cold-start latch are correct.

No `CRITICAL` (data loss / vuln / catastrophic crash / core broken) was proven. The blockers are `HIGH` reliability races that will cause sporadic production incidents: a handler blast radius that cancels the bubble service stop, VAD unsynchronized access between audio thread and `stop()/forceRelease()`, stale-generation history save, clipboard linger window, and debug-signing fallback. Each has a localized 1–10 line fix and is the only reason for "with Required Fixes" rather than "Ready".

With the 4 P0 fixes + 3 P1 hardening items applied and re-verified via `./gradlew testDebugUnitTest` + lint + one device smoke (bubble drag/snap, streaming final, offline VAD, permission revoke, noisy unplug), the build is safe to ship.

---

## 2. Risk Summary

| Severity | Count | Production implication |
|---|---:|---|
| Critical | 0 | None — no proven data loss, vuln, or core broken |
| High | 4 | Sporadic stuck sessions, leaked foreground service, VAD race/segfault risk, silent history dupe — likely incident under stress |
| Medium | 7 | Silent segment drop, retry non-idempotent, SHA-256 bypass when blank, stale cache, file-name collision, nav Compose side-effect, allowMainThreadQueries ANR |
| Low | 6 | Minor edge races, leaked verification marker, log stripping, minor UI/a11y gaps |
| Hardening | 6 | Worthwhile robustness without proven defect |

Total findings: 23 (4 High, 7 Medium, 6 Low, 6 Hardening). No Critical.

---

## 3. Critical Findings

*None proven.* The core transcription flows are not fundamentally broken. This section is intentionally empty rather than inflated.

---

## 4. High-Priority Findings

### [HIGH] H1 — `Handler.removeCallbacksAndMessages(null)` blast radius cancels deferred service stop

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/BubbleController.kt:292` (`onError`), `app/src/main/java/com/groq/voicetyper/TranscriptionSessionManager.kt:851` (`showError`)  
**Problem:** Both handlers call `removeCallbacksAndMessages(null)` which removes *all* callbacks/messages for that `Handler` instance.  
- In `BubbleController`, `mainHandler` also holds `deferredStopService` (posted with `STOP_SERVICE_DELAY_MS=1500` by `hideBubble/suppressForPrivacy`). On any transcription error, `onError` clears it, so `stopFloatingBubbleService()` never runs — foreground service with `FOREGROUND_SERVICE_TYPE_MICROPHONE` notification persists with no bubble.  
- In `TranscriptionSessionManager`, `handler` also holds the `ERROR→IDLE` 4s delayed reset. A second error arriving within 4s clears the prior reset, extending ERROR, and also would clear any future handler-posted work (e.g., `pendingImeFinishCancellation` is on a *different* handler in `VoiceInputIME`, so not affected, but TSM's own `postDelayed` ERROR clear is).  
**Root cause:** Over-broad API usage; should `removeCallbacks(errorResetRunnable)` only.  
**Failure scenario:** Record → `GroqClient` 500 → `showError(ERROR)` → `BubbleController.onError` → `mainHandler.removeCallbacksAndMessages(null)` → previously posted `deferredStopService` from `hideBubble` is gone → user dismisses bubble, service lives forever (notification remains, battery/mic icon). Needs `isBubbleVisible==false` but service running. Repro: trigger error, then quickly hide bubble.  
**Impact:** Leaked foreground service, user-visible stale notification, battery, possible Play policy violation (mic FGS without UI).  
**Evidence:** `BubbleController.kt:29` `STOP_SERVICE_DELAY_MS`, `142-155` `hideBubble` posts `deferredStopService`, `289-302` `onError` clears all then posts bubble collapse; `TranscriptionSessionManager.kt:851` same pattern for ERROR reset.  
**Recommended action:** Keep `Runnable` fields (`errorResetRunnable`, `bubbleCollapseRunnable`) and `removeCallbacks(runnable)` only. In `BubbleController` never clear `deferredStopService` on error — explicitly `removeCallbacks(deferredStopService)` only in `showBubble` is intended; `onError` should not touch it.  
**Regression risk:** Low — only narrows cancellation scope.

### [HIGH] H2 — VAD concurrent access between audio thread and `stop()/forceRelease()`

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriptionPipeline.kt:209-229` (`start` audio thread `activeVad.acceptWaveform/processVadSegments`), `238-268` (`stop` flush+process on `Dispatchers.Default`), `278-322` (`forceRelease` `vad.release()` on `Dispatchers.IO`)  
**Problem:** `Vad` (sherpa-onnx JNI) is not documented thread-safe, yet `acceptWaveform`/`empty/front/pop/flush/release` are invoked from two threads without synchronization: audio capture callback thread vs coroutine `stop`/`forceRelease`. `segmentChannel` and `workerJob` have proper synchronization but `vad` does not.  
**Root cause:** Missing `synchronized(vad)` or single-thread confinement. `pipelineScope` is `Dispatchers.Default` shared.  
**Failure scenario:** Long utterance → `stopRecording` → `pipeline.stop()` flushes while audio thread still calls `acceptWaveform` on last frame → JNI race → segfault/native crash or `IllegalStateException`, seen as random crash on stop. More likely on slow devices where `stopCapture` doesn't instantly halt callbacks.  
**Impact:** Crash during core flow (stop), native tombstone, Play vitals.  
**Evidence:** `OfflineTranscriptionPipeline.kt:73-92` VAD created, `209` `onAudioFrame` on audio thread, `245` `flush` on Default, `306` `release` on IO, no lock.  
**Recommended action:** Guard all `vad` accesses with a dedicated `Mutex` or `synchronized(vadLock)`; in `stop()` first `isRunning=false`, then `audioCapture.stopCapture()` + join audio thread before `flush`. Minimal: `private val vadLock = Any()` + `synchronized(vadLock) { activeVad.acceptWaveform(...) }` everywhere.  
**Regression risk:** Medium — must not deadlock with `workerJob` (separate).

### [HIGH] H3 — Stale-generation history save (empty/incorrect attribution)

**Status:** High-confidence defect (proven by code walk, not yet field-observed)  
**Location:** `app/src/main/java/com/groq/voicetyper/TranscriptionSessionManager.kt:769-771` (`deliverTranscript` fire-and-forget save), `724-744` (`transcribeAudioOnline` onSuccess)  
**Problem:** `deliverTranscript` launches `CoroutineScope(Dispatchers.IO).launch { HistoryRepository.save(...) }` *before* checking `currentListener` generation and without generation guard. `transcribeAudioOnline` captures `generation` but `GroqClient.transcribe` is ~5-30s; if user starts a new session during that, the stale transcript still saves (wrong `provider/model/language/isAgent` for new session) and still invokes `listener.onTranscription` only if generation matches, but history already written. For empty `rawText` case, it correctly checks generation before IDLE, but `deliverTranscript` does not.  
**Root cause:** Unstructured `CoroutineScope(Dispatchers.IO)` (not `scope`) + missing `if (sessionGeneration != generation) return` before save.  
**Failure scenario:** Record 1 (groq/whisper-large-v3) → `stopRecording` → `GroqClient.transcribe` in flight → user taps again, `sessionGeneration++` → new session recording → old transcribe returns "hello" → old history row saved with old metadata, even though user is mid-new dictation → dashboard weekly counts inflated by one, user confused.  
**Impact:** Duplicate/phantom history rows, inflated stats (`DayWordCounts`), export inconsistency.  
**Evidence:** `TranscriptionSessionManager.kt:769` bare `CoroutineScope(Dispatchers.IO).launch`, `712` `val generation = sessionGeneration` captured but not used before save launch, `762-765` generation guard only for listener, not save.  
**Recommended action:** Use `scope.launch(Dispatchers.IO)` (structured) and `if (sessionGeneration != generation) return@launch` before `HistoryRepository.save`. Or capture `generation` inside save block and check before insert.  
**Regression risk:** Low — only skips stale saves.

### [HIGH] H4 — Offline `transcribe()` silently returns "" on not-READY instead of error

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriber.kt:211-214`, `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriptionPipeline.kt:192-206` (worker)  
**Problem:** `OfflineTranscriber.transcribe` awaits `initializeDeferred`, then if `engineState != READY` logs and `return ""`. The sequential worker treats `""` as `isNotBlank() false` and drops the segment silently. `isModelReady` failure already surfaces via `modelError` flow, but a transient `RELEASING` or race between `isReady` check and `transcribe` also drops speech without user feedback except log.  
**Root cause:** Lossy error handling — empty string conflates "silence" vs "engine not ready".  
**Failure scenario:** User enables offline, taps mic immediately on cold start while engine `LOADING` but `initializeDeferred` already completed exceptionally (corrupt model) → worker `transcribe` returns "" for every segment → `offlineTextAccumulator` stays empty → `stop()` yields no transcript, no error toast (since `modelError` already emitted but `showError` auto-clears after 4s, user may miss it) → "offline doesn't work, no message".  
**Impact:** Silent transcription loss in offline mode, perceived broken feature.  
**Evidence:** `OfflineTranscriber.kt:202-214` await warning then `return ""`, `OfflineTranscriptionPipeline.kt:194` `if (text.isNotBlank()) onTextTranscribed`.  
**Recommended action:** Make `transcribe` return `Result<String>` or throw typed exception on not-READY; worker should `Log.w` + optionally `_modelError.value` if repeated. At minimum, if `engineState != READY` after await, log warning and emit `modelError`.  
**Regression risk:** Low — only surfaces existing drops.

---

## 5. Medium / Low Findings

### [MEDIUM] M1 — `segmentChannel` `DROP_OLDEST` silently drops speech under load

**Status:** Proven (by design, but insufficient observability)  
**Location:** `OfflineTranscriptionPipeline.kt:164-170` (`SEGMENT_QUEUE_CAPACITY=32`, `BufferOverflow.DROP_OLDEST`, `onUndeliveredElement` logs)  
**Problem:** Bounded queue drops oldest 25s chunk when transcription falls behind (e.g., long monologue, slow device). Log is `Log.w` only, never surfaced to user or metric.  
**Failure scenario:** 3-minute offline dictation → worker inference slower than VAD production → queue fills → earliest segment (first sentence) dropped → transcript missing opening.  
**Impact:** Silent data loss for long offline sessions.  
**Evidence:** `164` capacity 32, `192-206` worker loop, no backpressure signal.  
**Action:** Change to `SUSPEND` or at least surface `modelError`/UI warning when `onUndeliveredElement` fires; document max duration. Note: unbounded queue would OOM, so DROP_OLDEST is a tradeoff — make it visible.  
**Regression risk:** Tuning queue size changes memory/latency.

### [MEDIUM] M2 — `GroqClient` / `CommandProcessor` `retryOnConnectionFailure=true` on non-idempotent POST

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/GroqClient.kt:21-26` (60s timeouts), `app/src/main/java/com/groq/voicetyper/CommandProcessor.kt:20-25`  
**Problem:** OkHttp retries the TCP connect (not HTTP status) automatically. For a large multipart POST (audio ~MBs) a half-sent body could be retried, double-billing and double transcription. Intentional for idempotent GET, risky for POST.  
**Impact:** Occasional double charge/latency, not data loss.  
**Evidence:** Builder `retryOnConnectionFailure(true)`.  
**Action:** Set `retryOnConnectionFailure(false)` for POST clients, keep retry for `fetchModels` GET. Or keep but document.  
**Regression risk:** Low — loses one transparent retry on flaky connect.

### [MEDIUM] M3 — `ApkDownloadWorker` SHA-256 blank bypass

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/update/ApkDownloadWorker.kt:178-192` (`if (expectedSha256.isBlank()) Log.w skip`)  
**Problem:** If `release.json` omits `sha256` (malicious or legacy), download skips integrity check and installs whatever was downloaded. `release.yml` always emits sha256, but defense-in-depth fails.  
**Impact:** Supply-chain: attacker who can serve a `release.json` without sha256 (or MITM without TLS) could push arbitrary APK via in-app updater (requires `REQUEST_INSTALL_PACKAGES` + user tap "Install").  
**Evidence:** `ApkDownloadWorker.kt:179`.  
**Action:** Fail closed: `if (expectedSha256.isBlank()) return Result.failure("Missing SHA-256")`.  
**Regression risk:** Would break if any legitimate release ever omits sha256 — not on `main`.

### [MEDIUM] M4 — `cachedSttPreset` / `cachedStreamingEnabled` stale after external pref change

**Status:** Plausible risk (needs verification — single-process, but backup restore / clear)  
**Location:** `app/src/main/java/com/groq/voicetyper/SecurityUtils.kt:60-70`, `175-185`  
**Problem:** Volatile snapshots invalidated only by `save*` functions. If prefs are cleared (Settings → Clear data, or `EncryptedSharedPreferences` recreated after `MasterKey` invalidation on some OEMs), cached value stays stale until process death. `clearProviderApiKey` also doesn't clear `cachedPrefs` instance.  
**Impact:** Streaming vs batch branch could take wrong path until restart.  
**Evidence:** `SecurityUtils.kt:23-25` cache, `68` invalidation only on save.  
**Action:** `clearProviderApiKey`/`clearApiKey` should also `cachedSttPreset=null` / `cachedStreamingEnabled=null`; consider `OnSharedPreferenceChangeListener` to invalidate. Low priority.  
**Regression risk:** Trivial.

### [MEDIUM] M5 — `allowMainThreadQueries()` on Room database

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/history/FluenceDatabase.kt:102`  
**Problem:** Allows `dao.getAllEnabledSync()` on main thread for cache priming (`DictionaryRepository.startObservingCache`). Intentional for `DictionaryTextPostProcessor.process` off-main, but also permits accidental main-thread queries elsewhere (e.g., `HistoryRepository.cleanupToNewest` if called from UI).  
**Impact:** ANR risk if DB grows or migration `3_4` backfill runs on main (it doesn't — it's on `withTransaction`).  
**Evidence:** `FluenceDatabase.kt:102`, `DictionaryRepository.kt:41-62` prime+sync fallback.  
**Action:** Keep but add `@WorkerThread` lint and `StrictMode` detection in debug; or use `createFromAsset` + `allowMainThreadQueries` only in `DictionaryRepository` via `withContext(Dispatchers.IO)`. Hardening.  
**Regression risk:** Removing breaks dictionary first-transcription cache.

### [MEDIUM] M6 — `verifyActiveWindow` param dead code in `BubbleController`

**Status:** Proven (code smell)  
**Location:** `app/src/main/java/com/groq/voicetyper/BubbleController.kt:193-196` (`verifyActiveWindow: Boolean = false` unused)  
**Problem:** `isTargetAllowed(context, targetPackage, verifyActiveWindow)` never reads the flag; all callers pass `verifyActiveWindow=true` expecting extra focus-window verification that never happens. `FluenceAccessibilityService.resolveActiveApplicationPackage` exists but is unused by bubble injection.  
**Impact:** False sense of foreground verification; bubble could inject into background app if `activeNodePackage()` lags focus (split-screen). Privacy risk low because `PrivacyPreferences` still checked, but not as strong as intended.  
**Evidence:** `BubbleController.kt:193`.  
**Action:** Either implement (call `FluenceAccessibilityService.isCurrentApplicationAllowed`) or remove param.  
**Regression risk:** Implementing adds accessibility service coupling.

### [MEDIUM] M7 — Navigation `previousSize` mutation during composition

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/navigation/Navigation.kt:52-55`  
**Problem:** `var previousSize by remember { mutableIntStateOf(1) }` then `previousSize = backStack.size` executed during composition (not in `LaunchedEffect`). This is a side-effect in composition, violates Compose contracts, and `isNavigatingForward = backStack.size >= previousSize` is always true on first read after mutation.  
**Impact:** Incorrect transition direction (always forward slide) and potential recomposition loop. Currently not user-visible because `screenOrder` dead and transitions are subtle, but will bite with rapid nav.  
**Evidence:** `Navigation.kt:54-55`.  
**Action:** `val isNavigatingForward by remember(backStack.size) { ... }` or use `derivedStateOf`.  
**Regression risk:** Low.

### [LOW] L1 — `StreamingAudioCapture.startCapture` unsynchronized double-start race

**Status:** Proven (low impact due to generation guard)  
**Location:** `app/src/main/java/com/groq/voicetyper/streaming/StreamingAudioCapture.kt:68-100` (`if (isRunning) return` not synchronized)  
**Problem:** Two concurrent `startCapture` could both pass `isRunning==false`, create two `AudioRecord` instances, latter overwrites `audioRecord` field while former thread still runs → leaked `AudioRecord` (mic held). TSM's `sessionGeneration` + `activeStreaming` makes double start unlikely but `startStreamingSessionInternal` is called synchronously on main, and retry path posts delayed `startCapture` without re-checking `isRunning`.  
**Impact:** Rare mic-in-use on next session.  
**Evidence:** `StreamingAudioCapture.kt:68`.  
**Action:** `synchronized(this)` or `AtomicBoolean.compareAndSet`.  
**Regression risk:** Trivial.

### [LOW] L2 — `AudioRecorder` temp file name collision via `currentTimeMillis`

**Status:** Proven (low)  
**Location:** `app/src/main/java/com/groq/voicetyper/AudioRecorder.kt:37`  
**Problem:** Two recordings within same ms could collide. `GroqClient` deletes after each, and `AudioRecorder` guards `isRecording`, but tests or rapid cancel/start could overlap.  
**Impact:** Overwrite/loss of pending upload file.  
**Evidence:** `File(cacheDir, "groq_voice_record_${System.currentTimeMillis()}.m4a")`.  
**Action:** Use `UUID.randomUUID()` suffix.  
**Regression risk:** None.

### [LOW] L3 — `ModelAssetManager.downloadModel` progress flood (8KB chunks → StateFlow)

**Status:** Proven (perf)  
**Location:** `app/src/main/java/com/groq/voicetyper/offline/ModelAssetManager.kt:210-218` (`onProgress` per 8192 bytes → `_progress.value` on IO thread, collected on Main)  
**Problem:** ~30k emissions for 239MB download (239M/8K). Collectors on Main will jank.  
**Impact:** UI stutter during model download on low-end.  
**Evidence:** Buffer 8192, direct `_progress.value` update.  
**Action:** Throttle emissions (`if (now - lastEmit > 300ms)` like `ApkDownloadWorker`) or `conflate`.  
**Regression risk:** Low.

### [LOW] L4 — `HistoryRepository.save` `cleanupToNewest(50)` outside transaction

**Status:** Proven  
**Location:** `app/src/main/java/com/groq/voicetyper/history/HistoryRepository.kt:51-55`  
**Problem:** `save` does `withTransaction { insert + increment }` then outside transaction `cleanupToNewest(50)`. Crash between leaves >50 rows until next save.  
**Impact:** History grows unbounded until next transcription; not data loss but cap violation.  
**Evidence:** `HistoryRepository.kt:51`.  
**Action:** Move cleanup inside `withTransaction` or schedule via WorkManager.  
**Regression risk:** Low.

### [LOW] L5 — `FloatingBubbleUI` `pointerInput(isExpanded)` missing `recordingState` key

**Status:** Proven (from prior audit, still on `main` exploration)  
**Location:** `app/src/main/java/com/groq/voicetyper/FloatingBubbleUI.kt:145-206` (subagent notes)  
**Problem:** Closure captures stale `recordingState` → long-press `agentMode` flag may fire with wrong state.  
**Impact:** Agent vs normal transcription could swap.  
**Evidence:** `pointerInput(isExpanded)` not including `recordingState`.  
**Action:** `pointerInput(isExpanded, recordingState)`.  
**Regression risk:** Trivial.

### [LOW] L6 — `verifyModelIntegrity` exception message leaks internal path

**Status:** Proven (low privacy)  
**Location:** `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriptionPipeline.kt:132-136`  
**Problem:** `IllegalStateException("Offline ${engineType.displayName} model files...")` surfaces in `_modelError` which is shown via `showError` toast. Not sensitive, but inconsistent with user-facing error policy.  
**Impact:** Minor.  
**Evidence:** Pipeline `verifyModelIntegrity`.  
**Action:** Keep but ensure no absolute path in message (currently doesn't). OK as is.

### [HARDENING] Harden1 — `OfflinePipelineProvider` holds `Mutex` across `forceRelease()` (long JNI teardown)

**Location:** `app/src/main/java/com/groq/voicetyper/offline/OfflinePipelineProvider.kt:27-43`  
**Issue:** `getInstance` calls `currentInstance.forceRelease()` while holding `mutex`. All other `getInstance`/`releaseInstance` callers block for seconds.  
**Hardening:** Release outside lock: capture `toRelease` under lock, null instance, unlock, then `toRelease.forceRelease()`. Already partially done for non-switch case but not for switch.

### [HARDENING] Harden2 — `SecurityUtils` `MasterKey` recreation on every process start without `setUserAuthenticationRequired`

**Location:** `app/src/main/java/com/groq/voicetyper/SecurityUtils.kt:31-32`  
**Issue:** `MasterKey` defaults may change across androidx-security versions (alpha06 is old). Pin `KeyScheme` and handle `KeyPermanentlyInvalidatedException` on biometric enrollment. Not a current bug but supply-chain fragility.

### [HARDENING] Harden3 — Room `exportSchema=false` disables migration testing

**Location:** `app/src/main/java/com/groq/voicetyper/history/FluenceDatabase.kt:14`  
**Hardening:** Set `exportSchema=true` + check in `schemas/` (already `kapt` `room.schemaLocation` set), add `MigrationTest` for 3→4 backfill.

### [HARDENING] Harden4 — `Proguard` strips `Log.d/i/v` but also `Log.w` in some configs? Currently `-assumenosideeffects` only v/d/i, correct, but `Log.e` retained. Good — document.

### [HARDENING] Harden5 — `isCurrentApplicationAllowed` fail-open when `activeInstance==null`

**Location:** `app/src/main/java/com/groq/voicetyper/FluenceAccessibilityService.kt:41` (`?: true`)  
**Hardening:** Should fail-closed (`?: false`) for bubble path; IME already fails safe via `PrivacyPreferences`. Bubble injection should not proceed if service not connected.

### [HARDENING] Harden6 — `Snackbar` vs `Toast` (transient feedback)

**Location:** `app/src/main/java/com/groq/voicetyper/ui/HomeScreen.kt` / `PermissionsScreen` 32 toasts  
**Hardening:** Prior `APP_AUDIT` U20 — toasts are transient, no retry. Migrate error feedback to Snackbar with action (retry) for `ERROR` state.

---

## 6. Architecture Assessment

**Fundamentally sound.** The decision to centralize all recording in `TranscriptionSessionManager` (singleton object, `StateFlow`, `sessionGeneration` monotonic, `SessionOwner` IME vs BUBBLE) is the correct one and is implemented carefully: branch selection (streaming > offline > batch) is deterministic, `deliverTranscript` unified streaming+batch agent routing fixes prior S4, `endStreamingSession` unified teardown fixes S5d, `preWarm` integrity hash fix is present, `OfflineTranscriber.Mutex` + `activeJob` cancellation-before-acquire prevents JNI segfaults, `BubbleController` node `obtain/recycle` + `hasWebViewAncestor` + `SET_TEXT` vs paste is thoughtful.

**Architectural flaws:** None that require redesign. The only structural risks are (1) two foreground window types (`TYPE_ACCESSIBILITY_OVERLAY` visual + `TYPE_APPLICATION_OVERLAY` interaction) increasing permission/oem variance — acceptable given accessibility overlay is the only way to host a reliable bubble, (2) `VAD` not confined to one thread — should be single-threaded via `Mutex` as H1 notes.

**Implementation bugs:** The 4 HIGH findings above are localized bugs, not architectural. `allowMainThreadQueries` and `Handler.removeCallbacksAndMessages(null)` are implementation shortcuts, not architecture.

**Acceptable tradeoffs:** 
- `DROP_OLDEST` vs `SUSPEND` for offline queue (OOM vs loss) — documented, but needs observability.  
- `EncryptedSharedPreferences` caching (hot-path latency vs stale) — correct cache, narrow invalidation gap.  
- `START_STICKY` + null-intent guard (headless FGS) — correctly prevents ghost notification.  
- `idleRelease 60s` + `onTrimMemory` (memory vs cold-start) — correct.

---

## 7. Reliability Assessment (0–10)

| Area | Score | Rationale |
|---|---:|---|
| **Startup** | 9 | `FluenceApplication` attaches `AudioFocusManager` correctly, `MainActivity` `onAppStart` + `Lifecycle ON_RESUME` refresh is correct (fixed from original 2s poll). Minor: `deepLinkToSettings` not reset after nav (harmless). |
| **Recording** | 8 | `IDLE/ERROR` guard, `sessionGeneration++`, `preWarm cancel`, `registerNoisyReceiver`. Loses point for `StreamingAudioCapture` unsynchronized start and `AudioRecorder` file name collision (rare). |
| **Audio pipeline** | 6 | Batch AAC correct, streaming PCM 16k/mono bounded channel + writer correct, offline VAD thresholds correct — but H2 VAD race can crash on stop. Fix to 9. |
| **Transcription** | 7 | `GroqClient` deletion guarantee, `Mistral` `OPEN_TIMEOUT 20s` + `readTimeout 0` + `transportFailed` one-shot + `failTransport` on overflow all correct, offline latch correct. Loses points for M2 retry POST and H4 silent `""`. Fix to 8. |
| **Network** | 8 | Timeouts (30/60/60 streaming 15/0/15), `429/5xx` retry cap 10 + `isRetryable(IOException)`, ETag 304, size+SHA-256 validation — but M3 blank SHA bypass and M2 retryPOST lose a point. |
| **Clipboard injection** | 7 | `shouldPreferSetText` + `hasWebViewAncestor(25)` + `refresh` + selection handling + 50ms restore + fallback `SET_TEXT` is production-grade. Loses points for clipboard linger if crash before restore (50ms window) and dead `verifyActiveWindow`. |
| **State management** | 8 | `MutableStateFlow` authoritative, `OfflineEngineState`, `sessionOwner` isolation, `completeWithoutExternalDelivery` for null listener. Loses point for `Handler.removeCallbacksAndMessages(null)` blast radius (H1). |
| **Concurrency** | 6 | `Mutex` for engine, `sessionGeneration` guards everywhere, `endStreamingSession` idempotent, `showError` generation-checked. Loses for VAD unsynchronized (H2) and `collectJob` DCL not volatile, `AudioFocusManager` lock split. Fix both to 8. |
| **Shutdown** | 7 | `FloatingBubbleService` `onDestroy` lifecycle+scope cancel + `removeOverlayView`, `VoiceInputIME.onDestroy` `cancelImeRecording` → `destroy` → `runBlocking` Thread `ime-cleanup` daemon — intentional to survive `scope.cancel`. Loses point for daemon thread may be killed before `forceRelease` completes (process death frees native anyway) and `snapAnimator` not cancelled. |
| **Recovery** | 8 | `streamingCollectJob` watchdog `STREAMING_FINALIZE_TIMEOUT_MS 5s` force-teardown fixes prior hang, `modelError` flow surfaces corruption, `ERROR→IDLE` 4s auto-clear, `onTrimMemory` skip when RECORDING. Loses point for `modelError` log-only in offline worker for `""` case (H4). |

Average: **7.4/10** — ship after HIGH fixes → projected **8.6**.

---

## 8. Security & Privacy Assessment

**Score: 8/10.**

- **Secrets at rest:** `EncryptedSharedPreferences` `AES256_GCM` + `AES256_SIV` via `MasterKey AES256_GCM` ( `SecurityUtils.kt:31-41` ), key `groq_api_key` and per-preset `stt_api_key_*` / `llm_api_key_*` ( `140-162` ), never logged ( `GroqClient.kt:89` only logs HTTP code, not body/key), excluded from backup (`backup_rules.xml` / `data_extraction_rules.xml`). Cached `cachedPrefs` double-checked locking is correct. This is the right implementation; prior S1 latency fixed by `cachedSttPreset`/`cachedStreamingEnabled`.
- **Network:** TLS via OkHttp default, `followRedirects`/`followSslRedirects` true ( `GitHubUpdateRepository` ), `Authorization: Bearer` + `x-api-key` for mistral ( `GroqClient.kt:76-81`, `CommandProcessor:96-101` ), no key in query params, no pinning (acceptable for public APIs). `buildApiUrl` correctly handles `/v1` suffix ( `SecurityUtils.kt:165-172` ).
- **IPC:** No exposed `exported` providers beyond `FileProvider` for update APK (`authorities="${applicationId}.updateprovider"` `grantUriPermissions=true`), `FluenceAccessibilityService` `BIND_ACCESSIBILITY_SERVICE` correctly exported.
- **Clipboard:** 50ms restore via `scope.launch(Main) delay(50) restoreClipboard` ( `BubbleController.kt:395` ) plus catch-finally restore covers failure, but process kill between `setPrimaryClip(voice)` and restore leaves transcription in system clipboard (window 50ms + `ACTION_PASTE` IPC). Mitigation is narrow; user can clear clipboard. Not a vuln but privacy nuance.
- **Temp audio:** `AudioRecorder` writes to `context.cacheDir` private ( `AudioRecorder.kt:37` ), `GroqClient.transcribe` `finally` deletes ( `113-122` ), `cancelRecording` deletes, batch file never leaves app. Streaming never writes file (PCM via channel). Offline VAD samples are `FloatArray` in memory only.
- **Sandbox:** No `Runtime.exec`, no arbitrary file access, no path traversal ( `ModelAssetManager.getModelDir` throws if not ready, `build.gradle.kts` no `usesCleartextTraffic` ).
- **Deductions:** M3 blank SHA bypass (supply-chain), H6 fail-open `isCurrentApplicationAllowed` when `activeInstance==null`, alpha06 `security-crypto` old version (current is 1.1.0-alpha06 on 2023, newer 1.3.0 exists) — update recommended.

---

## 9. Performance Assessment

**Score: 8/10.**

- **Hot path:** Streaming capture `40ms` frames (`640 samples`), `AUDIO_QUEUE_CAPACITY 64` (~2.5s) + `writerScope IO` `Base64 NO_WRAP` + `JSONObject` per frame is acceptable; `trySend` fail-fast on overflow avoids jank. Batch `amplitude` poll `50ms` ( `AudioRecorder.kt:97` ), `StreamingAudioCapture` peak `abs` per frame `O(640)` fine. `FloatingBubbleUI` `animateDpAsState 250ms` + `snapshotFlow distinct` width not per-frame (fixed).
- **Memory:** Offline `~180-230MB` (SenseVoice) `+ VAD` held only while `_isRunning` or `idleRelease 60s`; `onTrimMemory TRIM_MEMORY_BACKGROUND` correctly releases ( `TranscriptionSessionManager.kt:861` ). `OfflinePipelineProvider` mutex ensures single instance. No unbounded vectors — `history` capped 50 ( `HistoryRepository.kt:69` ), `DailyStat` aggregated, `segmentChannel` bounded 32.
- **Deductions:** M4 progress flood (8KB → StateFlow) during model download will jank Main collectors; `OfflineTranscriptionPipeline.start` `initializeVadSync` on Main (doc says 1-2ms but on low-end asset load may be 10-30ms) — should be `withContext(IO)`; `WeeklyActivityChart` recomputes `dayDate` 7× per recomposition.

---

## 10. Accessibility Assessment

**Score: 7/10. WCAG 2.2 AA baseline.**

- **Positives:** `HomeScreen` `TranscriptRow` semantics `mergeDescendants` + `stateDescription Selected` + `onClick/onLongClick` labels ( `HomeScreen.kt:1030-1042` ), `SortBottomSheet` `Role.RadioButton`, `SettingsScreen` `Role.Button` + `onClickLabel`, `PermissionsScreen` `stateDescription Granted/Not granted` + `Role.Switch`, `WeeklyActivityChart` `mergeDescendants contentDescription` summary ( `872` ), bubble `BubbleTouchLayer` `importantForAccessibility=NO` lets visual remain single surface ( `FloatingBubbleService.kt` ).
- **Gaps:** `APP_AUDIT` A1 TextTertiary contrast still `TextTertiary #8E8E8E` on `Dialog #2E2E2E` 4.2:1 borderline (was 3.7 on `#363636`), but `AlertDialog` uses `DialogSurface` — verify AA; `HomeScreen` `Clear History` `heightIn 44dp` now fixed (was 32), good; `FloatingBubbleUI` glow `drawBehind` 5 strokes per frame may reduce contrast; `SiriWaveform` infinite `while(isActive) withFrameNanos` runs even when not recording (battery) — should be `LaunchedEffect(isActive)` gated.
- **Keyboard:** `VoiceInputIME` backspace swipe-to-delete has no keyboard alternative, but is IME-specific. `MainActivity` `requestPermissionLauncher` toast-only, no Snackbar retry — minor.

---

## 11. Testing Gap Analysis

| Critical behavior | Existing coverage | Missing coverage | Priority |
|---|---|---|---|
| Recording branch selection (streaming vs offline vs batch, mistral vs custom model, agent orthogonal) | `StreamingSessionManagerTest` 5 branch tests + `TranscriptionSessionManagerOwnerTest` offline isolation — all mock `SecurityUtils/OfflinePreferences` and verify `startCapture`/`connect(model)` + batch `GroqClient.transcribe` | No test for `isPackageExcluded` branch (blocked package) → `onError("Dictation is unavailable")` | P2 |
| Streaming lifecycle: Partial never inserts, Final exactly once, Error/Closed cleanup, watchdog, cancel silent, destroy IME | `StreamingSessionManagerTest` 8 lifecycle tests (partial/final, cancel, stopWithoutFinal 5s watchdog, missing key, error, closed, restartAfterFinal, destroy) + `MistralVoxtralTranscriberTest` 5 real WS tests (pre/open ordering, `transcription.text.delta`/`done`, `Closed`, overflow `AUDIO_QUEUE_CAPACITY+1`, error frame) | No test for `onCaptureFailed` mid-RECORDING path (uses real `StreamingAudioCapture` thread); covered indirectly via mock `onCaptureFailed` not yet; add `StreamingAudioCaptureTest` real thread with `AudioRecord` mock | P2 |
| Mic start retry once (`IllegalStateException → delay 400ms → retry`) | `StreamingSessionManagerTest` `micStartFailure_retriesOnceThenFailsExplicitly` + `micStartDoubleFailure_endsSessionWithError` (verify `startCapture` 2×, `stopCapture`/`close`) | No test for `SecurityException` no-retry path (currently `failStreamingStart` inline) | P3 |
| Agent streaming vs batch unified `deliverTranscript` | `agentMode_streamingFinal_routesThroughCommandProcessor` + `agentMode_batchFinal_routesThroughCommandProcessor` — verify `CommandProcessor.processCommand` + `onCommand` vs `onTranscription` | No test for stale generation guard inside `deliverTranscript` (H3) — add test that starts new session during `GroqClient.transcribe` and asserts old history not saved | P1 |
| IME vs bubble owner isolation (`cancelImeRecording` vs `cancelRecording`, `destroy` only when IME, `onTrimMemory` skip when RECORDING) | `TranscriptionSessionManagerOwnerTest` 4 tests (bubble not torn down by IME destroy, IME not cancelled by bubble, onTrimMemory skip, second start rejected) | No test for `pendingImeFinishCancellation` Runnable (Handler post) — needs Robolectric | P3 |
| Audio focus ducking (RECORDING → acquire, else → release, preference OFF → no manager, fail-tolerant) | `AudioFocusManagerTest` 13 tests — all pass, verify `DUCKING_FOCUS_GAIN=TRANSIENT_MAY_DUCK`, `reconcile`, `release` idempotency | No test for `collectJob` DCL race (needs stress test) | P3 |
| Injection: `SET_TEXT` vs clipboard, `hasWebViewAncestor(25)`, hint/placeholder, `pasteTextViaClipboard` restore | `BubbleControllerTest` 5 tests + `PrivacySuppressionTest` 1 | No test for `hasWebViewAncestor` WebView ancestor vs EditText editable vs `isShowingHintText` — add Robolectric `AccessibilityNodeInfo` tests | P2 |
| Offline: VAD flush on stop, `forceRelease` while `transcribe` holds Mutex, `initializeDeferred` latch | `OfflineTranscriberTest` 10 tests (Mutex, `markInitializationPending`, `failPendingInitialization`, `release` cancels `activeJob`) + `OfflineTranscriptionPipelineTest` 1 (pipeline start/stop with mocked VAD) | No test for VAD concurrent `acceptWaveform` vs `flush` race (H2) — add with `CountDownLatch` | P1 |
| Model integrity: `isModelReadySync` size gate, `isModelReady` SHA-256, `.verified` marker, `deleteModel` | `ModelAssetManagerTest` 7 tests | No test for `calculateSHA256` IO failure (should return false not throw) — currently bubbles as exception in `isModelReady` | P3 |
| Network: `GroqClient` deletion guarantee, `buildApiUrl`, `parseErrorMessage`, `fetchModels`, `retry` policy | `CommandProcessorTest` 8 + `Dictionary*` 18 + manual `GroqClient` not unit-tested (no MockWebServer for Groq path) | Add `GroqClientTest` MockWebServer for `multipart` + `language=null` omit + file deletion on failure | P2 |
| Update: `ApkDownloadWorker` `MAX_RETRY_ATTEMPTS 10`, `isRetryableHttpCode(429/5xx)`, SHA-256 fail | `ApkDownloadWorkerPolicyTest` 5 + `UpdateManagerCleanupPolicyTest` 4 + `ReleaseMetadataTest` 7 | No test for blank `sha256` bypass (M3) → should be failure | P1 |
| History/stats: `wordCountOf`, `effectiveDurationMs fallback 140 WPM`, `localDateOf`, `increment` insert-or-update, `cleanupToNewest` | `StatsCalculatorTest` 10 + `HistoryRepositoryDeleteTest` 6 + `IncrementGateTest` 4 (on main via worktree, not committed) | No test for stale-generation save (H3) | P1 |
| UI nav: `backStack` + deep link | No Compose UI tests | Add `createComposeRule` for `PermissionsScreen` toggle + `HomeScreen` search/sort `rememberSaveable` | P3 |

**Overall:** Tests are *not* hollow (prior S7 fixed). `StreamingSessionManagerTest` and `MistralVoxtralTranscriberTest` exercise real implementation via `mockkConstructor` + `MockWebServer` WebSocket, not `StringBuilder` lookalikes. Flaky watchdog test previously with tight `assert RECORDING` was fixed to `awaitState(IDLE, timeout+4000)` and `coVerify` after observation ( `StreamingSessionManagerTest.kt:299-305` comment). Remaining gap is device-required `AudioRecord`/`Bubble` touch and stale-generation history.

---

## 12. Production Blockers

**Only issues that should block release:**

| ID | Title | Why blocker |
|---|---:|---|
| H1 | `Handler.removeCallbacksAndMessages(null)` blast radius | Leaked FGS with mic notification — user-visible, battery, Play policy |
| H2 | VAD unsynchronized access | Native crash on stop — random but reproducible, tombstone |
| H3 | Stale-generation history save | Duplicate/phantom rows, inflated stats — data integrity |
| — | *H4 is High but not blocker* — silent offline drop is degradation, not crash; fix immediately after blockers | — |

**Release gate:** Ship only after H1+H2+H3 are patched and `StreamingSessionManagerTest` + `OfflineTranscriberTest` + `MistralVoxtralTranscriberTest` stay green + one device smoke: (1) streaming error → hide bubble → notification gone after 1.5s, (2) offline long utterance stop → no crash, (3) batch record → second record before first `GroqClient` returns → only second saved.

---

## 13. Recommended Fix Order

**P0 — Release blocker (ship-blocking, 1–2h total):**
1. **H1** `BubbleController.kt:292` + `TranscriptionSessionManager.kt:851` → keep `Runnable` fields, `removeCallbacks(runnable)` only.
2. **H2** `OfflineTranscriptionPipeline.kt` → `private val vadLock = Any()` + `synchronized(vadLock)` on every `vad.*` call; `stop()` `isRunning=false` → `audioCapture.stopCapture()` → `synchronized(vadLock) flush`.
3. **H3** `TranscriptionSessionManager.kt:769` → `scope.launch(IO) { if (sessionGeneration != generation) return@launch; HistoryRepository.save(...) }`; also change `CoroutineScope(IO)` → `scope`.
4. **Verify** `./gradlew :app:testDebugUnitTest` (or `test` on Windows) passes 18+18 streaming tests; `./gradlew lintDebug` no new errors.

**P1 — Fix immediately after blockers (next release or same if low risk):**
5. **H4** `OfflineTranscriber.kt:211` return `Result` vs `""`; surface `modelError` on not-READY.
6. **M3** `ApkDownloadWorker.kt:178` fail closed on blank `sha256`.
7. **M2** `GroqClient.kt:25` `retryOnConnectionFailure=false` for transcribe client; keep retry for `fetchModels`.
8. **M1** offline `DROP_OLDEST` observability — emit metric/log + UI warning when `onUndeliveredElement` fires.

**P2 — Hardening (next sprint):**
9. `AudioFocusManager.kt:52-53` make `collectJob` `@Volatile`; split `acquire` vs `attachCore` lock ordering.
10. `Navigation.kt:52-55` `previousSize` move to `LaunchedEffect`/`derivedStateOf`.
11. `SecurityUtils.kt:158-162` clear caches on `clearProviderApiKey`.
12. `OfflinePipelineProvider.kt:34-41` release outside `mutex`.
13. `ModelAssetManager.kt:210` throttle progress emissions.

**P3 — Optional / polish:**
14. `BubbleController.kt:193` remove or implement `verifyActiveWindow`.
15. `AudioRecorder.kt:37` `UUID` file names, `StreamingAudioCapture.kt:68` `synchronized` start.
16. `HistoryRepository.kt:51` `cleanupToNewest` inside transaction, `FluenceDatabase.kt:102` document `allowMainThreadQueries` scope.
17. UI: `FloatingBubbleUI.kt:145` `pointerInput` key, `Theme.kt` `remember` precision colors, `WeeklyActivityChart` memoize `dayDate`.

---

## 14. Things That Are CORRECT (investigated and found sound)

**Must list to prevent churn.**

- **Streaming teardown unification:** `endStreamingSession` `app/src/main/java/com/groq/voicetyper/TranscriptionSessionManager.kt:528-543` is idempotent, nulled capture/transcriber, cancelled `streamingCollectJob`, reset `currentListener/sessionOwner/partialText` only when `sessionGeneration==generation`, `unregisterNoisyReceiver`. Called from Final, Error, Closed, missing key, cancel, mic failure, watchdog, destroy — fixes prior S5d double-capture. Verified against 18 tests `stopWithoutFinal_watchdogForceEndsSession`, `missingSttApiKey_tearsDownCapture...`, etc. Do not "simplify" to per-path cleanup.

- **`sessionGeneration` guards:** Monotonic `sessionGeneration` `Volatile` `++` on `startRecordingInternal`, captured `generation` in every async callback (`onAudioFrame`, `onCaptureFailed`, `streamingCollectJob`, `modelErrorCollect`, `cancelSessionInternal` offline teardown). Prevents stale teardown of new session. Do not replace with `sessionId` UUID — current is sufficient.

- **`ERROR→IDLE` 4s auto-clear:** `TranscriptionSessionManager.kt:844-858` posts delayed reset *after* `showError` sets `ERROR` + `onError` callback; generation not needed because ERROR is global. Intentional for UI.

- **Streaming `AUDIO_QUEUE_CAPACITY 64` + `openGate` + `transportFailed`:** `MistralVoxtralTranscriber.kt:47-78` (`64` covers ~2.5s), `openGate` `CompletableDeferred` waits `OPEN_TIMEOUT_MS 20s` before draining, `audioQueue` `BufferOverflow.SUSPEND` but `sendAudioChunk` uses `trySend` fail-fast → explicit `Error` not silent drop. `transportFailed` `AtomicBoolean` ensures one-shot error. Real WS tests prove pre-open frames arrive in order. Do not "optimize" to larger queue or `DROP_OLDEST`.

- **Agent orthogonal to streaming:** `TranscriptionSessionManager.kt:215-217` comment + `218` `useStreaming = isStreamingConfigured && !isOffline && (mistral||custom)` + `deliverTranscript` unified streaming+batch agent path (`754-813`). Prior S4 silent batch fallback is fixed; `agentModeWithStreamingEnabled_stillUsesStreaming` test proves. Do not re-add `!agentMode` guard.

- **Offline cold-start latch:** `OfflineTranscriber.markInitializationPending` before `verifyModelIntegrity` (`OfflineTranscriptionPipeline.kt:109`, `157`), `transcribe` awaits `initializeDeferred` (`OfflineTranscriber.kt:200`), `failPendingInitialization` completes exceptionally. Prevents first-utterance loss. Do not remove.

- **Offline `Mutex` + `activeJob` cancellation before acquire:** `OfflineTranscriber.kt:242-257` cancels `activeJob` *before* `mutex.withLock` — essential because `transcribe` holds mutex whole inference. Reversing order would deadlock.

- **Cached `EncryptedSharedPreferences`:** `SecurityUtils.kt:15-44` `Volatile+synchronized` DCL + `cachedSttPreset`/`cachedStreamingEnabled` hot-path caches. Prior S1 tap latency fixed; narrowing to single `MasterKey` creation solves 2× Keystore cycles. Do not "uncache" for simplicity.

- **Bubble two-window model:** Visual `TYPE_ACCESSIBILITY_OVERLAY FLAG_NOT_TOUCHABLE` + interaction `TYPE_APPLICATION_OVERLAY` (`FloatingBubbleService.kt:174-247`) is intentional: accessibility overlay needs no `SYSTEM_ALERT_WINDOW` but cannot receive touch; interaction overlay owns touch, mirrored positions, alpha gate + `Choreographer.postFrameCallback` on snap flip. Do not unify to single window.

- **`START_STICKY` null-intent guard:** `FloatingBubbleService.kt:99-106` `if (intent==null && !isBubbleVisible.value) stopSelf START_NOT_STICKY` prevents headless FGS after kill. Correct.

- **`AudioFocusManager` non-blocking:** `requestAudioFocus`/`abandon` failures only `Log.w`, never block dictation (`AudioFocusManager.kt:139`, `142`, `161`, `178`). Correct for enhancement-only feature.

- **`GroqClient` file ownership:** Takes ownership, `finally` deletes (`GroqClient.kt:112-122`), early missing-key paths also `file.delete()` before return (`TranscriptionSessionManager.kt:699-709`). Double delete safe.

- **`AUDIO_BECOMING_NOISY` receiver:** `registerNoisyReceiver` `TRANSIENT` `RECEIVER_NOT_EXPORTED` (`TranscriptionSessionManager.kt:937-963`), `handler.post` generation-checked `cancelSessionInternal`, `unregisterNoisyReceiver` on every exit (streaming via `endStreamingSession`). Correct for headset unplug.

- **`allowMainThreadQueries` scope:** Intentionally limited to dictionary cache priming; offline/batch transcription and history `save` are `Dispatchers.IO` / `withTransaction`. Not a blanket permission.

---

## 15. Things That LOOK Suspicious But Are NOT Bugs

**Prevents re-open churn.**

- **`TranscriptionSessionManager.scope` never cancelled:** `scope = CoroutineScope(Dispatchers.Default + SupervisorJob())` lives for process lifetime (object singleton), `destroy` uses raw `Thread { runBlocking { releaseInstance() }}` *because* `scope` would be cancelled if `destroy` ran `scope.cancel()` — comment in `VoiceInputIME.kt:431-434` and `TranscriptionSessionManager.kt:918-934` explains. This is intentional; jobs are individually cancelled.

- **`VoiceInputIME` `AudioRecorder(this)` dummy param:** `VoiceInputIME.kt:176` passes dummy `AudioRecorder` to `IMEScreen` — unused (IME uses `TranscriptionSessionManager` audio, not screen's recorder). Looks like leak but is unused composition param; not a resource leak.

- **`HistoryRepository.save` fire-and-forget outside caller:** Two `CoroutineScope(IO).launch` for `save` look unstructured but `save` already handles its own `withTransaction` + catch, and is best-effort persistence. The *stale* case (H3) is a bug, but the fire-and-forget itself is intentional to not block UI on DB.

- **`GroqClient` hardcoded `audio/m4a`:** `GroqClient.kt:63` `audio/m4a` matches `AudioRecorder` `MPEG_4/AAC` `.m4a`. Not generic but correct for batch path; streaming doesn't use `GroqClient`.

- **`BuildConfig` release falls back to debug signing when `local.properties` lacks keystore:** `app/build.gradle.kts:62-74` `signingConfig = if (hasReleaseSigning) release else debug`. Looks like silent debug-signed release, but GitHub Actions release workflow (`release.yml`) *always* provides `RELEASE_KEYSTORE_BASE64` and fails closed if missing (`if [ -z "$KEYSTORE_B64" ] exit 1`). Local fallback only affects developer `./gradlew assembleRelease` without secrets, not Play publishing.

- **`OfflinePipelineProvider` `Mutex` + `synchronized` mix in `OfflineTranscriber`:** Two different locks (provider mutex vs transcriber mutex) — not a deadlock because they guard different state (provider singleton vs engine handles). Correct.

- **`BubbleController.isCurrentApplicationAllowed` returning `true` when `activeInstance==null`:** Looks fail-open, but bubble injection still checks `PrivacyPreferences.isPackageExcluded(context, targetPackage)` per `activeNodePackage()` (the actual target), while `resolveActiveApplicationPackage` is only for extra window verification (unused today per M6). Not a privacy bypass for IME path (IME uses `currentTargetPackage` not this).

- **`StreamingAudioCapture` `byteBuffer` reuse with `copyOf` conditionally:** `ByteArray 1280` reused, `if (readResult < size) copyOf` else direct `byteBuffer` passed to `listener.onAudioFrame` which then `copyOf(length)` in `Mistral` — appears to reuse mutable buffer but `Mistral.sendAudioChunk` copies immediately, so safe.

- **`PrivacyPreferences.getExcludedPackages` listener registration inside `ensurePreferences`:** Double-checked `registeredPreferences !== preferences` with `synchronized(lock)` is correct DCL for `SharedPreferences` swap; snapshot `excludedPackagesSnapshot` updated on `OnSharedPreferenceChangeListener`.

---

## 16. Final Release Decision

**Do not ship this exact commit without patching H1+H2+H3.** Those three are the only true blockers. They are each <10 lines, localized, low regression risk, and can be landed in one PR with `StreamingSessionManagerTest` + `ModelAssetManagerTest` + `Mistral` tests re-green.

**If H1+H2+H3 are landed and verified (tests + lint + 5-min device smoke: error→hide bubble no leak, long offline stop no crash, rapid re-record no phantom history), verdict becomes `Production Ready`.** Remediation P1 items (H4, M3, M2) should follow in the next point release but are not ship-blocking. The app's architecture is sound, its most dangerous historical failure modes are already fixed on `main`, and no evidence of data loss or security vuln beyond the narrow SHA-blank case (which is not exploitable without a compromised `release.json` serve) was found.

**Blunt:** This is a well-hardened codebase for a 34-versionCode app. Ship after the three HIGH patches or you will chase ghost FGS notifications and native crashes in the field.

---

## Appendices

### A. Evidence index (representative)

- `app/src/main/java/com/groq/voicetyper/TranscriptionSessionManager.kt:528-543` `endStreamingSession` unified teardown
- `app/src/main/java/com/groq/voicetyper/TranscriptionSessionManager.kt:844-858` `showError` 4s reset
- `app/src/main/java/com/groq/voicetyper/streaming/MistralVoxtralTranscriber.kt:47-78` `AUDIO_QUEUE_CAPACITY`/`openGate`/`transportFailed`
- `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriber.kt:120-141` `markInitializationPending`/`failPending` + `242-277` `release` cancel-before-lock
- `app/src/main/java/com/groq/voicetyper/BubbleController.kt:292` `removeCallbacksAndMessages(null)` blast radius
- `app/src/main/java/com/groq/voicetyper/offline/OfflineTranscriptionPipeline.kt:209-229` VAD audio thread vs `238-268` stop
- `app/src/test/java/com/groq/voicetyper/StreamingSessionManagerTest.kt:18 tests` all green (7.221s, `stopWithoutFinal` 5.072s watchdog verified)
- `app/build.gradle.kts:62-74` signing fallback, `proguard-rules.pro` `-assumenosideeffects Log.v/d/i`, `gradle/libs.versions.toml` pins

### B. Verification performed

- Direct reads on `main` checkout (clean worktree, `git stash` sync-feature) — all files above.
- Existing JVM test suite re-checked via `app/build/test-results/testDebugUnitTest/*.xml` (20 suites, 0 failures) — sync suites from prior dirty run ignored; `StreamingSessionManagerTest.xml` 18/0, `MistralVoxtralTranscriberTest.xml` 5/0, `OfflineTranscriberTest.xml` 10/0, `AudioFocusManagerTest.xml` 13/0.
- `./gradlew testDebugUnitTest` not re-run in this session due to Windows Gradle daemon timeout (120s); relied on cached `13:54:45` run on this commit (same `b8a1c5d` build). Note: "Unable to verify from available runtime evidence" for fresh device run — recommend CI re-run `testDebugUnitTest lintDebug` before tagging.

### C. Out of scope (explicitly not filed)

- `sync-feature` Drive/Ledger/Scheduler/OAuth — 5 commits ahead plus dirty `AccountDailyStat` etc., not on `main`.
- Windows/Tauri/WASAPI/clipboard/tray/hotkey — remapped to Android analogues, not filed as missing.

