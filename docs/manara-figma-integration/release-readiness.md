# Release readiness — Figma integration programme

Engineering readiness only. Merging, deploying, production migrations, commerce-mode changes, turning
refund intake on, and production email are owner decisions outside this run (master prompt; D10).

## Readiness by scope

| Scope | Ready to merge | Ready to enable in production | Conditions |
|---|---|---|---|
| Settings shell, account, password (P01) | yes | yes | Appearance absent (X1) |
| Avatar (P02) | yes | yes | — |
| Email change (P03) | yes | after config | `EMAIL_CHANGE_CODE_SECRET` set in the secret store (else pending codes die on restart); a working email provider (Resend key) — delivery not verified here |
| Public catalogue: category, outline, instructor card (P04) | yes | yes | Categories show nothing until rows exist (X8) |
| Checkout sheet (P05) | yes | yes, in FREE_ONLY / DEMONSTRATION | — |
| Billing history, receipts, quote (P06) | yes | yes | Receipts are non-fiscal (X6) |
| Payment-methods tab, capabilities (P07) | yes | yes | Shows "unavailable" honestly until X3 |
| Saved-method checkout, external payments (P08) | — | no | X3 |
| Auto-renewal (P09) | — | no | X3, X5; fixed-term display is live via P06 |
| Refund requests (P10) | yes | **keep off** | `MANARA_REFUND_REQUESTS_ENABLED` must stay `false` until a reviewer surface and a 7-day operator process exist (X4); refunds themselves need X3 |

## Merge order

Every PR is a draft stacked on its parent branch; review bottom-up, merge in this order.

**Backend** (`backend-foundation`)
1. `update/manara-figma-integration-plan` → `develop` (docs; independent)
2. `feat/manara-p02-profile-api` → `develop` (V17)
3. `feat/manara-p03-email-change` (V18)
4. `feat/manara-p04-public-catalogue` (V19)
5. `feat/manara-p06-billing-ledger` (V20, backfill)
6. `feat/manara-p07-billing-capabilities`
7. `feat/manara-p10-refund-requests` (V21)

**Frontend** (`frontend-foundation`)
1. `update/manara-figma-integration-link` → `develop` (independent)
2. `feat/manara-p01-settings-shell` → `develop`
3. `feat/manara-p02-avatar-ui` · 4. `feat/manara-p03-email-change-ui` · 5. `feat/manara-p04-public-courses-ui`
6. `feat/manara-p05-checkout-sheet` · 7. `feat/manara-p06-billing-history-ui`
8. `fix/manara-p11-core-integration` · 9. `feat/manara-p07-payment-methods-ui` · 10. `feat/manara-p10-refund-request-ui`

Cross-repo: each frontend phase calls the backend phase of the same number. Deploy backend first.
The frontend degrades safely if it is ahead: missing `refundEligibility` reads as UNAVAILABLE and the
refund section only renders for LIVE rows; a missing capabilities endpoint shows a retry. Earlier
phases (P02–P06) need their backend.

Flyway V17–V21 are allocated serially (D11); nothing else may take those numbers before these merge.
If `develop` gains a migration first, renumber on the branch and re-run the suite before merging.

Mechanics that have worked here: once the bottom PR is approved, a merge commit on the **top** PR
lands the whole stack; then update-branch and auto-merge each remaining PR. Verify the merge result
against current `develop`, not the PR head's CI alone.

## Rollout

1. Operator prep on the host (the production host lags infra `develop`).
2. Backend release by tag push (merging `develop`→`main` deploys nothing). Flyway applies V17–V21 on
   start. All are additive: new nullable columns, new tables, a sequence, an evidence-only idempotent
   backfill (V20). Take a database backup first.
3. Config: `EMAIL_CHANGE_CODE_SECRET` (new); keep `MANARA_COMMERCE_MODE` unchanged;
   `MANARA_REFUND_REQUESTS_ENABLED` unset (false).
4. Smoke: `/auth/me`, `/profile`, `/public/courses/{id}`, `/student/transactions`,
   `/student/billing/capabilities` (expect `refundRequests: false`).
5. Frontend release by tag push, built with `VITE_API_BASE_URL=/api`.
6. Post-deploy: legacy purchases show as LEGACY/SIMULATED with no confirmed total; subscriptions FIXED.

## Rollback

- Frontend: redeploy the previous tag; no data dependency.
- Backend: redeploy the previous jar. Each migration states the previous build never reads the new
  tables and columns, so the older code runs on the migrated schema. Flyway has no down migrations;
  do not drop V17–V21 objects while a newer build may return. Data written meanwhile (avatars, email
  change requests, ledger rows, receipts) stays and is used again on roll-forward.
- If only refund intake misbehaves: set `MANARA_REFUND_REQUESTS_ENABLED=false` (it is already the default).

## Not verified here
Figma export parity (X1); CI on GitHub (PRs are drafts); production email delivery; screen-reader
and 200 % zoom passes; a provider sandbox (X3).
