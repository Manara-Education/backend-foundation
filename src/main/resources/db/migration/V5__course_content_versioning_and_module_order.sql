-- =============================================================================
-- V5 — Publication baseline, content version, and deterministic module order
-- =============================================================================
--
-- Three things this migration establishes, all additive.
--
-- 1. A publication baseline (`last_published_at`) and a content version signal
--    (`content_updated_at`) on `courses`.
--
--    `updated_at` cannot serve either purpose. It is Hibernate's @PreUpdate
--    stamp, and it moves for reasons that have nothing to do with an instructor
--    editing anything a learner can see: `CheckoutProcessor` increments
--    `students_count` on every purchase, and `VideoMetadataService` rewrites
--    `duration` from a background thread after a video lookup lands. A learner
--    badge driven by `updated_at` would light up because somebody else bought
--    the course.
--
--    Derived, never stored:
--        hasUpdatesSincePublish =
--            status = 'PUBLISHED'
--            AND last_published_at IS NOT NULL
--            AND content_updated_at > last_published_at
--
-- 2. A safe back-fill, so no course that is live today starts shouting "Updated"
--    at its students the moment this deploys.
--
--    Published rows get last_published_at = content_updated_at = the best
--    timestamp we have, which makes the two equal and the comparison strictly
--    false. Draft rows get a NULL baseline, which is also false. Every existing
--    course therefore begins at "not updated", and only an instructor's next
--    real edit moves it.
--
-- 3. Deterministic module order.
--
--    `course_modules.order_index` already exists and is already NOT NULL, but
--    nothing in the database stopped two modules of one course from claiming the
--    same position. This normalises whatever is there into a contiguous 0..N-1
--    run per course — preserving the order those rows are read in today — and
--    then makes duplicates impossible.
--
--    The constraint is DEFERRABLE INITIALLY DEFERRED on purpose. A reorder is a
--    permutation: rewriting A:0→1 and B:1→0 passes through a state where two
--    rows briefly share a position. Checking at COMMIT rather than per statement
--    is what lets the application write a permutation directly instead of
--    shuffling rows through temporary positions, while still guaranteeing that
--    no transaction can *end* with a duplicate.
--
-- Deployment compatibility: every change is additive and every new column is
-- nullable, so an older application instance running against this schema keeps
-- working untouched — it neither reads nor writes the new columns, and it
-- already writes contiguous positions, so it cannot trip the new constraint.
-- =============================================================================

-- 1. The two timestamps. -----------------------------------------------------

ALTER TABLE public.courses
    ADD COLUMN IF NOT EXISTS last_published_at timestamp(6) without time zone,
    ADD COLUMN IF NOT EXISTS content_updated_at timestamp(6) without time zone;

COMMENT ON COLUMN public.courses.last_published_at IS
    'When the course last became publicly visible. NULL means never published. '
    'The baseline hasUpdatesSincePublish is measured against.';

COMMENT ON COLUMN public.courses.content_updated_at IS
    'When an instructor last made a meaningful, student-facing change to this '
    'course. Never moved by student activity, background jobs or audit writes — '
    'unlike updated_at, which is moved by all three.';

-- 2. Back-fill, so today''s live courses start at "not updated". --------------
--
-- WHERE ... IS NULL keeps this idempotent and keeps it from touching a row that
-- a newer application instance has already stamped, in the window where both
-- versions are running.

UPDATE public.courses
SET last_published_at  = COALESCE(updated_at, created_at),
    content_updated_at = COALESCE(updated_at, created_at)
WHERE status = 'PUBLISHED'
  AND last_published_at IS NULL
  AND content_updated_at IS NULL;

-- A draft has no baseline to be newer than, so hasUpdatesSincePublish is false
-- for it whatever content_updated_at says. Seeding it anyway means the first
-- publish after this deploy has an honest history behind it.
UPDATE public.courses
SET content_updated_at = COALESCE(updated_at, created_at)
WHERE status <> 'PUBLISHED'
  AND content_updated_at IS NULL;

-- 3. Deterministic, gap-free module order. -----------------------------------
--
-- ORDER BY order_index, id reproduces the order these rows are served in today
-- (findByCourseIdOrderByOrderIndexAsc) and settles ties the only stable way
-- available, so a course whose modules currently read 0,5,5,9 becomes 0,1,2,3
-- with its visible order intact rather than reshuffled.

WITH normalised AS (SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY course_id
                               ORDER BY order_index, id
                               ) - 1 AS position
                    FROM public.course_modules)
UPDATE public.course_modules m
SET order_index = n.position
FROM normalised n
WHERE m.id = n.id
  AND m.order_index IS DISTINCT FROM n.position;

-- Positions are per course and can never be negative.
ALTER TABLE public.course_modules
    DROP CONSTRAINT IF EXISTS ck_course_modules_order_index_non_negative;

ALTER TABLE public.course_modules
    ADD CONSTRAINT ck_course_modules_order_index_non_negative
        CHECK (order_index >= 0);

-- Deferred to COMMIT so a permutation can be written directly; still absolute
-- at the end of every transaction.
ALTER TABLE public.course_modules
    DROP CONSTRAINT IF EXISTS uk_course_modules_course_order;

ALTER TABLE public.course_modules
    ADD CONSTRAINT uk_course_modules_course_order
        UNIQUE (course_id, order_index)
        DEFERRABLE INITIALLY DEFERRED;

-- The published catalogue is read on every learner page load and now also
-- filters on publication state for the update badge.
CREATE INDEX IF NOT EXISTS idx_courses_status
    ON public.courses USING btree (status);
