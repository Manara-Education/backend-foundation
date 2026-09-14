-- Manara eligible-offer audit — query version tech-1/v1 (TECH-1).
--
-- One row per course that may be shown to an anonymous visitor, with every commercial field a
-- public offer is built from, and the reasons a row cannot yet be shown truthfully.
--
-- READ-ONLY. Run it the way docs/catalog/offer-audit/README.md describes — in a session where
-- default_transaction_read_only is on, so the server refuses a write even if this file were
-- edited into one. It selects no learner, purchaser, instructor or account data: eligible course
-- titles are already public by definition, and nothing else here identifies a person.
--
-- Eligibility is Course.isDiscoverable() and CourseRepository.findAllDiscoverableWithInstructor():
-- status = PUBLISHED and visibility = PUBLIC. Nothing else makes a course eligible, and nothing
-- here repairs, infers or defaults a price.
--
-- offer_status is the rule the public course API applies (docs/catalog/offer-audit/README.md,
-- "Pricing semantics"):
--   FREE         access_type = FREE. Decided by the access type alone, never by a price.
--   PRICED       PURCHASE with a price above zero, or SUBSCRIPTION with at least one active plan
--                whose price and duration are both above zero.
--   UNAVAILABLE  anything else. A paid offer whose price cannot be stated is flagged, not shown
--                as free and not shown as 0 EGP.
WITH eligible AS (
    SELECT c.id,
           c.title,
           c.access_type,
           c.status,
           c.visibility,
           c.price AS purchase_price,
           c.description,
           c.last_published_at,
           (SELECT count(*) FROM lessons l WHERE l.course_id = c.id) AS lesson_count
    FROM courses c
    WHERE c.status = 'PUBLISHED'
      AND c.visibility = 'PUBLIC'
),
active_plans AS (
    -- Retired plans are off the offer (SubscriptionPlan.isActive()); they stay in the table only
    -- for the subscriptions already written against them, and are not counted here.
    SELECT p.course_id,
           count(*) AS active_plan_count,
           count(*) FILTER (WHERE p.price > 0 AND p.duration > 0) AS valid_active_plan_count,
           jsonb_agg(jsonb_build_object(
                   'planId', p.id,
                   'name', p.name,
                   'duration', p.duration,
                   'unit', p.unit,
                   'price', p.price,
                   'orderIndex', p.order_index,
                   'valid', p.price > 0 AND p.duration > 0)
               ORDER BY p.order_index, p.id) AS plans
    FROM subscription_plans p
    WHERE p.retired_at IS NULL
      AND p.course_id IN (SELECT id FROM eligible)
    GROUP BY p.course_id
),
assessed AS (
    SELECT e.*,
           COALESCE(a.active_plan_count, 0) AS active_plan_count,
           COALESCE(a.valid_active_plan_count, 0) AS valid_active_plan_count,
           COALESCE(a.plans, '[]'::jsonb) AS plans
    FROM eligible e
    LEFT JOIN active_plans a ON a.course_id = e.id
)
SELECT 'tech-1/v1' AS query_version,
       (now() AT TIME ZONE 'UTC') AS observed_at_utc,
       x.id AS course_id,
       x.title,
       x.status,
       x.visibility,
       x.access_type,
       CASE
           WHEN x.access_type = 'FREE' THEN 'FREE'
           WHEN x.access_type = 'PURCHASE' AND x.purchase_price > 0 THEN 'PRICED'
           WHEN x.access_type = 'SUBSCRIPTION' AND x.valid_active_plan_count > 0 THEN 'PRICED'
           ELSE 'UNAVAILABLE'
       END AS offer_status,
       -- The schema has no currency column: CheckoutProcessor charges and records EGP only.
       CASE WHEN x.access_type = 'FREE' THEN NULL ELSE 'EGP' END AS currency,
       x.purchase_price,
       x.active_plan_count,
       x.valid_active_plan_count,
       x.plans::text AS active_plans,
       x.lesson_count,
       x.last_published_at,
       -- Blocking: the offer cannot be shown with a truthful price until an owner fixes the data.
       array_to_string(array_remove(ARRAY[
           CASE WHEN x.access_type = 'PURCHASE' AND x.purchase_price IS NULL
                THEN 'PURCHASE_PRICE_MISSING' END,
           CASE WHEN x.access_type = 'PURCHASE' AND x.purchase_price <= 0
                THEN 'PURCHASE_PRICE_NOT_POSITIVE' END,
           CASE WHEN x.access_type = 'SUBSCRIPTION' AND x.active_plan_count = 0
                THEN 'SUBSCRIPTION_NO_ACTIVE_PLAN' END,
           CASE WHEN x.access_type = 'SUBSCRIPTION' AND x.active_plan_count > 0
                     AND x.valid_active_plan_count = 0
                THEN 'SUBSCRIPTION_NO_VALID_PLAN' END,
           CASE WHEN x.lesson_count = 0
                THEN 'NO_LESSONS' END
       ], NULL), ', ') AS blocking_findings,
       -- Review: showable, but ambiguous enough that the owner has to confirm what is offered.
       array_to_string(array_remove(ARRAY[
           CASE WHEN x.access_type = 'SUBSCRIPTION' AND x.valid_active_plan_count > 0
                     AND x.active_plan_count > x.valid_active_plan_count
                THEN 'SUBSCRIPTION_HAS_INVALID_PLAN' END,
           CASE WHEN x.access_type = 'SUBSCRIPTION' AND x.valid_active_plan_count > 1
                THEN 'SUBSCRIPTION_MULTIPLE_PLANS' END,
           CASE WHEN x.access_type <> 'PURCHASE' AND x.purchase_price IS NOT NULL
                THEN 'STALE_PURCHASE_PRICE' END,
           CASE WHEN x.access_type <> 'SUBSCRIPTION' AND x.active_plan_count > 0
                THEN 'ACTIVE_PLANS_ON_NON_SUBSCRIPTION' END,
           CASE WHEN x.description IS NULL OR btrim(x.description) = ''
                THEN 'DESCRIPTION_MISSING' END,
           CASE WHEN x.last_published_at IS NULL
                THEN 'PUBLICATION_DATE_MISSING' END
       ], NULL), ', ') AS review_findings
FROM assessed x
ORDER BY x.id;
