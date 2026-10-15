# Sync Wire Contract — frozen v1.2.1

These fixture files are the canonical, frozen wire-contract for sync v1 between the
Android and Windows clients. Both platforms must serialize and accept these payloads
byte-identically. This contract is frozen: additive-only evolution; breaking changes
require a version bump. The fixtures in this directory are the source of truth.

## Envelope

All files share one envelope shape:

```json
{"v":1,"entries":[...]}
```

- Envelope: `{"v":1,"entries":[...]}` plus trailing newline; compact JSON; fixed key order; deterministic sorts — dict/snippet: businessKey then syncId; stats: day then eventId; settings: key.
- Exactly one trailing newline after the closing `}`.
- Compact JSON — no whitespace between tokens, no pretty-print.
- Fixed key order as listed per item type below (keys appear in the declared order).
- Deterministic sorts — dict/snippet: businessKey then syncId; stats: day then eventId; settings: key.
- Whole payload <=8 MiB.
- Wrong envelope `v` rejects the whole file; malformed JSON rejects the whole file.

## Item schemas (fixed key order)

### dictionary.json

- Deterministic sort: businessKey then syncId
- Dict item keys: syncId, businessKey, spoken, corrected, isEnabled, updatedAt, deletedAt, deviceId. Readers recompute identity from `spoken` (trim+lowercase) and IGNORE wire businessKey.
- `spoken` is the canonical identity source: trim whitespace, lowercase, then hash/compare. `businessKey` is transported for debugging but MUST be ignored by readers.
- `syncId` is a UUID string; `isEnabled` is boolean; `updatedAt` is millis-since-epoch; `deletedAt` is null or millis; `deviceId` is string.

### snippets.json

- Deterministic sort: businessKey then syncId
- Snippet item keys: syncId, businessKey, trigger, expansion, isEnabled, updatedAt, deletedAt, deviceId.
- `trigger` is the typed shortcut; `expansion` is the replacement text.
- Same `syncId`/`isEnabled`/`updatedAt`/`deletedAt`/`deviceId` semantics as dictionary.

### settings.json

- Deterministic sort: key
- Settings item keys: key, value, updatedAt, deviceId (+ optional deletedAt); only the five allowed keys (language, dictionary_enabled, snippets_enabled, auto_learn_enabled, ai_polish_style).
- Only these five keys are recognized: language, dictionary_enabled, snippets_enabled, auto_learn_enabled, ai_polish_style. Unknown keys are ignored.
- `key` is the setting name; `value` is its stringified value; `updatedAt` is millis; `deviceId` is string; `deletedAt` if present marks tombstone.
- Last-write-wins per `key` based on `updatedAt`.

### stats.json

- Deterministic sort: day then eventId
- Stats item REQUIRED: eventId (UUID string), day (YYYY-MM-DD UTC). OPTIONAL (omit/0 when unknown): timestampMs, words, chars, durationMs. LEGACY-IGNORED if present: updatedAt, deviceId, deletedAt — readers MUST NOT require them. timestampMs==0/absent => reader falls back to day@UTC-midnight.
- `eventId` uniquely identifies the aggregation bucket; `day` is the UTC calendar day bucket.
- OPTIONAL fields — timestampMs (millis, 0 or absent means unknown), words, chars, durationMs — when unknown they are omitted or set to 0.
- LEGACY-IGNORED if present: updatedAt, deviceId, deletedAt — readers MUST NOT require them; they are ignored for compatibility with older producers.
- timestampMs==0/absent => reader falls back to day@UTC-midnight (parse `day` as UTC midnight).
- Entries are deduplicated by `eventId`; union across devices.

## Validation caps

Caps: dict/snippet strings <=4096 chars; expansion <=8192; settings value <=1024; wordCount<=1_000_000; chars<=10_000_000; durationMs<=604_800_000; envelope <=50_000 entries (settings <=20); payload <=8 MiB. Invalid individual records are skipped, never fatal; wrong envelope `v` rejects the whole file.

Detailed caps table:

| Limit | Value |
|---|---|
| dict/snippet strings (spoken, corrected, trigger, etc.) | <=4096 chars |
| snippet expansion | <=8192 chars |
| settings value | <=1024 chars |
| wordCount / words | <=1_000_000 |
| chars | <=10_000_000 |
| durationMs | <=604_800_000 (7 days) |
| envelope entries | <=50_000 entries (settings <=20) |
| whole payload | <=8 MiB |

- An individual record violating a cap is skipped, never fatal for the rest of the envelope.
- Only envelope-level problems (wrong version, malformed JSON, payload >8 MiB) reject the whole file.
- Wrong envelope `v` rejects the whole file.

## Canonical fixtures

- `dictionary.json` — 3 entries demonstrating enabled, disabled, and tombstoned states
- `snippets.json` — 2 entries
- `settings.json` — 5 entries covering all allowed keys
- `stats.json` — 2 entries demonstrating required + optional + legacy-ignored fields

All fixture files are compact JSON with fixed key order and deterministic sorts and end with exactly one trailing newline.

## Evolution rules

- Frozen v1.2.1 — no breaking changes without a major version bump.
- Additive fields must be OPTIONAL and ignored by old readers.
- Never reorder keys; never change sort order; never change envelope shape without bumping `v`.

## Stats time shards (monthly per-event envelopes)

The Stats ledger is one LOGICAL set of per-dictation events stored as
SEVERAL physical v1 envelopes so no single file hits the 8 MiB / 50k caps:

- `stats.json` — legacy file, FROZEN: new clients read it, never write or
  delete it. Old released clients own it (exact-name listing).
- `stats-YYYY-MM.json` — month shard (UTC month of each record's `day`),
  part 1. Overflow parts: `stats-YYYY-MM-pNN.json` (`NN >= 02`).

Every shard is a complete v1 envelope: same shape, same `StatsItem`
schema, same `(day, eventId)` sort, same validation, same per-file caps,
same union-dedup-by-`eventId` merge with the same LWW tiebreak, same CAS
and upload-liveness per file. Storage location changes; record meaning
never does (sessions = record count, totals = field sums).

Shard content rule: a shard holds exactly the merged rows of its month
that are NOT byte-identical to the legacy `stats.json` version — history
is never recopied, moved, or deleted. Old clients list `stats.json` by
exact name and never see shard siblings.

## Agents and Styles (account-partitioned additive domains)

Phase 6 adds two synced domains, `agents.json` and `styles.json`. They are
ADDITIVE: a client that predates them simply never reads or writes them, and
the four original domains are unchanged.

- Storage: `appDataFolder/fluence/v1/acct-<segment>/{agents,styles}.json`,
  where `<segment>` is a SECOND, tagged derivation of the account hash
  (`SHA-256("fluence/acct-path/v1\0" + accountHash)`). It is deliberately
  not correlatable with the locally stored account hash.
- Envelope: a normal v1 envelope, same shape, key order and caps as above.
- `businessKey` is NOT derived from mutable content: it IS the record's own
  stable id, minted at creation as `agent:<uuid>` / `custom:<uuid>` and
  never rewritten. Renaming an agent therefore cannot fork it into two records.
  Because identity arrives from the wire it is VALIDATED on ingest
  (`<prefix>` + strict UUID); an unvalidated key would let a corrupt or
  hostile file forge a merge identity and win an LWW against a real record.
- Key order is `syncId, businessKey, name, hint, updatedAt, deletedAt, deviceId`
  with `deletedAt` ALWAYS present (explicit `null` when absent).
- Caps: `name` <= 1024 code points, `hint` <= 8192 code points.
- `name` is validated with the Unicode `White_Space` trim set (Rust
  `str::trim`), NOT Kotlin's `String.trim()` or `Char.isBlank` — those
  disagree in both directions and make ingest asymmetric.
- A tombstone (`deletedAt` present) is a VALID record: dropping it at ingest
  would resurrect the deleted record on the next merge.
- Sort: `businessKey` then `syncId`, UTF-8 byte order.

These two domains carry NO cross-platform fixture coverage in
`convergence/scenario-*.json`; the corpus asserts dictionary, snippets,
settings and stats only.