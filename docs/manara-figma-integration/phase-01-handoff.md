# Phase 01 handoff — Settings shell, name and password editors

**State:** implemented; verified except Appearance (blocked, X1) and export-level visual parity (X1).

| Item | Value |
|---|---|
| Repo / branch | frontend-foundation `feat/manara-p01-settings-shell` |
| Commit | `3a1cd39` on `d4094cf` (develop) |
| PR | not opened yet (draft PRs pending — see execution board) |
| Backend dependency | none (reuses `GET/PUT /profile`, `POST /auth/change-password`) |

## Delivered
- `/settings` → `/settings/account`; `/settings/billing` → `/settings/billing/subscriptions`; unknown `/settings/*` → account. Student `/profile` → `/settings/account` (replace); instructors keep `/profile` and are redirected there from `/settings/*`.
- Student sidebar entry `الإعدادات`; route handle title/subtitle from the report; 1000 px content width.
- Section rail (256 px, sticky) at `lg+`; two rows of pills below `lg`; billing group expands when active; `aria-current` from the URL; no Overview tab.
- Account overview from `GET /profile`: identity card (two-letter initials, role, LTR email, member since), name/email tiles, security card with anti-phishing note. Email change and photo are shown as unavailable (phases 03/02).
- Name editor (`/settings/account/name`): trim, required, ≤ 70 UTF-16 units (same unit as `@Size`), save disabled when unchanged, failure keeps input, retry, unsaved-changes guard (router blocker + `beforeunload`).
- Password editor (`/settings/account/password`): three fields with show/hide, shared checklist from `features/auth/password-policy`, strength meter, 400 → field/general errors, 401 → shared sign-out handling, success returns to account with a notice.
- Billing sections: honest "قريبًا" states with no data or actions.

## Not delivered
- Appearance / Aspo Kids preview (C1): not ported; the export is unreadable (X1). The section is absent from navigation rather than faked.
- Anti-phishing copy and tips text are written from the report's description, not the export.

## Validation
- `npm run typecheck` ✔, `npm test` 51 files / 663 tests ✔, `VITE_API_BASE_URL=/api vite build` ✔.
- New tests: `src/features/settings/settings.routes.test.tsx` (15). Negative control: raising the name limit to 80 fails the name test.
- Live run against the P02 backend jar (8091, disposable Postgres/Redis): screenshots at 1280 and 390 of account, name editor, password editor, billing pages; `/profile` redirect; zero horizontal overflow, RTL, Cairo. A live rename through the UI stored the trimmed name and updated the sidebar. Two layout defects found in the screenshots (banner painting over the avatar; email wrapping) were fixed before commit.
