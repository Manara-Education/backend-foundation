# Phase 09 handoff — subscriptions and auto-renewal

**State:** the prompt's fallback scope is complete; auto-renewal is **blocked on X3 and X5** and
stays disabled.

The prompt: "If these [policy/provider decisions] are absent, finish fixed-access views, contracts and
tests, leave auto-renew disabled, and report the exact missing decisions."

## Done (phase 06, verified at the phase 11 checkpoints)
- `GET /student/subscriptions`: `renewalMode: "FIXED"` for every row; `displayStatus`
  FIXED_ACCESS / EXPIRED; access-through dates; linked transaction and provenance.
- Settings subscriptions view: current/previous split, "لا يتجدد تلقائيًا", no cancel-renewal or
  change-card controls, no cadence wording ("شهريًا") — tested.
- Legacy subscriptions stay FIXED; nothing backfills `AUTO` from a duration (V20 tested on upgrade).

## Missing decisions (X5) and capability (X3)
1. Consent wording and where consent is captured; evidence retained.
2. Renewal schedule relative to expiry; price-change and term-change handling for existing subscribers.
3. Retry schedule after a failed renewal, and whether any grace period exists (none is invented).
4. Cancellation effect (end of paid period is assumed by the prompt; needs confirmation).
5. Charging authority: the provider's managed recurring facility or an application scheduler — one, not both.
6. Provider support for off-session tokens/mandates for the enabled methods (X3).

`cancel-renewal`, `reactivate-renewal` and `renewal-method` were not added: with every subscription
FIXED they could only refuse, and their semantics depend on the decisions above.
