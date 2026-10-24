# Phase 05 handoff — checkout sheet (current capabilities only)

**State:** implemented and verified; export parity not verified (X1). No backend change: the existing contract already prices server-side and replays idempotently.

| Item | Value |
|---|---|
| Branch | frontend `feat/manara-p05-checkout-sheet` (stacked on P04-FE) |
| Commit | `9a569b2` |
| Backend used | `POST /api/v1/student/courses/{id}/checkout` unchanged; `CheckoutResponse.simulated` now read by the client |

## Verified backend facts relied on
- Amount: `CheckoutProcessor` charges `course.purchasePrice` or the stored plan's price; the client sends no amount.
- Replay: idempotency key `course-{id}:student-{id}:{purpose}`; an active entitlement returns the same success without charging. This is why "retry after an uncertain outcome, if the status check finds no access" is safe today. **Limit for phase 08:** with a real PSP an uncertain outcome needs a transaction record and provider reconciliation, not only an entitlement check.
- `FREE_ONLY` refuses paid checkout with `PAYMENTS_UNAVAILABLE` before writing anything.

## Delivered
- Sheet states review → submitting → success / refused (4xx, neutral copy, no "nothing was charged" claim) / uncertain (no response or 5xx) → status check → success or retry.
- Simulation disclaimer only when `simulated === true`; the reference is shown when present; no invented amount, paidAt, invoice.
- Explicit "الانتقال إلى الدورة"; no timed navigation. Cancelling no longer raises the failure banner. The 7-day refund promise is removed. Hidden plan radios made keyboard-reachable (`sr-only`).
- Renewal from the subscription status card uses the same sheet.

## Validation
- typecheck ✔; 56 files / 700 tests ✔; build ✔. `checkout-sheet.test.tsx` (10 + refund-copy check). Negative control: a first version of the duplicate-submit test passed without the guard; it was strengthened (Enter while submitting) and now fails without it.
- Live (disposable backend): FREE_ONLY → refusal shown in the sheet at 390px; DEMONSTRATION → `/student/explore/1?plan=2` preselected the 3-month plan, server recorded `price_paid 400.00` for that plan, success shows the simulation label and `sim_…` reference, and no navigation happened. The backend was put back in FREE_ONLY.
