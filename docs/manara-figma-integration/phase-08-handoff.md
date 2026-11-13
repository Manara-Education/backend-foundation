# Phase 08 handoff — saved-method checkout, external payments, reconciliation

**State:** blocked on X3. No provider-neutral work remained that would not be speculative; see D15.

## What already exists (from earlier phases)
| Prompt item | Where it stands |
|---|---|
| `GET /student/transactions/{id}` (owner status and detail) | Done in phase 06 (`/transactions/{reference}`, 404 for others). |
| Canonical transaction on checkout (`transactionId`, status, server amount/currency, simulation flag, receipt only when issued) | Done in phase 06 (additive `CheckoutResponse` fields). |
| Pending/unknown grants no access; uncertain ≠ failed in the UI | Done in phase 05 (checkout sheet state machine: uncertain → status check → retry of the same logical checkout). |
| Idempotent repeat of the same checkout | Existing: gateway key `course-{id}:student-{id}:{purpose}`; an active entitlement is answered as held without charging. |
| Capability-driven unavailable states | Phase 07 (`statusRefresh: false`, `savedMethods: false`). |

## Blocked, with the exact prerequisite
| Item | Needs |
|---|---|
| Saved-method / one-time token checkout, `nextAction` (wallet, InstaPay, 3DS) with real `expiresAt`, redirect allowlist | A chosen provider (Kashier is the intended one) with sandbox credentials in the secret store and its enabled methods confirmed (X3). |
| Authenticated, deduplicated provider webhook; out-of-order handling; reconciliation after provider success + local failure | The provider's signature scheme and event model (X3). |
| `POST …/refresh-status`, `POST …/retry` | A provider status API (X3). |
| Request-fingerprint idempotency for conflicting payload reuse | Only meaningful once a persisted checkout precedes an asynchronous provider charge (X3); today the simulator settles synchronously. |
| Sandbox journey Settings → add method → checkout → history | X3. |
