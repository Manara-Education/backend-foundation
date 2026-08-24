-- =============================================================================
-- V2 — Case-insensitive email uniqueness, and an index for OTP lookups
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Email uniqueness is currently case-SENSITIVE.
--
-- users.email carries a plain UNIQUE constraint, and PostgreSQL compares text
-- byte-for-byte. So 'Ali@example.com' and 'ali@example.com' are two different
-- values and both can be registered — two accounts, one mailbox. Every OTP,
-- password reset and notification for that person then targets whichever row
-- they happened to hit, and an attacker can deliberately register the
-- differently-cased twin of an address that already exists.
--
-- This index closes that. It is additive: the existing UNIQUE constraint stays,
-- and no column is rewritten.
--
-- If this migration fails with a uniqueness violation, the database already
-- contains such a pair. That is the migration doing its job — resolve the
-- duplicate rows first rather than dropping the index.
--
-- NOTE for reviewers: this stops new duplicates being created. It does not by
-- itself make LOGIN case-insensitive — UserRepository.findByEmail still does an
-- exact match, so signing in as 'Ali@…' when the row says 'ali@…' still fails.
-- Changing that is an application behaviour change and does not belong in a
-- migration; it is called out in the PR as a follow-up.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_lower
    ON public.users (LOWER(email));

-- -----------------------------------------------------------------------------
-- 2. The otps table has no index other than its primary key.
--
-- Both queries against it are on the hot authentication path:
--
--   findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(...)
--   UPDATE Otp o SET o.used = true WHERE o.user.id = ? AND o.type = ? AND o.used = false
--
-- Each is filtered by user_id and type, so today each one sequentially scans a
-- table that grows by a row for every OTP ever issued and is never pruned. That
-- cost lands on every login, registration and password reset, and it gets worse
-- forever.
--
-- created_at DESC matches the ORDER BY, so the first query can stop at the first
-- matching row instead of sorting.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_otps_user_type_created
    ON public.otps (user_id, type, used, created_at DESC);
