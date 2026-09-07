-- =============================================================================
-- V13 — Recorded consent to the Terms and Conditions
-- =============================================================================
--
-- One new table: terms_acceptances. One row per (account, terms version), each
-- one saying that a specific account accepted a specific published version of
-- the terms at a specific instant.
--
-- NO BACKFILL, AND ABSENCE MEANS "UNKNOWN"
--
--   Every account that exists when this migration runs comes out of it with no
--   row, and that is the whole point. Consent was never asked for before this
--   deploy, so there is nothing to record; a row invented here would assert that
--   a named person agreed to a document they were never shown, which is worse
--   than having no record at all and is precisely the claim this table exists to
--   be able to make honestly.
--
--   So a missing row means "we do not know whether this account has agreed". It
--   does NOT mean "declined", and nothing in the application may read it that
--   way. Registration is the only writer, and it writes on the way in.
--
--   The practical consequence is deliberate: existing accounts keep working and
--   keep signing in. This migration takes nothing away from anyone. What it adds
--   is that no account created from now on can exist without a row.
--
-- WHY timestamptz, WHEN EVERY OTHER TIMESTAMP HERE IS NOT
--
--   The rest of this schema uses `timestamp(6) without time zone`, which is what
--   Hibernate generated for LocalDateTime and which is only unambiguous while
--   every writer happens to agree about the zone. That is tolerable for a lesson
--   edit; it is not tolerable for the one column in this database that may have
--   to be produced years later as evidence of when somebody agreed to something.
--   A bare wall-clock reading is not a point in time until you also know where
--   the clock was.
--
--   accepted_at is therefore `timestamptz`, written from an injected Clock as a
--   UTC java.time.Instant, and never from the request — a client-supplied time
--   is a client-supplied claim. This is a considered departure from the
--   surrounding convention, not an oversight, and it is why TermsAcceptance maps
--   Instant where its neighbours map LocalDateTime.
--
-- WHAT IS NOT COLLECTED
--
--   No IP address and no user-agent. Neither is needed to establish that consent
--   was given — the row itself is that record — and both would turn this into a
--   table of personal data with a retention obligation attached to it.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. No existing table is touched: no column added, dropped or
--   retyped, no constraint altered, no data rewritten. Nothing that runs today
--   reads or writes this table.
--
--   Both deployment orders are safe. An instance running the previous build
--   against this schema never mentions terms_acceptances and is completely
--   unaffected — it keeps registering accounts without consent, which is why the
--   backend must be deployed before the frontend and why there is no window in
--   which the new build accepts a registration without one. An instance running
--   the new build against the old schema would fail on the missing table, so
--   this migration goes first, as Flyway already ensures by running before the
--   application context is refreshed.
-- =============================================================================


-- 1. The table. --------------------------------------------------------------
--
-- Append-only in practice: every column is written once and never updated. The
-- application maps them all `updatable = false` for the same reason — this row
-- is a statement about a moment, not a projection of anything current.
--
-- ON DELETE CASCADE on the account, deliberately. Consent belongs to the person
-- who gave it; if their account is ever erased, the record of what they agreed
-- to goes with it rather than surviving as an orphan naming a user id that no
-- longer resolves to anybody. There is no account-deletion path in the
-- application today — this is what the constraint should say when there is one.

CREATE TABLE IF NOT EXISTS public.terms_acceptances
(
    id            bigserial PRIMARY KEY,
    user_id       bigint      NOT NULL,
    terms_version varchar(32) NOT NULL,
    accepted_at   timestamptz NOT NULL,

    CONSTRAINT fk_terms_acceptances_user
        FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT uk_terms_acceptances_user_version
        UNIQUE (user_id, terms_version)
);


-- 2. The per-account access path. --------------------------------------------
--
-- Stated plainly: the unique constraint above already creates a btree index led
-- by user_id, so a lookup of "what has this account accepted" is served with or
-- without this index. It is declared anyway so that access path does not depend
-- on the column order of a constraint that exists for a different reason — the
-- uniqueness rule could be reshaped later without anyone noticing they had also
-- removed the index behind every read.

CREATE INDEX IF NOT EXISTS idx_terms_acceptances_user_id
    ON public.terms_acceptances USING btree (user_id);


-- 3. What the columns mean, for whoever reads the schema before the code. -----

COMMENT ON TABLE public.terms_acceptances IS
    'One row per account per accepted Terms and Conditions version, written '
    'during registration and never updated. NO row for an account means the '
    'answer is UNKNOWN — accounts predating this table were never asked — and '
    'never that consent was declined. Not back-filled: an invented row would '
    'assert agreement to a document the person was never shown.';

COMMENT ON COLUMN public.terms_acceptances.terms_version IS
    'The opaque published version id that was accepted, stored rather than '
    'derived so publishing a new version cannot retroactively change what this '
    'account agreed to. The text of each version lives in the frontend, keyed '
    'by this id; superseded versions are kept there forever for that reason.';

COMMENT ON COLUMN public.terms_acceptances.accepted_at IS
    'When consent was given, UTC. timestamptz rather than the timestamp without '
    'time zone used elsewhere in this schema: this is the one column that may '
    'have to be read as evidence long after the fact, and a wall-clock reading '
    'is not a point in time without a zone. Server-generated from the injected '
    'Clock — never taken from the request.';
