# Phase 03 handoff — email change with a code

**State:** implemented and verified (backend + frontend); export-level visual parity not verified (X1).

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p03-email-change` (stacked on `feat/manara-p02-profile-api`) | `feat/manara-p03-email-change-ui` (stacked on `feat/manara-p02-avatar-ui`) |
| Commit | `76e5b13` | `b4f68c3` |
| Migration | `V18__email_change_requests.sql` | — |
| Config | `EMAIL_CHANGE_CODE_SECRET` (HMAC key; optional — unset means a per-run key and pending codes die at restart) | — |

## Contract
- `POST /api/v1/profile/email/change-requests` `{currentPassword, newEmail}` → `{requestId, maskedEmail, codeLength, expiresAt, resendAvailableAt, expiresInSeconds, resendAvailableInSeconds}`.
  Errors: 400 `EMAIL_CHANGE_PASSWORD_INVALID`, 400 `EMAIL_CHANGE_SAME_ADDRESS`, 400 validation (`newEmail: …`), 429 `EMAIL_CHANGE_COOLDOWN` (+`Retry-After`). A taken address returns the normal 200 and sends nothing.
- `POST …/resend` `{requestId}` → same shape. 429 cooldown; 429 `EMAIL_CHANGE_LOCKED` after 3 resends or when locked; 400 `EMAIL_CHANGE_EXPIRED` / `EMAIL_CHANGE_CODE_INVALID`.
- `POST …/verify` `{requestId, code}` → `ProfileResponse` and a rotated session (other sessions end via `authVersion`). 400 `EMAIL_CHANGE_CODE_INVALID` (wrong, foreign or used), 400 `EMAIL_CHANGE_EXPIRED` (expired or superseded), 429 `EMAIL_CHANGE_LOCKED` (5 wrong codes), 409 `EMAIL_CHANGE_UNAVAILABLE` (address taken since the request).
- Policy: code 10 min; resend cooldown 60 s; ≤ 3 resends; attempts cumulative per request; 30 min absolute lifetime; one pending request per account; a new request supersedes the old one. Codes stored only as HMAC-SHA256(key, requestId:generation:code).
- Rate limits: `email-change` 5/15 min, `email-change-resend` 5/15 min, `email-change-verify` 10/10 min (refuses while Redis is down, like sign-in).
- Notice to the previous address after commit (existing informational template); a delivery failure does not undo the change.

## Validation
- Backend `./mvnw -o clean verify`: 1377 tests, 0 failures, 1 skipped. `EmailChangeFlowTest` (12): wrong password, same/malformed address, enumeration-neutral taken address, success + session rotation + old-session refusal + replay + sign-in by new address, notice failure, uniqueness race at verification, concurrent verification (exactly one 200), expiry, supersession, resend cooldown/replacement, 5-attempt lock, owner scoping, no plaintext code stored. Negative control: sending codes to taken addresses fails the enumeration test. `RateLimiterTest` updated: `email-change-verify` joins the refuse-on-outage rules.
- Frontend: typecheck ✔; 53 files / 678 tests ✔; build ✔. New: 5 email-flow tests; negative control (the auth screen's `\\D` paste regex) fails the paste test.
- Live: P03 jar + Vite + headless Chrome at 390 px: send → wrong code (400, attempts=1) → right code (200) → email changed, `auth_version` 0→1, caller's rotated session still valid. The code was recovered from `code_hash` with the disposable environment's own key; there is no local mail sink.

## Limitations
- Visual stepper follows the report, not the export (X1).
- Real delivery of the code and notice was not exercised (no mail sink; Resend unconfigured locally).
