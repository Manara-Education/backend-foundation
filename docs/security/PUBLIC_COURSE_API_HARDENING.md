# Public course API — hardening review (TECH-8)

- Task: [TECH-8 — Harden public course API against overexposure](https://app.notion.com/p/3d99027b821c81089c89ff41c489dae6)
- Scope: `GET /api/v1/public/courses` and `GET /api/v1/public/courses/{courseId}` from TECH-7
  (contract: `docs/api/PUBLIC_COURSE_API.md`, from TECH-6).
- Reviewed on 2026-09-14 against the code on branch `fix/tech-8-public-course-api-hardening`,
  which is stacked on TECH-7 and TECH-6.
- Method: the source was read, and every protection was then asserted over HTTP through the real
  security filter chain with no session, against PostgreSQL 17.10 and Redis 7.4 in Testcontainers.
  Each assertion is written to fail if the protection it names is removed.
- **Outcome:** two low-severity findings, both fixed in this change. No path was found that returns
  a private, draft or protected field, and reads are bounded.

## Surface reviewed

| Area | What was checked | Where |
| --- | --- | --- |
| Authorization | Only the two `GET` routes are public. They are named exactly, with no wildcard. Other methods and all learner, checkout and instructor routes still get 401/403. | `PublicCourseSecurityConfig`; `PublicCourseApiTest.Boundaries` |
| Eligibility | Every course query in `PublicCourseRepository` has `PUBLISHED` + `PUBLIC` in its `WHERE` clause, so there is no unfiltered finder to misuse. | `PublicCourseRepository` |
| Serialization | New records, written field by field by `PublicCourseMapper`. No entity and no signed-in DTO is serialized. | `PublicCourseMapperTest`; `PublicCourseApiHardeningTest.Overexposure` |
| Errors | Draft, private and missing ids share one 404 body. Malformed input gets a generic 400 that neither echoes the value nor names an exception. | `PublicCourseApiTest.Eligibility`; `HostileInput`; `errorBodiesSayNothingExtra` |
| Parameters | Paging is bounded, including offset overflow. Unknown sort and filter parameters are ignored. Ids are parsed strictly. | `HostileInput` |
| Counts | `totalItems` comes from the same predicate as the items, so private and draft courses do not move it. | `Counts` |
| Caching | `Cache-Control: no-cache, no-store` is sent on 200, 404 and 400. There is no server-side cache, so withdrawal takes effect on the next request. | `Transitions` |
| Cost | The number of statements per request is measured with Hibernate's statistics. Only to-one fetch joins are used, and plans load in one `IN` query. | `Bounded` |
| Throughput | A per-client rate limit applies to the anonymous routes. | `PublicCatalogueRateLimitRuleTest` |

## Findings

### F1 — Non-canonical ids reached the same course (low, fixed)

`{courseId}` was bound as `Long` through Spring's number conversion, which decodes hexadecimal
and accepts a sign and leading zeros. `/api/v1/public/courses/0x10`, `/+16` and `/016` all served
course 16. Eligibility still applied to every spelling, so nothing hidden was exposed. But one
public resource answered at several addresses, and the parser's behaviour was surprising enough
to mislead a reviewer or a cache key.

**Fix:** the controller accepts only `[1-9][0-9]{0,18}` that also fits in a `long`. Anything else is
`400 error.request.parameterInvalid`, returned before the database is queried and depending only on
how the id is written. Regression: `nonCanonicalIdsAreNotAliases` and `malformedIdsAreRefused`
(`abc`, `1.5`, `1e3`, `0`, `-1`, `+5`, a 20-digit value).

### F2 — The anonymous catalogue had no throttling (low, fixed)

The catalogue is the only application-data read that needs no account, so it is the one a scraper
can loop over without signing up. Pagination bounds the cost of a single request, but nothing
bounded how many requests a client could make.

**Fix:** a `public-catalogue` rule in `RateLimitProperties` allows `GET /api/v1/public/courses/**`
300 requests per minute per client, keeping a local count during a Redis outage (`LOCAL_LIMIT`). The
client is the remote address that Tomcat's RemoteIp valve derives in production
(`server.forward-headers-strategy=native`), so a forged `X-Forwarded-For` header does not reset the
count. A visitor makes one list call per landing page and one call per course opened. Five
requests a second, sustained, is far beyond a person, including a classroom behind one address.
Environments can tune the limit through `app.rate-limit.rules`.

## Checks with no finding

| Check | Result | Test |
| --- | --- | --- |
| A course with a video lesson, a rich-content lesson and a quiz with an answer key, an enrolled learner, an instructor bio, plus a private and a draft course, all tagged with one unique marker | The marker, both emails and the video id appear in no list, detail or 404 body. No property at any depth of the list or detail `data` is named `videoUrl`, `videoProvider`, `richContent`, `lessons`, `modules`, `quiz`, `finalQuiz`, `questions`, `options`, `correctOptionId`, `explanation`, `enrolled`, `enrollment`, `progress`, `access`, `email`, `password`, `bio`, `studentsCount`, `status`, `visibility`, `revision`, `instructorId`, `createdAt`, `updatedAt`, `orderIndex` or `retiredAt`. Property names are checked structurally, so another test's course title can neither trip the check nor hide a real field. | `nothingProtectedIsServed` |
| Draft, private and private-draft ids compared with an unused id | Same 404 body, differing only in the id sent; titles never appear | `ineligibleAndMissingAreIndistinguishable` (TECH-7) |
| Error bodies | Exactly `{status, errors}`: no `code`, no trace, no `Exception`, `java.` or `org.` text, no course title | `errorBodiesSayNothingExtra` |
| Paging limits | `size` from 1 to 50 is allowed. 51, 1000000, −5, 1.5 and non-numeric values are refused. `page` below 0, overflowing `int`, or giving an offset past `Integer.MAX_VALUE` is refused. The largest valid offset returns an empty page. | `pagingBoundaries` |
| Reflected input | `size=<script>…</script>` is refused and not echoed | `rejectedValuesAreNotReflected` |
| Sort and filter injection | `sort=title,asc`, `sort=instructor.user.email,desc`, `status=DRAFT`, `visibility=PRIVATE`, `accessType=FREE` and `instructorId=1` each return the identical body | `unknownParametersChangeNothing` |
| Public → private → public through the editor | Gone from the list and a 404 on the next request, then back | `goingPrivateWithdrawsImmediately` |
| Unpublish, then publish | Gone, then back | `unpublishingWithdrawsImmediately` |
| Plan removed; switched to PURCHASE at 250.00 | Served on the next request; no stale offer | `pricingChangesAreNeverStale` |
| `no-store` on 200, 404 and 400 | Present on every response | `nothingIsCacheable` |
| Private and draft courses added | `totalItems` unchanged; a public course adds exactly 1 | `totalsRevealNothingHidden` |
| List cost with 20 subscription courses | **3 statements** (page, count, plans), the same for a 2-item and a 20-item page | `pageCostIsIndependentOfItsSize` |
| Detail cost | **1 statement** for a purchase course, **2** for a subscription course (course and its plans) | `detailCostIsConstant` |

The page and count queries match the partial index `idx_courses_discoverable`
(`WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC'`, on `id`) from V12. Their fixed
`ORDER BY id DESC` is the index key. Both joins are to-one (instructor and user), so PostgreSQL
applies `LIMIT`/`OFFSET` itself. Hibernate never pages in memory.

## Accepted by design, not defects

- **Course ids are sequential and therefore guessable.** Guessing only reaches courses that are
  already public; every other id gets the same 404. Predictable public ids are not a defect.
- **The instructor's display name is public.** It is the name signed-in learners already see on the
  card. No id, email, bio or specialization is returned.
- **An external `https` cover image** makes the visitor's browser contact that host. The frontend's
  Content-Security-Policy `img-src` governs which hosts can load. Only `https` URLs without
  credentials, and Manara upload paths, are passed on at all.
- **No HTTP caching.** Every visit reads the database, which keeps withdrawal immediate. Pagination
  and the rate limit bound the cost, and any future shared cache must preserve the `no-store`
  guarantee (see the contract).

## Verification

- `./mvnw -B -ntp test -Dtest=PublicCourseApiHardeningTest,PublicCatalogueRateLimitRuleTest,PublicCourseApiTest`
  → 41 run, 0 failures. Before the F1 fix, `malformedIdsAreRefused[0x10]` failed with a 200
  serving course 16, which is how F1 was found.
- The first CI run of this branch failed `nothingProtectedIsServed`. It searched the response text
  for words such as "quiz", and the shared test database had put another test class's course,
  whose title contains that word, on the same page. The check was testing titles, not fields. It
  now collects property names from the JSON tree. Negative control: adding `accessType`, a key the
  response does contain inside `offer`, to the forbidden list makes the test fail, which proves
  nested keys are inspected.
- `./mvnw -B -ntp verify` results are in the pull request for the exact revision. The PR's CI run is
  authoritative.

## Gate

This review gates production promotion of the public course API. TECH-6, TECH-7 and TECH-8 are
meant to be released together: promote the API only with this change in the same release, and never
TECH-7 alone.
