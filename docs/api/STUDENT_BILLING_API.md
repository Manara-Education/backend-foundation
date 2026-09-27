# Student billing API contract

All routes need a signed-in **student** session; any other role is refused `400`. Every record is
scoped to the caller inside the query — another learner's reference or receipt number is answered
`404`, exactly like one that never existed. Money is a JSON number with two decimals plus a
`currency`; `null` means unknown, never zero.

## Provenance — what a figure means

| Value | Meaning | Counted in totals |
|---|---|---|
| `LIVE` | Charged by a real payment provider (none is integrated yet) | yes |
| `SIMULATED` | Charged by the simulator; no money moved | no |
| `LEGACY` | Recorded before the ledger existed (V20 backfill); only the stored amount is known | no |

## `POST /api/v1/student/courses/{id}/checkout/quote`

Body `{ "planId": 9 }` (required for subscription courses). Charges and writes nothing; checkout
prices the order again itself.

```json
{ "courseId": 7, "planId": 9, "accessType": "SUBSCRIPTION", "subtotal": 400.00, "discount": 0.00,
  "amount": 400.00, "currency": "EGP", "accessKind": "FIXED_TERM", "accessDuration": 3,
  "accessUnit": "MONTH", "renewalMode": "FIXED", "payable": true, "unavailableReason": null,
  "simulated": true }
```

`unavailableReason`: `PAYMENTS_UNAVAILABLE` (FREE_ONLY deployment) or `ALREADY_ENTITLED`. A purchase
course without a price is refused `400` (`error.course.purchasePriceRequired`), never quoted as free.

## `POST /api/v1/student/courses/{id}/checkout` — additive fields

`transactionId`, `transactionStatus`, `amount`, `currency`, `paidAt`, `receiptNumber`. All `null` when
the call charged nothing (a free course, or a repeat of a checkout that already granted access).

## `GET /api/v1/student/transactions`

Query: `q` (≤ 100 chars; matches the item, receipt number, transaction reference and gateway
reference), `status` (one or comma-separated of `AWAITING_PAYMENT, PROCESSING, PAID, FAILED,
CANCELLED, REFUNDED, PARTIALLY_REFUNDED`), `from`/`to` (ISO dates, inclusive, server time), `page`
(0-based), `size` (default 10, max 50). Sorted newest first.

```json
{ "items": [ { "reference": "5b2c…", "purpose": "PURCHASE", "course": { "id": 7, "title": "…", "imageUrl": null, "instructorName": null },
               "description": "…", "amount": 450.00, "currency": "EGP", "status": "PAID", "provenance": "SIMULATED",
               "createdAt": "2026-09-27T12:00:00", "paidAt": "2026-09-27T12:00:00", "receiptNumber": "DEMO-2026-000001",
               "courseAccess": "ACTIVE" } ],
  "page": 0, "size": 10, "totalItems": 1, "totalPages": 1,
  "confirmedTotals": [ { "currency": "EGP", "amount": 150.00, "count": 2 } ],
  "provenanceCounts": { "LIVE": 2, "SIMULATED": 1, "LEGACY": 0 } }
```

`confirmedTotals` and `provenanceCounts` cover **every row the filters match**, not just the page.
Totals are LIVE + PAID only and are never summed across currencies. `courseAccess` is the learner's
access now (`ACTIVE`/`NONE`), independent of payment status.

## `GET /api/v1/student/transactions/{reference}`

`{ summary, lines:[{description, amount}], subtotal, discount, total, refundedAmount, gatewayReference, subscriptionTerm }`.

## `GET /api/v1/student/subscriptions`

`page`, `size` as above. Items carry `course`, `plan {id,name,duration,unit}`, `pricePaid` (as recorded
when bought), `currency`, `startsAt`, `expiresAt`, `status`, `displayStatus` (`FIXED_ACCESS` while the
term runs, `EXPIRED` after), `renewalMode: "FIXED"` (nothing renews), `courseAccess`,
`transactionReference`, `provenance`. `activeCount` counts all running subscriptions.

## Receipts

- `GET /api/v1/student/receipts/{number}` → the immutable snapshot: `number, issuedAt, simulated,
  fiscal (always false), customerName, customerEmail, lineDescription, amount, currency,
  transactionReference, gatewayReference`.
- `GET /api/v1/student/receipts/{number}/pdf` → `application/pdf`, `Content-Disposition: attachment`,
  `Cache-Control: private, no-store`. Rendered on the server from bundled Cairo fonts; nothing is fetched.

Receipts exist only for PAID transactions recorded at checkout. Simulated ones are numbered
`DEMO-YYYY-NNNNNN` and say "محاكاة دفع — لم تُحصَّل أي أموال"; numbers are unique but may have gaps and
are not a tax-invoice series. History from before the ledger has no receipt.
