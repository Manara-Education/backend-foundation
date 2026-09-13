-- =============================================================================
-- V16 — at most one usable code per account and purpose
-- =============================================================================
--
-- WHAT IT IS FOR
--
--   Issuing a code is meant to retire the account's previous one: the service
--   marks every unused code of that type used, then inserts a fresh row. Two
--   requests doing that at the same time both retire what they can see, neither
--   sees the row the other has not committed yet, and both insert. The account
--   ends up with two live codes for one purpose, and the older one — the one the
--   user was told had been replaced — still opens the door.
--
--   The application now guards its own read-modify-write steps. This index is
--   the guarantee underneath them: it does not depend on the application doing
--   anything in particular, and it holds for a hand-run INSERT, a future code
--   path, or a service that gets refactored by someone who has not read this.
--
-- WHY A PARTIAL INDEX
--
--   The constraint is only about codes that can still be used. Spent codes are
--   kept — they are the record of what happened, and the attempt counts on them
--   are evidence — so a plain unique constraint on (user_id, type) would forbid
--   an account from ever being sent a second code. The WHERE clause restricts
--   uniqueness to the rows the invariant is actually about, and lets the history
--   accumulate freely beside it.
--
-- WHAT A LOSING REQUEST SEES
--
--   A unique-violation, surfacing as a failed request. That is the intended
--   outcome and it is the safe direction: two simultaneous "resend" clicks are
--   one user pressing twice, one of them wins, and the code that arrives is the
--   code the database holds. The alternative — two valid codes, one of which the
--   user has been told is dead — is the defect.
--
-- BEFORE IT CAN BE CREATED
--
--   Any account that already holds more than one usable code of a type would
--   block the index. The statement below retires all but the newest of each such
--   group first. It keeps the newest because that is the one the user was last
--   told about; the others are exactly the stale duplicates this index exists to
--   prevent. Nothing is deleted — they are marked used, like any spent code.
--
-- ON THE VERSION NUMBER
--
--   V16, and it is the last of the group to be numbered. This repository's own
--   FlywayConfigurationTest requires the versions to run 1..n with no gaps, so a
--   branch cannot reserve a number ahead of the branches that will land before
--   it — every one of these files was written as V13 and renumbered on the way
--   in. develop took V13 for terms acceptance (#66), V14 for the session
--   authentication epoch (#60) and V15 for upload ownership (#64), which leaves
--   V16 here.
--
--   THIS MIGRATION MUST THEREFORE MERGE AFTER #64. Landing it while V15 is still
--   open leaves a 14 -> 16 gap, and FlywayConfigurationTest fails on it. The file
--   is self-contained and order-independent in what it does; only its number
--   depends on the others.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive: one backfill UPDATE and one index. No column is added, dropped or
--   retyped. An instance running the previous build against this schema keeps
--   working, except that a genuinely concurrent double-issue now fails instead
--   of silently leaving two live codes — which is the point, and is why this is
--   safe to apply before the new build rolls out.
-- =============================================================================

-- 1. Retire pre-existing duplicates, newest survivor per (account, purpose). ---

UPDATE otps
   SET used = true
 WHERE used = false
   AND id NOT IN (
       SELECT DISTINCT ON (user_id, type) id
         FROM otps
        WHERE used = false
        ORDER BY user_id, type, created_at DESC, id DESC
   );

-- 2. The invariant. -----------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS uk_otps_one_active_per_user_and_type
    ON otps (user_id, type)
 WHERE used = false;
