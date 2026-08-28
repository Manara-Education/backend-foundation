-- =============================================================================
-- V10 — A submitted attempt outlives the quiz it was taken from
-- =============================================================================
--
-- V9 stopped an answer row depending on the question and option it pointed at,
-- by giving it snapshots of both. It deliberately left quiz_id alone, and said
-- why: "an attempt at a quiz that no longer exists has nothing left to
-- describe".
--
-- That reason stopped being true the moment V9 landed. An answer row now
-- carries its own question text, its own chosen-option text and its own answer
-- key, and the attempt header carries the score, the counts and the pass mark.
-- Between them they are a complete account of what somebody was asked and what
-- they answered — an account that no longer needs the quiz rows for anything.
-- The only thing quiz_id ON DELETE CASCADE still did was destroy it.
--
-- And it destroyed it from three directions, because deleting a quiz is not the
-- only thing that deletes a quiz: removing a quiz from a lesson does, deleting
-- the lesson does, and deleting the module above it does. An instructor
-- retiring one lesson of a published course was silently erasing every exam
-- result any learner had ever earned inside it, with nothing in the UI saying
-- so and no way to get it back.
--
-- Deleting content an instructor no longer teaches stays legitimate and is
-- unchanged. What stops happening is the second, unasked-for deletion that rode
-- along with it.
--
-- So:
--
-- 1. quiz_id becomes nullable and ON DELETE SET NULL. The attempt survives the
--    quiz; while the quiz still exists the id keeps pointing at it, so nothing
--    about a live course changes.
--
-- 2. quiz_title records what the attempt was an attempt at, written at
--    submission time and never rewritten — the attempt-level twin of the three
--    columns V9 added to the answer rows, and for the same reason: a quiz
--    renamed after the fact describes an exam this learner never sat.
--
-- Back-fill: existing attempts take the title of the quiz they still point at,
-- which is the only source there is and is accurate for every attempt whose
-- quiz has not been renamed since.
--
-- Deployment compatibility: an instance running the previous build keeps
-- working. It writes NULL into one nullable column it does not know about, and
-- its inserts still satisfy the foreign key because it always supplies quiz_id.
-- What it loses is only the title snapshot for attempts submitted during the
-- rollout — never a row, never a score.
--
-- Not addressed here, deliberately: course_id still has no ON DELETE rule of
-- its own, because deleting a whole course is a different operation from
-- editing one and is out of this change's scope.
-- =============================================================================

-- 1. The snapshot column. ----------------------------------------------------

ALTER TABLE public.quiz_attempts
    ADD COLUMN IF NOT EXISTS quiz_title text;

-- 2. Back-fill from the quizzes that are still there. ------------------------

UPDATE public.quiz_attempts a
SET quiz_title = q.title
FROM public.quizzes q
WHERE a.quiz_id = q.id
  AND a.quiz_title IS NULL;

-- 3. The reference stops being mandatory. ------------------------------------

ALTER TABLE public.quiz_attempts
    ALTER COLUMN quiz_id DROP NOT NULL;

-- 4. And stops taking the attempt with it. -----------------------------------
--
-- Dropped by what it constrains rather than by name, for the reason V9 gives at
-- length: V1 recorded this constraint under a Hibernate-generated name
-- (fkfwipvfipnnwsoacoyv5k7fbxc), and a database built by an older ddl-auto run
-- carries a different generated name for the same constraint. A
-- DROP ... IF EXISTS that missed would leave the cascade in place beside the
-- new rule and go on deleting the history this migration exists to keep.

DO
$$
    DECLARE
        stale_constraint text;
    BEGIN
        FOR stale_constraint IN
            SELECT c.conname
            FROM pg_constraint c
                     JOIN pg_class t ON t.oid = c.conrelid
                     JOIN pg_namespace n ON n.oid = t.relnamespace
                     JOIN pg_class referenced ON referenced.oid = c.confrelid
            WHERE c.contype = 'f'
              AND n.nspname = 'public'
              AND t.relname = 'quiz_attempts'
              AND referenced.relname = 'quizzes'
            LOOP
                EXECUTE format('ALTER TABLE public.quiz_attempts DROP CONSTRAINT %I', stale_constraint);
            END LOOP;
    END
$$;

ALTER TABLE public.quiz_attempts
    ADD CONSTRAINT fk_quiz_attempts_quiz
        FOREIGN KEY (quiz_id) REFERENCES public.quizzes (id)
            ON DELETE SET NULL;

-- 5. The uniqueness rule follows quiz_id. ------------------------------------
--
-- uk_quiz_attempts_student_quiz_number is (student_id, quiz_id, attempt_number).
-- PostgreSQL treats NULLs as distinct in a unique index, so detached attempts
-- simply stop participating in it — which is what should happen: "this learner's
-- third attempt at that quiz" is a statement about a quiz that still exists.
-- Left exactly as it is; recorded here so the next reader does not have to
-- work out whether it was overlooked.
