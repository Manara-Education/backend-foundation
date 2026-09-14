-- Manara eligible-offer audit — exclusion summary, query version tech-1/v1 (TECH-1).
--
-- How many courses the eligibility rule leaves out, and why — as counts only. A draft or private
-- course's title, instructor and price are exactly what this audit must not copy anywhere, so
-- nothing about an individual excluded course is selected.
--
-- READ-ONLY. Run it in the same read-only session as eligible-offers.sql.
SELECT 'tech-1/v1' AS query_version,
       (now() AT TIME ZONE 'UTC') AS observed_at_utc,
       c.status,
       c.visibility,
       c.access_type,
       (c.status = 'PUBLISHED' AND c.visibility = 'PUBLIC') AS eligible,
       count(*) AS course_count
FROM courses c
GROUP BY c.status, c.visibility, c.access_type
ORDER BY c.status, c.visibility, c.access_type;
