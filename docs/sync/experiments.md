# Gate experiments — runbook (layer 10)

External-Google-behavior experiments required by `spec.md` §26.10, §29. The
acceptance gate for Phases 0 and 5/6 is **Experiments 1 and 4** (spec §29
blocker #1). Experiment 2 tunes backoff constants only. Experiments 3, 5, 6
inform the storage layer and hardening; they do not gate.

Mirror of the docs in the Fluence-Windows repo — keep byte-identical.

## Prerequisites

- One GCP project owned by the user (name recorded below).
- Google Drive API enabled for that project.
- OAuth consent screen configured (External testing) with the user as a test user.
- Two OAuth client IDs in that project:
  - **Desktop client** (application type "Desktop app") — for the Windows
    loopback PKCE flow (§24). Google auto-applies PKCE for this type.
  - **Android client** (application type "Android", package name + SHA-1) — for
    the system-browser PKCE flow (§24).
- One throwaway Google account used **only** for the experiments (never a
  production account).
- A device that can run the Android app (emulator OK for Drive) and the
  Windows dev app.

## Recording convention

Every experiment records: date, GCP project id, client IDs used, the account,
each step's observed output, and a PASS / FAIL / PARTIAL verdict. Findings must
be pasted back to the orchestrator, not summarized from memory.

---

## Experiment 1 — cross-client `drive.file` visibility in one GCP project

**Question:** In one GCP project, can two clients (Desktop + Android) with only
the `drive.file` scope both see a file created by the other in the app folder?

This is the pivot test: if visibility FAILS, Phase 5/6 storage pivots to an
AppData folder + delegation (`drive.appdata`) and the engine/wire are
unaffected (spec §29.1).

**Steps**

1. Sign the throwaway account in on both clients under the SAME GCP project.
2. Android client creates file `00000000-0000-4000-8000-00000000000A.json`
   (content = any valid schema-v1 record) in the sync folder.
3. Windows client lists the sync folder. Record: is `...000A.json` present?
   What is its `id`, `name`, `parents`?
4. Windows client creates `...000B.json`. Android lists. Record visibility both
   ways.
5. Repeat with the OTHER account signed in on Android only — record whether
   Windows (account A) sees Android's account-B folder (expected: no — §13).

**PASS criteria**

- Files created by either client of the same project are visible to the other
  client's listing with `drive.file` scope only.
- Cross-account data is NOT visible (§13 namespace holds at the Drive layer).

**FAILS →** pivot decision recorded (AppData + delegation), engine unchanged.

---

## Experiment 4 — Desktop loopback PKCE token endpoint

**Question:** Does the Desktop client obtain a token through the loopback
PKCE (S256) flow with the real `oauth2.googleapis.com` endpoints, and can the
refresh token be used again after the access token expires?

**Steps**

1. Windows dev app opens `http://localhost:<port>/` listener, redirects to
   `https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=<desktop_id>&redirect_uri=http://localhost:<port>/&code_challenge=...&code_challenge_method=S256&scope=https://www.googleapis.com/auth/drive.file`.
2. Complete consent. Record: the authorization code arrives at the listener.
3. Exchange at `https://oauth2.googleapis.com/token` with
   `code_verifier`, `redirect_uri`, `grant_type=authorization_code`.
   Record: does the exchange succeed? What scopes were granted?
4. Refresh: call the token endpoint with `grant_type=refresh_token`.
   Record: new access token issued, no re-consent.
5. Security: verify `drive.file` (not `drive`) is the granted scope; verify the
   access token lives in memory only and the refresh token is saved to Windows
   Credential Manager (no token persisted to disk as plaintext).
6. Error paths: expired/revoked refresh token → `invalid_grant` → surfaced as
   reauth (§23 401 → reauth).

**PASS criteria**

- PKCE code exchange + refresh both succeed against the real endpoints.
- Granted scope is exactly `drive.file`.
- No access token persisted; refresh token only in Credential Manager.

**FAILS →** gate stays red; record the exact error and endpoint response.

---

## Experiment 2 — list-lag bounds (informative, tunes backoff)

**Question:** How stale can a `files.list` listing be after a create? Measures
the lag window that the duplicate-identical-absorb rule must tolerate (§10).

**Steps**

1. With both clients idle, create a file via the Android client. Poll the
   Windows client listing every 1s. Record the time until the new file appears
   and the observed ordering (insertion position vs alphabetical).
2. Repeat 10x. Record min/max/median lag and any occurrence where the listing
   was missing the file for > 30s.

**Output** — lag bounds to feed backoff constants; no gate impact beyond
documented ranges (§28 "backoff constant values within documented ranges").

---

## Experiment 3 — quota envelope (informative)

**Question:** With one account, what is the observed Drive quota budget and the
429/403 behavior envelope? No hardcoded quota numbers may enter the code (§28);
this only verifies `429`/`403` error mapping in §23 (Retryable / `NotOurs`).

**Steps**

1. Create files until a `403`/`429` is observed. Record status code + retry
   `Retry-After` (if any) and Google's error body.
2. Verify the app maps: `401` → reauth; `403 drive.file` → `NotOurs` (skip, no
   retry-bomb); `429`/5xx/timeouts → `Retryable` with backoff.

**Output** — recorded envelope + error-body samples.

---

## Experiment 5 — version-bump confirmation (informative)

**Question:** Does a schema-v1 file with an unknown `type` (spec §30.1) fail
cleanly (invalid → quarantine) rather than crash the parser? Confirms the
additive `type` discriminator is forward-safe.

**Steps**

1. Upload a valid record with `"type": "unknown_kind"`. Run a sync pass.
2. Record: group becomes DIVERGENT → quarantined, file never deleted, no crash.
3. Upload a `dictionary` record missing `spoken`/`corrected`. Same expectation.

**Output** — parser behavior logged; confirms §30.1 quarantine semantics.

---

## Experiment 6 — AppData scoping (informative, fallback storage)

**Question:** If Experiment 1 fails and the storage pivots to AppData
(`drive.appdata` scope + folder), confirm the app folder is user-invisible and
works for `list`/`get`/`create`/`update`.

**Steps**

1. Enable `drive.appdata` scope on both clients.
2. Create/list/get/update files in the AppData folder on both clients.
3. Verify the folder is NOT visible in the user's normal Drive UI.

**Output** — whether the fallback storage path is viable (only needed if
Experiment 1 FAILS).

---

## Gate summary

| Exp | Gates | Current status |
|---|---|---|
| 1 | Phases 5/6 | PENDING |
| 4 | Phases 5/6 | PASS (2026-08-15) |
| 2 | none (backoff tuning) | PENDING |
| 3 | none (error mapping) | PENDING |
| 5 | none (forward compat) | PENDING |
| 6 | only if Exp 1 fails | PENDING |

Verdicts and raw outputs are pasted back to the orchestrator before Phases 5/6
are dispatched.

## Recorded results

### Experiment 4 — PASS (2026-08-15)

- Date: 2026-08-15. Client: Desktop app type, client id
  `236666538373-005rdohmcf6cgh0in10v5v8nhcc1m85k.apps.googleusercontent.com`,
  loopback port 58611, account = throwaway test user (consent screen External
  testing).
- Steps: PKCE S256 challenge generated; consent completed in browser; auth code
  arrived at the loopback listener; exchange at
  `https://oauth2.googleapis.com/token` succeeded; refresh with
  `grant_type=refresh_token` succeeded (new access token, no re-consent);
  refresh token stored in Windows Credential Manager (`FluenceSyncExp4`);
  bogus refresh token → `invalid_grant` observed.
- Granted scope exactly `drive.file`.
- **Finding:** the token endpoint **requires `client_secret`** for this Desktop
  client. A secret-less PKCE exchange returned
  `400 invalid_request "client_secret is missing."`. PKCE is enforced by Google
  (S256 challenge required) but does not waive the secret for installed-app
  clients; only "TVs & limited-input devices" get secret-less exchange. The app
  already models this via `OAuthConfig.client_secret` (Windows auth.rs) and the
  Android client secret (system-browser flow); no spec change required.
- Verdict: `VERDICT: PASS - PKCE exchange + refresh succeed; scope exactly
  drive.file; refresh in Credential Manager`
