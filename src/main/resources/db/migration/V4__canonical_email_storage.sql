-- =============================================================================
-- V4 — Canonical email storage
-- =============================================================================
--
-- V2 stopped NEW case-variant duplicates from being created, by adding the unique
-- index uk_users_email_lower on LOWER(email). It deliberately left two things for
-- this migration, both called out in its own comments:
--
--   * the rows already in the table were not touched, so users.email can still
--     hold 'Ali@x.com';
--   * nothing made LOGIN case-insensitive, because that was an application
--     behaviour change rather than a schema one.
--
-- The application half now stores and looks up one canonical form of every
-- address: trimmed, lower-cased (see EmailAddress). This migration makes the
-- database agree — it normalises the rows that predate that rule, and then makes
-- a non-canonical address impossible to store at all.
--
-- After this runs, users.email = lower(btrim(users.email)) for every row and
-- forever, which is what lets the rest of the application compare addresses with
-- a plain equality instead of remembering to lower-case at every call site.
--
-- SAFETY
--
--   * No row is deleted, merged, or overwritten with another account's data. The
--     only write is an in-place normalisation of the address a row already holds.
--   * If two accounts would collapse onto the same canonical address, this
--     migration aborts before writing anything and names them (step 1). Choosing
--     which of two real accounts survives is not a decision a migration may make
--     unattended.
--   * Flyway runs each migration inside a transaction and PostgreSQL's DDL is
--     transactional, so a failure at any step below leaves the database exactly
--     as it was. There is no half-applied state to clean up.
--   * Locking: step 2 takes ROW EXCLUSIVE and touches only non-canonical rows;
--     step 3 takes ACCESS EXCLUSIVE on users for one sequential scan. On a table
--     of accounts this is milliseconds. Step 4 is a no-op on any database that
--     ran V2.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Refuse to run if two accounts already share one canonical address.
--
-- V2's index means LOWER(email) collisions cannot have survived to here — a
-- database carrying 'Ali@x.com' and 'ali@x.com' fails at V2, not here. What that
-- index does NOT cover is whitespace: ' ali@x.com' and 'ali@x.com' are distinct
-- under LOWER(), so both can be present, and step 2 would make them identical.
--
-- Left alone, that UPDATE would fail on the unique index with a message naming
-- one key and no ids. This block fails first, and says exactly which accounts are
-- involved and how each address is currently stored, so whoever is running the
-- deployment can resolve it rather than reverse-engineer it.
--
-- Nothing is written before this check passes.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    collision_count integer;
    collision_report text;
BEGIN
    SELECT count(*), string_agg(line, E'\n' ORDER BY line)
      INTO collision_count, collision_report
      FROM (
          SELECT lower(btrim(email))
                 || '  ->  user ids ' || string_agg(id::text, ', ' ORDER BY id)
                 || '  (stored as ' || string_agg(quote_literal(email), ', ' ORDER BY id) || ')'
                 AS line
            FROM public.users
           GROUP BY lower(btrim(email))
          HAVING count(*) > 1
      ) AS collisions;

    IF collision_count > 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'unique_violation',
            MESSAGE = format(
                'V4 aborted: %s email address(es) are claimed by more than one account once trimmed and lower-cased. No row has been modified.',
                collision_count),
            DETAIL  = collision_report,
            HINT    = 'These are real accounts and this migration will not choose between them. '
                      'For each address decide which account survives, migrate anything that '
                      'belongs to the others, remove or re-address the losers, then re-run the '
                      'deployment. To review them without deploying: SELECT lower(btrim(email)), '
                      'array_agg(id ORDER BY id), array_agg(email ORDER BY id) FROM users '
                      'GROUP BY 1 HAVING count(*) > 1;';
    END IF;
END
$$;


-- -----------------------------------------------------------------------------
-- 2. Normalise the addresses that predate the canonical rule.
--
-- Every row keeps its own address; only its representation changes. Accounts
-- registered as 'Ali@x.com' remain reachable — more so, since sign-in now matches
-- on the canonical form.
--
-- updated_at is deliberately not touched. This is a representation change made by
-- a deployment, not a profile edit made by the account holder, and moving the
-- timestamp would misreport it as one.
-- -----------------------------------------------------------------------------
UPDATE public.users
   SET email = lower(btrim(email))
 WHERE email <> lower(btrim(email));


-- -----------------------------------------------------------------------------
-- 3. Make a non-canonical address impossible to store.
--
-- This is what turns "the application lower-cases before writing" from a
-- convention into a guarantee. Anything that reaches this table — a future code
-- path that forgets, a data fix typed straight into psql, an import script — is
-- rejected rather than quietly creating a row that no lookup will ever find.
--
-- It also makes the uniqueness in step 4 exact: with every stored value already
-- canonical, LOWER(email) IS the canonical address, so a unique index on it is a
-- unique index on the account's identity.
--
-- btrim() here strips spaces, where Java's String.trim() strips everything up to
-- U+0020. That asymmetry is deliberate and safe in the only direction that
-- matters: the application's rule is the stricter one, so no value the
-- application produces can ever be rejected by this constraint, while a
-- space-padded value inserted by hand still is.
-- -----------------------------------------------------------------------------
ALTER TABLE public.users
    ADD CONSTRAINT ck_users_email_canonical
    CHECK (email = lower(btrim(email)));


-- -----------------------------------------------------------------------------
-- 4. Re-assert the case-insensitive unique index.
--
-- V2 created this and every database that ran V2 already has it, so this is
-- normally a no-op — IF NOT EXISTS makes it one. It is repeated because this
-- index is the actual guarantee of one-account-per-address, and a database
-- baselined at a version above 1 would otherwise reach V4 without it. Cheap to
-- state, expensive to be missing.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_lower
    ON public.users (LOWER(email));
