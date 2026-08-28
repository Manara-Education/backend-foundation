-- =============================================================================
-- V11 — A lesson is no longer necessarily a video
-- =============================================================================
--
-- Until now `lessons` had no column saying what kind of lesson it was, because
-- there was only one kind. Every surface inferred it: the validator from
-- `video_url` being blank, the student player from the same field being null.
-- This migration makes the kind a stored fact and gives the second kind
-- somewhere to live.
--
-- Three changes, all additive, and none of them touches a single existing value:
--
--   1. `content_type` — the discriminator, defaulted to VIDEO so that every row
--      written before this column existed is exactly what it already was.
--
--   2. `rich_content` — the authored document for a RICH_CONTENT lesson, as
--      canonical JSON. Nullable, and null for every existing row.
--
--   3. `video_url` becomes nullable, which is the one change here that is not
--      purely additive and is unavoidable: a rich-content lesson has no video,
--      and NOT NULL is the schema asserting the very thing this feature exists
--      to stop asserting. Dropping a NOT NULL cannot fail and cannot invalidate
--      a stored row — every existing value still satisfies the looser column —
--      so no data is at risk. What replaces the guarantee is a CHECK that ties
--      the requirement to the type instead of to every row (see part 4).
--
-- What is deliberately NOT done here:
--
--   * No lesson is reclassified. There is no heuristic anywhere in this file —
--     nothing looks at whether `description` is long, or whether `video_url`
--     looks like a real link. Every existing lesson becomes VIDEO because that
--     is what the DEFAULT says, and for no other reason. A mis-migrated lesson
--     would show a learner an empty player where their video used to be.
--
--   * No content is moved. `description` keeps its own meaning — the short blurb
--     shown beside a lesson — and is not repurposed as the rich-content body.
--     Rich content is a new column precisely so that a course whose lessons have
--     descriptions today does not silently acquire rich-content lessons.
--
--   * Nothing is written to `content_updated_at`. This is a schema change, not
--     an instructor's edit, and moving those timestamps would tell every
--     enrolled learner on the platform that every lesson they own had changed on
--     the morning of the deploy. The whole of the update-tracking design in V7
--     depends on that stamp meaning "a person changed this".
--
-- Deployment compatibility. Both new columns are safe for an instance running
-- the previous build to write around: `content_type` is NOT NULL *with a
-- default*, so an INSERT that does not name it still succeeds and produces a
-- video lesson, and `rich_content` is nullable. The previous build also still
-- always supplies `video_url`, so relaxing that column cannot affect it. The two
-- builds can run against this schema at the same time.
-- =============================================================================


-- 1. The discriminator. -------------------------------------------------------
--
-- NOT NULL with a default rather than nullable: "unknown kind of lesson" is not
-- a state any reader should have to handle, and defaulting it means no back-fill
-- pass and no window in which a row exists without a type.

ALTER TABLE public.lessons
    ADD COLUMN IF NOT EXISTS content_type character varying(32) NOT NULL DEFAULT 'VIDEO';

COMMENT ON COLUMN public.lessons.content_type IS
    'What the lesson teaches with: VIDEO or RICH_CONTENT. Never inferred from whether '
        'video_url is null - both content columns survive a change of type, and this is the only '
        'thing that decides which one is read.';


-- 2. The authored document. ---------------------------------------------------
--
-- TEXT holding canonical JSON, not jsonb. Nothing queries inside it, and jsonb
-- would re-serialise the value on its own terms - leaving the application
-- comparing a document it wrote against a document Postgres rewrote, and
-- announcing a content change to every enrolled learner whenever the two
-- happened to differ in key order.

ALTER TABLE public.lessons
    ADD COLUMN IF NOT EXISTS rich_content text;

COMMENT ON COLUMN public.lessons.rich_content IS
    'Sanitized, canonical JSON document for a RICH_CONTENT lesson. Written only by '
        'RichContentSanitizer, which rebuilds it from an allowlist rather than filtering it, so '
        'this column cannot hold markup, a handler or an unsafe URL. Retained, not cleared, when '
        'a lesson is switched back to VIDEO.';


-- 3. A video is required of video lessons, not of lessons. --------------------

ALTER TABLE public.lessons
    ALTER COLUMN video_url DROP NOT NULL;


-- 4. The requirement, restated against the type. ------------------------------
--
-- This is what stops part 3 from being a loss. The old NOT NULL said "every
-- lesson has a video"; this says "every video lesson has a video", which is the
-- true statement and the one worth enforcing in the schema.
--
-- Validated against existing rows on purpose - they all have a video_url and are
-- all VIDEO, so the constraint is provably satisfiable now and the check is
-- cheap. RICH_CONTENT rows are deliberately not required to carry a document at
-- the schema level: emptiness is a judgement about meaning (formatting with no
-- text in it is empty; a single call-to-action is not) and that judgement lives
-- in RichContentSanitizer, where it can explain itself to the instructor.

ALTER TABLE public.lessons
    DROP CONSTRAINT IF EXISTS lessons_content_type_check;

ALTER TABLE public.lessons
    ADD CONSTRAINT lessons_content_type_check
        CHECK (content_type IN ('VIDEO', 'RICH_CONTENT'));

ALTER TABLE public.lessons
    DROP CONSTRAINT IF EXISTS lessons_video_required_for_video_type;

ALTER TABLE public.lessons
    ADD CONSTRAINT lessons_video_required_for_video_type
        CHECK (content_type <> 'VIDEO' OR video_url IS NOT NULL);
