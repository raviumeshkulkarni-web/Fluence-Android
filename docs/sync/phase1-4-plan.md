# Fluence Transcribe — Phase 1–4 Implementation Plan

**Status: REVISED** · Supersedes the earlier conversational plan.
Contract: `docs/sync/spec.md` (Spec v2). This plan is the mapping of Phases 1–4
(Windows DB migration, Windows pure engine, Android Room 4→5 + cap removal,
Android pure engine) onto the actual codebases. **No files were modified while
producing this plan.**

Protocol is NOT redesigned. §20 "DO-NOT-VIOLATE" is a hard constraint. The
revisions below (R1–R6) were mandated by review after the initial plan.

---

## 0. Spec-conflict verification (requested checks)

**Check 1 — Android 50-record cleanup: CONFLICT, confirmed.**
`HistoryRepository.save()` calls `cleanupToNewest(50)` (HistoryRepository.kt:52)
which invokes `deleteAllExceptNewest` (TranscriptionHistoryDao.kt:32-33),
hard-deleting every row outside the newest 50 after each save. Contradicts §20
"no restoring the Android 50-cap". Must be removed in Phase 3. Only caller is
`HistoryRepository.cleanupToNewest` (HistoryRepository.kt:69-71).

**Check 2 — Existing deletion semantics: CONFLICT with §14, confirmed (both).**
- Windows: `delete_history_entry` hard-DELETEs a row (history.rs:184-192);
  `clear_history` hard-DELETEs all (history.rs:194-203). §14 requires:
  `server_file_id IS NULL` → hard delete; uploaded → tombstone (`deleted_at` set,
  `clean→dirty`); already tombstoned → no-op.
- Android: `dao.delete` (DetailSheet:187), `dao.deleteByIds` (HomeScreen:607),
  `dao.deleteAll` (HomeScreen:628) all hard-delete. Same §14 conversion.
- Until an upload path exists (Phase 5) `server_file_id` is always NULL, so the
  converted helpers reduce to today's behavior — safe and deterministic.

**Check 3 — Windows/Android schema asymmetry: RESOLVED by spec, GAP fixed.**
- Windows: `char_count` (Windows-only) + RFC3339 `timestamp` TEXT, no
  `model`/`language` (history.rs:14-22). Android: epoch-ms `timestamp`, has
  `model`/`language`, no `char_count`. Spec §2/§5 resolves this: Windows adds
  `model`/`language`; `char_count` stays Windows-derived.
- **GAP (revision R1):** Android `model`/`language` were non-null `String`
  (TranscriptionEntry.kt:11-12) while the wire allows `null` (spec §4) and
  Windows-created records emit `model: null`. Resolution: **make the Android
  columns nullable** via a non-destructive table rebuild in `MIGRATION_4_5`.

**Check 4 — Migration/versioning: CONFLICT on Windows, compatible on Android.**
- Windows: `init_db` uses `CREATE TABLE IF NOT EXISTS` with **no
  `PRAGMA user_version`** (history.rs:33-58). The version guard must be
  introduced (Phase 1).
- Android: Room version 4 (FluenceDatabase.kt:16), migrations 1→4 registered
  (FluenceDatabase.kt:101), **no destructive fallback** (confirmed). Add
  version 5 + `MIGRATION_4_5`.

**Verified facts (no conflict):**
- Android already depends on **WorkManager (`work-runtime-ktx` 2.9.0) and okhttp
  4.12.0** (app/build.gradle.kts:116,121; libs.versions.toml:11,32) — no new
  main dependencies for Phases 6/8.
- `androidx.security.crypto` present (EncryptedSharedPreferences, Phase 6).
- `org.json:json:20231013` already a unit-test dependency (app/build.gradle.kts:137);
  real `org.json` already exercised in JVM tests (`MistralVoxtralTranscriberTest`)
  and used in production across 5+ modules. Phase 4 needs **no new dependency**.
- Android write sites confirmed: TranscriptionSessionManager.kt:615 (offline)
  and :770 (online) pass raw `durationMs`, `model`, `language`, `isAgentMode`.
  Wire `duration_ms` must read raw `entry.durationMs`, never
  `StatsCalculator.effectiveDurationMs` (HomeScreen.kt:100-101 is display-only).
- **No production code reads `entry.model`/`entry.language`** (grep over
  `app/src/main` found only `Locale.getDefault().language`, unrelated) → making
  the Android columns nullable is a **zero call-site ripple** change.
- `TranscriptionEntry` has no `@Index`; only the implicit `AUTOINCREMENT` PK →
  the rebuild re-creates exactly one index (`syncId` unique).
- `isAgentMode` maps to wire `mode` `"transcription"|"agent"` (fixture `...004`).
- `save_history_entry` has no Rust callers besides the command; frontend callers
  overlay.js:540,653. `history-updated` events untouched.
- `capabilities/default.json` needs no change for Phases 1–4.
- No `androidTest` dir and no `app/schemas/*.json` exist today;
  `exportSchema=false` (FluenceDatabase.kt:17). Affects the Phase 3 migration test.

---

## R. Revision record (deltas applied)

- **R1 — Nullability.** Rejected the proposed `null ≡ ""` equivalence. `T` uses
  **exact field equality** (§9, §20); `null` and `""` remain distinct. Android
  `model`/`language` become nullable via a non-destructive table rebuild in
  `MIGRATION_4_5`. No canonicalization anywhere; both platforms store and emit
  wire values verbatim. **Spec-text delta (mandated):** §25's Android
  "`ALTER TABLE ADD COLUMN` per new column" is superseded by the rebuild; the
  rebuild is non-destructive and covers the same version hop 4→5.
- **R2 — Schema counts corrected.** Windows: 7 existing + 8 = **15 columns**.
  Android: 8 existing + 6 = **14 columns**. All DDL, `PRAGMA table_info`
  assertions, and tests updated.
- **R3 — Renamed-file test corrected.** Replaced
  `renamed_file_is_inert_until_renamed_back` with
  `renamed_file_counts_as_absent_reupload_left_untouched`, matching §10/§15:
  renamed file is absent from the `trashed=false` listing → confirmed by one
  fresh re-list → re-upload current record under `<uuid>.json`; the renamed file
  is never deleted or modified.
- **R4 — Phase 4 JSON verified.** `org.json` is JVM-test-viable with **zero new
  dependency** (real `org.json:json:20231013` already shadows the stubbed
  `android.jar` in unit tests, proven by `MistralVoxtralTranscriberTest`).
  Engine is JSON-free; only the codec uses JSON. org.json gotchas encoded
  identically to `serde_json` (see Phase 4).
- **R5 — Full re-check against spec §4–§29.** See §0 table below.
- **R6 — No file was modified during planning.**

### R5 re-check vs spec §4–§29

| Spec concern | Status after revisions |
|---|---|
| §9/§20 exact `T` equality | Restored to exact. No equivalence; `null` and `""` distinct; both platforms store/emit wire values verbatim. |
| §4 nullable `model`/`language` | Honored — Android columns nullable (rebuild); Windows already nullable. |
| §5 column lists | Windows 7+8=**15**; Android 8+6=**14** (plus R1 nullability, same migration). |
| §6 `sync_state` | Only the §6 table drives transitions; `syncState`/`sync_state` default `'local'`; debug-assert derived==persisted. Unchanged. |
| §7 stages/traits | Unchanged. Per-row tx, one mutex per device, full re-scan, no cursor. |
| §10 absence | Unchanged; confirmed only by "not in listing after one re-list"; re-upload reproduces exact `T`; absence never deletes/tombstones. |
| §11 tombstones | Unchanged — PATCH full record (`T` + `deleted_at`) to every listed live file; tombstone-wins; no GC. |
| §12 quarantine | Unchanged — per-UUID latch, user-resolve only, offending files untouched. |
| §13 account scope | Unchanged — query-layer stamp filter `syncAccount ∈ {null, active}`; foreign rows byte-untouched. |
| §14 local deletion | Unchanged — NULL `server_file_id` hard-delete; else tombstone; idempotent. |
| §16 `server_file_id` | Unchanged — sticky, never cleared, set atomically on create/re-upload. |
| §20 DO-NOT-VIOLATE | No new mechanisms introduced. |
| §21/§22 mappings | Updated: Android entity/model/language nullable; counts corrected. |
| §25 migrations | Windows `user_version` 0→1 unchanged. Android `MIGRATION_4_5` now a rebuild (documented delta, R1). |
| §26 test list | Renamed-file test corrected (R3); migration tests updated (15/14 cols, nullability, reopen-at-v5). |
| §27 phases | Structure unchanged. |

---

## PHASE 1 — Windows DB migration (additive, `user_version` 0→1)

**Files: `src-tauri/src/history.rs`, `src-tauri/src/settings.rs`.**
`main.rs` / `Cargo.toml` / `capabilities/default.json`: no required change.

### history.rs — exact changes
| Change | Existing code | New code |
|---|---|---|
| Extend struct | `HistoryEntry` (13-22) | add `pub model: Option<String>`, `pub language: Option<String>` |
| Migration guard | `init_db()` (33-58), `execute_batch(CREATE TABLE IF NOT EXISTS ...)` | Refactor to `fn run_migration(conn: &Connection) -> Result<()>` (takes `&Connection` for in-memory tests). Fresh DB: create the **15-column** v2 schema. Existing DB (`PRAGMA user_version = 0`): one tx → `ALTER TABLE ADD COLUMN` × 8 → backfill `timestamp_ms` in Rust from `timestamp` via `chrono::DateTime::parse_from_rfc3339` (fallback 0) → `PRAGMA user_version = 1`. Error: rollback, `log::error!`, `static MIGRATION_OK: AtomicBool = false` |
| Row mapping | `map_row` (157-167) | read `model`(8) / `language`(9) as `Option<String>`; all SELECTs keep a consistent column order |
| Insert | `add_history_entry` (83-117) | `INSERT` adds `timestamp_ms` = `Utc::now().timestamp_millis()`; `model`/`language` NULL; `sync_state` defaults `'local'` |
| List | `get_history` (121-155) | `WHERE deleted_at IS NULL`; `ORDER BY timestamp_ms DESC` (both branches) |
| Stats | `get_history_stats` (205-230) | `WHERE deleted_at IS NULL` on all three aggregations |
| Weekly | `get_weekly_activity` (232-244) | add `AND deleted_at IS NULL` |
| Delete | `delete_history_entry` (183-192) | new `pub(crate) fn delete_history_by_id(id)`: tx → read `server_file_id`; NULL → hard `DELETE`; set → `UPDATE deleted_at=now, sync_state='dirty'`; emit `history-updated`. Wrap in new `static LOCAL_MUTATION_MUTEX: Mutex<()>` (Phase 7 seam) |
| Clear | `clear_history` (194-203) | one tx: `DELETE FROM history WHERE server_file_id IS NULL`; `UPDATE history SET deleted_at=now, sync_state='dirty' WHERE server_file_id IS NOT NULL AND deleted_at IS NULL`; emit event |
| Tests | none today | `#[cfg(test)] mod tests` (layer 3, in-memory `Connection`) — see tests below |

### settings.rs — exact changes
`AppSettings` (46-91): add `#[serde(default)] pub sync_enabled: bool`
(default `false`), `#[serde(default)] pub sync_account_key: Option<String>`
(default `None`); wire into `Default` (153-179). Old `settings.json` deserializes
untouched. No command changes.

### Migration impact
`%LOCALAPPDATA%\Fluence\history.db` gains 8 columns → **15 total**,
`user_version` 0→1, `timestamp_ms` backfilled (0 fallback sorts last).
**No existing row deleted or rewritten.** Failure → rollback, log,
`MIGRATION_OK=false`, reads serve as today (sync-disabled is the default).

### Tests to add (layer 3)
`old_db_gets_fifteen_columns`, `timestamp_ms_backfilled_from_rfc3339`,
`rfc3339_parse_failure_falls_back_zero`, `existing_rows_unchanged`,
`user_version_set_to_1`, `failed_migration_rolls_back_and_disables_sync`, plus
§14 delete-rule unit tests.

### Acceptance criteria
`npm run check` green; `cargo test` green; `npm run clippy` clean; old DB
upgrades with data intact; sync-disabled path **byte-identical** to today;
`get_history` ordered by `timestamp_ms`. Manual: copy a pre-migration
`history.db`, upgrade, diff `get_history`/`get_history_stats`/
`get_weekly_activity` JSON against pre-upgrade output.

### Rollback/safety
Additive, version-guarded; `user_version` stays 0 on failure → retried next
launch; back up `history.db` before manual upgrade verification; `deleted_at`/
`sync_state` mutations are inert until `server_file_id` can be set (Phase 5).

---

## PHASE 2 — Windows pure sync engine (no Drive, no Auth, no scheduler)

**Files: new `src-tauri/src/sync/mod.rs`, `sync/wire.rs`, `sync/engine.rs`;
`lib.rs` + `main.rs` add `mod sync;` only (no commands).**
`Cargo.toml`: no changes (chrono, uuid, serde_json, rusqlite already present).

### sync/wire.rs — wire codec (spec §4, fixtures in `examples/sync/`)
- `WireRecord { v, id, created_at, deleted_at, text, mode, duration_ms, provider,
  model, language }` + serde_json compact serialize.
- `parse(bytes, basename) -> Result<WireRecord, InvalidReason>`: `v==1`, id is
  lowercase UUID v4 equal to basename, `created_at > 0`, `deleted_at` null-or-
  positive, mode ∈ {transcription, agent}, ints are ints.
- Content tuple `T` + `tuples_equal`: **exact field equality** — no
  equivalence, no canonicalization (R1).
- `tombstone(record, deleted_at)` — full record (same `T`) with `deleted_at` set
  (matches fixture `...003`).
- Tests (layer 1): `fresh_android_record_roundtrip`,
  `minimal_windows_record_roundtrip`, `tombstone_roundtrip`,
  `agent_mode_roundtrip`, `malformed_json_rejected`,
  `unknown_schema_version_rejected`, `filename_id_mismatch_rejected`,
  `negative_deleted_at_rejected`, `null_model_roundtrips_as_null`,
  `empty_string_model_distinct_from_null`.

### sync/engine.rs — pure reconciliation (spec §7)
- Types: `LocalRow`, `GroupedFile`, `GroupVerdict { ABSENT, HEALTHY_LIVE,
  HEALTHY_DELETED, DIVERGENT }`, `QuarantineReason` (5 values), `SyncAction`,
  `SyncOutcome`, `SyncError { Retryable, Fatal, AuthRequired }`.
- Traits per §7: `LocalStore` (`listRows(stamp)`, `findRow(uuid)`,
  `import(row)`, `markTombstoned`, `setServerFileId`, `setSyncState`,
  `quarantine`, `clearQuarantine`, `hardDelete`; >1-column ops are one tx),
  `DriveStore` (`findOrCreateFolder`, `listFiles` paginated `trashed=false`,
  `getContent`, `createFile`, `updateContent` tombstone-only).
- `run(account)`: stages PREFLIGHT→FINALIZE per §7; **absence per §10**
  (candidates → one fresh re-list → confirmed → re-upload exact `T` or tombstone;
  never delete/tombstone on absence); **tombstone-wins per §11** (DELETED group →
  live local row with `T` agreement converts to tombstone in RECONCILE before
  PUSH; disagreement → quarantine; unknown-UUID DELETED group → import
  placeholder tombstone); **quarantine latch per §12** (skip latched groups;
  `clearQuarantine` via user resolve only); **hard-delete of never-uploaded
  deleted rows** (§14 defensive); **`sync_state` only via the §6 table** +
  `debug_assert!(derived == persisted)` after each pass; per-row txs; full
  re-scan; no cursor.
- Tests (layers 2 + 5, `FakeLocalStore` + `FakeDrive`): the 12 layer-2 names
  plus layer-5 names: `post_timeout_creates_duplicate_identical_file`,
  `crash_halfway_through_100_duplicate_tombstones_completes_next_pass`,
  `remote_tombstone_deleted_reuploaded_by_tombstone_holder`,
  `local_tombstone_destroyed_boundary_documented`,
  `entire_folder_deleted_all_records_restored`,
  `drive_list_lag_absorbed_by_duplicate_identical`,
  `folder_recreation_restores_records`,
  `renamed_file_counts_as_absent_reupload_left_untouched` (R3),
  `restored_trash_file_joins_healthy_group`, plus
  `sync_state_equals_derived` invariant per pass.

### Renamed-file test (R3, layer 5)
`renamed_file_counts_as_absent_reupload_left_untouched`:
1. Seed: row `X`, `server_file_id = F1`, `F1` present as `X.json`, exact `T`.
2. FakeDrive renames `F1` → `X-copy.json` (same file_id, new name; absent from
   the `trashed=false` listing for `X`).
3. Run pass: `LIST` → `F1` not listed → absence candidate → fresh re-list →
   still absent → confirmed → re-upload `X.json` (createFile) with exact `T` →
   `server_file_id = F2`.
4. Assert: `F2` exists as `X.json` with exact `T`; `F1` still exists, content and
   modified-time unchanged; no row deleted, no tombstone, no quarantine; single
   row, no winner.

### Migration impact / deps / acceptance / rollback
No DB or config change; no new crates. Acceptance: `cargo test` green with zero
real Drive/Auth code; `npm run clippy` clean; same fixture corpus → same outcome
(determinism). Rollback: module reachable only from tests; drop `mod sync;` and
nothing changes at runtime.

---

## PHASE 3 — Android Room 4→5, cap removal, soft delete

**Files: `TranscriptionEntry.kt`, `FluenceDatabase.kt`,
`TranscriptionHistoryDao.kt`, `HistoryRepository.kt`,
`app/build.gradle.kts` + `gradle/libs.versions.toml` (androidTest deps only).**

### TranscriptionEntry.kt
- **R1:** `model: String? = null`, `language: String? = null` (nullable).
- Add 6 columns per §5: `syncId: String? = null`, `deletedAt: Long? = null`,
  `@ColumnInfo(defaultValue = "local") syncState: String = "local"`,
  `serverFileId: String? = null`, `syncAccount: String? = null`,
  `quarantineReason: String? = null`.
- **14 columns total** (R2). Long `id` remains local PK, never synced.
- Zero production call-site changes (verified — nothing reads `.model`/
  `.language`; `save()` passes non-null Strings).

### FluenceDatabase.kt
`version = 5`; `MIGRATION_4_5` (**table rebuild**, R1) — one transaction, no FKs
on this table so no `PRAGMA foreign_keys` handling needed:
1. `CREATE TABLE transcription_history_new` — exact **v5 expected DDL** (must
   match Room's runtime schema validation):
   `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, provider
   TEXT NOT NULL, model TEXT, language TEXT, durationMs INTEGER NOT NULL,
   isAgentMode INTEGER NOT NULL, timestamp INTEGER NOT NULL, syncId TEXT,
   deletedAt INTEGER, syncState TEXT NOT NULL DEFAULT 'local', serverFileId
   TEXT, syncAccount TEXT, quarantineReason TEXT`
2. `INSERT INTO transcription_history_new (id, text, provider, model, language,
   durationMs, isAgentMode, timestamp, syncId, deletedAt, syncState,
   serverFileId, syncAccount, quarantineReason) SELECT id, text, provider,
   model, language, durationMs, isAgentMode, timestamp, NULL, NULL, 'local',
   NULL, NULL, NULL FROM transcription_history` — **existing rows copied
   verbatim** (v4 rows never contain NULL `model`/`language`).
3. `DROP TABLE transcription_history`.
4. `ALTER TABLE transcription_history_new RENAME TO transcription_history`.
5. `CREATE UNIQUE INDEX IF NOT EXISTS index_transcription_history_syncId ON
   transcription_history(syncId)` (SQLite allows multiple NULLs).
Register on `.addMigrations(...)` (101). **No destructive fallback** (preserve).
Do NOT generate `syncId` in migration (§25). `exportSchema` stays `false`.

### TranscriptionHistoryDao.kt
- **Remove** `deleteAllExceptNewest` (32-33).
- Filter tombstones from user queries: `getAll` (11), `search` (17), `getById`
  (14), `getCount` (35) gain `WHERE deletedAt IS NULL` (getById also filters so
  a tombstoned item can't linger in an open detail sheet).
- **Add** engine primitives: `getSyncRows(stamp)`
  (`WHERE syncAccount IS NULL OR syncAccount = :stamp`), `getBySyncId(syncId)`,
  `markTombstonedById(id, deletedAt, syncState='dirty')`,
  `markTombstonedBySyncId`, `quarantineBySyncId`, `clearQuarantineBySyncId`,
  `setServerFileIdAndStateBySyncId`, `hardDeleteBySyncId`,
  `@Update update(entry)` (import upsert-by-syncId: `getBySyncId` →
  insert-or-update preserving Long id). Import/update carry
  `model: String?` / `language: String?` through to/from the wire — no mapping,
  no sentinel (R1).

### HistoryRepository.kt
- **Remove** `cleanupToNewest(50)` call (52-55) and the function (69-71).
- `save()` (29-56): unchanged semantics — insert-only, stats increment in same
  tx, `syncId` null (lazy at first sync).
- `delete(entry)` (58): sync-aware — `serverFileId == null` → `dao.delete`
  (hard); else `markTombstonedById` (`deletedAt=now`, `syncState='dirty'`).
- `deleteByIds` (60-63): one tx — fetch rows, split NULL-vs-set `serverFileId`,
  hard-delete unsynced / tombstone uploaded.
- `clearAll` (65-67): one tx — same split across all rows.
- All in `db.withTransaction` (one tx per user action; store-level per-row tx).

### Migration impact
Room 4→5, **14 columns**, `syncState` defaults `'local'`, all existing rows
preserved exactly, `syncId` NULL. `stats_daily` untouched.

### Tests to add (layer 4)
New `app/src/androidTest/java/com/groq/voicetyper/history/Migration45Test.kt`
(hand-rolled, since `exportSchema=false` blocks `MigrationTestHelper`):
build a v4 `SupportSQLiteDatabase` with all four tables' DDL (derived from
`MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4` + the entity DDL), insert rows
in all four tables, `PRAGMA user_version=4`, close; reopen the real
`FluenceDatabase` (v5, migrations registered) and assert via DAOs:
`migration_4_5_keeps_all_rows` (all rows + values intact across all tables),
`migration_4_5_does_not_generate_sync_id`, `new_columns_null_for_existing_rows`
(syncId/deletedAt/serverFileId/syncAccount/quarantineReason NULL;
syncState='local'), `model_language_now_nullable` (insert NULL model/language at
v5 succeeds), `unique_index_allows_multiple_null_sync_ids` (two NULL syncIds OK;
two same non-null syncId → conflict), `room_opens_cleanly_at_version_5` (Room
schema validation passes on reopen). JVM tests (mocked DAO):
`delete_unsynced_hard`, `delete_synced_tombstones`, `clearAll_splits`,
`getAll_hides_tombstones`.
**New androidTest dependencies (smallest addition):** `androidx.test.ext:junit`
1.1.5 + `androidx.test:core` in `libs.versions.toml`/`build.gradle.kts`. First
instrumentation test in the repo; requires an emulator/device (AGENTS.md).

### Acceptance criteria
Unit + instrumentation tests green; save >50 records → all persist (cap gone);
never-uploaded delete hard-deletes, uploaded delete tombstones; all user queries
hide `deletedAt != null`; migration non-destructive on a real v4 backup; Room
opens cleanly at v5.

### Rollback/safety
Non-destructive single-version-hop migration (4→5). **Release gating:** cap
removal + soft delete + schema ship in the same release as sync enablement
(§29 blocker #3a) — never cap-removal alone in a sync-less release.

---

## PHASE 4 — Android pure sync engine

**Files: new package `com.groq.voicetyper.sync` — `wire/WireRecord.kt`,
`engine/EngineTypes.kt`, `engine/LocalStore.kt`, `engine/DriveStore.kt`,
`engine/SyncEngine.kt`; tests under
`app/src/test/java/com/groq/voicetyper/sync/`; fixtures copied from
`examples/sync/*.json` into `app/src/test/resources/sync/`.**
`build.gradle.kts`: **no new dependencies** (R4).

- `WireRecord.kt`: `org.json` codec, **identical rules to `sync/wire.rs`** (§4
  validation, exact `T` equality, tombstone builder). org.json rules encoded to
  match serde_json semantics exactly (R4):
  - Emit explicit nulls with `JSONObject.NULL` so `"model":null` appears (a bare
    `put(key, null)` deletes the key).
  - Parse nulls via presence + null check: `has(key) && !isNull(key)` before
    `optString(...)`; never call `optString` on a `JSONObject.NULL` value
    (returns literal `"null"`).
  - Tests compare parsed field values, not raw bytes (org.json does not
    guarantee key order).
- `EngineTypes.kt`: `LocalRow`, `GroupVerdict`, `QuarantineReason` (same 5
  values), `SyncAction`, `SyncOutcome`, `SyncError` sealed class.
- `LocalStore.kt` / `DriveStore.kt`: interfaces mirroring the Rust traits (§7
  method list; >1-column ops one tx).
- `SyncEngine.kt`: stages PREFLIGHT→FINALIZE, identical algorithm — absence via
  re-list confirmation (§10), tombstone-wins + placeholder tombstones (§11),
  quarantine latch (§12), hard-delete never-uploaded deleted rows (§14),
  `syncState` only via §6, debug-assert derived==persisted, per-row tx, full
  re-scan. Pure Kotlin, **zero Android-framework imports**, **zero JSON**
  (only the codec touches JSON) → plain JVM unit tests.
- Tests: layer 2 + layer 5 scenarios on the **same fixture corpus** as Phase 2,
  including `renamed_file_counts_as_absent_reupload_left_untouched` (R3),
  `null_model_roundtrips_as_null`, `empty_string_model_distinct_from_null`, and
  the `isAgentMode`↔`mode` mapping cross-checks.

### Migration impact / acceptance / rollback
None (pure code). Acceptance: `./gradlew test` green; scenario list identical to
Phase 2; deterministic. Rollback: package unreferenced by any Activity/
Repository; safe to remove.

---

## Dependency-ordered execution plan

```
T0  [immediately]  Phases 1 + 3 in parallel   ── Windows migration            (independent)
                 └── Android Room 4→5 + cap removal + soft delete            (independent)
T1  [after T0]     Phase 2 (Windows engine)   ── may start at T0; finalize fixtures first
T2  [after T0]     Phase 4 (Android engine)   ── consumes shared fixture corpus + exact-equality rule
T3  [gate, §29]    Experiments 1 & 4 (GCP)    → unblock Phases 5/6 (out of scope here)
T4  [release gate] Product decision: cap removal ships same release as sync → then Phases 7–10
```
No phase below depends on the gating experiments; only Phases 5/6 do.
Phases 1–4 may be implemented now.

---

## Remaining blockers / decisions

1. **`MIGRATION_4_5` rebuild shape — recorded decision (R1).** Nullable Android
   `model`/`language` via table rebuild. Documented delta to §25's add-column
   wording; **not** a protocol change. Room's runtime schema check requires the
   rebuilt DDL (incl. `DEFAULT 'local'`, `AUTOINCREMENT` PK, `syncId` unique
   index) to match the v5 entity exactly — enforced by
   `room_opens_cleanly_at_version_5`.
2. **Android instrumentation-test infrastructure (blocker for test
   execution).** First `androidTest` in the repo; requires `androidx.test.ext:
   junit` + `androidx.test:core` and a hand-rolled four-table v4 SQL fixture (no
   schema export exists); runs on emulator/device per AGENTS.md. JVM tests
   cannot exercise Room.
3. **§29 blocker #3a — product decision, RESOLVED (2026-08-15):** Android 50-cap
   removal ships only in the same release as sync enablement. Never cap-removal
   alone.
4. **§29 blocker #3b — product decision, RESOLVED (2026-08-15):** foreign-account
   rows are **read-only with an ownership indicator** (account badge, edit/delete
   disabled) — best UX practice; hidden rows rejected. Protocol treats them as
   untouchable either way. Applies to Phase 7/9 UI.
5. **§29 blockers #1/#2 — external Google behavior, open (gates Phases 5/6,
   not 1–4):** Experiments 1 & 4; Experiment 2 tunes backoff constants only.
6. **R7 — Phase 5 extension: dictionary + snippets sync (recorded decision).**
   Spec §30 adds `type` discriminator (`history` default) + `dictionary`/
   `snippet`/`settings` record kinds to the wire format, and syncs the custom
   dictionary + snippets (edits = tombstone + new UUID; `snippets_enabled`
   toggle syncs as a `settings` record). Pending autolearn suggestions never
   sync — only accepted dictionary entries. **No impact on Phases 1–4** (the
   `type` field is optional/additive; history fixtures unchanged). Phase 5
   (Windows) and Phase 6 (Android) implement it.
