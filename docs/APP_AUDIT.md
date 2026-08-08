# Fluence Transcribe — Production Readiness UI/UX & Feature Audit


Date: 2026-08-07
Scope: all UI screens, IME UI, theme layer, navigation, history, res/values,
manifest, build config. Floating bubble, overlay lifecycle, accessibility
service, and transcription pipeline are FROZEN and out of scope.
Release pipeline is FROZEN — release-infra recommendations are documented
with a decision gate, not implemented.

## Classification

- Critical = blocks shipping
- High = must fix soon
- Medium = should fix
- Low = polish
- Future = roadmap

- S = <1h · M = 1–4h · L = 4–12h · XL = >12h

---

## 1. UI Audit

| ID | Finding | Where | Severity | Cost |
|---|---|---|---|---|
| U1 | Launcher-icon background `#090C10` differs from design-system app background `#0D0D0D` / Canvas `#121212` | `app/src/main/res/values/ic_launcher_background.xml:3` | Low | S |
| U2 | Duplicate hero-stat label: two cells both read "Typing Time Saved" (till today / last 30 days) | `app/src/main/java/com/groq/voicetyper/ui/HomeScreen.kt:687-727` | Low | S |
| U3 | "Clear All" button sits in the first group header but clears all history — ambiguous scope | `app/src/main/java/com/groq/voicetyper/ui/HomeScreen.kt:441-447` | Low | S |
| U4 | `DashboardHeroStats` shadow `8.dp` + chart shadow `6.dp` violate DESIGN_SYSTEM elevation rule ("depth from tonal ladder, never shadows — except menus/dialogs/popovers") | `app/src/main/java/com/groq/voicetyper/ui/HomeScreen.kt:669,798` | Low | S |
| U5 | Raw `sp`/`dp` literals throughout instead of design tokens, breaking "no raw visual values". Worst: `TranscriptionDetailSheet.kt:84-115`, `update/ui/UpdateCard.kt:60-133`, all config screens | all screens | Medium | L |
| U6 | Settings entrance animation `320ms` exceeds design-system structural token (~200–250ms) | `app/src/main/java/com/groq/voicetyper/ui/SettingsScreen.kt:112-113` | Low | S |
| U7 | `HomeSearchBar` creates a `FocusRequester` that is never used to request focus (dead code) | `app/src/main/java/com/groq/voicetyper/ui/HomeScreen.kt:889,936` | Low | S |
| U8 | About credits say "Precision Ink" — a superseded brand name per DESIGN_SYSTEM changelog | `app/src/main/java/com/groq/voicetyper/ui/AboutScreen.kt:103` | Low | S |
| U9 | `AppBackground` (`#121212`) duplicated with Canvas; only one is used by the theme | `app/src/main/java/com/groq/voicetyper/theme/Color.kt:11,14` | Low | S |

## 2. UX Audit

| ID | Finding | Where | Severity | Cost |
|---|---|---|---|---|
| U10 | No first-run onboarding. Fresh install opens to an empty Home showing "Inactive"; no guided path to enable the keyboard, grant mic, or add an API key. IME pill shows "API KEY REQUIRED" with no CTA | `ui/HomeScreen.kt:618-657`, `IMEScreen.kt:182-183` | Critical | M–L |
| U11 | Mic permission is effectively dead wiring: `MainActivity.kt:34-42` registers a launcher but nothing on Home ever calls it; `onRequestPermission` is threaded through `FluenceNavHost` (`Navigation.kt:41`) but unused. Mic is only requestable from the Permissions screen | `MainActivity.kt:73-77`, `navigation/Navigation.kt:41` | High | S |
| U12 | Home status "Ready/Inactive" is inaccurate: checks whether the IME is merely enabled (`enabledInputMethodList.any`) rather than whether it is the current/default IME — shows "Ready" even when another keyboard is selected | `ui/HomeScreen.kt:265-275` | High | S |
| U13 | 2-second infinite poll (`while (true) { … delay(2000) }`) runs forever while Home is composed; wasteful, never cancelled | `ui/HomeScreen.kt:265-275` | Medium | S |
| U14 | Detail-sheet Delete has no confirmation — inconsistent with every other destructive action; irreversible data loss | `ui/TranscriptionDetailSheet.kt:153-175` | High | S |
| U15 | History silently capped at 30 entries (`cleanupToNewest(30)`); older entries vanish with no notice, export, or setting | `history/HistoryRepository.kt` | High (product decision) | M |
| U16 | Agent-mode model fetch fires on every keystroke in the API-key field (network storm while typing) | `ui/AgentConfigScreen.kt:199-202` | Medium | S |
| U17 | Provider chip auto-saves STT preset but Agent screen only saves on explicit Save → inconsistent "did it stick?" behavior | `ui/SttConfigScreen.kt:279-306` vs `ui/AgentConfigScreen.kt:152-178` | Low | S |
| U18 | Offline engine RadioButtons are not tappable via their label text (only the radio circle), no row-level semantics merge | `ui/OfflineConfigScreen.kt:163-228` | Medium | S |
| U19 | Rotation/process-death wipes `searchQuery`, `selectedIds`, `expandedGroups`, sort (plain `remember`, not `rememberSaveable`) | `ui/HomeScreen.kt:254-261` | Medium | M |
| U20 | Toast-only feedback (32 toasts) — inconsistent, transient, no error persistence | all screens | Medium | M |

## 3. Accessibility Audit

| ID | Finding | Where | Severity | Cost |
|---|---|---|---|---|
| A1 | TextTertiary `#8E8E8E` fails WCAG AA on Dialog surfaces: 5.1:1 on Panel OK, 4.6:1 on PanelElevated (borderline), 4.2:1 on Dialog `#2E2E2E`, 3.7:1 on DialogElevated `#363636`. Used for real text ("Skip Version", "Remind Me Later", dropdown metadata) | `update/ui/UpdateDialog.kt:161-164`; `theme/Color.kt:27` | High | M |
| A2 | TextTertiary token drifts from DESIGN_SYSTEM `#808080`; code raises it to `#8E8E8E` to pass AA — intentional, doc must be reconciled | `theme/Color.kt:27` vs `DESIGN_SYSTEM.md:55` | Low | S |
| A3 | Weekly activity chart has no semantics — bar values invisible to TalkBack (summary line partially compensates) | `ui/HomeScreen.kt:782-881` | Medium | S |
| A4 | `SettingsRow` clickable rows lack explicit button role / `onClickLabel` | `ui/SettingsScreen.kt:48-57` | Low | S |
| A5 | Some controls below the 44dp minimum (e.g. "Clear All" `heightIn(min = 32.dp)` text button) | `ui/HomeScreen.kt:441-447` | Low | S |
| A6 | Fixed `fontSize` literals ignore user font-scale; several lack `maxLines`/`overflow` | all screens | Medium | M |
| A7 | Good baseline to preserve: TranscriptRow semantics (`onClick`/`onLongClick`/`stateDescription`), IME mic semantics with states, sort-sheet `Role.RadioButton`, permission `stateDescription` | `ui/HomeScreen.kt:958-970`, `IMEScreen.kt:355-390` | — | — |

## 4. Production Readiness

| ID | Finding | Where | Severity | Cost |
|---|---|---|---|---|
| P1 | Missing Google Fonts provider in manifest. `Type.kt:16-20` declares `GoogleFont.Provider(com.google.android.gms.fonts)` and every screen loads Hanken Grotesk/Sora/Geist Mono via it, but `AndroidManifest.xml` declares no `com.google.android.gms.fonts.provider.FontsProvider`. Result: downloadable-font requests fail and the entire brand typography silently falls back to system fonts. Fix is 5 lines | `app/src/main/AndroidManifest.xml`, `theme/Type.kt:16-20` | Critical | S |
| P2 | No lint config/baseline, no CI lint gate; minify/shrink on with no verification beyond a rules file | `app/build.gradle.kts:62-74` | Medium | M |
| P3 | Nav back-stack is `remember`ed — lost on process death (app returns to Home); acceptable at this scale, document it | `navigation/Navigation.kt:42` | Low | S |
| P4 | Backup rules deliberately exclude history DB + secure prefs (privacy win, data-loss tradeoff) — confirm intent and surface "history not backed up" | `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Medium | S |
| P5 | Accessibility-service label hardcoded in manifest ("Fluence On-Screen Dictation"), not in strings — localization gap | `app/src/main/AndroidManifest.xml:49` | Low | S |
| P6 | `verifyApiKey` surfaces raw `e.localizedMessage` to the UI — potential info leak of endpoint internals | `ui/SttConfigScreen.kt:153`, `ui/AgentConfigScreen.kt:415` | Low | S |
| P7 | Release build falls back to debug signing when `local.properties` lacks keystore values — silently ships a debug-signed APK. FROZEN PIPELINE — decision gate | `app/build.gradle.kts:65-69` | High | S |

## 5. Missing Features (pre-release gaps)

| ID | Finding | Severity | Cost |
|---|---|---|---|
| M1 | Onboarding / first-run checklist (enable keyboard → mic → API key → test dictation) | Critical | M |
| M2 | Keyboard-enablement row in "Permissions & Services" (currently only overlay/accessibility/battery) | High | S |
| M3 | IME settings deep link — `method.xml:3` `settingsActivity` opens the whole app (Home) | Low | S |
| M4 | History export/share (transcriptions exist only inside the app + 30-entry cap) | Medium (Future) | M |
| M5 | Snackbar in place of toasts for persistent/retryable feedback | Medium | M |
| M6 | Empty-dashboard CTA cards (e.g. "Add your API key", "Enable keyboard") instead of passive text | Medium | M |

## 6. Material 3 Compliance

- Good: M3 composables throughout; consistent dark scheme; `ModalBottomSheet`, `FilterChip`, `OutlinedTextField`, `selectable` roles.
- Gap: XML theme is `android:Theme.Material.NoActionBar` (`res/values/themes.xml:3`) — not `Theme.Material3.*`; no `dynamicColor` (Material You) support; no light theme (acceptable for this product; document it). **Medium.**

## 7. Android Best Practices

- Good: `EncryptedSharedPreferences` for API keys, Room + Flow, lifecycle/saved-state owners correctly wired on the IME (`VoiceInputIME.kt:41-53`), edge-to-edge, `FileProvider`, backup exclusions, `registerForActivityResult`.
- Gaps: dead `FocusRequester` (U7), infinite poll (U13), per-keystroke network fetch (U16), no lint baseline (P2), debug-signing fallback (P7), no Compose previews/UI tests for screens.

## 8. Testing

JVM tests exist for core logic (bubble, command, offline, update, autolearn, dictionary). **Zero UI tests, ~zero previews.** Recommend: lint gate first, then 2–3 Compose UI tests for the highest-risk flows (permissions → IME enable → dictation; settings save; dictionary CRUD). **M/L.**

---

## Prioritized Roadmap

**P0 — Ship-blocking (before release)**
1. P1 Google Fonts provider in manifest (+ runtime verify) — S
2. U10 + U11 first-run onboarding incl. working mic-permission path — M/L
3. P7 fail the build instead of debug-signing releases — decision gate (frozen pipeline)
4. U14 confirm-before-delete on detail sheet — S

**P1 — High-confidence fixes**
5. U12/U13 correct IME-active check + replace poll with listener — S/M
6. A1 TextTertiary on Dialog surfaces (swap to TextSecondary in dialog contexts) — M
7. U16 debounce model fetch — S
8. U19 `rememberSaveable` for Home state — M
9. M2 keyboard-enablement row — S
10. A6 font-scale-safe text pass on config screens — M

**P2 — Compliance/polish**
11. U5 token consolidation (incremental, not a rewrite) — L
12. U4 elevation rule, U2/U3 hero-stat labels, U8 credits — S
13. U20/M5 toast → snackbar — M
14. P2/P8 lint baseline + first UI tests — M/L
15. U15 history cap surfaced (warning/export) — M (product decision)

**P3 / Future**
16. In-app IME enable helper, M3/M4 export & deep-link, M3 dynamic color + M3 XML theme, A3 chart semantics, P4 backup messaging, full i18n — XL

---

## Implementation Plans

### P0

**0.1 — Google Fonts provider (P1) — S**
- `app/src/main/AndroidManifest.xml`: add inside `<application>`:
  ```xml
  <provider
      android:name="com.google.android.gms.fonts.provider.FontsProvider"
      android:authorities="com.google.android.gms.fonts"
      android:exported="false" />
  ```
- Verify: build + run on device; confirm Hanken Grotesk/Sora/Geist Mono render (Settings title, Home headline, mono timestamps).

**0.2 — First-run onboarding + working mic path (U10, U11) — M/L**
- Thread `onRequestPermission` into `HomeScreen` (currently dead at `Navigation.kt:41` → `MainActivity.kt:73-77`).
- Add first-launch detection (prefs flag) → setup checklist card on Home: (1) Enable keyboard → `ACTION_INPUT_METHOD_SETTINGS`, (2) Grant microphone → launcher, (3) Add API key → navigate to `SttConfig`, (4) Start dictating. Items auto-complete as conditions are met; card dismisses when all done.
- Empty-dashboard state gets a CTA (M6).

**0.3 — Confirm-before-delete in detail sheet (U14) — S**
- `TranscriptionDetailSheet.kt:153-175`: gate `repository.delete(item)` behind an `AlertDialog` (same pattern as HomeScreen).

**0.4 — Release-signing guard (P7) — documented decision**
- Recommend failing the release build when signing keys are absent; **defer change** to pipeline owner per frozen pipeline.

### P1

- **IME status accuracy (U12/U13):** replace the infinite 2s poll in `HomeScreen.kt:265-275` with a refresh on `ON_RESUME` that checks the current IME (`Settings.Secure.DEFAULT_INPUT_METHOD` package match); drop the loop.
- **Dialog contrast (A1):** swap `TextTertiary` → `TextSecondary` in dialog/sheet/menu contexts on Dialog surfaces; verify AA (TextSecondary = 5.0:1 on Dialog `#2E2E2E`).
- **Debounce model fetch (U16):** remove `fetchModelsForProvider()` from `AgentConfigScreen.kt:200`; refetch via `LaunchedEffect(apiKey)` with ~600ms debounce.
- **Rotation-safe Home state (U19):** `rememberSaveable` for `searchQuery`, `selectedIds`, `expandedGroups`, `currentSortOption` (custom list savers for Set types).
- **Keyboard-enablement row (M2):** add "Voice Typing Keyboard" row to Permissions screen opening the IME picker; status from `enabledInputMethodList`.
- **Font-scale pass (A6):** replace fixed `sp` literals with `FluenceTypography` roles in config screens + `TranscriptionDetailSheet`; add `maxLines`/`overflow`.

### P2

- **Token consolidation (U5):** incremental — prioritize `HomeScreen`, `SttConfigScreen`, `AgentConfigScreen`, `OfflineConfigScreen`, `UpdateCard`, `TranscriptionDetailSheet`; map raw `sp/dp` to existing `FluenceSpacing/Shapes/Typography`; no new tokens.
- **Micro-polish (U4, U2, U3, U8):** remove hero/chart shadows, dedupe "Typing Time Saved", reword "Clear All", fix "Precision Ink" credits.
- **Snackbar migration (U20/M5):** app-level `SnackbarHost`; convert error + destructive feedback.
- **Lint baseline + UI tests (P2/P8):** add `lint { }` + baseline; add `compose-ui-test` deps and 2–3 `createComposeRule` tests.
- **History cap surfacing (U15):** warning copy at/near cap + export entry point.

### P3 / Future

- **Export/share (M4):** share intent on detail sheet + export-all.
- **IME settings deep link (M3):** `method.xml:3` → Settings-hub deep link.
- **Material You + M3 XML theme:** `dynamicColor` support in `Theme.kt`; XML theme → `Theme.Material3.*`.
- **Chart semantics (A3):** TalkBack summary of weekly counts on `WeeklyActivityChart`.
- **Backup messaging (P4):** surface "history not backed up" in About/Settings.
- **Full i18n (XL):** extract all hardcoded strings incl. the 32 toasts and manifest labels.
