# Phase 04 handoff — public catalogue: categories, outline, instructor, plan choice

**State:** implemented and verified; category *data* blocked on an approved taxonomy (X8); export parity not verified (X1).

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p04-public-catalogue` (stacked on P03-BE) | `feat/manara-p04-public-courses-ui` (stacked on P03-FE) |
| Commits | `7a1a5ca`, `04ad8a7` | `8df5f38` |
| Migration | `V19__course_categories_and_instructor_headline.sql` (not seeded) | — |

## Contract
- `GET /public/courses` items + `category {id,name,color}|null` (color ∈ indigo, teal, amber, rose, violet, emerald, sky, slate; retired → null).
- `GET /public/courses/{id}` + `category`, `instructor {name, avatarUrl, headline}`, `outline [{moduleTitle|null, lessons:[{id,title,durationSeconds|null,preview:false}]}]`, read in one projection statement from published public courses only. Detail cost: 2 statements (3 with plans), independent of size. `docs/api/PUBLIC_COURSE_API.md` updated.
- Course create/update: presence-tracked `categoryId` (active only; null clears; omitted keeps; echoing a retired current category is accepted). Instructor editor response carries `categoryId`.
- `GET /instructor/course-categories` (instructor role); `GET/PUT /profile/instructor {headline ≤ 120}` (instructor role, else 404).

## Deliberate contract changes to existing tests
- `PublicCourseApiHardeningTest.nothingProtectedIsServed`: lesson **titles** are now public by design (P2); bodies, video, quiz, learners, bio and hidden courses remain forbidden; outline entries are held to exactly four fields.
- `detailCostIsConstant`: 1→2 / 2→3 statements, plus a module-course case at 2.

## Validation
- Backend `./mvnw -o clean verify`: 1385 tests, 0 failures, 1 skipped (+ `PublicCatalogueDetailsTest` 8 after the retired-category fix).
- Frontend: typecheck ✔; 55 files / 691 tests ✔; build ✔. Negative control: RTL edge detection without `Math.abs` fails the carousel test.
- Live (P04 jar + Vite + headless Chrome): instructor registered, headline and photo set, 3 categories inserted, 7 courses created through the instructor API; landing carousel at 1280/390 and course detail at 1280/390 with zero overflow; real durations from the video lookup; plan radios with a fixed-term total.

## Not delivered / decisions
- No auto-advance on the carousel (optional in the prompt); manual arrows and scrolling only.
- The header's "العودة إلى الصفحة الرئيسية" link is kept beside the breadcrumb, to preserve the recent back-navigation fix.
- The student page's existing plan radios are `display:none` (not keyboard reachable) — pre-existing; queued for phase 05, which owns that component.
