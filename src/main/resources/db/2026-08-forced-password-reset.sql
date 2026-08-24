-- =============================================================================
-- Forced password reset: users an administrator requires to pick a new password
-- =============================================================================
--
-- This project has no migration tool: the schema is maintained by Hibernate
-- (`spring.jpa.hibernate.ddl-auto=update`). This script exists so the change can
-- be applied deliberately, ahead of the deployment, on environments where that
-- is preferred to letting the application do it on first start.
--
-- RUN THIS BEFORE DEPLOYING THE NEW BUILD, against each environment:
--
--     psql "$DATABASE_URL" -f 2026-08-forced-password-reset.sql
--
-- Afterwards Hibernate's own update finds the column in place and does nothing.
--
-- The script is idempotent and safe to re-run: every statement is guarded.
-- =============================================================================


-- --------------------------------------------------------------------------
-- 1. users.requires_password_reset
-- --------------------------------------------------------------------------
-- A NOT NULL column added to a populated table, so it carries a DEFAULT: every
-- row that already exists becomes FALSE and every existing account keeps
-- signing in exactly as it did before. Nobody is enrolled into the forced-reset
-- flow by this migration.
--
-- The DEFAULT is kept on the column rather than dropped after the back-fill.
-- Hibernate does not emit the flag in its INSERT when the entity leaves it at
-- its Java default, and a row written by hand -- a seed, a support fix -- should
-- land on "no reset required" too. `false` is the safe value to fall back to:
-- forgetting it locks a user out of the application, never into it.
--
-- The flag is cleared by the application, in the same transaction that persists
-- the new password hash (AuthService#changePassword). It is never cleared from
-- the client.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS requires_password_reset BOOLEAN NOT NULL DEFAULT FALSE;


-- --------------------------------------------------------------------------
-- 2. Marking a user for a forced reset
-- --------------------------------------------------------------------------
-- There is no administrative screen for this yet. Until there is, an operator
-- flips the flag directly; the next successful sign-in is routed straight to the
-- change-password screen and no other endpoint answers until it succeeds.
--
--     UPDATE users SET requires_password_reset = TRUE WHERE email = '<address>';
