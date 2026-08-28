-- =============================================================================
-- V12 — Course visibility, beside course status rather than inside it
-- =============================================================================
--
-- One additive column: courses.visibility, PUBLIC or PRIVATE.
--
-- WHY IT IS NOT A NEW STATUS
--
--   The obvious shape — adding PRIVATE to courses_status_check alongside DRAFT
--   and PUBLISHED — is wrong, and expensively so. A course that is published and
--   private is both things at once: it has a publication baseline
--   (last_published_at), it has learners measuring their "Updated" badge against
--   that baseline, and it has an instructor who may take it off the catalogue
--   without any of that being destroyed. Folding the two into one column means
--   "make this private" overwrites "this is published", which erases the
--   baseline's meaning and makes DRAFT+PRIVATE — an unfinished course for a
--   closed cohort — inexpressible.
--
--   So: two columns, four legal combinations, and one derived rule that reads
--   both. Discoverable means PUBLISHED *and* PUBLIC; everything else is reached
--   only by somebody who already holds the course, owns it, or is staff.
--
-- WHY EVERY EXISTING ROW IS PUBLIC
--
--   Because every existing row *is* public: nothing on this platform has ever
--   been private, so PUBLIC is not a chosen default but a statement of fact
--   about the data. It is written three ways on purpose — the column DEFAULT,
--   the explicit back-fill below, and the Java field initialiser — because each
--   covers a different way a row could otherwise arrive with no value, and a
--   course that goes dark because a default was missed is the single worst
--   outcome this migration could have. No course becomes hidden by deploying it.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. No column is dropped or retyped, nothing changes nullability,
--   and no existing constraint is altered — courses_status_check keeps naming
--   exactly DRAFT and PUBLISHED, which is the point.
--
--   Both deployment orders are safe. An instance running the previous build
--   against this schema never mentions the column, and its inserts take the
--   DEFAULT, so every course it creates is public — which is what that build
--   means by a course. An instance running the new build against the old schema
--   would fail on the column, so this migration goes first, as Flyway already
--   ensures by running before the context is refreshed.
-- =============================================================================

-- 1. The column. -------------------------------------------------------------

ALTER TABLE public.courses
    ADD COLUMN IF NOT EXISTS visibility character varying(20)
        DEFAULT 'PUBLIC'::character varying NOT NULL;

-- 2. Existing rows. ----------------------------------------------------------
--
-- The DEFAULT above already back-filled every row PostgreSQL rewrote. Stated
-- again anyway, so a column added by an earlier hand-run script — without a
-- default, or with a different one — is normalised to the same fact.

UPDATE public.courses
SET visibility = 'PUBLIC'
WHERE visibility IS NULL OR visibility NOT IN ('PUBLIC', 'PRIVATE');

-- 3. The domain. -------------------------------------------------------------
--
-- Mirrors courses_status_check and courses_access_type_check: the enum is
-- enforced by the database, not only by Hibernate, so a value that no Java enum
-- constant matches cannot be written by a script, a fixture or a future service.

ALTER TABLE public.courses
    DROP CONSTRAINT IF EXISTS ck_courses_visibility;

ALTER TABLE public.courses
    ADD CONSTRAINT ck_courses_visibility
        CHECK (((visibility)::text = ANY ((ARRAY['PUBLIC'::character varying,
                                                 'PRIVATE'::character varying])::text[])));

-- 4. The discovery index. ----------------------------------------------------
--
-- Discovery is the platform's most-run query and it now filters on both axes.
-- Partial rather than a plain two-column btree: the rows it has to find are the
-- discoverable ones, and indexing only those keeps the index proportional to the
-- catalogue rather than to every draft and private course on the platform. It is
-- also what keeps the count behind a paginated catalogue an index-only scan
-- instead of a filter applied to a wider one.

CREATE INDEX IF NOT EXISTS idx_courses_discoverable
    ON public.courses USING btree (id)
    WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC';
