# Implementation plan

## Repositories (observed 2026-09-26)

| Repo | Path | Base | SHA | Notes |
|---|---|---|---|---|
| backend-foundation | `~/Desktop/Manara/backend-foundation` | `develop` | `ac72672` | Main checkout on `develop`, untracked `docs/DATABASE_SCHEMA.md` (user's; untouched). Spring Boot, Java 25 target, `./mvnw`. |
| frontend-foundation | `~/Desktop/Manara/frontend-foundation` | `develop` | `d4094cf` | Main checkout on `fix/public-site-history-and-landing-cleanup` (tree identical to develop), untracked `docs/LEGAL_RELEASE_READINESS.md` (user's; untouched). React 19 + Vite 8 + TS, Vitest. |
| prompt pack | `~/Desktop/Manara/manara-phase-prompts` | — | — | 12 prompts + README + coverage + `reference/` report. |
| Figma export | `~/Downloads/Manara` | — | — | **Unreadable** (X1). |

No open PRs in either repository at start. Remote default branch is `main`; integration base is
`develop` (`origin/HEAD → main` is the release branch).

## Validation commands (from repo skills and CI)

- Backend: `./mvnw -q compile`, focused `./mvnw -q test -Dtest=<Pattern>`, full `./mvnw -q clean verify`
  (Testcontainers; needs Docker and disk). Skip `*IT` tests that send real email.
- Frontend: `npm run typecheck`, `npm test` (Vitest, jsdom), `VITE_API_BASE_URL=/api npm run build`.
  CI runs `npm test` even though the PR skill says there is no runner.
- Commit/PR title: `[Area Emoji] - Sentence case description.`; branches `feat/ fix/ update/ remove/`;
  reviewer `Mohamed-Hamza`; assignee = `gh api user` (`Hamed-mohamed-Git`). PRs are opened as drafts.

## Worktrees

All programme worktrees live under `~/Desktop/Manara/worktrees/figma/`, named `<be|fe>-p<NN>-<slug>`.
Dependent branches are stacked on their parent branch; the PR base names the parent until it merges.

## Migrations (serial allocation)

Current history ends at `V16__one_active_otp_per_user_and_type.sql`.

| Version | Phase/task | Content |
|---|---|---|
| V17 | P02-BE-01 | `users.avatar_url`, `users.password_changed_at` (NULL for existing rows) — **used** (`10e0007`) |
| V18 | P03-BE-01 | email-change pending address on `otps` (or side table), `EMAIL_CHANGE` otp type |
| V19 | P04-BE-01 | `course_categories`, `courses.category_id`, `instructors.headline` |
| V20 | P06-BE-01 | `payment_transactions`, `payment_receipts` |

Re-check `origin/develop` before creating each file; if develop moved past a number, shift every
unwritten row and note it here.

## Task list

| Task | Repo | Branch | Depends on | Scope |
|---|---|---|---|---|
| P00-DOC-01 | BE | `update/manara-figma-integration-plan` | — | This directory. |
| P00-DOC-02 | FE | `update/manara-figma-integration-link` | — | Link to canonical docs. |
| P01-FE-01 | FE | `feat/manara-p01-settings-shell` | P00 | `/settings/*` routes, nav, shell, account overview, name editor, password editor, billing "قريبًا" placeholders. Appearance blocked on X1. |
| P02-BE-01 | BE | `feat/manara-p02-profile-api` | P00 | V17, profile DTO fields, PUT response, name validation, avatar endpoints, password timestamp. |
| P02-FE-01 | FE | `feat/manara-p02-avatar-ui` | P01-FE-01, P02-BE-01 | Avatar editor, verified badge, password date, PUT response adoption. |
| P03-BE-01 | BE | `feat/manara-p03-email-change` | P02-BE-01 | Email-change lifecycle. |
| P03-FE-01 | FE | `feat/manara-p03-email-change-ui` | P02-FE-01, P03-BE-01 | 5-step flow. |
| P04-BE-01 | BE | `feat/manara-p04-public-catalogue` | P02-BE-01 | Categories, outline, instructor card. |
| P04-FE-01 | FE | `feat/manara-p04-public-courses-ui` | P04-BE-01 | Card/details refresh, plan selector, outline, instructor card, category editor field. |
| P05-FE-01 | FE | `feat/manara-p05-checkout-sheet` | P04-FE-01 | Sheet, summary, result/failed/uncertain states, simulated disclaimer, remove K8 line. |
| P06-BE-01 | BE | `feat/manara-p06-billing-ledger` | P02-BE-01 | V20, ledger writes, quote, reads, receipts JSON/PDF. |
| P06-FE-01 | FE | `feat/manara-p06-billing-history-ui` | P05-FE-01, P06-BE-01 | Subscriptions + invoices tabs, receipt drawer, quote summary, rich success. |
| P11-CORE | both | `fix/…` as needed | P01–P06 | Core validation checkpoint. |
| P07–P10 | both | — | X3–X5 | Provider-neutral groundwork only where it adds real value; live capability blocked. |
| P11-FINAL | both | — | all | Final checkpoint. |

## Rollout order (for the eventual release)

Backend before the frontend that consumes it, per repo tag (`vX.Y.Z` on `main`). Additive DTO
changes keep the currently deployed frontend working against the new backend; `PUT /profile`'s
response change is verified against the deployed client in phase 02.
