# Phase 07 handoff — billing capabilities and payment methods

**State:** provider-neutral scope implemented and verified. Saved payment methods, provider setup
and method management are **blocked on X3** (no payment provider); see D12.

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p07-billing-capabilities` (on P06-BE) | `feat/manara-p07-payment-methods-ui` (on `fix/manara-p11-core-integration`) |
| Commit | `f4c4c1d` (P06 quote-test fix `dcb32dd` below it) | `040d968` |
| Migration | none | — |

## Contract
`GET /api/v1/student/billing/capabilities` →
`{commerceMode, provider, oneTimeCheckout, simulated, methodTypes, savedMethods, recurringCharges, statusRefresh, refunds}`
(phase 10 adds `refundRequests`). Derived from `MANARA_COMMERCE_MODE` and the gateway bean actually
wired: `oneTimeCheckout` is false in FREE_ONLY, true in DEMONSTRATION (with `simulated: true`), and in
LIVE only with a real adapter. With no provider, `provider` is `null`, `methodTypes` is empty and
every provider-backed flag is `false`. The server enforces each value itself; a client ignoring the
answer is refused anyway. Documented in `backend-foundation/docs/api/STUDENT_BILLING_API.md`.

## Frontend
The Settings payment-methods tab reads capabilities and says why saving a method is unavailable in
this deployment's terms (payments off / simulation / provider without the capability), with no add
action and a reminder that card details are never entered in Manara. A failed load offers a retry
rather than guessing. The static "قريبًا" placeholder and `pending-capability.tsx` are removed.

## Validation
- Backend: `BillingCapabilitiesTest` (mode × adapter matrix), capability check in `BillingFlowTest`
  (401 anonymous; no secret-like fields). Full verify on the stack top: see phase 10.
- Frontend: 3 tests (demonstration, free-only, retry) + route test; negative control (ignore the
  mode) fails 2 of 3. Typecheck, 716 tests, build ✔.
- Live (P07 jar, DEMONSTRATION): tab at 1280/390 — RTL, Cairo, no overflow, demonstration wording.

## Not done / blocked
- Payment-method setup, list, default, delete (B6/B7, K2/K3): X3. D12 records why no schema was built.
