-- =============================================================================
-- V13 — users.auth_version: the epoch a session was established under
-- =============================================================================
--
-- One additive column: users.auth_version, a counter that goes up by one every
-- time the account's credentials change.
--
-- WHAT IT IS FOR
--
--   A session is a snapshot. Once it exists, nothing about the account that
--   created it is consulted again — not the password it was opened with, not
--   the role it was opened under. So changing a password did not end anybody
--   else's session, and "change your password, you may have been compromised"
--   left the person who had been compromised exactly where they were.
--
--   This column is the fixed point that makes a session checkable. The session
--   carries the value it was opened under; the row carries the current one; a
--   request whose session disagrees with the row is refused. Changing or
--   resetting a password increments the row in the same transaction that writes
--   the new hash, so the moment the new password is real, every session opened
--   under the old one is stale.
--
-- WHY A COUNTER IN POSTGRESQL AND NOT A REDIS SWEEP
--
--   Because the increment has to be atomic with the password write, and a
--   multi-key delete against the session store is not. A sweep that half
--   succeeds leaves live sessions behind a reset the user was told had worked,
--   and nothing afterwards would ever notice. An UPDATE in the same transaction
--   as the hash either happens with it or does not happen at all.
--
--   It is also the only form that reaches a role change. Roles here move by
--   hand, in SQL — no application code runs — so there is no hook a session
--   deletion could ever hang off. A value read from the row on each request
--   needs no hook.
--
-- WHY EVERY EXISTING ROW IS 0
--
--   0 is not a chosen default, it is the count of credential changes that have
--   happened since this column started counting them: none. Written twice — the
--   column DEFAULT and the explicit back-fill below — so a column added by an
--   earlier hand-run script, with no default or a different one, is normalised
--   to the same fact.
--
--   Note what this does NOT do: it does not make existing sessions valid. A
--   session established before this deploy carries no stamp at all, and the
--   application treats an unstamped session as stale rather than as zero. Every
--   currently signed-in user is signed out once, on their next request, and
--   signs back in normally. That is deliberate, and it is why no session store
--   has to be touched by this migration — see the deployment note below.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. No column is dropped or retyped, nothing changes nullability
--   on an existing column, no constraint is altered, no index is rebuilt.
--
--   Both deployment orders are safe. An instance running the previous build
--   against this schema never mentions the column and its inserts take the
--   DEFAULT. An instance running the new build against the old schema would
--   fail validation on the column, so this migration goes first — which Flyway
--   already guarantees by running before the context is refreshed.
--
--   NO REDIS COMMAND IS PART OF THIS MIGRATION, and none is needed. Sessions are
--   invalidated by the absence of a stamp, one at a time, as their owners come
--   back. Flushing the session store would additionally destroy the rate-limit
--   counters that share the instance under a different prefix, which is an
--   abuse-control bypass, not a cleanup.
-- =============================================================================

-- 1. The column. -------------------------------------------------------------

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS auth_version bigint DEFAULT 0 NOT NULL;

-- 2. Existing rows. ----------------------------------------------------------
--
-- The DEFAULT above already back-filled every row. Stated again anyway, so a
-- column introduced by some earlier hand-run script is brought to the same
-- value rather than left at whatever it happened to hold.

UPDATE public.users
SET auth_version = 0
WHERE auth_version IS NULL;
