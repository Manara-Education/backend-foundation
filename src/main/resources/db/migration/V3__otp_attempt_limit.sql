-- =============================================================================
-- V3 — Count failed OTP verification attempts
-- =============================================================================
--
-- An OTP is a six-digit code with a ten-minute lifetime, and nothing limited how
-- many times it could be guessed. One million possibilities and an unlimited
-- number of tries inside the validity window is not a meaningful secret — and
-- these codes gate registration verification AND password reset, so guessing one
-- is account takeover.
--
-- Counting attempts on the row (rather than in Redis) makes the limit survive a
-- Redis restart and keeps it tied to the specific code being guessed, so an
-- attacker cannot reset the counter by rotating IP addresses or requesting a new
-- session. When the count is exhausted the application marks the row used, which
-- burns the code — the user must request a new one.
--
-- DEFAULT 0 back-fills every existing row, so this is safe on a populated table.
-- =============================================================================
ALTER TABLE public.otps
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
