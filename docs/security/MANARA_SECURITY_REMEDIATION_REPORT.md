# Manara — security remediation report (assessment of 10 September 2026)

> Nothing here is merged or deployed. Every change is an open pull request against `develop`, and a
> release needs a tag push. No production system was attacked or modified. Evidence, harness and
> tooling are in the workspace folder `security-remediation/2026-09-11/`; paths below are relative to it.

## Scope and baseline

| Repository | Assessed revision (report) | Remediation base (`origin/develop`, 2026-09-11) |
|---|---|---|
| `Manara-Education/backend-foundation` | `b6b49b3fb346458f570d29f235bd260f68ddd73b` | `823a825` |
| `Manara-Education/frontend-foundation` | `8dd8b65bbc10933af91551204e0f394e27801be5` | `de81dd4` |
| `Manara-Education/manara-infrastructure` | `ffe517552378116287caa715d972f913e5471513` | `ba6b676` |

The assessed revisions sat on a side branch (`fix/report-the-fix-you-can-actually-upgrade-to`)
and are not ancestors of `develop`. Each finding was therefore re-checked against current
`develop` before anything was changed. Evidence, harness and tooling are in
`security-remediation/2026-09-11/` (`TRACKER.md`, `evidence/<task>/`, `integ/`).

## Status summary

| Task | Finding | Status |
|---|---|---|
| SEC-F01 | Cross-instructor private/draft metadata | `FIXED_AND_VERIFIED` (PR open) |
| SEC-D01 | Demonstration checkout vs paid entitlement | `PARTIALLY_FIXED`: safeguards verified; real provider **BLOCKED** (none selected) |
| SEC-F02 | Weak password accepted | `FIXED_AND_VERIFIED` (PRs open) |
| SEC-F03 | CSP report-only | `FIXED_AND_VERIFIED` (PR open) |
| SEC-F04 | Caddy as container root | `ALREADY_FIXED_AND_VERIFIED` on `develop`; production ACME renewal unverified |
| SEC-D02 | Dev DB ports on all interfaces | `FIXED_AND_VERIFIED` (PR open) |
| SEC-F05 | Registration account enumeration | `FIXED_AND_VERIFIED` (PRs open) |
| SEC-F06 | Upload validation | `FIXED_AND_VERIFIED` (PR open) |
| SEC-F07 | Session ceiling ineffective | `FIXED_AND_VERIFIED` (PR open) |
| SEC-X01 | Malformed input → 500 | `FIXED_AND_VERIFIED` (PR open) |
| SEC-X02 | HTTPS/cookies/proxy/HSTS/exposed APIs | `ALREADY_FIXED_AND_VERIFIED` (configuration); HSTS = owner decision |
| SEC-X03 | Redis-outage rate limiting | `FIXED_AND_VERIFIED` (PR open) |
| SEC-X04 | Dependency/container/secret scans, image digests | `FIXED_AND_VERIFIED`: scans complete; the one new finding (introduced and fixed here) is resolved; two inherited image findings remain tracked |

## Findings

### SEC-F01 — instructor catalogue (High) — `FIXED_AND_VERIFIED`
- **Root cause:** `CourseService.getAllCourses` required only INSTRUCTOR or ADMIN, then returned `findAllWithInstructor()`. INSTRUCTOR is self-assignable at registration, so anyone who could confirm an emailed code could read other instructors' draft and private course metadata.
- **Fix:** the list is scoped in the query. INSTRUCTOR gets `findAllOwnedByUserWithInstructor(principal id)`, ADMIN (not self-assignable) gets the platform-wide list, and learners are refused. Every other instructor read path was inventoried and was already scoped. No co-instructor model exists, and self-registration and onboarding are unchanged.
- **Verification:**
  - Before: a new HTTP test with real signup and verify-otp ran 5 tests with 1 failure; the stranger's list contained the other instructor's DRAFT+PRIVATE marker.
  - After: 114 focused tests, 0 failures, and PR CI green.
- **Delivery:** backend `fix/security-f01-scope-instructor-catalogue` → `a189a6e` → [be#78](https://github.com/Manara-Education/backend-foundation/pull/78).

### SEC-D01 — commerce mode (conditional High) — `PARTIALLY_FIXED`
- **Root cause:** `manara.commerce.mode` defaulted to DEMONSTRATION in every profile, and the production compose file never set it. A deployment could therefore grant paid access against simulated receipts without anyone choosing that.
- **Fix, backend:**
  - The `prod` profile refuses to start without an explicit mode.
  - A new `FREE_ONLY` mode means free enrolment works and paid checkout is refused before any write.
  - Simulated receipts grant nothing outside DEMONSTRATION: the simulator and the grant point both check.
  - Checkout responses gain an additive `simulated` flag.
  - The LIVE guard now fires before bean creation, with its own message.
- **Fix, infrastructure:** compose requires `MANARA_COMMERCE_MODE` via `:?`.
- **Verification:**
  - 141 focused tests, 0 failures.
  - Full application starts: DEMONSTRATION simulates; FREE_ONLY refuses paid checkout with zero rows written and the lesson still locked; LIVE without a provider fails with the guard's message.
  - Compose: all three values render; absent or blank is refused; a negative control confirms the old file rendered with no mode.
  - Forged amount, price, currency and status fields are ignored (stored 499.00 EGP).
- **Blocked:** real paid checkout. No provider is selected, so there is no webhook verification, order binding or once-only fulfilment. No callback or return endpoint exists to forge.
- **Delivery:** backend `b4b1a6a` → [be#81](https://github.com/Manara-Education/backend-foundation/pull/81); infrastructure `a3136a8` → [infra#12](https://github.com/Manara-Education/manara-infrastructure/pull/12), same branch name.
- **Rollout:** set `MANARA_COMMERCE_MODE=DEMONSTRATION` in `.env`, deploy infra#12, deploy the backend, then switch to `FREE_ONLY` (owner's decision).

### SEC-F02 — password policy (Medium) — `FIXED_AND_VERIFIED`
- **Root cause:** `@Size(min = 6)` only. A password over 72 bytes reached BCrypt and returned 500.
- **Fix:** one policy for register, change and reset:
  - at least 15 code points and at most 72 UTF-8 bytes, checked before encoding;
  - a bundled SecLists blocklist (MIT, 15,848 entries);
  - repeat, service-name and personal-detail rules;
  - NFKC applied for comparison only, so existing hashes and sign-in are unchanged.
- **Frontend:** the same limits, a length meter in place of composition scoring, and Arabic guidance.
- **Verification:**
  - Before: 22 HTTP tests with 17 failures (`123456` got 201, over 72 bytes got 500).
  - After: 120 backend tests, 0 failures; frontend typecheck 0, vitest 29/29, build passes.
- **Delivery:** backend `53482ee` (rewritten from `9266bd7`, see below) → [be#82](https://github.com/Manara-Education/backend-foundation/pull/82); frontend `0d07898` → [fe#109](https://github.com/Manara-Education/frontend-foundation/pull/109).
- **Existing weak credentials:** no forced reset. A soft prompt at next sign-in is proposed separately; MFA for privileged accounts is separate work.

### SEC-F03 — CSP enforcement (Medium, hardening) — `FIXED_AND_VERIFIED`
- **Fix:** `Content-Security-Policy` now enforces. Directives were re-derived from the source and the built output, plus `https://i.ytimg.com` in `img-src`, which a browser run showed the page fetches during YouTube playback. `verify-container-runtime.sh` asserts enforcement and `script-src 'self'`.
- **Verification:**
  - Canary: report-only gave `INLINE_EXECUTED` and `HANDLER_EXECUTED`; enforced gave `INLINE_BLOCKED` and `HANDLER_BLOCKED` (headless Chrome through Caddy).
  - Integration browser run 4, full flow set: **0 enforced CSP violations**. Sign-in, registration and OTP, password recovery, the course editor, rich content, YouTube and Vimeo playback, and demonstration checkout all worked.
- **Delivery:** frontend `54753eb`, `96d87b6` → [fe#108](https://github.com/Manara-Education/frontend-foundation/pull/108) (ready for review).
- **Not a CSP effect:** the registration-OTP sign-in ending on `/login` in automated runs is a pre-existing race in `develop`'s `shared/auth/auth-context.tsx`. With a 2.5 s settle, 3 of 3 attempts signed in; without it, 3 of 4 failed, with identical API traces (`evidence/integration/otp-signin-race.md`).

### SEC-F04 — non-root Caddy (Medium, hardening) — `ALREADY_FIXED_AND_VERIFIED`
- Merged before this remediation, in fe#102 and infra#11.
- `develop`'s CI run `34471218052` (`de81dd48`): 28 PASS, 0 FAIL. PID 1 runs as uid 1001; HTTPS on 443 via Caddy's internal CA; a root-owned volume migration preserves the certificate; persistence survives restart; the health check runs non-root.
- Re-run on #108: 30 PASS, 0 FAIL.
- **Remaining production check:** Let's Encrypt renewal on the migrated production volume.

### SEC-D02 — development database ports (Medium if reachable) — `FIXED_AND_VERIFIED`
- **Fix:** ports published on `127.0.0.1`, no default database password, and Redis AUTH turned on when `REDIS_PASSWORD` is set.
- **Verification:**
  - Before: `*:5432` and `*:6379`; both accepted connections on the LAN address, and an unauthenticated Redis `PING` returned `+PONG`.
  - After: bound to `127.0.0.1` only, the LAN is refused, container-to-container traffic works, the old default password is refused, and Redis without AUTH gets `NOAUTH`.
- **Delivery:** `6c7fa16` → [be#79](https://github.com/Manara-Education/backend-foundation/pull/79).
- Production does not publish these ports; this finding makes no production claim.

### SEC-F07 — session ceiling (Low) — `FIXED_AND_VERIFIED`
- **Root cause:** `maximumSessions(5)` configures Spring Security's in-memory concurrency control, which the custom login flow never invoked.
- **Fix:** a per-account Redis sorted set maintained by one Lua script. It counts a session after Spring Session stores it and deletes the oldest past five, atomically across instances. Rotation, CSRF, logout and revocation are preserved.
- **Verification:**
  - Before: 8 tests, 6 failures.
  - After: 8/8, plus 28/28 existing session and auth tests.
  - A second real instance with 10 concurrent logins leaves exactly 5 sessions.
- **Delivery:** `4e7f016` → [be#80](https://github.com/Manara-Education/backend-foundation/pull/80).

### SEC-X03 — Redis-outage rate limiting — `FIXED_AND_VERIFIED`
- **Root cause:** `RateLimiter` returned *allow* on any Redis failure, and logged a WARN on every request.
- **Reproduced** with a real client pointed at a closed port: 20 of 20 logins and 20 of 20 OTP verifications reached the endpoint, registration went 12 of 12 past a limit of 5, and 60 WARN lines were logged.
- **Fix:** each rule has an outage policy.
  - `login` and `otp-verify` are **refused with 503 + `Retry-After` before authentication**; they need the Redis session store anyway.
  - The rest keep limiting with a bounded, per-instance fallback (≤10,000 keys, fail-closed when full).
  - Outage logging happens at most once a minute.
- **After:** 0 of 20 logins reach authentication; registration and recovery are capped at 5; 15 of 15 tests pass.
- **Delivery:** `02577bf` → [be#83](https://github.com/Manara-Education/backend-foundation/pull/83).

### SEC-F06 — upload validation (Low) — `FIXED_AND_VERIFIED`
- **Root cause:** only the header's dimensions were checked, and the original bytes were stored. A 33-byte header-only PNG and a PNG with a script appended were both accepted and served byte-for-byte.
- **Fix:** accept only content that decodes as PNG, JPEG or GIF. Bound dimensions, pixels and decode memory from the header, decode fully (warnings count as a refusal), refuse animation, apply EXIF orientation, and write **fresh bytes with no metadata** under the extension of the format actually written. The temp file is moved atomically and deleted on failure.
- **Verification:**
  - Before: 39 tests, 23 failures.
  - After: 39/39.
- **Behaviour changes:** BMP, TIFF and WBMP are now refused, and images between 16.7 and 50 MP are refused as too large. WebP is still always rejected (no decoder); whether to drop it from the allow-list or add a decoder is a decision for the team.
- **Delivery:** `7629b24` → [be#84](https://github.com/Manara-Education/backend-foundation/pull/84).

### SEC-F05 — registration enumeration (Low) — `FIXED_AND_VERIFIED`
- **Root cause:** an existing address got 400 "Email is already registered" and a new one 201. The existing path also skipped bcrypt and every insert, so its median was 5.2 ms against 96.4 ms.
- **Fix:**
  - The same 201 and body for every address. An existing account is never modified.
  - The owner is emailed a notice at most once an hour, throttled in Redis by SHA-256 of the canonical address; if Redis fails, the notice is skipped.
  - The duplicate-insert race is settled outside the failed transaction.
  - bcrypt always runs, and both emails are sent after commit, off the request thread.
  - The frontend OTP page explains without revealing which case applied.
- **Verification:**
  - Before: 16 tests, 8 failures. 7 of 8 concurrent duplicates got 400.
  - After: 53/53 Testcontainers tests plus unit tests. 8 concurrent duplicates all got an identical 201, leaving 1 row. Medians are now 80.4 ms against 85.9 ms: comparable, not constant.
  - Frontend: 19/19, typecheck and build pass.
- **Residual:** unverified-account squatting (pre-existing), an unsalted throttle hash, per-IP-only limits on recovery endpoints, and a ~5 ms timing gap.
- **Delivery:** backend `5018265` → [be#85](https://github.com/Manara-Education/backend-foundation/pull/85); frontend `fe070aa` → [fe#110](https://github.com/Manara-Education/frontend-foundation/pull/110).

### SEC-X01 — malformed input (observation) — `FIXED_AND_VERIFIED`
- **Root cause:** Spring MVC's binding and matching exceptions fell through to the catch-all 500. A 406 arrived with an empty body, and upload failures were never classified.
- **Fix:**
  - 400 for a type mismatch, a missing parameter or a missing part;
  - 405 with `Allow`, 415 with `Accept`, 406 in JSON, 413 for oversize uploads;
  - a multipart failure is 400 only when Tomcat's parser refused the body;
  - localised messages that never echo the value; `consumes = multipart/form-data` on uploads;
  - genuine server faults stay 500.
- **Verification:**
  - Before: 8 probes returned 500.
  - After: 43/43, including real-port probes (the two reported probes → 400, uploads → 413 through Tomcat) and three 500 controls.
- **Found along the way:**
  - A test-harness pitfall: Spring Security's MockMvc `csrf()` swaps the shared `CsrfFilter` repository and breaks real-port requests in the same context.
  - A CSRF refusal reaches clients as an empty 401 rather than 403 (`/error` requires authentication). Not changed.
- **Delivery:** `f166ea8` → [be#86](https://github.com/Manara-Education/backend-foundation/pull/86).

### SEC-X02 — deployment configuration — `ALREADY_FIXED_AND_VERIFIED` (configuration)
See `evidence/x02/deployment-configuration.md`.
- HTTPS works, with valid certificates on all three hostnames, 308 redirects, and a Secure XSRF cookie.
- Caddy replaces client-supplied forwarded headers.
- Actuator and API docs are neither proxied nor enabled in production.
- **HSTS:** two of the three preconditions hold; the third, that the hostname is settled, is the owner's decision.

### SEC-X04 — scans and candidate image digests — `FIXED_AND_VERIFIED`
- gitleaks found 0 across all refs of all three repositories.
- trivy fs found 0 vulnerabilities (`pom.xml` and `package-lock.json` analysed) and 0 failed Dockerfile misconfigurations.
- osv-scanner found 0 on the frontend lockfile.
- **Coverage gap:** trivy analysed no targets in the infrastructure repository, because it has no Compose checks.
- **Gate incident, found and fixed during this remediation:** F02's first push (`9266bd7`) carried a synthetic common password (`1qaz2wsx3edc4rfv`) in a test. gitleaks' `generic-api-key` rule flagged it. The gitleaks job fetches every branch and scans all refs, so while that commit was on the remote the Security Gate failed on **every** backend PR (#82–#86).
  - Fix: the fixture was replaced with another blocklisted value in all three tests, and F02's single commit was rewritten (`53482ee`) and force-pushed with lease. There is no allowlist and the gate is not weakened.
  - A re-sweep over every remote branch found 0 findings in all three repositories.
- **Frontend candidate image** `manara-frontend:candidate-0b63435`: local build of `develop` + F03 + F02 + F05, not pushed.
  - Image ID (config digest) `sha256:56b9dc92e9d65eb155966fc712e437799ce325bd233d3d340f52f2aafd6ff47a`, running as `USER=caddy`.
  - Trivy 0.74.0 finds two issues, both in the Caddy binary and both inherited from `develop`'s image (no task changed the Dockerfile):
    - **MEDIUM** GHSA-gcjh-h69q-9w9g, `github.com/google/cel-go` 0.28.1, fixed in 0.29.0. This is the already-tracked finding: the fix does not compile against Caddy.
    - **UNKNOWN** GO-2026-5932, `golang.org/x/crypto` 0.56.0, with no fixed version published.
  - The frontend Security Gate passes on fe#108, fe#109 and fe#110.
- **Backend candidate image** `manara-backend:candidate-4103964`: local build of the full backend candidate, not pushed.
  - Image ID (config digest) `sha256:f3928cd4c2c73c4664af004d1a1339c108720613fda9ed1016637128056b21eb`, 151,281,036 bytes, running as `USER=app`.
  - Trivy 0.74.0, including the Java database, finds **0 vulnerabilities**: 0 in the Alpine 3.24.1 packages and 0 in the Java JARs.
  - The first attempt hit Trivy's default 5-minute timeout while it downloaded the 1.4 GB Java database. It was re-run with `--timeout 20m` against that fresh database.
- Evidence: `evidence/x04/` (`summary.txt`, `trivy-*.json`, `gitleaks-*`, `*-candidate-image.txt`).

## Combined candidate verification

- **Backend candidate `4103964`** is `develop` `823a825` plus F01 `a189a6e`, D02 `6c7fa16`, F07 `4e7f016`, D01 `b4b1a6a`, F02 `53482ee`, X03 `02577bf`, F06 `7629b24`, F05 `5018265` and X01 `f166ea8`.
  - All nine merged cleanly, and Flyway versions stay dense at V1–V16.
  - **`./mvnw -B -ntp verify`: 1,194 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS.** `develop`'s last CI baseline was 1,043.
- **Frontend candidate `0b63435`** is `develop` `de81dd4` plus F03 `96d87b6`, F02 `0d07898` and F05 `fe070aa`.
  - typecheck 0 errors, vitest 30 files / 354 tests, build OK, 0 inline `<script>`.
- **Final browser run through Caddy on both candidates: 19/19 steps pass, 0 enforced CSP violations, 0 exceptions, 0 server errors.**
  - **Setup:** the backend candidate ran as the real application on Testcontainers PostgreSQL and Redis, with email mocked. The frontend candidate's build was served by `caddy:2-alpine` with the candidate Caddyfile. Flows were driven in headless Chrome over the DevTools protocol, capturing CSP issues, console errors and every `/api` response.
  - **Flows covered:**
    - registration → OTP (the F05 note was screenshotted) → signed in;
    - instructor sign-in, the course editor, and the YouTube still (`img.youtube.com`);
    - an authoring command (`connect-src 'self'` + CSRF);
    - a rich-content lesson;
    - YouTube and Vimeo players loading, talking over `postMessage`, and playing;
    - demonstration checkout (`simulated: true`, D01) and the purchased lesson;
    - forgot-password → code → reset → sign in with the new password, using a phrase compliant with F02's policy.
  - The only console errors are the anonymous `/auth/me` 401s the app makes before sign-in.
  - **Evidence:** `evidence/integration/browser-flows-final.json`, `final-run.log`, and `evidence/f05/screens/`.
  - **Earlier runs:** they found and resolved the `i.ytimg.com` CSP gap. They also found a pre-existing auth-bootstrap race that only automation can trigger (`evidence/integration/otp-signin-race.md`); run 4 is in `browser-flows-run4-final.json`.

## Observations (not findings)
- The registration-OTP → `/login` race in automated runs described under F03.
- `spring.session.*` properties in `application.properties` have no effect: Spring Boot's session auto-configuration isn't in the build. Flush mode and timeout equal the defaults, and the namespace is now named in code (noted in be#80).

## Cross-repository dependencies and rollout order

Every change is an open PR against `develop`. Merging `develop` deploys nothing in these repositories: a release needs a `vX.Y.Z` tag, and backend goes before frontend.

**Merge order.** Each PR is independent in code. These orderings avoid friction:

| Order | PR(s) | Reason |
|---|---|---|
| any | be#78 F01, be#79 D02, be#80 F07, be#83 X03, be#84 F06, be#86 X01, fe#108 F03 | Disjoint files. The combined candidate merged cleanly. |
| F02 before F05 | be#82 → be#85; fe#109 → fe#110 | Both touch registration: F02 changes the DTOs, F05 changes `register()`. A dry-run merge is clean either way; F02 first keeps review simple. |
| infra before backend | infra#12 → be#81 (D01) | Compose must pass `MANARA_COMMERCE_MODE` before a backend that requires it under `prod` is deployed. |

**Rollout, when a release is cut:**
1. Production `.env` on the host: add `MANARA_COMMERCE_MODE=DEMONSTRATION`, which today's running backend already accepts.
2. Deploy infra#12. Compose then refuses to run without the variable.
3. Tag and deploy the backend release (F01, D01, D02, F02, F05, F06, F07, X01, X03).
4. Tag and deploy the frontend release (F02, F03, F05). F05's frontend expects F05's uniform 201; F02's forms expect F02's policy.
5. **Owner decision:** switch `.env` to `FREE_ONLY` and recreate the backend. This is the recommendation while no payment provider exists.
6. First sign-in after deploy: sessions opened before F07 are counted only once they are re-established. No forced sign-out is needed for this remediation.

## Rollback considerations

| Change | Rollback | Watch out for |
|---|---|---|
| D01 | Set `MANARA_COMMERCE_MODE=DEMONSTRATION` **before** rolling the backend back. | An older backend does not understand `FREE_ONLY` and refuses to start with it. |
| F03 (enforced CSP) | Revert the Caddyfile header name to `Content-Security-Policy-Report-Only`; the directives stay. | A missed origin shows up as a blocked resource, not a hole. |
| F05 | A plain revert restores the duplicate-address 400. | Notices already sent can't be recalled; none carries a secret. |
| F06 | A plain revert restores header-only validation. | Files stored after F06 are re-encoded PNG/JPEG and stay valid. |
| F07 | A plain revert removes the ceiling. | Leftover `manara:account-sessions:*` sets expire within 30 days. |
| F02 | A plain revert restores the 6-character minimum. | Passwords set under the policy keep working after rollback, since hashing is unchanged. |
| X03 | A plain revert restores fail-open. | — |
| D02 | Development only. | — |
| F01, X01 | Plain reverts. | Reverting brings the vulnerabilities back. |

## Remaining blockers and production checks

**Blocked on a decision or an external dependency:**
- **D01 real payments:** no payment provider has been selected. The provider-specific work is still to do: webhook verification with the provider's official mechanism, order binding, once-only fulfilment, and a real gateway.
- **Commerce mode in production:** the owner chooses between `FREE_ONLY` (recommended) and `DEMONSTRATION`.
- **HSTS:** the owner confirms the canonical hostname, then enables `max-age=86400` (no `includeSubDomains`, no `preload`).
- **F06 WebP:** either drop it from the allow-lists or add a decoder.
- **F05:** unverified-account squatting policy, and an HMAC key for the throttle hash.

**Production checks this remediation could not perform:**
- The deployed image digests and releases. Production currently serves **no** CSP header, which suggests a frontend older than `develop`.
- Let's Encrypt renewal on the migrated non-root Caddy volume (F04).
- The production `MANARA_SESSION` cookie attributes on a signed-in response, and the host firewall.
- Redis-outage behaviour under production load (X03 was verified at the filter level with a genuinely unreachable client).

**CI follow-up, pre-existing:** the scheduled `Security` run cannot evaluate the `main` target (`evidence/x04/develop-scheduled-gate.md`).


## Pull requests

| Repository | PR | Task | Head |
|---|---|---|---|
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/78 | SEC-F01 | `a189a6e` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/79 | SEC-D02 | `6c7fa16` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/80 | SEC-F07 | `4e7f016` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/81 | SEC-D01 | `b4b1a6a` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/82 | SEC-F02 | `53482ee` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/83 | SEC-X03 | `02577bf` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/84 | SEC-F06 | `7629b24` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/85 | SEC-F05 | `5018265` |
| backend-foundation | https://github.com/Manara-Education/backend-foundation/pull/86 | SEC-X01 | `f166ea8` |
| frontend-foundation | https://github.com/Manara-Education/frontend-foundation/pull/108 | SEC-F03 | `96d87b6` |
| frontend-foundation | https://github.com/Manara-Education/frontend-foundation/pull/109 | SEC-F02 | `0d07898` |
| frontend-foundation | https://github.com/Manara-Education/frontend-foundation/pull/110 | SEC-F05 | `fe070aa` |
| manara-infrastructure | https://github.com/Manara-Education/manara-infrastructure/pull/12 | SEC-D01 | `a3136a8` |

All are open against `develop` and not drafts. `Mohamed-Hamza` is requested as reviewer on each. CI is green on all 12 app-repository PRs; the infrastructure repository has no CI workflows.

## Closing table

| Task ID | Finding | Repository | Branch | Commit(s) | PR | Verification | Status |
|---|---|---|---|---|---|---|---|
| SEC-F01 | F01 cross-instructor metadata | backend | fix/security-f01-scope-instructor-catalogue | a189a6e | be#78 | before 1 failure → after 114/0; CI green | FIXED_AND_VERIFIED |
| SEC-D01 | D01 demo checkout vs entitlement | backend + infra | fix/security-d01-explicit-commerce-mode | be b4b1a6a · infra a3136a8 | be#81 · infra#12 | 141/0; full starts in 3 modes; compose 3 values, absent/blank refuse | PARTIALLY_FIXED (provider BLOCKED) |
| SEC-F02 | F02 password policy | backend + frontend | fix/security-f02-password-policy | be 53482ee · fe 0d07898 | be#82 · fe#109 | before 17 failures → 120/0 (+42/0 after fixture fix); fe 29/29 | FIXED_AND_VERIFIED |
| SEC-F03 | F03 CSP enforcement | frontend | fix/security-f03-enforce-csp | 54753eb, 96d87b6 | fe#108 | canary EXECUTED→BLOCKED; browser run: 0 enforced violations | FIXED_AND_VERIFIED |
| SEC-F04 | F04 Caddy as root | frontend + infra | — (already on develop: fe#102, infra#11) | — | — | CI: uid 1001, HTTPS, volume migration, restart | ALREADY_FIXED_AND_VERIFIED |
| SEC-D02 | D02 dev DB ports | backend | fix/security-d02-loopback-dev-databases | 6c7fa16 | be#79 | before LAN accepts + unauth PONG → after loopback only, auth enforced | FIXED_AND_VERIFIED |
| SEC-F05 | F05 registration enumeration | backend + frontend | fix/security-f05-uniform-registration | be 5018265 · fe fe070aa | be#85 · fe#110 | identical 201; 53/53 + unit; fe 19/19 | FIXED_AND_VERIFIED |
| SEC-F06 | F06 upload re-encoding | backend | fix/security-f06-reencode-uploaded-images | 7629b24 | be#84 | before 23 failures → 39/39 | FIXED_AND_VERIFIED |
| SEC-F07 | F07 session ceiling | backend | fix/security-f07-enforce-session-ceiling | 4e7f016 | be#80 | before 6 failures → 8/8 + 28/28; 2 instances, 10 concurrent → 5 | FIXED_AND_VERIFIED |
| SEC-X01 | malformed input → 4xx | backend | fix/security-x01-malformed-input-400 | f166ea8 | be#86 | before 8×500 → 43/43 incl. real port | FIXED_AND_VERIFIED |
| SEC-X02 | HTTPS/cookies/proxy/HSTS/APIs | all | — | — | — | passive prod check + local Caddy header test | ALREADY_FIXED_AND_VERIFIED (HSTS: owner decision) |
| SEC-X03 | Redis-outage rate limiting | backend | fix/security-x03-rate-limit-redis-fallback | 02577bf | be#83 | before 20/20 logins through → 0/20; 15/15 | FIXED_AND_VERIFIED |
| SEC-X04 | scans + image digests | all | — (F02 rewrite fixed the one new finding) | — | — | gitleaks 0/0/0 on all remote branches; trivy fs 0; backend image 0; frontend image 1 MEDIUM (cel-go, tracked) + 1 UNKNOWN (x/crypto, no fix), both inherited | FIXED_AND_VERIFIED |
| SEC-DOC | this report | backend | fix/security-doc-remediation-report | this commit | this PR | — | delivered |
