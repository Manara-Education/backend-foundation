-- =============================================================================
-- V7 — Per-item content versions, a change log, and a purchase record
-- =============================================================================
--
-- V5 gave `courses` a content version and compared it to the publication
-- baseline, which answers the instructor's question: "have I edited since I
-- published?". This migration adds what the *learner's* question needs, which is
-- a different one and is answered per enrollment:
--
--     course.content_updated_at > enrollment.enrolled_at
--
-- Three parts, all additive.
--
--   1. `content_updated_at` on course_modules, lessons and quizzes, so a
--      curriculum can point at the one lesson that changed instead of marking
--      every row updated because the course did.
--
--   2. `course_changes` — an append-only log of what an instructor did, used
--      only for wording, and for the two things a timestamp provably cannot
--      express: content that was removed (no row left to stamp) and content that
--      was moved (the old parent is gone after the write).
--
--   3. `course_purchases` — what a one-off buyer actually paid. Subscriptions
--      already snapshot this on course_subscriptions.price_paid; outright
--      purchases persisted nothing at all, so repricing a course erased the only
--      surviving evidence of what its existing buyers were charged.
--
-- Deployment compatibility. Every new column is NOT NULL *with a default*, not
-- NOT NULL alone: an application instance running the previous build does not
-- know these columns exist, so its INSERTs do not name them and the default
-- fills them in. Both builds can run against this schema at once. `courses`
-- gains no new column and its existing `content_updated_at` deliberately stays
-- nullable, because the previous build *does* name that one and writes NULL to
-- it before stamping it a statement later.
-- =============================================================================


-- 1. Per-item content versions. ----------------------------------------------
--
-- Back-filled to created_at rather than to updated_at or now(), and that choice
-- is the whole of the "no false notifications on deploy" guarantee.
--
-- The read rule is:
--
--     created_at        > enrolled_at  ->  NEW
--     content_updated_at > enrolled_at ->  UPDATED
--     otherwise                        ->  UNCHANGED
--
-- With content_updated_at = created_at, the UPDATED branch is unreachable for
-- every existing row: it can only fire when created_at <= enrolled_at, and in
-- that case content_updated_at <= enrolled_at too. So no learner is shown an
-- item-level badge for anything that happened before this deploy. Only an
-- instructor's next real edit moves one.
--
-- updated_at would have been wrong for exactly the reason V5 documented at
-- course level, and worse here: lessons.updated_at is moved by
-- VideoMetadataService writing the resolved duration back from a background
-- thread, minutes after the instructor closed the form.

ALTER TABLE public.course_modules
    ADD COLUMN IF NOT EXISTS content_updated_at timestamp(6) without time zone;
UPDATE public.course_modules SET content_updated_at = created_at WHERE content_updated_at IS NULL;
ALTER TABLE public.course_modules
    ALTER COLUMN content_updated_at SET DEFAULT now(),
    ALTER COLUMN content_updated_at SET NOT NULL;

ALTER TABLE public.lessons
    ADD COLUMN IF NOT EXISTS content_updated_at timestamp(6) without time zone;
UPDATE public.lessons SET content_updated_at = created_at WHERE content_updated_at IS NULL;
ALTER TABLE public.lessons
    ALTER COLUMN content_updated_at SET DEFAULT now(),
    ALTER COLUMN content_updated_at SET NOT NULL;

ALTER TABLE public.quizzes
    ADD COLUMN IF NOT EXISTS content_updated_at timestamp(6) without time zone;
UPDATE public.quizzes SET content_updated_at = created_at WHERE content_updated_at IS NULL;
ALTER TABLE public.quizzes
    ALTER COLUMN content_updated_at SET DEFAULT now(),
    ALTER COLUMN content_updated_at SET NOT NULL;

COMMENT ON COLUMN public.lessons.content_updated_at IS
    'When an instructor last changed something about this lesson a learner can '
    'see. Never moved by the background video lookup that rewrites duration — '
    'unlike updated_at, which is.';
COMMENT ON COLUMN public.course_modules.content_updated_at IS
    'When an instructor last changed something about this module a learner can see.';
COMMENT ON COLUMN public.quizzes.content_updated_at IS
    'When an instructor last changed this quiz''s title, instructions, pass mark, '
    'questions, answers or their order.';


-- 2. Re-derive the course-level content version. -----------------------------
--
-- This one needs care, and it is the only non-obvious step in the migration.
--
-- V5 back-filled courses.content_updated_at = COALESCE(updated_at, created_at)
-- and set last_published_at to the same value. That was safe for the rule V5
-- introduced, because equal timestamps make `content_updated_at >
-- last_published_at` strictly false. It is NOT safe for the rule this migration
-- introduces: compared against an enrollment instead, that same value has no
-- such protection, and updated_at is exactly the column V5 itself documented as
-- untrustworthy — it moves when a learner buys the course (students_count) and
-- when a background video lookup lands (duration).
--
-- Left alone, a course whose updated_at moved because somebody else bought it
-- in July would announce itself as updated to everyone who enrolled in June.
-- That is precisely the false notification this migration must not produce.
--
-- So it is re-derived from facts that are actually about content: when the
-- course was created, and when the newest piece of content in it appeared. Any
-- learner who enrolled after the last lesson was added sees nothing. Any learner
-- who enrolled before it sees "updated" — and the curriculum will show them that
-- lesson marked NEW, which is true, checkable, and the correct answer rather
-- than a suppressed one.
--
-- GREATEST ignores NULLs in PostgreSQL, so a course with no modules, no lessons
-- and no quizzes falls back to its own created_at.

WITH content_age AS (SELECT c.id,
                            GREATEST(
                                    c.created_at,
                                    (SELECT max(l.created_at) FROM public.lessons l WHERE l.course_id = c.id),
                                    (SELECT max(m.created_at) FROM public.course_modules m WHERE m.course_id = c.id)
                            ) AS derived
                     FROM public.courses c)
UPDATE public.courses c
SET content_updated_at = a.derived
FROM content_age a
WHERE c.id = a.id
  AND a.derived IS NOT NULL
  AND c.content_updated_at IS DISTINCT FROM a.derived;

-- Keep the instructor-facing badge dark on deploy day, which is what V5 intended
-- and what is true: nobody edited anything during a migration. Without this, a
-- course whose content is newer than its V5-assigned baseline would come out of
-- this migration claiming to have unpublished edits it does not have.
--
-- Only ever moves the baseline forward, and only for a course that has one, so a
-- never-published draft keeps its NULL.

UPDATE public.courses
SET last_published_at = content_updated_at
WHERE last_published_at IS NOT NULL
  AND content_updated_at IS NOT NULL
  AND content_updated_at > last_published_at;


-- 3. The change log. ---------------------------------------------------------
--
-- Append-only, and deliberately carries no foreign key to the entity it
-- describes: a REMOVED row has to outlive the row it is about, which is the one
-- case the table exists for. entity_title is snapshotted for the same reason —
-- it is the only name a deleted lesson has left.
--
-- Empty after this migration, on purpose. There is no history to reconstruct:
-- nothing recorded what past edits were, and inventing rows would put words in an
-- instructor's mouth. Item state still reads correctly from the timestamps
-- above; what predates this table simply has no caption, which the API models
-- explicitly (changeSummary is nullable).

CREATE TABLE IF NOT EXISTS public.course_changes
(
    id           bigserial PRIMARY KEY,
    course_id    bigint                      NOT NULL,
    entity_type  varchar(20)                 NOT NULL,
    entity_id    bigint,
    entity_title varchar(255)                NOT NULL,
    change_type  varchar(20)                 NOT NULL,
    detail       varchar(255),
    occurred_at  timestamp(6) without time zone NOT NULL,

    CONSTRAINT fk_course_changes_course
        FOREIGN KEY (course_id) REFERENCES public.courses (id) ON DELETE CASCADE
);

-- The only read of this table: one range scan per course-details request,
-- bounded below by the reader's own enrolled_at. Leading course_id, then
-- occurred_at, is exactly that access path.
CREATE INDEX IF NOT EXISTS idx_course_changes_course_occurred
    ON public.course_changes USING btree (course_id, occurred_at);

COMMENT ON TABLE public.course_changes IS
    'Append-only log of instructor changes to published courses. Supplies the '
    'wording a learner is shown, and is the only record of content that was '
    'removed or moved. Never decides whether something is new or updated — that '
    'is derived from created_at and content_updated_at.';
COMMENT ON COLUMN public.course_changes.entity_id IS
    'The changed entity, or NULL for a change to the course itself. Deliberately '
    'not a foreign key: a REMOVED row outlives what it describes.';


-- 4. Outright purchases. -----------------------------------------------------
--
-- The one-off twin of course_subscriptions. Nothing back-fills it: purchases
-- made before this deploy were never recorded anywhere, and a row invented from
-- today's course price would assert something about the past that may well be
-- false — which is the exact error this table exists to prevent. Those purchases
-- remain unauditable, and that is a fact about the data rather than something a
-- migration can repair.

CREATE TABLE IF NOT EXISTS public.course_purchases
(
    id                bigserial PRIMARY KEY,
    course_id         bigint                         NOT NULL,
    student_id        bigint                         NOT NULL,
    list_price        numeric(38, 2)                 NOT NULL,
    amount_paid       numeric(38, 2)                 NOT NULL,
    currency          varchar(3)                     NOT NULL,
    payment_reference varchar(100),
    purchased_at      timestamp(6) without time zone NOT NULL,
    created_at        timestamp(6) without time zone NOT NULL,

    CONSTRAINT fk_course_purchases_course
        FOREIGN KEY (course_id) REFERENCES public.courses (id),
    CONSTRAINT fk_course_purchases_student
        FOREIGN KEY (student_id) REFERENCES public.students (id),
    CONSTRAINT ck_course_purchases_amounts_non_negative
        CHECK (list_price >= 0 AND amount_paid >= 0)
);

CREATE INDEX IF NOT EXISTS idx_course_purchases_student_course
    ON public.course_purchases USING btree (student_id, course_id);
CREATE INDEX IF NOT EXISTS idx_course_purchases_course_id
    ON public.course_purchases USING btree (course_id);

COMMENT ON TABLE public.course_purchases IS
    'One immutable row per outright course purchase. What a learner paid is read '
    'from here, never derived from the course''s current price — repricing a '
    'course must not rewrite what somebody was charged for it.';
