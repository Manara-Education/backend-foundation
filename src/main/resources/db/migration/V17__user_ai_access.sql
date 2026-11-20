-- =============================================================================
-- V17 — users.ai_access: whether an account may use Manara AI
-- =============================================================================
--
-- One additive column. It is independent of role: an account's role says what it
-- may do in this application, this says whether it may use Manara AI.
--
-- NOBODY IS GRANTED ACCESS BY THIS MIGRATION. Every existing row, and every row
-- inserted by an instance that does not know the column, is false. Access is
-- given afterwards, per person, by an administrator in SQL. No endpoint sets it.
--
-- WHY THIS IS SAFE ON A LARGE TABLE
--
--   A constant, non-volatile DEFAULT is recorded in the catalog by PostgreSQL 11
--   and later; the table is not rewritten and existing rows are not touched. The
--   statement needs only a brief ACCESS EXCLUSIVE lock.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. The previous build never mentions the column and its inserts
--   take the DEFAULT. The new build needs the column to pass ddl-auto=validate,
--   so this migration runs first, as Flyway already guarantees.
-- =============================================================================

-- Give up after 5 seconds rather than wait for the lock. ADD COLUMN needs a brief ACCESS
-- EXCLUSIVE lock on users, and while it queues behind a long-running transaction on that table
-- every later query on it queues behind IT -- logins included. Failing fast makes the deploy
-- fail visibly and be retried, instead of stalling sign-ins. Flyway runs this file in a
-- transaction, so LOCAL scopes the setting to it and nothing else is affected.
SET LOCAL lock_timeout = '5s';

ALTER TABLE users ADD COLUMN ai_access boolean NOT NULL DEFAULT false;
