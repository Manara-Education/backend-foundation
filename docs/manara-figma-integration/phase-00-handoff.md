# Phase 00 handoff — repository audit and contracts

**State:** implemented (documentation only). No application or production behaviour changed.

## Scope delivered

- Repositories, SHAs, dirty files, commands and conventions: `implementation-plan.md`.
- Every report ID mapped with evidence, API, schema, phase and PSP dependency: `gap-coverage.md`.
- API contracts (existing / agreed / proposed), status mapping and money/time conventions: `api-contracts.md`.
- Serial Flyway allocation V17–V20: `implementation-plan.md` §Migrations.
- Protected appearance, removed Overview, `MANARA_COMMERCE_MODE` preservation, refund-promise removal,
  password-date honesty and simulated/live separation: `decisions-and-blockers.md` D2–D10.

## Observed facts that change the report

- Frontend branch `fix/public-site-history-and-landing-cleanup` is already merged (tree equals
  `origin/develop@d4094cf`).
- Latest migration is V16, not V9; the report's `V10__user_avatar.sql` suggestion is obsolete.
- OTPs are database rows (`otps`, one active per user and type since V16) with an attempt recorder,
  not Redis; email change reuses that store.
- The report's `password_changed_at` backfill from `created_at` is rejected (D5).
- Commerce modes are `FREE_ONLY | DEMONSTRATION | LIVE`; `LIVE` refuses to start without a provider.

## Validation

Docs-only: JSON validated with `python3 -m json.tool`; links checked by hand. No application suites run
(no code changed).

## Limitations

Design mapping is from the report, not the export (X1, X2). Provider phases blocked on X3–X5.
