# Execution board

Machine-readable state: `orchestration-state.json`. Blockers: `decisions-and-blockers.md`.

| Task | Status | Branch | Commit | PR | Notes |
|---|---|---|---|---|---|
| P00-DOC-01 | implemented | `update/manara-figma-integration-plan` (BE) | `508c0cd`+ | BE#107 (draft) | This directory |
| P00-DOC-02 | implemented | `update/manara-figma-integration-link` (FE) | `632a387` | FE#131 (draft) | Link only |
| P01-FE-01 | partially_blocked | `feat/manara-p01-settings-shell` | `3a1cd39` | FE#132 (draft) | Appearance blocked (X1) |
| P02-BE-01 | verified | `feat/manara-p02-profile-api` | `10e0007` | BE#108 (draft) | V17 |
| P02-FE-01 | verified | `feat/manara-p02-avatar-ui` | `8422566` | FE#133 (draft) | stacked on P01 |
| P03-BE-01 | verified | `feat/manara-p03-email-change` | `76e5b13` | BE#109 (draft) | V18, stacked on P02-BE |
| P03-FE-01 | verified | `feat/manara-p03-email-change-ui` | `b4f68c3` | FE#134 (draft) | stacked on P02-FE |
| P04-BE-01 | verified | `feat/manara-p04-public-catalogue` | `04ad8a7` | BE#110 (draft) | V19; taxonomy X8 |
| P04-FE-01 | verified | `feat/manara-p04-public-courses-ui` | `8df5f38` | FE#135 (draft) | |
| P05-FE-01 | verified | `feat/manara-p05-checkout-sheet` | `9a569b2` | FE#136 (draft) | stacked on P04-FE |
| P06-BE-01 | verified | `feat/manara-p06-billing-ledger` | `dcb32dd` | BE#111 (draft) | V20 |
| P06-FE-01 | verified | `feat/manara-p06-billing-history-ui` | `a9d85d9` | FE#137 (draft) | |
| P11 core | verified (limitations) | `fix/manara-p11-core-integration` (FE) | `4d7fd2a` | FE#138 (draft) | see phase-11-core-validation.md |
| P07-BE-01 | verified | `feat/manara-p07-billing-capabilities` | `f4c4c1d` | BE#112 (draft) | capabilities; methods X3 (D12) |
| P07-FE-01 | verified | `feat/manara-p07-payment-methods-ui` | `040d968` | FE#139 (draft) | stacked on P11 core fix |
| P08 | blocked | — | — | — | X3; see phase-08-handoff.md |
| P09 | fallback done | — | — | — | fixed-term via P06; X3, X5 |
| P10-BE-01 | verified (off) | `feat/manara-p10-refund-requests` | `012b4b9` | BE#113 (draft) | V21; review X4, money X3 |
| P10-FE-01 | verified | `feat/manara-p10-refund-request-ui` | `9218168` | FE#140 (draft) | |
| P11 final | verified (limitations) | — | — | — | phase-11-final-validation.md, release-readiness.md |
