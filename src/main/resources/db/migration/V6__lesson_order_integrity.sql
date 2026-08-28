-- =============================================================================
-- V6 — Deterministic lesson order, per scope
-- =============================================================================
--
-- V5 gave `course_modules.order_index` the guarantees it needed and left the
-- lessons alone, because only modules had a reorder command at the time. Both
-- lesson scopes now have one too — a flat course's root lessons, and the
-- lessons inside one module — so this extends the same three guarantees to
-- `lessons.order_index`.
--
-- 1. The scope is (course_id, module_id), not course_id.
--
--    A lesson's position is relative to its siblings under one parent. Two
--    modules of the same course both having a lesson at position 0 is correct
--    and must stay legal; two lessons of the *same* module sharing position 0 is
--    the ambiguity this closes. NULLS NOT DISTINCT is what extends that to a
--    flat course, whose lessons all have module_id IS NULL — without it Postgres
--    would treat every root lesson's scope as distinct from every other's and
--    the constraint would cover nothing at all where flat courses are concerned.
--
-- 2. A normalising back-fill, because the constraint cannot be trusted onto
--    existing rows.
--
--    Nothing in the database has been stopping duplicates, and duplicates are
--    likely: until this release the aggregate save wrote positions from the
--    submitted array, and a lesson moved between modules carried its old
--    position into its new parent. ROW_NUMBER() over the order those rows are
--    read in today rewrites each scope as a contiguous 0..N-1 run, so every
--    existing course keeps the curriculum order its learners currently see.
--
-- 3. DEFERRABLE INITIALLY DEFERRED, for the same reason as V5's.
--
--    A reorder is a permutation, and rewriting A:0→1 and B:1→0 passes through a
--    state where two rows briefly share a position. Checking at COMMIT lets the
--    application write a permutation directly while still guaranteeing that no
--    transaction can end with a duplicate.
--
-- Deployment compatibility: additive, and no column is added, dropped or
-- retyped. An older application instance running against this schema keeps
-- working — it writes contiguous per-scope positions already, and its structure
-- switches move lessons between parents inside a single transaction, which the
-- deferred check permits.
-- =============================================================================

-- 1. Normalise every scope to a contiguous 0..N-1 run. -----------------------
--
-- Partitioned by the pair that defines a sibling group. The ORDER BY reproduces
-- how the application reads lessons today — position first, id as the
-- tiebreaker — so this preserves the visible order rather than reshuffling it.

WITH normalised AS (SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY course_id, module_id
                               ORDER BY order_index, id
                               ) - 1 AS position
                    FROM public.lessons)
UPDATE public.lessons l
SET order_index = n.position
FROM normalised n
WHERE l.id = n.id
  AND l.order_index IS DISTINCT FROM n.position;

-- 2. Positions can never be negative. ----------------------------------------

ALTER TABLE public.lessons
    DROP CONSTRAINT IF EXISTS ck_lessons_order_index_non_negative;

ALTER TABLE public.lessons
    ADD CONSTRAINT ck_lessons_order_index_non_negative
        CHECK (order_index >= 0);

-- 3. One lesson per position, per parent. ------------------------------------
--
-- NULLS NOT DISTINCT so a flat course — every lesson of which has module_id
-- IS NULL — is covered by the same constraint as a modular one.

ALTER TABLE public.lessons
    DROP CONSTRAINT IF EXISTS uk_lessons_scope_order;

ALTER TABLE public.lessons
    ADD CONSTRAINT uk_lessons_scope_order
        UNIQUE NULLS NOT DISTINCT (course_id, module_id, order_index)
        DEFERRABLE INITIALLY DEFERRED;

-- 4. The lookup both lesson reorder commands take their row locks through. ----

CREATE INDEX IF NOT EXISTS idx_lessons_course_module_order
    ON public.lessons USING btree (course_id, module_id, order_index);
