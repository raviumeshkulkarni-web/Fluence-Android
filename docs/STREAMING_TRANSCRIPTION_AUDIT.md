# Streaming Transcription — Audit Report

Date: 2026-08-09
Branch: `feature/android-streaming-transcription` (commits `4e0308c..4e14ae9`, HEAD `4e14ae9`)
Scope: the build containing all streaming-transcription changes (the squashed feature build tested on device).

All findings below were verified against the committed blobs (`git show HEAD:...`). Line numbers refer to HEAD.

---

## Summary

The streaming feature works end-to-end on device: activation, capture, WebSocket transcription,
partial-update rendering, and final insertion. The regressions come from two root causes:

1. **The overlay window is `WRAP_CONTENT` and Compose resizes it at runtime** (card show/hide, card
   height growth). This single cause produces the screen flash, the right-side stutter, and the
   vertical bubble jump — and it is the streaming-card addition that triggers it.
2. **Streaming is wired as a separate parallel pipeline** instead of a plug-in to the existing
   session lifecycle. That is why Agent mode silently degrades to batch, and why several
   streaming-only paths skip cleanup (double mic capture, stuck RECORDING, receiver leak).

Plus one latency regression: encrypted-prefs reads moved onto the main thread's recording hot path.

Severity: 3 P1, 4 P2, 4 P3 (see table at the end).

---

## S1. Tap latency — EncryptedSharedPreferences on the main thread (P1)

**Observed:** noticeable delay between tapping the bubble and the mic/recording starting.

**Root cause:** `TranscriptionSessionManager.startRecordingInternal` now reads STT config
synchronously on the main thread before the mic opens:

- `TranscriptionSessionManager.kt:186` — `SecurityUtils.getSttPreset(context)`
- `TranscriptionSessionManager.kt:187` — `SecurityUtils.isStreamingEnabled(context)`

Every `SecurityUtils` getter calls `getSharedPrefs()`, which builds a **fresh** `MasterKey` and a
**fresh** `EncryptedSharedPreferences` instance on every call (`SecurityUtils.kt:12-24`) — there is
no caching of the prefs instance or the resolved values. That is 2+ Keystore/encrypted-prefs
open+read cycles (each tens of ms, worse on cold boot/OEM devices) on the main thread, between the
tap and `_recordingState = RECORDING`. The baseline hot path had none of these.

**Fix direction:**
- Cache a single lazy `MasterKey`/prefs instance in `SecurityUtils` (thread-safe singleton).
- Better: resolve `sttPreset`/`isStreamingEnabled` off the main thread, or start recording
  immediately and select the streaming path asynchronously (the streaming branch already does its
  key/model reads inside an IO coroutine at `TranscriptionSessionManager.kt:336-352`).

---

## S2. Bubble jumps vertically when speaking (P1)

**Observed:** the floating bubble moves down while speaking and back up when stopped.

**Root cause:** the streaming card is rendered *inside* the WRAP_CONTENT window column:

- `FloatingBubbleUI.kt:97` — `showStreamingCard = isExpanded && state==RECORDING && partialText.isNotBlank()`
- `FloatingBubbleUI.kt:110-115` — card placed **above** the bubble when `!isTopHalf` (bottom-anchored)
- `FloatingBubbleUI.kt:298-303` — card placed **below** the bubble when `isTopHalf`

When the first partial arrives, the card (40–96 dp + 6 dp spacing) is inserted into the column.
The window's top edge is fixed (`Gravity.TOP`, `FloatingBubbleService.kt:161`), so a card **above**
the bubble pushes the bubble **down** on screen; hiding the card on stop moves it back up. Card
height growth as text accumulates moves it incrementally. Top-half bubbles (card below) do not move,
which is why the symptom is positional-dependent.

`isTopHalf` is only updated on drag (`FloatingBubbleService.kt:197`) and initial placement
(`:168`) — never after the card shifts the bubble — so the above/below decision can go stale.

**Fix direction:**
- Reserve the card slot so the window size is constant for the whole expanded session (see S3).
- Re-evaluate `isTopHalf` after any vertical shift (or derive it from `lp.y` at render time).
- Alternative: keep WRAP_CONTENT but translate the bubble (or the whole window) to compensate.

---

## S3. Window resize artifacts — screen flash + right-side stutter (P2)

**Observed:** a flash when the streaming card first appears; right-side stutter while streaming.

**Root cause:** the overlay is `WRAP_CONTENT` (`FloatingBubbleService.kt:159-160`). The card
show/hide and its height changes (40→96 dp) resize the window and its hardware surface every time.
On `Gravity.END` (right-anchored) the right edge stays fixed and the **left** edge moves, so the
anchored bubble visually wobbles; the surface resize also renders one bad frame (the flash). The
width-related fix is correctly in place at HEAD (`FloatingBubbleUI.kt:80-84` snapshotFlow +
`FloatingBubbleService.kt:224-227` no-op), so the residual stutter is the height resize, not width.

**Fix direction:** one fix addresses S2 + S3 together —
- Give the window a stable measured size per expand/collapse state: render the card as a
  fixed-size, always-laid-out slot that is only made visible when `showStreamingCard` is true
  (transparent when hidden), instead of conditionally inserting/removing it. Then no runtime window
  resize occurs during a session.
- (More invasive) switch to a fixed-size window (bubble area + reserved card area) set once on expand.

---

## S4. Streaming and Agent Mode are mutually exclusive — silent fallback (P1)

**Observed:** Agent Mode does not work when streaming mode is enabled.

**Root cause (verified coupling):**
- `TranscriptionSessionManager.kt:188` — `useStreaming = isStreamingConfigured && !isOffline && !agentMode && (sttPreset == "mistral" || sttPreset == "custom")`.
  With Agent on, `useStreaming` is forced false → session silently runs the **batch** path
  (`transcribeAudioOnline`, which is the only path that handles `onCommand`/agent).
- The streaming Final handler (`TranscriptionSessionManager.kt:370-391`) calls
  `currentListener?.onTranscription(...)` directly; there is **no** `onCommand`/agent/command
  processing in the streaming branch. So even removing `!agentMode` would not make Agent work with
  streaming.
- Additional constraint: streaming only activates for `mistral`/`custom` presets; for `groq` the
  streaming toggle is a no-op by design (Mistral-only realtime protocol).

So with streaming enabled + Agent on, the user gets batch (no partials/card). If the provider's
batch endpoint rejects the request or the key/model mismatches, Agent appears broken.

**Fix direction:**
- Route streaming Final through the same downstream as batch: run agent/command processing
  (`CommandProcessor.processCommand`) when `_isAgentMode.value` is true, then deliver
  `onCommand`/`onTranscription` accordingly.
- Remove the `!agentMode` exclusion only after the above is implemented (otherwise Agent silently
  loses its processing path).
- Surface the provider constraint in `SttConfigScreen` (toggle currently implies support for
  groq users with no effect).

---

## S5. Streaming robustness/cleanup gaps (P2/P3)

**S5a — Initial audio is dropped during the connect handshake (P3).**
`MistralVoxtralTranscriber.sendAudioChunk` (`:137-151`) uses `webSocket ?: return`; OkHttp's
`newWebSocket` opens asynchronously (~1–3 s). All chunks before `onOpen` are silently lost — the
first word(s) of the utterance. No buffering.

**S5b — Blocking network write on the real-time capture thread (P3).**
`onAudioFrame` (`TranscriptionSessionManager.kt:322-324`) runs on the `MAX_PRIORITY` capture thread
(`StreamingAudioCapture.kt:87-92`) and calls `sendAudioChunk`, which does Base64 + JSON + a
blocking `socket.send` every 40 ms. On slow networks the capture thread stalls → audio gaps.
Feed PCM to a bounded channel drained by a writer coroutine instead.

**S5c — No reconnect/heartbeat; session can hang in RECORDING (P2).**
- `Closed` is a no-op (`TranscriptionSessionManager.kt:400-402`). A server-initiated close leaves
  the session in RECORDING with a dead socket until the user stops; `stopRecording` then falls
  through to the batch path with a null recorder (`:440-448` guard exists only when
  `activeStreaming` is still true — after a `Closed` it is, so recovery is the manual stop).
- `readTimeout(0)` (`MistralVoxtralTranscriber.kt:32`) means a silently-dropped connection never
  surfaces. No heartbeat/watchdog.

**S5d — Missing-API-key path leaks the capture (P1 edge).**
`TranscriptionSessionManager.kt:339-348`: sets IDLE + unregisters the receiver but never calls
`cleanupStreamingResources()`. The capture thread keeps running with state IDLE; the next tap
creates a **second** `StreamingAudioCapture` and overwrites `streamingAudioCapture` without stopping
the first → dual `AudioRecord` (mic-in-use failure). `currentListener`/`_partialText` also not reset.

**S5e — Streaming Final path skips normal session teardown (P3).**
The Final handler (`TranscriptionSessionManager.kt:370-391`) does not call
`unregisterNoisyReceiver`, does not reset `recordingStartTimestampMs`, and does not reset
`sessionOwner`. The noisy receiver leaks until the next start or IME destroy.

**Fix direction:** unify teardown — one `endStreamingSession()` used by Final/Error/Closed/stop/
cancel paths that stops capture, closes the socket, cancels the collect job, unregisters the
receiver, and resets state consistently.

---

## S6. Process death / force-close → bubble no longer expands correctly (P2)

**Observed:** after the process is killed/closed while recording, relaunching and tapping the bubble
does not expand (or expands into an error).

**Analysis:**
- Bubble position (`FloatingBubbleService.kt:355-357` statics), expand/visible flags
  (`BubbleController`), and the active streaming session are all in-memory — nothing survives
  process death (no persistence of position or session).
- `FloatingBubbleService.onStartCommand` (`:88-95`) restarts headless on `START_STICKY` (no
  null-intent guard at HEAD): it shows the sticky notification even though no bubble is visible
  until a text field is focused again.
- **Leading hypothesis for the expansion failure:** a force-kill while recording leaves the mic
  briefly held by the dead process's `AudioRecord`. The first tap re-initializes `AudioRecord` while
  the HAL still reports the mic in use → `startRecordingInternal` reports "Could not start the
  microphone" (`TranscriptionSessionManager.kt:288-298`) → error state → bubble auto-collapses → it
  looks like "does not expand correctly". This is device/OEM timing dependent and needs on-device
  reproduction (logcat) to confirm.

**Fix direction:**
- Retry `AudioRecord` start once after a short delay when the initial start fails.
- Add the null-intent guard to `onStartCommand` (skip headless `startForeground` when
  `BubbleController.isBubbleVisible.value` is false).
- Consider persisting bubble position (`lastX/lastY/lastIsAnchoredRight`) in SharedPreferences so a
  recreated bubble restores its screen position.

---

## S7. Tests do not cover the implementation (P3)

The "streaming lifecycle coverage" tests
(`app/src/test/java/com/groq/voicetyper/streaming/MistralVoxtralTranscriberTest.kt`,
`StreamingAudioCaptureTest.kt`) only exercise local `StringBuilder`s and constants. They pass even if
the entire streaming pipeline is deleted, so they give zero regression protection.

**Fix direction:** add real unit tests for the branch selection logic
(`startRecordingInternal` streaming vs offline vs batch), the partial/final/error/closed routing,
cleanup on each exit path, and `SessionListener.onPartialTranscription` dispatch.

---

## Fixes already present at HEAD (do not re-introduce)

- Width-stutter fix: `onWidthUpdated` fires only on distinct width changes
  (`FloatingBubbleUI.kt:80-84`) and the service ignores it (`FloatingBubbleService.kt:224-227`).
- Crash fix: `SessionListener.onPartialTranscription` has a default no-op
  (`TranscriptionSessionManager.kt:36`).
- Overlay side-flip alpha gate at snap completion (`FloatingBubbleService.kt:294-312`).

---

## Severity table

| ID | Title | Severity | File:line |
|----|-------|----------|-----------|
| S1 | Tap latency (EncryptedSharedPreferences on main thread) | P1 | `TranscriptionSessionManager.kt:186-188`, `SecurityUtils.kt:12-24` |
| S2 | Bubble jumps vertically when speaking | P1 | `FloatingBubbleUI.kt:97,110-115,298-303`, `FloatingBubbleService.kt:161` |
| S3 | Screen flash + right-side stutter from window resizes | P2 | `FloatingBubbleService.kt:159-160` |
| S4 | Streaming + Agent mutually exclusive, silent batch fallback | P1 | `TranscriptionSessionManager.kt:188,370-391` |
| S5a | Initial audio dropped during connect handshake | P3 | `MistralVoxtralTranscriber.kt:137-151` |
| S5b | Network write on real-time capture thread | P3 | `TranscriptionSessionManager.kt:322-324`, `StreamingAudioCapture.kt:87-92` |
| S5c | Session hangs in RECORDING after server close; no reconnect | P2 | `TranscriptionSessionManager.kt:400-402`, `MistralVoxtralTranscriber.kt:32` |
| S5d | Missing-API-key path leaks capture → dual AudioRecord | P1 | `TranscriptionSessionManager.kt:339-348` |
| S5e | Streaming Final skips noisy-receiver/state teardown | P3 | `TranscriptionSessionManager.kt:370-391` |
| S6 | Process death → bubble expansion failure (mic lock / headless restart) | P2 | `FloatingBubbleService.kt:88-95,355-357` |
| S7 | Tests don't exercise the actual implementation | P3 | `app/src/test/.../streaming/*.kt` |

## Recommended fix order

1. **S2 + S3 together** — reserve the card slot / stable window size. One change removes the bubble
   jump, the flash, and the stutter.
2. **S1** — cache `SecurityUtils` prefs; move config reads off the recording hot path.
3. **S5d + S5c** — unify streaming teardown so every exit path cleans up (prevents double-capture
   and stuck-RECORDING sessions).
4. **S4** — route streaming Final through the agent/command pipeline.
5. **S6** — AudioRecord retry + null-intent guard; then verify on device.
6. **S5a/S5b/S5e** — buffering, channel-based capture writer, teardown consistency.
7. **S7** — real tests guarding the lifecycle branches.
