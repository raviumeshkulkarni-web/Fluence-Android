# Fluence Transcribe — Cross-Platform History Sync Specification

**Spec v2** · Wire protocol v1 · Status: IMPLEMENTATION READY (gating experiments pending)

Supersedes v1. v2 resolves four inconsistencies found between v1 and the
adversarial-review corrected protocol. No new architecture was introduced.

Applies to both clients, which must stay protocol-identical:
- Windows: Rust/Tauri/SQLite (`src-tauri/src/history.rs`)
- Android: Kotlin/Room (`app/src/main/java/com/groq/voicetyper/history/*`)

## 0. v2 resolutions (the four fixed inconsistencies)

1. **`sync_state` restored as a persisted column.** The implementation plan had
   removed it as "derivable". The corrected protocol requires it. v2 keeps the
   column with the exact transition table (§6) and the invariant that it is
   transactionally maintained and equals the derived value at the end of every
   pass (debug-asserted).
2. **Windows stores `model` and `language`.** Without them, a Windows re-upload
   of an imported Android record would emit `null` and make the group DIVERGENT
   on Android. Windows now round-trips the full content tuple `T` (§9). Windows
   migration adds 8 columns; Android adds 6 (§13).
3. **Quarantine requires explicit user resolution.** No auto-clear. A latched
   group is skipped every pass until the user resolves it in UI; resolve clears
   the latch and the next pass re-evaluates (§12).
4. **Absence is listing-based.** A row's file is absent when it is absent from
   the pass listing (`trashed=false`) after one re-list. Trashed and renamed
   files count as absent → re-upload current state. The earlier
   `files.get == 404` gate is removed; list lag produces harmless
   duplicate-identical files (§10).

## 1. Objective

Sync transcription history across a user's devices over Google Drive using the
`drive.file` scope. Priority for all decisions:

1. DATA SAFETY — never silently lose, overwrite, or corrupt a record
2. DETERMINISTIC CONVERGENCE — same input state, same output state, everywhere
3. FAILURE RECOVERY — every failure has an explicit, safe recovery path
4. SIMPLICITY — fewest moving parts that satisfy 1–3
5. PERFORMANCE — fast enough to feel local

## 2. Verified current state (source of truth)

### Windows (`history.rs`)
- Table `history`; `id` TEXT PK UUID v4 (history.rs:90); `timestamp` TEXT RFC3339
  (history.rs:91); columns `text, mode, duration_ms, provider, char_count`
  (history.rs:14-22); DB `%LOCALAPPDATA%\Fluence\history.db` (history.rs:29);
  `static DB: Mutex<Option<Connection>>` (history.rs:24);
  `ORDER BY timestamp DESC` (history.rs:131); hard deletes (history.rs:186, :197).
- Windows stores NO `model`/`language` (provider only).
- App v1.14.0, identifier `com.fluence.desktop`; single-instance plugin
  (main.rs:46); commands registered at main.rs:156-239.

### Android (`history/`)
- Table `transcription_history`; `id` Long `autoGenerate` (TranscriptionEntry.kt:8);
  columns `text, provider, model, language, durationMs, isAgentMode, timestamp`
  (epoch ms); Room v4 (FluenceDatabase.kt:16); `cleanupToNewest(50)` after every
  save (HistoryRepository.kt:52); `deleteAllExceptNewest` (Dao:33); write call
  sites TranscriptionSessionManager.kt:615, :770; no transcription update path.
- Android stores NO `char_count` (derived); has `model`/`language`.

Asymmetry resolved in v2: **both platforms round-trip the full wire content** —
Windows adds `model`/`language`; `char_count` stays Windows-derived (not in wire).

## 3. Storage layout on Drive

- One shared folder per account: `Fluence Transcribe/`, created lazily by the
  first client under the active account. The folder is the account namespace.
- One JSON file per history record: `<uuid-v4>.json`.
- No meta/manifest/index files, no per-device folders.

## 4. Wire record — schema v1 (unchanged)

`<uuid-v4>.json`, UTF-8, compact JSON:

```json
{ "v": 1, "id": "<uuid>", "created_at": 1713456000123, "deleted_at": null,
  "text": "...", "mode": "transcription", "duration_ms": 8400,
  "provider": "groq", "model": "whisper-large-v3", "language": "en" }
```

| Field | Type | Rules |
|---|---|---|
| `v` | int | Must be `1`. Otherwise → quarantine, never import, never delete. |
| `id` | string | UUID v4 lowercase. Must equal basename. Otherwise → quarantine. |
| `created_at` | int | Epoch ms UTC. Immutable. Part of content tuple `T`. |
| `deleted_at` | int \| null | Null = live. Epoch ms when tombstoned. Presence is truth; values never compared. |
| `text` | string | Immutable once uploaded. Part of `T`. |
| `mode` | string | `"transcription"` \| `"agent"`. Part of `T`. |
| `duration_ms` | int | Part of `T`. |
| `provider` | string | Part of `T`. |
| `model` | string \| null | Part of `T`. |
| `language` | string \| null | Part of `T`. |

**Content tuple `T` = (created_at, text, mode, duration_ms, provider, model,
language)**. `deleted_at` is NOT part of `T`. Validation on read: `v==1`, UUID
id matches basename, `created_at > 0`, `deleted_at` null or positive, all ints
are ints. Any failure → the file is invalid → group DIVERGENT (§9).

## 5. Local schema (v2)

### Windows `history` — migration adds 8 columns
`timestamp_ms INTEGER NOT NULL DEFAULT 0`, `model TEXT`, `language TEXT`,
`deleted_at INTEGER`, `sync_state TEXT NOT NULL DEFAULT 'local'`,
`server_file_id TEXT`, `sync_account TEXT`, `quarantine_reason TEXT`.
- `get_history`/`get_history_stats`/`get_weekly_activity` filter
  `deleted_at IS NULL`; order by `timestamp_ms DESC`.

### Android `transcription_history` — Room 4 → 5, adds 6 columns
`syncId TEXT`, `deletedAt INTEGER`, `syncState TEXT NOT NULL DEFAULT 'local'`,
`serverFileId TEXT`, `syncAccount TEXT`, `quarantineReason TEXT`.
- `cleanupToNewest(50)` / `deleteAllExceptNewest` removed in the SAME release as
  sync enablement.
- All user-facing queries filter `deletedAt IS NULL`.
- `syncId` is UUID v4, generated lazily at first sync (never at row creation).
- Long `id` remains the local PK and is never synced.

## 6. sync_state transition table

`sync_state ∈ {local, clean, dirty, quarantined}`. Persisted, transactionally
maintained. Invariant: at the end of every pass, `sync_state` equals the value
derived from `(deleted_at, server_file_id, quarantine_reason, group verdict)`;
the engine asserts this in debug builds. The pass computes actions from the
facts; it never decides behavior from `sync_state` alone.

| From → To | Trigger | Precondition | Local mutation | Remote op | Success | Failure | Retry |
|---|---|---|---|---|---|---|---|
| *(row created)* → `local` | new record | none | insert row | none | — | — | — |
| `local` → `clean` | create succeeded | live, `server_file_id` NULL, create 2xx | set `server_file_id`, `sync_state=clean` (one tx) | create file (exact `T`) | `clean` | stays `local` | next pass |
| `clean` → `dirty` | row becomes tombstoned (local delete OR tombstone-wins) | live, `server_file_id` set | set `deleted_at`, `sync_state=dirty` (one tx) | none yet | `dirty` | — | — |
| `dirty` → `clean` | tombstone fully propagated | tombstoned; every listed group file tombstoned; absent files re-uploaded as tombstones | `sync_state=clean` (tx after final ack) | PATCH tombstone per live group file; re-upload tombstone on absence | `clean` | stays `dirty` | next pass |
| `local` → `quarantined` | group DIVERGENT before upload | live, `server_file_id` NULL, group DIVERGENT | set `quarantine_reason`, `sync_state=quarantined` (tx) | none | `quarantined` | — | user resolve only |
| `clean` → `quarantined` | group DIVERGENT | live, `server_file_id` set, group DIVERGENT | set `quarantine_reason`, `sync_state=quarantined` | none | `quarantined` | — | user resolve only |
| `dirty` → `quarantined` | group DIVERGENT while tombstoned | tombstoned, group DIVERGENT | set `quarantine_reason` (row stays tombstoned), `sync_state=quarantined` | none | `quarantined` | — | user resolve only |
| `quarantined` → `local`/`clean`/`dirty` | user resolve (UI) | `quarantine_reason` cleared by user | clear `quarantine_reason`; next pass recomputes state from facts | none | recomputed state | re-quarantined next pass if still DIVERGENT | — |

Lifecycle events outside the table: a `local` (never-uploaded) row that is
deleted is **hard-deleted** (nothing remote exists; provably safe — §14); a
`quarantined` placeholder (no user content) is overwritten by import when the
group becomes HEALTHY after resolve.

## 7. Engine stages and interfaces (pure, platform-neutral)

Traits (conceptual; not a language API):

- **LocalStore** — `listRows(stamp)`, `findRow(uuid)`, `import(row)` (upsert by
  UUID), `markTombstoned(uuid, deletedAt)`, `setServerFileId(uuid, fileId)`,
  `setSyncState(uuid, state)`, `quarantine(uuid, reason)`, `clearQuarantine(uuid)`,
  `hardDelete(uuid)`. Every call that touches >1 column is one transaction.
- **DriveStore** — `findOrCreateFolder()`, `listFiles()` (full, paginated,
  `trashed=false`), `getContent(fileId)`, `createFile(name, wire)` → fileId,
  `updateContent(fileId, wire)` (**tombstone media only**). Token-injected; the
  engine never sees tokens.
- **SyncEngine** — `run(account)`. Pure reconciliation; no knowledge of OAuth,
  UI, WorkManager, Tauri, or HTTP.

Stages (each: inputs / outputs / side effects / failure):

1. **PREFLIGHT** — in: `account`. out: namespace or skip. Side effects: none.
   Failure: no token → emit `AuthRequired`, skip pass.
2. **LIST** — in: namespace. out: `files` (full listing). Side effects: folder
   creation only. Failure: transient → `Retryable`, abort pass, nothing mutated.
3. **VALIDATE** — in: files. out: per-file valid/invalid + parsed WireRecord.
   Side effects: none (content fetched via `getContent`, uncommitted). A
   fetch-404 (file vanished after list) drops the file from its group this pass.
4. **GROUP** — in: parsed files. out: per-UUID group verdict
   (ABSENT / HEALTHY-LIVE / HEALTHY-DELETED / DIVERGENT). Pure.
5. **RECONCILE** — in: groups + rows stamped null/active. out: per-row actions.
   Side effects: **local DB only** (imports, tombstone-wins, quarantine latches,
   hard deletes of unsynced rows). One tx per row.
6. **PUSH** — in: actions. out: Drive writes. Side effects: **Drive only**
   (create / re-upload / tombstone PATCH); each op independent; on success the
   row's `server_file_id` / `sync_state` commit in its own tx; on failure the row
   is left unchanged, emit `Retryable`, continue others.
7. **APPLY** — the local commits from RECONCILE and PUSH; crash anywhere leaves
   nothing half-committed (per-row txs; uncommitted work re-runs next pass).
8. **FINALIZE** — persist display-only `last_sync_at`, emit status.

Crash matrix (local / remote / next-pass):

| Crash at | Local | Remote | Next pass |
|---|---|---|---|
| before create POST | `local` | none | create |
| during create POST | unchanged | file may exist | create → duplicate-identical |
| after POST, before id commit | unchanged | file exists | create → duplicate-identical |
| before tombstone PATCH | `dirty` | live | PATCH |
| during PATCH | `dirty` | live or tombstoned | PATCH (idempotent) |
| after PATCH, before commit | `dirty` | tombstoned | re-PATCH no-op, commit |
| during content fetch | unchanged | untouched | re-fetch |
| after fetch, before import commit | unchanged | untouched | re-import |
| during quarantine latch | atomic | untouched | re-evaluate if uncommitted |
| during stamp/import commit | atomic | untouched | re-import if uncommitted |

## 8. Canonical record — there is none

No canonical file, no fileId ordering, no winner selection. See §9.

## 9. Group classification (resolves consistency A)

For a UUID, the group = files whose name matches `<uuid>.json` in the pass
listing. **Valid** = parses, `v==1`, id == basename. Verdicts:

- **ABSENT** — no name-matched files → absence rules (§10).
- **HEALTHY** — ≥1 valid file, all agree on `T`, and (if a local row exists) the
  local row agrees on `T`. Sub-verdict by tombstone resolution: any
  `deleted_at != null` → **DELETED**, else **LIVE**. Multiple identical files =
  duplicated-but-identical, a subtype of HEALTHY (coexists, one row, no writes).
- **DIVERGENT** — any invalid file in the group, OR two valid files disagree on
  `T`, OR a valid file disagrees with the local row on `T`. Tombstone state is
  not part of `T` (handled by tombstone-wins).

## 10. Absence rule (resolves consistency D)

- A row with `server_file_id = F` is **absent** when `F` is not in the pass
  listing (`trashed=false`). Trashed and renamed files are not in the listing →
  count as absent. Confirmation: one fresh re-list; if still absent → confirmed.
- Confirmed absence → **re-upload the row's current state** (live content with
  the exact `T`, or tombstone) as a new file; set `server_file_id` to the new id.
- Absence never deletes a row and never tombstones anything.
- List lag: a lag-induced re-upload produces a duplicate-identical file — harmless
  (§9). No `files.get` gating.
- `files.get` is used only to fetch content during VALIDATE, never to decide
  absence.

## 11. Tombstone rules (resolves B, C)

- **Deletion** = `deleted_at` set locally (`clean`→`dirty`), then the group is
  re-tombstoned every pass until every listed file carries `deleted_at`.
  Propagation is a converging best effort: PATCH each listed live file with the
  tombstone; crash anywhere (N=1, 10, 100 files) → the next pass continues,
  idempotently. No batching, no per-file ack state.
- **Tombstone-wins**: if ANY valid file in the group is tombstoned, the group is
  DELETED. A live local row (stamped null/active) with a DELETED group: if `T`
  agrees → the row becomes a tombstone (in RECONCILE, before PUSH); it is never
  uploaded as live afterward. If `T` disagrees → DIVERGENT → quarantine.
- **Retention**: tombstones are never GC'd, locally or remotely.
- **Unknown-UUID tombstone**: a DELETED group with no local row → import a
  tombstone row (so the tombstone is never re-broadcast as a create).

## 12. Quarantine (resolves consistency G)

- **Per-UUID group**, latched on the local row (`sync_state='quarantined'`,
  `quarantine_reason` set). DIVERGENT group with no local row → create a
  placeholder quarantined row (stamped active). Never import, never write.
- **No auto-clear.** The pass skips latched groups entirely until the user
  resolves them in UI. Resolve clears `quarantine_reason`; the next pass
  re-evaluates — imports if HEALTHY (placeholder rows are overwritten by import),
  re-quarantines if still DIVERGENT.
- Offending files are never deleted or rewritten by the app.
- Reasons: `ContentDeviation | CorruptFile | UnknownSchemaVersion |
  IdNameMismatch | Collision`.

## 13. Account namespace (resolves E, F)

- A pass captures active account `A` once. It reads/writes/imports/tombstones/
  quarantines only rows with `sync_account ∈ {null, A}`. Other stamps are
  excluded at the query layer and byte-untouched.
- Import sets stamp `= A`. Placeholder tombstones stamp `= A`. Quarantine keeps
  the existing stamp.
- Folder is per-account-namespace. Reinstall: empty DB + sign-in to A → pass
  imports A's folder only; other accounts' data is unreachable by construction
  (`drive.file` scope, folder-scoped listing only).
- Cross-device sync = same account on all devices. Switching accounts is an
  identity change; history does not follow the account. Cross-account
  merge/migration is a non-goal.

## 14. Local deletion (resolves consistency K)

| Case | Behavior |
|---|---|
| never-uploaded (`server_file_id` NULL), sync off or on | **hard delete** — nothing remote exists, no other device can hold it |
| uploaded live | **tombstone** (`deleted_at`, `clean`→`dirty`) → PUSH re-tombstones the group |
| already tombstoned | **no-op** (idempotent) |
| offline | tombstone persisted locally; push deferred; tombstone-wins resolves on reconnect |
| simultaneous deletes on two devices | identical idempotent PATCHes; no conflict |

Local mutations serialize with the pass via the per-device sync mutex.

## 15. Remote user manipulation (resolves consistency L)

| User action | Behavior |
|---|---|
| delete a live file | absence → re-upload current state (record survives) |
| delete a tombstone file | any device holding the tombstone row re-uploads the tombstone |
| duplicate a file | HEALTHY group; coexist; no winner |
| rename a file | name mismatch → not listed → inert; if it is the row's file → absence → re-upload under correct name; misnamed file left untouched |
| edit JSON | invalid → DIVERGENT/quarantine; valid but different `T` → DIVERGENT/quarantine; tombstone change → tombstone rules. Never overwritten. |
| delete the folder | listing empty → every row re-uploads current state; folder recreated |
| trash a file | trashed files are not listed → absence → re-upload current state |
| restore a trashed file | reappears in listing → HEALTHY (duplicate-identical) or DIVERGENT |

## 16. server_file_id (resolves consistency I)

Exists to (a) prove a row was ever uploaded, (b) anchor the absence check,
(c) prevent duplicate creation. Set atomically with a successful create or
confirmed-absence re-upload. Changes only on a confirmed-absence re-upload (new
file id). **Never cleared**; only a DB wipe removes it.

## 17. Failure matrix

| Failure | Handling |
|---|---|
| File lost on Drive | absence rule; re-upload current state |
| Delete on device B | tombstone propagates group-wide; retained forever |
| Resurrection attempt | tombstone-wins converts any live file; boundary = all tombstone copies destroyed (§18) |
| Retry duplicate | duplicate-identical → one row, no action |
| v4 collision | `T` deviation → whole group quarantined, surfaced, never merged |
| Corrupt/unknown-version/name-mismatch file | quarantined, surfaced, never deleted |
| Partial multi-file tombstone | tombstone-wins completes next pass |
| Token expired/revoked | re-auth; rows unchanged; no data change |
| Account switch | namespace change; other stamps untouched; return reads old folder unchanged |
| Reinstall | re-import of the signed-in account's folder only |
| All tombstones destroyed | X resurrects — irreducible, outside protocol authority |

## 18. Irreducible failure boundary

A deleted record X permanently resurrects **iff every copy of X's tombstone is
destroyed**: the Drive tombstone file(s) removed AND the tombstone row deleted
from every device that ever received it. One surviving tombstone file or row
prevents resurrection forever. Outside any protocol's authority.

## 19. Convergence argument (resolves consistency N)

- **I1** — no protocol op deletes a file, un-tombstones a row, or writes live
  content to an existing file.
- **I2** — a file is created only in response to a confirmed absence (create or
  re-upload); each create cures its absence → per row, finitely many creates per
  external deletion.
- **I3** — for a DELETED group, each pass tombstones every live file
  (idempotent): live-files-in-DELETED-groups strictly decreases and grows only
  by external action.
- **I4** — each HEALTHY group imports at most one row, idempotently.
- **I5** — quarantined groups are stable (latched; changed only by user resolve).
- Conclusion: after the last external mutation, finitely many creates fire (I2),
  then finitely many tombstone PATCHes (I3) and imports (I4); every monotone
  measure is bounded below → fixed point in a bounded number of passes.
- Fixed point: every live row has a present, correctly-named, group-consistent
  file; every tombstone row's group is fully tombstoned and present; every
  HEALTHY group is imported; every DIVERGENT group is latched.

## 20. DO-NOT-VIOLATE contract (copy into coding-agent prompts)

**Identity** — UUIDs immutable, never re-keyed; local PKs never synced;
`server_file_id` sticky.
**Wire** — name must equal `<uuid>.json`; id must equal basename; `v==1`;
`created_at` immutable; live content immutable after upload; PATCH is
tombstone-only.
**Account** — a pass touches only rows stamped null or the active account; other
stamps byte-untouched; account fixed per pass.
**Tombstone** — deletion is `deleted_at` + group re-tombstoned every pass;
tombstones never GC'd; presence is truth.
**Duplicate** — identical duplicates coexist, no winner, no auto-cleanup in v1;
any `T` disagreement quarantines the whole group.
**Absence** — confirmed only by "not in listing after one re-list"; never deletes
a row, never tombstones anything; re-upload reproduces the exact `T`.
**Quarantine** — per-UUID group latch; user-resolution only; offending files
never deleted or rewritten.
**Persistence** — per-row transactions; one sync mutex per device; full re-scan
each pass; no cursor; `sync_state` only via the §6 table; debug-assert
`sync_state == derived` after each pass.
**Prohibited** — no LWW/CRDTs/clocks/re-keying/manifests/device IDs/ownership
metadata/canonical-file selection; no live-content PATCH; no auto-deleting Drive
files; no tombstone GC; no absence-as-deletion; no timestamp comparison for
conflict resolution; no cross-account mutation; no restoring the Android 50-cap;
no logging of transcript text.

## 21. Platform mapping — Windows

- **history.rs** — add 8 columns (§5); filter `deleted_at IS NULL`; order by
  `timestamp_ms DESC`; delete/clear = hard-delete `server_file_id IS NULL` rows,
  tombstone the rest (through the engine mutex); `save_history_entry` unchanged
  (insert-only); `history-updated` events unchanged. Add nullable `model`/
  `language` to `HistoryEntry`.
- **main.rs** — `mod sync;`; register `sync::*` commands; spawn the scheduler in
  `setup`; single-flight = one scheduler task guarded by a mutex; "sync now"
  coalesces into the running pass.
- **settings.rs** — `sync_enabled: bool`, `sync_account_key: Option<String>` with
  `#[serde(default)]`.
- **credentials.rs** — `GOOGLE_DRIVE_TARGET = "Fluence/GoogleDrive"`; refresh
  token via existing `store_credential`; access token memory-only.
- **Cargo.toml** — no new stack required (reqwest/url/base64/sha2 already
  present).
- **capabilities/default.json** — no new capability unless a Tauri plugin is
  added (none is); app-internal commands only.
- **SQLite** — shared `Mutex<Connection>`; per-row txs; migration inside
  `init_db` guarded by `user_version`.

## 22. Platform mapping — Android

- **TranscriptionEntry.kt** — add 6 nullable columns (§5).
- **FluenceDatabase.kt** — version 5; `MIGRATION_4_5`; NO destructive fallback.
- **TranscriptionHistoryDao.kt** — remove `deleteAllExceptNewest`; add scoped
  (`syncAccount` null/active) queries, upsert-by-`syncId`, `markTombstoned`,
  `quarantine`/`clearQuarantine`, `hardDelete`.
- **HistoryRepository.kt** — remove `cleanupToNewest(50)` (line 52) in the SAME
  release as sync; delete/clearAll via sync-aware path; stats behavior untouched
  (`stats_daily` is never mutated by sync writes).
- **SecurityUtils.kt** — encrypted keys `sync_refresh_token`, `sync_account_key`;
  reuse the cached-prefs pattern.
- **MainActivity.kt** — host `fluence-transcribe://oauth2callback`; register
  SyncWorker (WorkManager, network+charging, periodic).
- **SettingsScreen.kt / Navigation.kt** — `Screen.Sync`; nav entry.
- **AndroidManifest.xml** — custom-scheme intent-filter on MainActivity
  (`INTERNET` already present).
- History UI never displays tombstones or placeholders (`deletedAt IS NULL`
  everywhere).

## 23. Google Drive layer (DriveStore)

`findOrCreateFolder`, `listFiles` (paginated to exhaustion, `trashed=false`),
`getContent` (per file id), `createFile`, `updateContent` (tombstone media only).
Error mapping: `401` → reauth; `403` drive.file → `NotOurs` (skip, never
retry-bomb); `429`/5xx/timeouts → `Retryable` with configurable exponential
backoff (no hardcoded quota figures); partial responses → failed, re-fetch;
fetch-404 during VALIDATE → drop file from group this pass. `Retryable` aborts
the pass cleanly; `Fatal` surfaces to UI.

## 24. Authentication

- **Windows** — loopback PKCE (S256), in-process listener on
  `http://localhost:<port>/`, refresh token in Credential Manager, access token
  memory-only.
- **Android** — native Google Sign-In (`play-services-auth`) with offline server
  auth code exchange (`requestServerAuthCode(webClientId, forceCodeForRefreshToken = true)`
  + `drive.file` scope) for access and refresh tokens. Exchanged at the Google
  token endpoint using the Web client ID and secret from `local.properties`
  (persisting the same token/refresh/expiry/account-email schema in
  EncryptedSharedPreferences). Replaces the earlier system-browser PKCE flow
  to present the native account-chooser sheet on device.
- Both implement the same `TokenProvider` interface; SyncEngine and DriveStore
  depend on the interface only.

## 25. Migrations

**Windows** — in `init_db`, guarded by `PRAGMA user_version` (0 → 1): one
transaction adds the 8 columns and backfills `timestamp_ms` from `timestamp`
(best-effort RFC3339 parse; fallback 0), then sets `user_version=1`. On error:
rollback, log, serve with sync disabled (existing reads unaffected). **No
existing row is deleted or rewritten** — only new columns added and a derived
sort value backfilled.

**Android** — `MIGRATION_4_5`: `ALTER TABLE transcription_history ADD COLUMN`
per new column; no data rewrite; `syncId` generated lazily at first sync (not in
migration). Unique index on `syncId` (SQLite allows multiple NULLs — existing
rows carry NULL). Registered on version 5, no destructive fallback. Migration
test fixture: real v4 DB → migrate → all rows present and unchanged.

## 26. Test plan (layers + scenarios)

1. **Wire codec** — `fresh_android_record_roundtrip`, `minimal_windows_record_roundtrip`,
   `tombstone_roundtrip`, `agent_mode_roundtrip`, `malformed_json_rejected`,
   `unknown_schema_version_rejected`, `filename_id_mismatch_rejected`,
   `negative_deleted_at_rejected`.
2. **Pure engine (FakeLocalStore + FakeDrive)** — `import_healthy_group`,
   `duplicate_identical_files_import_once`, `duplicate_divergent_files_quarantine_whole_group`,
   `tombstone_plus_live_duplicate_resolves_deleted`, `live_local_row_group_deleted_converts_to_tombstone`,
   `never_uploaded_deleted_is_hard_deleted`, `account_scope_excludes_foreign_rows`,
   `stale_offline_device_reconnects_no_resurrection`, `repeated_sync_reaches_fixed_point`,
   `absence_confirmed_by_listing_only`, `trashed_file_counts_as_absent_reupload`,
   `renamed_file_counts_as_absent_reupload`.
3. **Windows migration** — `old_db_gets_eight_columns`, `timestamp_ms_backfilled_from_rfc3339`,
   `rfc3339_parse_failure_falls_back_zero`, `existing_rows_unchanged`,
   `user_version_set_to_1`, `failed_migration_rolls_back_and_disables_sync`.
4. **Android migration** — `migration_4_5_keeps_all_rows`, `migration_4_5_does_not_generate_sync_id`,
   `new_columns_null_for_existing_rows`, `unique_index_allows_multiple_null_sync_ids`.
5. **Fake Drive integration** — `post_timeout_creates_duplicate_identical_file`,
   `crash_halfway_through_100_duplicate_tombstones_completes_next_pass`,
   `remote_tombstone_deleted_reuploaded_by_tombstone_holder`, `local_tombstone_destroyed_boundary_documented`,
   `entire_folder_deleted_all_records_restored`, `drive_list_lag_absorbed_by_duplicate_identical`,
   `folder_recreation_restores_records`, `renamed_file_is_inert_until_renamed_back`,
   `restored_trash_file_joins_healthy_group`.
6. **Crash injection** — one test per row of the §7 matrix.
7. **Account namespace** — `a_to_b_keeps_a_rows_untouched`, `a_to_b_to_a_restores_exact_history`,
   `a_to_b_to_a_to_b_symmetric`, `delete_while_connected_to_wrong_account_impossible`,
   `reinstall_under_a_reimports_only_a_folder`, `account_switch_during_pass_is_pass_scoped`.
8. **Duplicates** — `duplicate_identical_coexist_no_winner`, `duplicate_divergent_quarantine_and_user_resolve`.
9. **Tombstones** — `tombstone_wins_live_duplicate`, `tombstone_propagation_to_100_duplicates`,
   `tombstone_idempotent_repatch`, `simultaneous_deletes_two_devices_converge`.
10. **Real Google Drive** — the six gating experiments (cross-client visibility,
    list-lag, quota envelope, Desktop PKCE token endpoint, version-bump
    confirmation, AppData scoping).
11. **Cross-platform** — `windows_created_record_appears_on_android`,
    `android_delete_propagates_to_windows`, `both_offline_then_sync_union_no_dupes`,
    `delete_while_windows_offline_no_resurrection`.

## 27. Implementation phases

**0 — Spec + fixtures + GCP + experiments (gate).** `docs/sync/spec.md`,
`examples/sync/*.json`. Tests: layer 1 fixtures. Acceptance: experiments 1–4 pass.
Rollback: n/a.

**1 — Windows DB migration.** `history.rs`, `settings.rs`, `main.rs`. Tests:
layer 3. Acceptance: old DB upgrades; `npm run check` green; sync-disabled path
byte-identical to today. Rollback: `user_version` stays 0 on failure; no data
touched.

**2 — Windows sync engine (pure).** `sync/wire.rs`, `sync/engine.rs`. Tests:
layers 1–2, 5 (Fake). Acceptance: full engine suite green with zero Drive/Auth
code. Rollback: module not wired into the scheduler.

**3 — Android Room 4→5 + cap removal + soft delete.** `TranscriptionEntry.kt`,
`FluenceDatabase.kt`, `TranscriptionHistoryDao.kt`, `HistoryRepository.kt`.
Tests: layer 4. Acceptance: migration non-destructive; 50-cap gone; delete paths
correct. Rollback: bump to v6 if shipping an older release — never destructive.

**4 — Android sync engine.** `sync/engine/*.kt`, `sync/wire/WireRecord.kt`.
Tests: layers 2, 5. Acceptance: mirrors Windows engine on the same fixture corpus.

**5 — Windows auth + Drive layer + record kinds.** `sync/auth.rs`, `sync/drive.rs`,
`credentials.rs`, `sync/wire.rs` (type discriminator), dictionary/snippet stores.
Tests: layer 10 (experiment harness) + §30.5 additions. Acceptance: token
stored/refreshed; DriveStore error mapping per §23; dictionary + snippets sync.

**6 — Android auth + Drive layer.** `sync/auth/*.kt`, `sync/drive/GoogleDriveStore.kt`.
Tests: layer 10.

**7 — Windows wiring + settings UI.** `sync/scheduler.rs`, commands, `main.rs`,
frontend settings. Tests: manual. Acceptance: toggle, first sync, status,
single-flight verified.

**8 — Android wiring + WorkManager + SyncScreen.** `SyncWorker.kt`,
`SyncManager.kt`, `MainActivity.kt`, `SettingsScreen.kt`, `Navigation.kt`.
Tests: manual.

**9 — Quarantine UX + hardening.** Quarantine list + resolve on both clients;
failure-injection suite. Tests: layer 6.

**10 — Cross-platform e2e + release.** Tests: layer 11. Acceptance: S1–S6
scenarios from v1.

## 28. Coding-agent guardrails

**MUST NOT DECIDE:** no LWW/CRDTs/clocks/re-keying/canonical file/winner; no
auto-deleting or auto-deduping Drive files; no tombstone GC; no
absence-as-deletion; no timestamp comparison for conflict resolution; no syncing
local PKs; no live-content PATCH; no mixing account namespaces; no restoring the
Android 50-cap; no logging/persisting transcript text in sync code; no new
`sync_state` transitions beyond §6; no hardcoded Drive quota numbers; no
persisting access tokens.

**MAY DECIDE:** private helper/class names; internal data structures; test
fixture organization; harmless behavior-preserving refactors; error-message
wording (never containing transcript text); backoff constant values within
documented ranges; foreign-row UI presentation (hidden or read-only — the
protocol treats them as untouchable either way).

## 29. Implementation readiness gate

**ARCHITECTURE STATUS: IMPLEMENTATION READY** (blockers below are not design
flaws).

Blockers:
1. **External Google behavior** — Experiment 1 (cross-client `drive.file`
   visibility in one GCP project) and Experiment 4 (Desktop client token-endpoint
   secret + PKCE). Failure of Exp 1 pivots only the storage layer (AppData
   folder + delegation); engine and wire are unaffected. Gates Phases 5/6, not
   1–4.
2. **External Google behavior** — Experiment 2 (list-lag bounds) tunes backoff
   constants only.
3. **Product decision — RESOLVED (2026-08-15):** Android 50-cap removal ships in
   the same release as any sync-enabled build (never cap-removal alone). Foreign
   (account-namespace) rows follow best UX practice: **read-only with a clear
   ownership indicator** — shown in the history list, greyed/untouchable, with an
   account badge; edit/delete disabled (protocol already treats them as
   untouchable). Hidden rows are rejected: invisible data confuses users.

Phases 1–4 have no blockers and may start immediately.

## 30. Phase 5 extension — dictionary + snippets sync (record kinds)

Extends sync scope beyond history: custom dictionary entries and voice snippets
are synced with the same file-per-UUID, tombstone, quarantine, absence,
no-canonical-file design. **Auto-learn suggestions themselves NEVER sync** — only
entries that actually exist in the dictionary (manually entered OR accepted
autolearn suggestions, which land in `dictionary.json` / `custom_dictionary`)
plus the custom snippet collection.

### 30.1 Record kinds

The wire record gains an optional `type` discriminator. `v` stays `1`; the field
is additive, so **existing history fixtures and Phase 1–4 code are unchanged**.

| `type` | Content tuple `T` (besides `created_at`) | Notes |
|---|---|---|
| `history` (absent or explicit) | `text, mode, duration_ms, provider, model, language` | §4 schema v1, byte-identical |
| `dictionary` | `spoken, corrected, kind` | `kind ∈ {correction, expansion}` |
| `snippet` | `trigger, expansion` | — |
| `settings` | `key, value` | one record per key per account namespace |

Envelope unchanged for every type: `v, id, created_at, deleted_at`; `id` must
equal the basename UUID; validation rules of §4 apply per type (a `dictionary`
record missing `spoken`/`corrected` is invalid → group DIVERGENT, §9). Unknown
`type` value → invalid → quarantine, never imported, never deleted.

### 30.2 Edits (editable records) — user decision: tombstone + new UUID

Dictionary entries and snippets are editable, unlike immutable history rows.
An edit is performed **locally as one transaction**:

1. tombstone the old UUID (set `deleted_at`, `local→dirty` when uploaded), then
2. create a new UUID carrying the updated content (new record).

Both steps go through the local-mutation mutex, one transaction. Every wire file
stays immutable — **no live-content PATCH** (already prohibited in §20/§28).
Concurrent edits of the same logical item on two devices produce two distinct
live records (different UUIDs, both survive; no winner, no clocks). Accepted
behavior, surfaced as separate items in the UI; consistent with §8/§12.

### 30.3 Snippets `enabled` toggle — user decision: synced

The master toggle (`SnippetStore.enabled` Windows, `isSnippetsEnabled` Android)
syncs as a `settings` record with `key = "snippets_enabled"`. Rules:

- One settings record per key per account namespace; a toggle edit follows the
  same tombstone + new-UUID rule (§30.2).
- Two devices toggling concurrently → divergent content → group DIVERGENT →
  quarantine → user resolve (§12). No clocks, no LWW.
- `enabled` is NOT part of any snippet content tuple.

### 30.4 Local storage

- **Windows** — `dictionary.json` / `snippets.json` gain per-entry sync metadata
  (`created_at`, `deleted_at`, `sync_state`, `server_file_id`, `sync_account`,
  `quarantine_reason`) via serde defaults so legacy files load unchanged.
  `SnippetStore.enabled` additionally mirrors the `snippets_enabled` settings
  record. User-facing reads filter `deleted_at IS NULL`.
- **Android** — `custom_dictionary` (Room) gains the same 6 sync columns
  (migration `5 → 6`, non-destructive, mirrors §5); `SnippetPreferences` JSON
  gains per-entry sync metadata with a version bump; `snippets_enabled` mirrors
  the settings record.

### 30.5 Engine and tests

Engine stays pure: `LocalStore` is per record kind, `DriveStore` unchanged,
`sync_state` (§6) applies per type, quarantine is per (type, UUID) group.
Fixtures: add one `examples/sync/*.json` per new record kind.
New tests (add to §26):

- layer 1: `dictionary_record_roundtrip`, `snippet_record_roundtrip`,
  `settings_record_roundtrip`, `unknown_type_rejected`, `missing_dictionary_fields_rejected`.
- layer 2: `edit_creates_new_uuid_tombstones_old`, `edit_propagates_to_other_device`,
  `concurrent_edits_both_survive_no_winner`, `settings_toggle_quarantines_on_divergence`,
  `enabled_toggle_roundtrips`.
- layer 5: `edited_snippet_reuploaded_as_new_uuid`, `toggled_enabled_propagates`.

### 30.6 Phase changes

- **Phase 5 (Windows)** — add `type` discriminator + new codecs in `sync/wire.rs`;
  parameterize the engine by record kind; add dictionary/snippet sync columns and
  stores; settings-record handling; fixtures; new tests above.
- **Phase 6 (Android)** — mirror: `custom_dictionary` migration `5 → 6`,
  `SnippetPreferences` v2, engine parameterization, settings-record handling.
- **§28 guardrail additions** — "no live-content PATCH for editable records;
  edits are tombstone + new UUID"; "no syncing of autolearn suggestions — only
  accepted dictionary entries"; "no `enabled` inside a snippet content tuple".
