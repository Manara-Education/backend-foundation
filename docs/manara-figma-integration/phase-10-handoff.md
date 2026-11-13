# Phase 10 handoff — refund requests and review

**State:** the student-side request domain is implemented and verified, **off by default**
(`app.refund-requests.enabled=false`). Staff review is blocked on a missing reviewer surface (X4),
and money movement on a provider (X3).

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p10-refund-requests` (on P07-BE) | `feat/manara-p10-refund-request-ui` (on P07-FE) |
| Commit | `012b4b9` | `9218168` |
| Migration | `V21__refund_requests.sql` (new table, partial unique index) | — |
| Config | `app.refund-requests.enabled` (`MANARA_REFUND_REQUESTS_ENABLED`, default `false`), `app.refund-requests.window-days` (default 14) | — |

## Policy source
Published Terms 1.0 (`/terms#section-6`): §6 — full refund within 14 days of the purchase date, no
reason needed, no fees, the right is not lost by opening the course; §7 — requested through the
site's support channels, the request's date counts, processed and refunded within 7 days.

## Contract (see `STUDENT_BILLING_API.md`)
- `POST /student/transactions/{reference}/refund-requests` `{reason?, note? ≤ 1000}` → 201. No
  amount accepted: the request is for `amount − refundedAmount`. Refusals create nothing:
  `400 REFUND_REQUESTS_UNAVAILABLE`, `400 REFUND_NOT_ELIGIBLE` (message names the reason),
  `409 REFUND_REQUEST_OPEN`, 404 for another student's transaction.
- `GET /student/transactions/{reference}/refund-requests` → owner's requests, newest first.
- Transaction detail gains `refundEligibility` (ELIGIBLE, UNAVAILABLE, NOT_LIVE, NOT_PAID,
  NO_REFUNDABLE_AMOUNT, WINDOW_CLOSED, REQUEST_OPEN); capabilities gain `refundRequests`
  (`refunds` — provider money movement — stays false).

## Decisions (D13)
LIVE transactions only (simulated/legacy record no money the platform can verify); reason optional
(§6); window inclusive through the 14th calendar day in server time (Africa/Cairo), judged at the
request's date (§7); one open (SUBMITTED/APPROVED) request per transaction, held by a partial unique
index; a request changes neither payment status, paid totals nor access; APPROVED is worded as
"approved — awaiting completion", never as money returned.

## Frontend
Transaction drawer, LIVE rows only: eligible → optional-reason form with "submitting is not approval
and nothing is returned before review"; open request → its review state and "the refunded amount
appears here once actually completed"; unavailable → policy link and `/contact` (support email and
phone are still unapproved facts, so no channel is named). A lost response is reported as
unconfirmed; a 409 shows the open request.

## Validation
- Backend: `RefundPolicyTest` (6: off, eligible, provenance, status/amount, calendar-day window,
  open request), `RefundRequestFlowTest` (5, switch on: request-is-only-a-request, no reason,
  ineligible reasons, ownership/validation, 6-way concurrent submission → exactly one 201),
  default-off case in `BillingFlowTest`, capability tests. Negative control: without the partial
  unique index the concurrent test fails (all six created). Full verify on the stack top:
  1414 tests, 0 failures, 1 skipped.
- Frontend: 6 tests (submit without amount, no reason, unavailable links, simulated shows nothing,
  lost response, 409); negative control (ignore provenance) fails. 722 tests, typecheck, build ✔.
- Live (P10 jar, switch on, one LIVE row inserted in the disposable DB): request with and without a
  reason → `SUBMITTED 450.00`, transaction stays `PAID`, refunded 0; drawer at 1280/390. Rows removed
  afterwards; backend returned to defaults.

## Blocked, with the exact prerequisite
| Item | Needs |
|---|---|
| Review queue, approve/reject | A named reviewer authority and scope (which role may refund which courses). `Role.ADMIN` exists but no admin API or UI does; building an unrestricted admin surface is out of bounds for this prompt. |
| Provider refund, webhook settlement, partial refunds, document adjustments | X3. |
| Turning the switch on | Both of the above, and an operator process that meets §7's 7-day processing commitment. Until then requests go through the support channels §7 names. |
