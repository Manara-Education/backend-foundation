# Phase 11 — final validation (phases 00–10)

**Date:** 2026-09-27 · **Result:** all executable work verified. Provider-dependent and policy-dependent
scope remains blocked as listed. This is an engineering evaluation, not a release decision; see
`release-readiness.md`.

## Commit pair evaluated

| Repo | Stack top | Contains (base first) |
|---|---|---|
| backend-foundation | `012b4b9` `feat/manara-p10-refund-requests` | `develop@ac72672` → P02 `10e0007` → P03 `76e5b13` → P04 `7a1a5ca`, `04ad8a7` → P06 `cd90573`, `dcb32dd` → P07 `f4c4c1d` → P10 `012b4b9` |
| frontend-foundation | `9218168` `feat/manara-p10-refund-request-ui` | `develop@d4094cf` → P01 `3a1cd39` → P02 `8422566` → P03 `b4f68c3` → P04 `8df5f38` → P05 `9a569b2` → P06 `a9d85d9` → P11 core `eb0194f`, `4d7fd2a` → P07 `040d968` → P10 `9218168` |
| backend docs | `update/manara-figma-integration-plan` | this directory (independent of the code stack) |
| frontend docs link | `update/manara-figma-integration-link` `632a387` | README link only |

`origin/develop` re-fetched on 2026-09-27: still `ac72672` / `d4094cf`, and both stack tops contain
it, so each top is its own merge result.

## Checks

| Check | Result |
|---|---|
| Backend `./mvnw -o verify` at `012b4b9` | 1414 tests, 0 failures, 1 skipped |
| Frontend typecheck / `npm test` / `VITE_API_BASE_URL=/api` build at `9218168` | 0 errors / 722 tests / built |
| Migrations | Fresh install V1–V21 in every Testcontainers suite; upgrade V20 → V21 on the disposable DB holding the core checkpoint's legacy data; V1–V16 → V20 upgrade with legacy data verified at the core checkpoint (unchanged since). |
| Integrated journeys (headless Chrome → Vite → P10 jar, DEMONSTRATION, refund switch off) | 37/37 on the final SHAs. |
| Refund intake live (switch on, one inserted LIVE row) | with and without a reason → `SUBMITTED 450.00`; payment stays `PAID`, refunded 0. Scratch rows removed; backend back to defaults. |
| Payment-methods tab live (P07 jar) | RTL, Cairo, no overflow at 1280/390, demonstration wording. |
| Secrets | `gitleaks` over every new commit since the core checkpoint (both repos): no leaks. |
| Dependencies | `pom.xml`, `package.json`, `package-lock.json` unchanged since the core checkpoint's clean `osv-scanner` run (151 packages). |

### Harness notes (not product defects)
- The first final journey run failed 2 checks because the restart script's readiness probe was
  answered by the old backend while it shut down; the script now waits for the port to free.
- A later run failed 4 checks whose fixed settle (2.5–3 s) sat below the app's 2 s auth-bootstrap
  minimum plus dev-server module loading (measured 3.3 s); settles raised to 5 s, then 37/37.
- A Vite dev server without `VITE_API_BASE_URL=/api` makes the SPA call `/v1/...`, which falls
  through to `index.html` and reads as signed out.

## Defects found and fixed after the core checkpoint
1. Refund requests required a reason; the published Terms §6 grant the refund without one. Reason
   made optional end to end (`012b4b9`, `9218168`).
2. The refund window was an exact 336 h, up to a day stricter than "14 days from the purchase date";
   now inclusive calendar days (fixed-clock test).
3. "·" beside Arabic-Indic digits in the new refund lines and the receipt line read as a zero; Arabic
   comma used (D14).
4. The core checkpoint's own test fixture: a quote test depended on test order; it now creates its own
   course (`dcb32dd`).

## Final gap matrix

Status: **done** (implemented and verified), **fallback** (the prompt's stated fallback delivered;
full item blocked), **blocked** (prerequisite named), **excluded** (deliberately not built).

| ID | Status | Evidence / blocker |
|---|---|---|
| A1 Avatar | done | P02; O1 is a pre-existing instructor-upload issue, not this endpoint |
| A2 Password last changed | done | P02; NULL for existing rows (D5) |
| A3 Verified badge | done | P02 |
| A4 Email change OTP | done | P03; real email delivery not verified locally |
| A5 In-place password | done | P01 |
| A6 Name ≤ 70 | done | P01/P02 |
| A7 PUT returns profile | done | P02 |
| B1 My subscriptions | done | P06 |
| B2 Status display | done (fixed-term) | P06; recurring states blocked X3/X5 |
| B3 Cancel/reactivate | blocked | X3, X5 (phase-09-handoff) |
| B4 Change renewal method | blocked | X3, X5 |
| B5 Renewal processing | blocked | X3, X5 |
| B6 Saved methods | fallback | P07 capability + honest tab; methods X3 (D12) |
| B7 Ledger | done | P06; attempts/refund settlement arrive with X3 |
| B8 Receipts/invoices | done (receipts) | P06 non-fiscal receipts; formal invoices X6 |
| B9 Refresh/retry | blocked | X3 (phase-08-handoff) |
| B10 Refund requests | done (intake, off) | P10; review X4 (reviewer surface), money X3 |
| B11 Access vs payment | done | P06 |
| C1 Appearance | blocked | X1 |
| P1 Category chip | done (mechanism) | P04; empty until taxonomy X8 |
| P2 Public outline | done | P04 |
| P3 Instructor card | done | P04 |
| P4 Card discounts | excluded | no discount model |
| K1 Saved method at checkout | blocked | X3 |
| K2 Server summary | done | P06 quote |
| K3 Rich success | done | P05/P06 |
| K4 Pending external | blocked | X3 |
| K5 Uncertain / idempotency | done (synchronous scope) | P05; provider fingerprinting X3 |
| K6 Recurring eligibility | fallback | fixed-term copy only; X5 |
| K7 Invoice download at checkout | done | P06 receipt action |
| K8 Refund-guarantee copy | done | P05 removal; P11 core links the published policy |

Visual parity with the Figma Make export was not verified for any screen (X1).
