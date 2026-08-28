-- =============================================================================
-- V8 — A revision on the course, and retirement on its subscription plans
-- =============================================================================
--
-- Two additive columns, both closing a defect that had no workaround.
--
-- 1. courses.revision — optimistic concurrency for the aggregate PUT.
--
--    The course editor saves the whole course in one request, so a payload
--    built from a copy loaded an hour ago *is* an hour-old course: applying it
--    restored every field that copy was holding. Two tabs were enough to turn a
--    paid course back into a free one — one switched it to PURCHASE/199, the
--    other renamed a lesson from a stale copy, and both were answered 200.
--
--    Every accepted mutation now advances this counter, and an update has to
--    say which revision it was built from. NOT NULL with a default of 0 so
--    existing rows and any instance still running the previous build both read
--    as a real revision rather than as no revision. BIGINT because it is a
--    counter, not an identity: it wraps at a number no course will reach.
--
--    Not a JPA @Version column. @Version protects one row against two
--    persistence contexts; what has to be protected here is the whole aggregate
--    a client edits — modules, lessons, quizzes, options and plans — against a
--    client that is a revision behind. So the domain owns it, and it moves for
--    a nested quiz edit and a reorder exactly as it does for a title change.
--
-- 2. subscription_plans.retired_at — plans that stop being offered without
--    ceasing to exist.
--
--    A plan the payload no longer mentioned was hard-deleted, and
--    course_entitlements.subscription_plan_id and
--    course_subscriptions.subscription_plan_id both reference it. So one
--    subscriber was enough to make a plan permanently undeletable and to trap
--    the course in SUBSCRIPTION for good, with the instructor told only that
--    the request "conflicts with data that already exists".
--
--    The foreign keys are deliberately left exactly as they are. They are
--    correct: a subscription must not point at a row that is gone. ON DELETE
--    CASCADE would delete learners' subscriptions to satisfy the delete, and
--    ON DELETE SET NULL would erase which plan somebody bought — both destroy
--    the history the constraint exists to protect. Retirement takes the plan
--    off the offer instead and leaves every reference standing.
--
-- Deployment compatibility: additive only. No column is dropped or retyped and
-- no existing column changes nullability, so an instance running the previous
-- build keeps working against this schema — it never reads either column, and
-- both have defaults that describe the state its writes leave rows in (revision
-- 0, plan not retired).
-- =============================================================================

-- 1. The aggregate revision. ------------------------------------------------

ALTER TABLE public.courses
    ADD COLUMN IF NOT EXISTS revision bigint DEFAULT 0 NOT NULL;

-- Existing rows: every course starts at revision 0, which the DEFAULT above
-- already gave them. Stated rather than assumed, so a column added by some
-- earlier hand-run migration is normalised too.
UPDATE public.courses
SET revision = 0
WHERE revision IS NULL;

ALTER TABLE public.courses
    DROP CONSTRAINT IF EXISTS ck_courses_revision_non_negative;

ALTER TABLE public.courses
    ADD CONSTRAINT ck_courses_revision_non_negative
        CHECK (revision >= 0);

-- 2. Plan retirement. --------------------------------------------------------

ALTER TABLE public.subscription_plans
    ADD COLUMN IF NOT EXISTS retired_at timestamp(6) without time zone;

-- Nothing to back-fill: every plan that exists today is still being offered,
-- which is exactly what NULL means. A plan that had already been deleted is
-- gone and cannot be recovered — this stops the next one from being.

-- The offer lookup runs on every course read, and it filters on this column.
CREATE INDEX IF NOT EXISTS idx_subscription_plans_course_active
    ON public.subscription_plans USING btree (course_id, order_index)
    WHERE retired_at IS NULL;
