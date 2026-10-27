# Auto-Learn Suggestions Pipeline (Android)

Windows-parity port of the Flask auto-learn pipeline (Windows
`src-tauri/src/auto_learn/`); every mechanism below is a faithful port
unless marked as an Android-only divergence.

Also a known documentation gap: `AGENTS.md` references
`docs/ARCHITECTURE.md`, `docs/SYNC.md`, and `docs/RELEASE_PIPELINE.md`.
At the time of writing only `docs/RELEASE_PIPELINE.md` exists in this
repository; the two other referenced documents were never committed.
This page additionally documents the suggestion pipeline that was
previously described nowhere.

## Modules

| Mechanism | Windows source | Android implementation |
| --- | --- | --- |
| Extraction (LCS traceback, adjacency windows, ties prefer insertion) | `auto_learn/decision_core.rs::find_substitutions` | `DecisionCore.findSubstitutions` |
| Pair gates (known -> case-only -> corrected<3 -> dist/maxLen > 0.65) | `auto_learn/decision_core.rs::invalid_pair_reason` | `DecisionCore.invalidPairReason` |
| Rewrite guards (LCS coverage voted + >50% subs) | `decision_core.rs::extract_corrections` | `DecisionCore.extractCorrections` |
| Canonical pair identity | `dictionary.rs::canonical_entry_key` | `DecisionCore.canonicalPairKey` |
| Session dedup (one observation per canonical pair per session) | `auto_learn/monitor.rs` | `AutoLearnSessionManager.sessionPairKeys` |
| Settle gate + exit final diff | monitor.rs `SETTLED_POLLS` + final diff | `AutoLearnSessionManager` settle timer + `endSession` flush |
| Pending suggestions for human review | `suggestion.rs` (local JSON store; `dismiss_suggestion` keeps the row as Dismissed "deny memory"; `accept_suggestion` marks it Accepted for analytics/undo) | Room `suggestion_history` surfaced by `SuggestionRepository` (`PENDING` rows only; dismiss demotes to a sticky `DISMISSED`; accept promotes the pair to the dictionary and removes the row) |
| Stale-pending purge (re-judge all pending, demote) | `suggestion.rs::purge_invalid_suggestions` | `SuggestionRepository.purgeInvalidPendingResolved` |
| Session manager (arm, supersede, origin close, timeout, per-update revalidation) | `auto_learn/monitor.rs:234-376` | `AutoLearnSessionManager` |
| Shared field gate (surface-at-rest, one implementation) | monitor field policy + IMF contract | `AutoLearnFieldSnapshot` (`isObservationAllowedRest` / `isObservationAllowedForBubble` / `fromEditorInfo` / `fromAccessibilityNode`) |
| Bubble arming AFTER confirmed delivery (read-back) | monitor.rs `INITIAL_READ`/`InjectedTextNotObserved` parity | `BubbleController.initiateAutoLearnArmConfirm` → `AutoLearnSessionManager.armBubbleSession` |
| Span-anchored extraction (full-field diff safety) | `correction_extractor.rs::extract_user_corrections` / `extract_edited_span` | `DecisionCore.extractUserCorrections` |
| Accessibility observation feed (per-update evidence) | monitor event model (per-read parity) | `FluenceAccessibilityService.feedBubbleAutoLearnObservation` → `AutoLearnSessionManager.onTextUpdated` |

## Surface-at-rest gate

`DecisionCore.invalidPairReason` walks, in this exact order:

1. `already in dictionary` - pair key in the account's enabled
   dictionary (canonical, case-folded; `SuggestionRepository.knownPairKeys`
   computes this per batch).
2. `case-only difference` - lowercase-equal pair.
3. `corrected shorter than 3 chars`.
4. `below similarity floor` - `editDistance / maxLen > 0.65`
   (similarity floor 0.35).

Pairs below `SIMILARITY_FLOOR` never surface; owner-accepted boundary
residuals like `cool -> Good` (0.5) stay observable.

## Extraction invariants

- tokenizer strips edge punctuation and compares words case-insensitively
  (`Locale.ROOT` lowercasing, matching Windows `to_lowercase()`).
- substitution pairs form ONLY from consecutive `[orig, null] + [null, edited]`
  alignment windows (Java legacy rule paired two bare differing words).
- rewrite veto #1: `lcsLength * 2 < origWords.size` (RC-5).
- rewrite veto #2: `subs.size * 2 > origWords.size` (>50% words changed).

## Bubble dictation coverage

The floating-pill surface learns through the same session manager, with
surface-specific arming and feeding. Desktop-parity anchors
(`monitor.rs`): the baseline is the FULL post-injection field snapshot;
arming happens only AFTER delivery is confirmed (a refreshed read that
contains the exact injected text, polled `INITIAL_READ`-style 4 x
250 ms, fail-closed on a miss — a missed learning opportunity is always
preferred over an unverified one); each regular close (bubble hide,
timeout, focus loss after settle) runs the exit final diff; security
exits are revocation-first and never flush.

Flow: injection (`injectText`) confirms delivery → `armBubbleSession`
(field snapshot, window, injection, confirmed baseline) → accessibility
`TYPE_VIEW_TEXT_CHANGED` events whose source matches the anchor (same
window AND package, `isAutoLearnAnchorFeed`) feed `onTextUpdated`
(full text + current snapshot) → settle gate classifies the anchored
span diff → regular close flushes the un-settled state once.

Security posture:

- ONE choke point in `BubbleController.performAllowedAction` rejects
  every accessibility write into a secure field (password/className
  class hints) and revokes any live session first (revocation-first,
  never flush); `suppressForPrivacy` does the same for excluded targets.
- Every per-update feed runs the positive-evidence preamble: password
  field, excluded package, package drift, or asserted null-input-type /
  no-personalized-learning evidence ends the session as a
  security-equivalent exit (no final diff) regardless of surface.
- Lifecycle closes are surface-scoped: a bubble hide tears down only a
  bubble-origin session (`endBubbleSessionIfAny`); IME-origin sessions
  must be closed by the IME lifecycle (AOSP finishes views before the
  next target starts, so a cheaper surface cannot know the next field's
  posture). The IME fail-closes on an unknown/blank target package.

## Span-anchored extraction

`DecisionCore.extractUserCorrections` (Windows
`extract_user_corrections`): the injection anchors its span in the
baseline snapshot; only the current value's counterpart between the
unchanged head/tail affixes is diffed. Absent, repeated, or
head/tail-broken anchors learn NOTHING — safer than diffing the whole
field, which misaligns across sentences (`or -> Message` class). The
decision core then runs the ordinary extractors/guards over
`(injection, span)`. Bubble sessions always extract anchored; IME
sessions keep the whole-region diff exactly as verified on the
suggestion-pipeline branch (injection null there).

## Android-only divergences (intentional)

- `MAX_TEXT_CHARS = 10_000`, `MAX_WORDS = 1_000` caps reused from the
  legacy extractor; Windows applies no caps at this layer.
- Tokenizer splitting compiles `\s+` with
  `Pattern.UNICODE_CHARACTER_CLASS` (closest JVM approximation of Rust
  `split_whitespace`, so NBSP splits); edge stripping uses
  `Character.isLetterOrDigit` as the approximation of Rust
  `is_alphanumeric`, and lengths count UTF-16 units where Rust counts
  Unicode chars. Documented BMP assumption for this app's text.
- `getPendingSuggestions` filters `frequency >= 2`
  (`MIN_OBSERVATIONS_TO_SURFACE = 2` parity); upsert conflict bumps
  frequency and keeps the lifecycle status (never resurrects dismissed).
- Stale rows are demoted (`status = 'DISMISSED'`) not deleted, so
  regression analysis survives; user Dismiss demotes too (deny-memory
  parity with `dismiss_suggestion`, suggestion.rs:635-650) so the pair
  cannot resurface as pending later. Accepted rows are removed after
  the dictionary promotion because the known-gate makes re-capture
  impossible.
- Settle gate (monitor.rs `SETTLED_POLLS` parity, event-model
  translation): classification runs only after the text stopped
  changing for 2 x 250 ms; a regular session close with un-settled
  observed input runs Windows' final diff (FocusChanged/Timeout
  parity), while privacy/security exits skip it (SecureField/
  ReadOnly parity); the flush dedups against the closed session's
  recorded pairs (monitor.rs:526-529) and is detached from the next
  session's dedup set. An empty read cancels the armed observation
  (NoValue parity, monitor.rs:466-478) so a close right after a field
  clear flushes nothing, and the settle epoch is revalidated after
  the dictionary lookup and extraction (which are not interruption
  points) so a newer keystroke's arm is never recorded stale.
- Bubble-surface field gate (`Coverage A` owner decision): the
  accessibility surface can never obtain the IMF-contract metadata
  (trusting `inputType`/`imeOptions` over nodes is untrustworthy —
  WebView/Compose report `TYPE_NULL`/0 — and `imeOptions` is not
  exposed). The bubble gate therefore DENIES positive password
  evidence, asserted null types, asserted no-learning, blank/unknown
  packages and out-of-scope fields, but tolerates the two unknowable
  IMF members staying unknown; the IME gate still requires all three
  concretely false (behavior-identical to the verified EditorInfo
  chain, `AutoLearnFieldGateTest`).
- Bubble injections carry the autospace trailing space; the baseline
  is stored trimmed, so the injection is trimmed symmetrically and the
  anchor stays able to find its own injection.
- Windows-verbatim verdict kept: a single-token injection corrected
  inside pre-existing context anchors fine, but its span diff has LCS
  0, so the Fluence rewrite guard vetoes it and nothing is learned
  (multi-token dictations learn normally). Pinned in
  `DecisionCoreTest.anchoredSingleTokenAfterContextVetoedByRewriteGuard`.
- `MAX_FIELD_CHARS` (10,000) caps observed arms and feeds per session,
  mirroring the Windows monitor's full-value cap beyond which the
  session stops.
- Finding deferred by owner decision: the canonical fold is not a
  single transaction (exact lookup + twin lookup + upsert); at this
  table size the rare concurrent-twin race is accepted.
- Purge runs on application start AND on DictionaryScreen open; both on
  Dispatchers.IO and fail-open (sweep errors log + continue).
- Accept requires a per-row confirm dialog; the exact Windows copy is
  `Add "<spoken>" → "<corrected>" to the dictionary? This will
  automatically rewrite future dictations.` and the row id is
  revalidated against the live list.

## Test fixtures

`DecisionCoreTest` transliterates the portable Windows `decision_core.rs`
fixtures (tokenize, gates incl. the do/add known-gate ordering proof,
rewrite vetoes, RC-1 boundary-pair shape, quoted-token behavior,
pair-level dedup) plus Android-only cap fixtures.
`AutoLearnSessionManagerTest` covers the session dedup seam, the
settle-gate burst collapse, the exit-diff flush, the privacy-revocation
suppression, the blank-field pending cancel, the stale-extraction epoch
abort and the fail-open recording seams; it also covers the bubble
session lifecycle (arm-after-confirm, cross-surface takeovers, origin
close semantics, timeout flush, anchored extraction end to end).
`AutoLearnFieldGateTest` proves the shared field decision against a
verbatim legacy reference chain and the failure-closed node adapter;
`BubbleControllerAutoLearnTest` covers the controller pipeline
(secure-write refusal, read-back arming, lifecycle closes,
revision-agnostic masking) against the same session manager;
`FluenceAccessibilityServiceTest` covers the pure bubble gates and the
origin-aware feed matching. `SuggestionRepositoryTest` covers the
scheduler-free DAO cores with a mocked DAO (this repo's injectable-seam
convention, same as `DictionaryRepository.saveEntryResolved` tests).

Room SQL semantics: the `@Query` strings are compile-validated by the
Room/KAPT compiler only. The seam tests use mocked DAOs, so they never
execute SQL; runtime SQL behavior is verified manually via release QA
on-device alone. This repository has no instrumentation test suite, so
no in-memory Room test lives here yet.
