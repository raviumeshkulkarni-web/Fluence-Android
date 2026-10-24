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
| Humans review passing rows | MySQL "Suggestions" table | Room `suggestion_history` (`PENDING`, accepted/dismissed removed) |
| Stale-pending purge (re-judge all pending, demote) | `suggestion.rs::purge_invalid_suggestions` | `SuggestionRepository.purgeInvalidPendingResolved` |

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
  regression analysis survives; user Dismiss still deletes them.
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
`AutoLearnSessionManagerTest` covers the session dedup seam and the
stale-job generation seam; `SuggestionRepositoryTest` covers the
scheduler-free DAO cores with a mocked DAO (this repo's injectable-seam
convention, same as `DictionaryRepository.saveEntryResolved` tests).

Room SQL semantics: the `@Query` strings are validated by the Room/KAPT
compiler at build time. Runtime SQL behavior is exercised through the
seam tests against mocked DAOs and through release QA on-device; this
repository has no instrumentation test suite, so no in-memory Room test
lives here yet.
