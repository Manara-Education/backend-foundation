-- =============================================================================
-- V17 — users.avatar_url and users.password_changed_at
-- =============================================================================
--
-- Two additive, nullable columns for the account screen.
--
-- avatar_url
--   The served URL of the account's own profile photo, always a single-segment
--   /uploads/<name> path written by POST /api/v1/profile/avatar. NULL means the
--   account has no photo and the client shows initials.
--
-- password_changed_at
--   When the account's password was last set: at registration, by
--   change-password, or by reset-password. Written by the application in the
--   same transaction as the new hash.
--
-- WHY EXISTING ROWS STAY NULL
--
--   Nothing on record says when an existing account last set its password.
--   created_at is when the row was made, and a password may have been reset
--   any number of times since. Copying it here would make the account screen
--   state a date that is not the fact it claims to be, so existing rows keep
--   NULL, which the API returns as "unknown" and the client does not render.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. A previous build running against this schema never reads
--   either column and its inserts leave both NULL. Flyway runs this before the
--   new build's context validates the entity.
-- =============================================================================

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS avatar_url varchar(255);

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS password_changed_at timestamp(6) without time zone;
