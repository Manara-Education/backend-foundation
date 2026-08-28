-- =============================================================================
-- V9 — A submitted quiz attempt stops depending on the quiz it was taken from
-- =============================================================================
--
-- quiz_attempt_answers referenced quiz_questions and quiz_options with
-- ON DELETE CASCADE, while quiz_attempts — the row carrying the score — did
-- not. So an instructor deleting the option a learner had chosen deleted that
-- learner's answer row and left the attempt reading score=100, correct_count=1,
-- passed=true with nothing behind it. Reproduced end to end: the answer rows for
-- the attempt went from one to zero and the header was untouched.
--
-- Neither half was right on its own. Cascading the header too would delete a
-- result the learner actually earned, on the strength of the instructor tidying
-- up a distractor. Blocking the delete would make an authored quiz uneditable
-- the moment anybody sat it. What was actually wrong is that a historical record
-- was being stored as a pair of pointers into rows that go on being edited.
--
-- So:
--
-- 1. The two content foreign keys become nullable and ON DELETE SET NULL. The
--    answer row survives its question and its option, and the ids stay useful
--    for as long as those rows exist.
--
-- 2. Three snapshot columns record what the learner was actually asked and what
--    they actually chose, written at submission time and never rewritten. Those
--    are what makes the row readable after the authoring rows are gone — and
--    they are correct even while the rows are still there, because a question
--    reworded after the attempt describes a quiz this learner never sat.
--
-- What is deliberately unchanged: attempt_id and quiz_id still cascade.
-- Deleting a quiz, a lesson or a module is the documented destructive authoring
-- operation, and an attempt at a quiz that no longer exists has nothing left to
-- describe — it is removed whole rather than left as a score with no questions.
--
-- Back-fill: existing answers are given their snapshots from the rows they
-- still point at, which is the only source that exists and is accurate for
-- every attempt whose quiz has not been edited since. Nothing is invented for a
-- row whose question or option is already gone — there is no such row today,
-- because the cascade deleted them, and a future one would keep NULL text
-- rather than be given a guess.
--
-- Deployment compatibility: an instance running the previous build keeps
-- working. It writes NULL into three nullable columns it does not know about,
-- and its inserts still satisfy both foreign keys because it always supplies
-- them. What it loses is only the snapshot for attempts submitted during the
-- rollout — never a row, never a score.
-- =============================================================================

-- 1. Snapshot columns. -------------------------------------------------------

ALTER TABLE public.quiz_attempt_answers
    ADD COLUMN IF NOT EXISTS question_text text,
    ADD COLUMN IF NOT EXISTS selected_option_text text,
    ADD COLUMN IF NOT EXISTS correct_option_text text;

-- 2. Back-fill from the authoring rows that are still there. -----------------

UPDATE public.quiz_attempt_answers a
SET question_text = q.text
FROM public.quiz_questions q
WHERE a.question_id = q.id
  AND a.question_text IS NULL;

UPDATE public.quiz_attempt_answers a
SET selected_option_text = o.text
FROM public.quiz_options o
WHERE a.selected_option_id = o.id
  AND a.selected_option_text IS NULL;

-- The answer key as it stands now. For an attempt whose key has not been moved
-- since — every attempt that exists today, since moving it is what this column
-- was added to survive — that is also the key that applied at submission.
UPDATE public.quiz_attempt_answers a
SET correct_option_text = k.text
FROM public.quiz_options k
WHERE k.question_id = a.question_id
  AND k.is_correct
  AND a.correct_option_text IS NULL;

-- 3. The references stop being mandatory. ------------------------------------

ALTER TABLE public.quiz_attempt_answers
    ALTER COLUMN question_id DROP NOT NULL,
    ALTER COLUMN selected_option_id DROP NOT NULL;

-- 4. And stop taking the answer with them. -----------------------------------
--
-- Dropped by what they constrain rather than by name. V1 recorded the two
-- constraints under Hibernate-generated names (fk59s9075rs7i5520p8awj6ri1,
-- fk54g2hjt484k0nnvfx9kj1j8qe), but a database built by an older ddl-auto run
-- carries different generated names for the same two constraints — and a
-- DROP ... IF EXISTS that misses would leave the cascade in place beside the
-- new rule and silently keep deleting the history this migration exists to
-- keep. Reading pg_constraint means the outcome does not depend on the name.

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
              AND t.relname = 'quiz_attempt_answers'
              AND referenced.relname IN ('quiz_questions', 'quiz_options')
            LOOP
                EXECUTE format('ALTER TABLE public.quiz_attempt_answers DROP CONSTRAINT %I', stale_constraint);
            END LOOP;
    END
$$;

ALTER TABLE public.quiz_attempt_answers
    ADD CONSTRAINT fk_quiz_attempt_answers_question
        FOREIGN KEY (question_id) REFERENCES public.quiz_questions (id)
            ON DELETE SET NULL;

ALTER TABLE public.quiz_attempt_answers
    ADD CONSTRAINT fk_quiz_attempt_answers_selected_option
        FOREIGN KEY (selected_option_id) REFERENCES public.quiz_options (id)
            ON DELETE SET NULL;
