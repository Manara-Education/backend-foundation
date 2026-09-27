# Phase 11 — core validation checkpoint (provider-independent scope, phases 01–06)

**Date:** 2026-09-27 · **Result:** core scope verified with limitations below; not a production release decision.

## Commit pair evaluated

| Repo | Commit | Contains |
|---|---|---|
| backend-foundation | `cd90573` (`feat/manara-p06-billing-ledger`) | P02 `10e0007` → P03 `76e5b13` → P04 `7a1a5ca`, `04ad8a7` → P06 `cd90573`, on `develop@ac72672` |
| frontend-foundation | `eb0194f` (`fix/manara-p11-core-integration`) | P01 → P02 → P03 → P04 → P05 → P06 `a9d85d9` → core fixes `eb0194f`, on `develop@d4094cf` |

`develop` in both repos is unchanged since the stacks were cut, so the stack tops are the integrated result.

## Checks

| Check | Result |
|---|---|
| Backend `./mvnw -o clean verify` at `cd90573` | 1397 tests, 0 failures, 1 skipped |
| Frontend typecheck / `npm test` / production build at `eb0194f` | 0 errors / 58 files, 713 tests / built |
| Fresh install | Empty Postgres 17.10 → V1–V20 applied by the P06 jar |
| Upgrade with legacy data | `develop` jar on a fresh DB (V16), real purchase + subscription through its simulated checkout, provenance of one purchase erased, course and plan repriced; P06 jar then applied V17–V20. Result: `purchase:1` LEGACY 300.00 EGP (not the repriced 999), `subscription:1` SIMULATED 60.00 (not 777), 0 receipts invented, existing users' `password_changed_at` NULL; API: no confirmed total, provenance counts LIVE 0 / SIMULATED 1 / LEGACY 1, subscription `renewalMode FIXED`, profile `passwordChangedAt null`. |
| Dependency vulnerabilities | CycloneDX SBOM (151 packages, incl. pdfbox 3.0.7, icu4j 77.1) → `osv-scanner`: no issues. Frontend dependencies unchanged. |
| Secrets | `gitleaks` over all new commits in both repos and the docs branch: no leaks. |
| Integrated journeys (headless Chrome, Vite → P06 jar, DEMONSTRATION) | 37/37 pass on the second run. The first run failed from registration onwards because the platform's register throttle (5 / 15 min per address, shared with the upgrade test) refused the sign-up — expected behaviour, not a defect. |

## Journeys covered (37 checks)
Public plan choice (non-monthly) with fixed-term total → register keeps `from=/student/explore/1?plan=2` → student page preselects the plan → quote shows the simulation notice before paying → success with DEMO receipt, no automatic navigation → explicit "go to course" → free enrolment grants access and writes no transaction → student `/profile` → `/settings/account` → registration name stored trimmed → rename with a long Arabic name updates the sidebar without reload → no overflow at 390/834/1280 → password change in place; old password refused, new accepted → invoices at 390/834/1280, drawer from `?tx=`, receipt PDF (200, `%PDF-`), status filter from the address → fixed-term subscription at 834 → another student gets 404 for this transaction → instructor keeps `/profile` with headline, is sent from `/settings` to `/profile`, editor shows the category field → landing and public course at 834 without overflow → public detail JSON has no media, bodies or instructor email.

Email change was exercised end to end on the P03 build (same code in the P06 build); photo upload and removal on the P02 build.

## Defects found and fixed in this checkpoint (`eb0194f`)
1. Subscription plan label "فصلي · ٣ شهر": in Cairo the middle dot is indistinguishable from the Arabic-Indic zero, so it read "٣٠ شهر". Reworded to "فصلي، لمدة ٣ شهر"; no other "·" sits before a digit.
2. Initials took the article's alif from names like "… الشريف". Now skip "ال" (tested).

## Not covered / limitations
- **Figma export (X1):** no side-by-side comparison with the export was possible; Appearance (C1) is not ported.
- Real email delivery (no local mail sink; Resend unconfigured), production deploy and CI runs (no PRs open yet).
- Large text zoom and screen-reader passes were not run; keyboard semantics are covered by role-based tests only.
