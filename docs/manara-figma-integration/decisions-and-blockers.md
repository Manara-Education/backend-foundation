# Decisions and blockers — Figma Settings / public courses / billing

Canonical location: `backend-foundation/docs/manara-figma-integration/`. The frontend repository
links here from `docs/manara-figma-integration/README.md`; do not keep a second copy.

Each entry states whether it is an **observed fact** (seen in code on the SHA named), a **decision**
(taken for this programme, reversible by the product owner), or an **external blocker** (needs
something that is not in either repository).

## External blockers

| ID | Blocker | Affects | Work that continues without it | Smallest unblock action |
|---|---|---|---|---|
| X1 | The Figma Make export at `~/Downloads/Manara` cannot be read: macOS privacy protection (TCC) returns `Operation not permitted` for `~/Downloads` to the terminal, with or without the sandbox. No copy exists elsewhere on disk (searched 2026-09-26 for `SettingsView.tsx`, `CourseCheckout.tsx`, `PaymentStore.tsx`). | Visual fidelity of phases 01, 04, 05 (and later billing UI); the **exact** port of `AppearanceSettings.tsx` (C1) is impossible without the file. | All backend work; frontend structure, routing, data flow, validation, states and tests, built from the gap report's descriptions. Appearance stays out of the shell until the file is available. | Copy the export into the workspace, e.g. `cp -R ~/Downloads/Manara ~/Desktop/Manara/figma-export`, or grant the terminal Full Disk Access. |
| X2 | Figma REST access (Framelink MCP) returns `403 {"err":"Token expired"}` for file `PGHlxrcUz5Mcb89YxB4Wiw`. | Only a fallback visual reference; X1 is the primary source. | Everything X1 allows. | Refresh the Figma token configured for the `figma` MCP server. |
| X3 | No payment provider is integrated. `PaymentGateway` has two implementations, `SimulatedPaymentGateway` and `PaymentsUnavailableGateway`; `CommerceMode.LIVE` refuses to start (observed, `ac72672`). | Live capability in phases 07–10: saved methods, pending wallet/InstaPay payments, webhooks, refresh-status against a PSP, recurring charges, provider refunds. | Provider-neutral domain, ledger, capability endpoint reporting "unsupported", honest unavailable UI states. | Product selects a provider; sandbox merchant credentials delivered through the deployment secret store; the account's enabled methods confirmed against the provider's current documentation. |
| X4 | No recorded refund policy. | Phase 10 approval semantics; K8 copy. | Request intake and review state can be built only if a policy exists; otherwise the refund entry point stays unavailable. | Product/legal written refund policy (eligibility window, partial refunds, access effect). |
| X5 | No recorded recurring-billing policy (consent wording, grace period, retry schedule, cancellation effect). | Phase 09 B3–B5, K6. | Fixed-duration subscription display (phase 06). | Product/legal written recurring policy. |
| X6 | No approved legal/tax invoice configuration (seller identity, tax registration, numbering obligation). | Phase 06 B8 formal invoices. | Non-fiscal receipts clearly labelled as such. | Finance-approved invoice configuration. |
| X8 | No approved course-category taxonomy. V19 creates the table empty on purpose. | Category chips (P1) show nothing in production until rows exist. | Mechanism, public field, editor select, tests. | Product supplies the list (Arabic names, order, colour token per category); insert rows by migration or SQL. |
| X7 | Codex MCP server failed to connect this session (`CONNECTION_CLOSED`). The standing preference is that Codex runs the PR workflow. | Who runs push/PR. | Claude runs implementation, validation and commits directly; PRs are opened as drafts only. | Restart the `codex` MCP server if the Codex hand-off is still wanted. |

## Environment constraints (observed 2026-09-26)

- Disk: 7.7 GiB free on the data volume (96 % used). Testcontainers suites and a Trivy Java DB
  download (1.4 GB) can exhaust it. Keep jar/`target` copies to a minimum; clean scratch builds.
- JDK 26.0.1 and Node 26.3.0 are installed; Docker 29.4.0 is running.

## Decisions

| ID | Decision | Reason |
|---|---|---|
| D1 | Canonical orchestration record lives in `backend-foundation/docs/manara-figma-integration/`. | Backend owns the contracts every phase depends on; the frontend links to it. |
| D2 | The 7-day money-back line ("ضمان استرداد المبلغ خلال ٧ أيام", observed at `frontend-foundation/src/features/course/student/course-details/components/payment-cta-section.tsx:225` on `d4094cf`) is removed in phase 05. | No refund backend and no policy (X4). A request feature later does not restore the promise. |
| D3 | Appearance (C1) is frontend-only with no persistence; it resets to Normal on mount. It is ported only from the actual export file (X1). | Report §1.5: the Aspo Kids preview is protected content. |
| D4 | Billing Overview ("نظرة عامة") is not built. | Removed deliberately upstream (report §1.2). |
| D5 | `passwordChangedAt` is **not** backfilled from `created_at`. Existing rows stay `NULL` and the UI shows "unknown"/omits the date. | A backfilled creation date would be presented as a password-change time it is not. The report's `backfill = created_at` suggestion is rejected. |
| D6 | Student Settings lives at `/settings/*`; instructors keep `/profile`. A student's `/profile` redirects to `/settings/account`. | Report §1.1; instructor profile must stay available. |
| D7 | Subscriptions remain `FIXED` renewal mode until phase 09 has a real recurring capability and policy. No renewal cadence ("شهريًا") is shown. | Duration is not recurrence (K6). |
| D8 | Invoice identifiers are unique and concurrency-safe; gap-free legal numbering is **not** claimed. Simulated transactions produce receipts marked "non-fiscal / simulated", never formal invoices, and are excluded from confirmed-paid totals. | Report readme items 5–6. |
| D9 | Historic purchases/subscriptions written before the ledger exists are surfaced as `LEGACY` provenance with only the evidence the row holds (`amount_paid`/`price_paid`, `payment_reference`). No invoice is fabricated for them. | Records need provenance. |
| D10 | `MANARA_COMMERCE_MODE` semantics are unchanged by this programme. Production activation of any commerce mode is outside this run. | Master prompt §4. |
| D11 | Flyway versions are allocated serially in `implementation-plan.md` (§Migrations) before a branch writes a file. | Dense versions collide across parallel branches (V1–V16 in use on `ac72672`). |

## Observed defects outside this programme's scope

| ID | Observation | Evidence | Status |
|---|---|---|---|
| O1 | A signed-in **student** calling the instructor-only `POST /api/v1/uploads` receives **401** and the session is ended (subsequent requests 401), instead of a 403 that keeps the session. The upload is still refused, so the authorisation invariant holds. MockMvc tests report 403 because they run without Spring Session's filter. | Reproduced 2026-09-26 against a jar built from `develop@ac72672` (negative control) and against `feat/manara-p02-profile-api`: identical. | Pre-existing; not fixed in phase 02. Candidate `fix/` task: investigate the error dispatch for access-denied multipart requests. |
| O2 | The sign-up OTP screen's paste handler uses `/\\D/g` (matches a literal backslash followed by "D") instead of `/\D/g`, so a pasted code containing spaces or dashes is not cleaned. | `frontend-foundation/src/features/auth/otp/hooks/use-otp.ts:88` on `d4094cf`. | Pre-existing; the Settings code input is written correctly and tested; candidate `fix/` task. |
