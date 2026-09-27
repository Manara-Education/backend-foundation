# Phase 06 handoff — ledger, billing history, receipts, quote

**State:** implemented and verified. Formal tax invoices remain blocked on X6 (non-fiscal receipts shipped); export parity not verified (X1).

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p06-billing-ledger` (stacked on P04-BE) | `feat/manara-p06-billing-history-ui` (stacked on P05-FE) |
| Commit | `cd90573` | `a9d85d9` |
| Migration | `V20__payment_transactions_and_receipts.sql` | — |
| New dependencies | `io.github.openhtmltopdf:openhtmltopdf-pdfbox` + `-rtl-support` 1.1.87 (pdfbox 3.0.7, icu4j 77.1); Cairo static TTFs (OFL) under `src/main/resources/fonts/cairo` | — |

## Contract
See `backend-foundation/docs/api/STUDENT_BILLING_API.md` (quote, additive checkout fields, transactions list/detail with filter-scoped summary, subscriptions, receipts JSON/PDF).

## Decisions
- Provenance LIVE / SIMULATED / LEGACY on every transaction; only LIVE + PAID counts toward "paid" totals; totals are per currency.
- Backfill is evidence-only: recorded amounts and references; `sim_` references → SIMULATED; no receipts invented for history; subscription rows recorded as EGP because every charge path has only used EGP. Idempotent: source keys plus a skip for rows already linked to a transaction (a first version collided with checkout-recorded rows; found by the full suite and fixed).
- Receipts are immutable snapshots, numbered `DEMO-`/`RCT-YYYY-NNNNNN` from a sequence (unique, may gap, not a tax series); `fiscal: false` always.
- Failed/abandoned attempts are not yet recorded: with the simulator nothing fails after charging; attempt records arrive with a real provider (phase 08).
- The quote authorises nothing; checkout re-prices. `simulated` on the quote lets the sheet say "محاكاة" before the learner confirms.

## Validation
- Backend `./mvnw -o clean verify`: 1397 tests, 0 failures, 1 skipped. New: `BillingFlowTest` (7), `PaymentLedgerBackfillMigrationTest`, `ReceiptPdfRendererTest` (2), quote cases in `CheckoutProcessorTest`. Negative control: removing the LIVE filter from totals fails `totalsCountOnlyLiveMoney`.
- Arabic PDF inspected visually (shaping, RTL, mixed LTR runs, simulation band); Cairo embedded; hostile values render as text.
- Frontend: typecheck ✔; 57 files / 710 tests ✔; build ✔.
- Live (fresh disposable stack, DEMONSTRATION): purchase and 3-month subscription through the sheet → two SIMULATED PAID rows with DEMO receipts at server prices (450.00 / 400.00); Settings invoices at 1280/390, receipt drawer from `?tx=`, subscriptions at 390; PDF fetched in the browser session: 200 `application/pdf`, `%PDF-`, `attachment; filename=…`.
