# Course Update Tracking — Implementation Report

Phase 5 deliverable. Companion to
[`COURSE_UPDATE_TRACKING_INVESTIGATION.md`](COURSE_UPDATE_TRACKING_INVESTIGATION.md), which
records what the code looked like before any of this.

| Repository            | Branch                            | Base                                        |
|-----------------------|-----------------------------------|---------------------------------------------|
| `backend-foundation`  | `feature/course-update-tracking`  | `feat/course-publication-content-versioning` (PR #45) |
| `frontend-foundation` | `feature/course-update-tracking`  | `feat/course-update-badge-module-reorder` (PR #70)    |

Branched from the open PRs rather than `develop`, which has none of `content_updated_at`,
`CourseContentChanges`, the reorder commands, V5/V6 or `CourseUpdatedBadge`. Branching from
`develop` would have meant reimplementing all of it and shipping a competing `V5__` migration.
These branches stack; if #45/#70 change in review, they rebase.

---

## 1. Root cause

Four findings, one of which was an active defect.

### RC-1 — The badge answered the instructor's question, not the learner's

```java
// Course.java, before
public boolean hasUpdatesSincePublish() {
    return status == PUBLISHED && contentUpdatedAt.isAfter(lastPublishedAt);
}
```

"Have I edited since I published?" — one value for every viewer, cleared for everybody at once
when the author republishes. `DashboardMapper` was handed an `Enrollment` and read straight past
it to the course. There was no per-student comparison anywhere in the codebase.

### RC-2 — Price changes moved the content version (a live defect)

`CourseService#updateCourse` recorded `purchasePrice` and `accessType` alongside the title, and
`CourseContentSynchronizer#syncSubscriptionPlans` recorded every plan edit. An instructor
adjusting next quarter's pricing therefore told every enrolled student their course had changed,
and sent them looking through a curriculum in which nothing had moved.

### RC-3 — No item-level semantics

`CourseModule`, `Lesson` and `Quiz` had only `createdAt` and Hibernate's `updatedAt`. The latter
is untrustworthy for lessons: `VideoMetadataService` writes the resolved duration back from a
background thread, minutes after the instructor closed the form. So there was nothing to hang a
per-lesson badge on, and the curriculum could only ever mark every row or none.

### RC-4 — No record of *what* changed

No revision table, no audit table, no domain events — grepped for `Envers`, `ApplicationEvent`,
`@EventListener`, `Revision`, `AuditingEntityListener`: nothing. And two of the required
behaviours are provably beyond timestamps: a **removed** lesson has no row left to stamp, and a
**moved** lesson's previous parent is gone the moment the write commits.

### RC-5 — One-off purchases left no financial record

`CheckoutProcessor#grantPurchase` charged `courses.price`, handed the gateway receipt to the HTTP
response and persisted nothing. After a reprice, what an existing buyer paid was unrecoverable —
the only surviving number was the new one. Subscriptions never had this problem
(`course_subscriptions.price_paid`).

---

## 2. Architecture

### The rule, in one place

```java
// Enrollment.java — the only copy
public boolean hasCourseUpdates() {
    LocalDateTime contentUpdatedAt = course.getContentUpdatedAt();
    return contentUpdatedAt != null && contentUpdatedAt.isAfter(enrolledAt);
}
```

It lives on `Enrollment` because that is the one object holding both halves of the comparison.
My Courses reads it off the enrollment it already loaded; `CourseUpdateWindow` delegates to it
rather than repeating the line. A second copy is how two screens end up disagreeing.

### Two mechanisms, and it matters which decides what

| | Decides | Cost | Fails how |
|---|---|---|---|
| **Timestamps** (`createdAt`, `contentUpdatedAt`) | `changeState` — NEW / UPDATED / UNCHANGED | zero queries; the columns are already loaded | cannot describe a removal or a move |
| **`course_changes` log** | `changeSummary` and the removed-content list | one indexed range scan per course-details request | a missing row costs a caption, never a badge |

State is arithmetic and needs no log row, which is what makes content predating the log — every
course in the database on the day this ships — read correctly. The log only supplies the
sentence. They cannot contradict each other because only one of them decides anything.

```
if (createdAt        > enrolledAt) NEW
if (contentUpdatedAt > enrolledAt) UPDATED
otherwise                          UNCHANGED
```

The two branches are mutually exclusive **by construction**, not by check order: a created
entity's `contentUpdatedAt` starts equal to its `createdAt` and the request that created it never
moves it (`CourseContentJournal` skips `CREATED` and `REMOVED` when stamping). So anything newer
than the enrollment by one measure is newer by both, and NEW — the more useful answer — wins.

Strictly `isAfter` throughout: a change landing in the same microsecond as an enrollment reads as
not-updated, because somebody who joined at that instant joined the changed version.

### The write path

```
CourseService / LessonService
        │  changes.of(lesson).content(old, new, setter)     ← every change names its subject
        ▼
CourseContentChanges          identity-keyed, strongest description per entity
        │
        ▼
CourseContentJournal.commit(course, changes, at)            ← once, at the end, one instant
        ├── course.markContentChanged(at)
        ├── each touched entity .markContentChanged(at)      (not CREATED, not REMOVED)
        └── one CourseChange row per entity                  (only if ever published)
```

One instant per request, passed in rather than read here, so a course, its lessons and its log
rows all carry the same `content_updated_at` — a learner filtering "everything since I enrolled"
can never see a course marked updated whose changes all sort a microsecond before the badge.

Inside the caller's transaction by construction, so a rolled-back edit cannot leave a course
claiming to be updated and a committed one cannot fail to say so.

### Pricing is structurally out of reach

`applyPricing(course, settings)` takes no `CourseContentChanges`, and `syncSubscriptionPlans` no
longer receives one. None of `BigDecimal`, `CourseAccessType` or `SubscriptionPlan` implements
`TrackedContent`, so pricing cannot reach the recorder even by accident. The fix is not "remember
to leave price out"; it is that the parameter is not there.

---

## 3. Backend changes

### New

| File | Why |
|---|---|
| `course/model/TrackedContent.java` | The four things a curriculum draws a row for, so recording and stamping are written once, not four times |
| `course/model/ContentEntityType.java` | Learner vocabulary. A lesson's quiz is a *quiz*; a module's or course's is an *exam* — one row, two words, split in `Quiz#contentType()` |
| `course/model/ContentChangeType.java` | Ordered weakest→strongest, so "which of these two do I keep" is a comparison, not a table of cases |
| `course/model/CourseChange.java` | Append-only log. No FK to the entity it describes — a `REMOVED` row must outlive it |
| `course/model/CoursePurchase.java` | The one-off twin of `CourseSubscription` (RC-5) |
| `course/service/CourseContentJournal.java` | The single place timestamps and log rows are written |
| `course/service/CourseUpdateResolver.java` | Builds one learner's view; resolves the student from the authenticated `User` |
| `course/service/CourseUpdateWindow.java` | Answers per row from data already loaded |
| `course/service/CourseChangeNarrator.java` | Enum pair → sentence, via `MessageService`, so Arabic and English readers get one decision in their own words |
| `course/dto/ContentChangeState/-Response`, `RemovedContentResponse` | The wire contract |
| `course/repository/CourseChangeRepository`, `CoursePurchaseRepository` | |

### Changed

| File | Change |
|---|---|
| `Course`, `CourseModule`, `Lesson`, `Quiz` | implement `TrackedContent`; the latter three gain `content_updated_at`, defaulted to `createdAt` in `@PrePersist` |
| `Enrollment` | gains `hasCourseUpdates()` — the rule |
| `CourseContentChanges` | rewritten: a scoped API where every change names its subject; the bare boolean is gone |
| `CourseService` | journal instead of inline stamping; `applyPricing` extracted; ordering generic bounded to `TrackedContent`; course details resolves the viewer's window |
| `CourseContentSynchronizer` | per-entity recording; captures the previous parent before a re-parent; plans no longer touch the recorder |
| `LessonService` | same journal as the course editor, so a lesson edited from either surface produces identical timestamps and log rows |
| `QuizService` / `QuizSyncResult` | reports *what* it did (`CREATED`/`CONTENT_UPDATED`/`METADATA_UPDATED`/`REMOVED`) and returns the deleted quiz, which is the only thing left to record a removal against |
| `CheckoutProcessor` | writes `CoursePurchase` in the same transaction as the entitlement it paid for |
| `CourseAggregateMapper`, `LessonMapper`, `QuizMapper` | change-aware overloads; the pre-existing signatures still work and pass `null` |
| `DashboardMapper` / `DashboardService` / `CourseViewResponse` | `hasUpdatesSinceEnrollment`; fetch-join query |
| `EnrollmentRepository` | `findByStudentIdWithCourse` — see Performance |
| `messages.properties`, `messages_ar.properties` | 28 phrases each |

### API

`GET /api/v1/student/courses/{id}?mode=ENROLLED` gains, additively:

```jsonc
{
  "course": {
    "hasUpdatesSincePublish": false,       // kept, instructor-facing meaning
    "hasUpdatesSinceEnrollment": true,     // the learner's answer
    "latestContentUpdateAt": "2026-08-27T21:33:09.756711"
  },
  "modules": [{
    "change": { "state": "UNCHANGED", "summary": null, "at": null },
    "lessons": [{
      "change": { "state": "NEW", "summary": "New lesson added", "at": "..." },
      "quiz": { "change": { "state": "UPDATED", "summary": "Quiz updated", "at": "..." } }
    }]
  }],
  "finalQuiz": { "change": { "state": "NEW", "summary": "New exam added", "at": "..." } },
  "removedContent": [
    { "entityType": "LESSON", "title": "L2", "summary": "Lesson removed", "at": "..." }
  ]
}
```

`GET /api/v1/dashboard/student` gains `hasUpdatesSinceEnrollment` beside the existing field.
Every addition is optional on the wire, so a client written against the previous contract is
unaffected.

### Security

Unchanged, and deliberately so. The viewer is resolved from the authenticated `User` through
`StudentRepository#findByUserId`; there is no parameter through which a student id, enrollment id
or timestamp could be supplied, which is the strongest form the guarantee can take. Instructor
ownership checks are untouched. `StudentCourseUpdateApiTest#theAnswerIsPerViewer` hits one URL
with two sessions and gets two different answers, with no student named in either request.

---

## 4. Database changes

`V7__student_visible_content_tracking.sql`. Every new column is `NOT NULL` **with a default**,
not `NOT NULL` alone, so an instance running the previous build — whose INSERTs do not name these
columns — keeps working through a rolling deploy. `courses.content_updated_at` deliberately stays
nullable because the previous build *does* name it and writes null before stamping it.

### Item versions — a provable no-op

Back-filled to `created_at`. The UPDATED branch requires
`created_at <= enrolled_at AND content_updated_at > enrolled_at`, which with the two equal is
unsatisfiable. No learner sees an item-level badge for anything predating the deploy.

### Course version — re-derived, and this is the load-bearing step

V5 back-filled `courses.content_updated_at = COALESCE(updated_at, created_at)`. That was safe for
V5's own rule (it set `last_published_at` to the same value, and equal is not greater). It is
**not** safe compared against an enrollment: `updated_at` is the column V5 itself documented as
untrustworthy — it moves when somebody buys the course and when a background video lookup lands.
Left alone, a course whose `updated_at` moved because of a July purchase would announce itself to
everyone who enrolled in June.

So it is re-derived from facts that are actually about content:

```sql
content_updated_at = GREATEST(courses.created_at,
                              max(lessons.created_at),
                              max(course_modules.created_at))
```

A learner who enrolled after the last lesson appeared sees nothing. One who enrolled before it
sees "updated" — and the curriculum shows them that lesson marked NEW, which is true and
checkable. That is a correct notification, not a false one.

`last_published_at` is then pulled forward to match where it had fallen behind, so the
instructor's own badge stays dark on deploy day — which is what V5 intended and what is true,
since nobody edited anything during a migration.

`ContentVersionBackfillMigrationTest` seeds exactly the purchase-inflated shape and re-runs V7's
statements over it.

### New tables

`course_changes` — empty after migration, on purpose. There is no history to reconstruct; nothing
recorded what past edits were, and inventing rows would put words in an instructor's mouth. Item
state still reads correctly from the timestamps; what predates the table has no caption, which
the API models explicitly (`summary` is nullable).

`course_purchases` — also not back-filled. Purchases made before this deploy were never recorded
anywhere, and a row invented from today's price would assert something about the past that may
well be false, which is the exact error this table exists to prevent. Those purchases remain
unauditable; that is a fact about the data, not something a migration can repair.

---

## 5. Frontend changes

| File | Change |
|---|---|
| `shared/courses/courses.types.ts` | `ContentChangeState`, `ContentChangeResponse`, `RemovedContentResponse`; `change` on lesson/module/quiz; `hasUpdatesSinceEnrollment` |
| `course-updated-badge.tsx` | gains `NEW` (platform green, `Sparkles`) beside `UPDATED` (amber, `RefreshCw`); `UNCHANGED` renders nothing; server summary becomes the `aria-label` and tooltip |
| `courses.mapper.ts`, `course-details.mapper.ts` | carry the server's verdict through; `toChange` collapses absent/unchanged to one `UNCHANGED` constant |
| `course-card.tsx`, `hero-section.tsx` | read `hasUpdatesSinceEnrollment` |
| `lesson-item.tsx`, `module-group-card.tsx`, `exam-item.tsx` | per-row badges |
| `removed-content-notice.tsx` | new — the one change with no row to land on |

Two judgement calls:

* **A module shows only its own verdict.** A module whose third lesson changed is not itself
  changed; that lesson says so on its own row. Lighting the parent as well is how a curriculum
  ends up with every row badged and none of them meaning anything.
* **One badge per row.** A lesson and its quiz each carry a verdict; `louderChange` keeps the
  stronger and its wording, so a row lit by its quiz reads "تم تحديث الاختبار القصير" rather than
  claiming the video moved.

Nothing on the frontend compares a timestamp, and no enrollment date is shipped to the browser.

**RTL/LTR.** The product has no i18n library and is Arabic-only today, so "works in both" means
the components must not assume a direction: the badge uses logical properties (`paddingInline`),
flex `gap` rather than directional margins, and `flexWrap` so a status pill and a badge stack
rather than overflow on a narrow screen. A test renders the same card under `dir="rtl"` and
`dir="ltr"`. The *wording* is localised server-side from `Accept-Language`, so the day an English
locale is added the sentences arrive already translated.

**Responsive.** Titles keep `overflow: hidden` + ellipsis; badges are `flexShrink: 0` and
`whiteSpace: nowrap` so they cannot be squeezed out of shape; badge rows wrap. Covered by the
long-title test.

---

## 6. Pricing — proof existing enrollments are unaffected

| Claim | Evidence |
|---|---|
| No content badge | `Pricing#raisingThePriceTellsExistingStudentsNothing`; `CourseMutationMatrixTest#aPriceChangePersistsAndStaysSilent` over all 9 pricing mutations asserts `contentUpdatedAt` did not move |
| Enrollment untouched | `andLeavesTheirEnrollmentAndAccessExactlyWhereTheyWere` — same `enrolledAt`, still `enrolled` |
| Access untouched | same test — entitlement still perpetual and active. `EntitlementPolicy` never reads a price |
| Not re-charged / not asked to repurchase | no path from `updateCourse` reaches `PaymentGateway`; `CheckoutProcessor` is the only caller |
| Paid amount not derived from current price | `CoursePurchase` is written at checkout, every column `updatable = false`; `aPurchaseWritesAnImmutableRecordOfWhatWasCharged` |
| No receipt without access, no access without a receipt | `arefusedChargeLeavesNoPurchaseRecordBehind`, `aRepeatOfASucceededPurchaseChargesNothingAndWritesNoSecondReceipt` |
| New price applies to future buyers | live run: course repriced FREE→PURCHASE 700; the later student paid 700 and `course_purchases` recorded `list_price=700, amount_paid=700, currency=EGP` |

Live confirmation, after switching a course to PURCHASE at 700 with a student already enrolled:

```
course updated : False
lessons        : [(الدرس الأول, UNCHANGED), (الدرس الثاني, UNCHANGED)]
enrolledAt     : 2026-07-28 18:31:31.414545   (unchanged)
entitlement    : expires=never                (unchanged)
```

---

## 7. Progress preservation

Progress is keyed by immutable id — `completed_lessons (student_id, lesson_id)` — and nothing in
the authoring path writes it. `@DynamicUpdate` on all three entities means a reorder writes
`order_index` and nothing else.

`ProgressSurvivesContentChangeTest` asserts by **id**, not by count — a count survives a bug that
deletes one row and inserts another:

* reordered within a module → same lesson id completed, now at position 1
* module reordered around it → identical id list
* lesson re-parented to another module → identical id list, `module_id` changed
* full re-save of every lesson → still 4, not 8
* pure reorder → progress percentage unchanged at 50

Live confirmation: a student completed lesson 1, the instructor reversed the module's lessons,
and the completion row still pointed at lesson id 1 — now at position 2.

Quiz and exam attempts are untouched by every path above; they are only removed when their quiz
is deliberately deleted (see Remaining risks).

---

## 8. Test coverage

**Backend — 679 tests, all passing** (`./mvnw test`).

| Suite | Covers |
|---|---|
| `StudentCourseUpdateTest` (28) | the whole matrix: per-enrollment badge, two students one course, pricing isolation, NEW vs UPDATED for lesson/module/quiz/exam, move/reorder/remove, four compound edits, not-enrolled viewers, and edges — same-instant enrollment, rolled-back edit, unpublish/republish, flat course |
| `StudentCourseUpdateApiTest` (5) | the HTTP boundary: nested `change` paths survive serialisation, `Accept-Language` switches the wording, the answer is the authenticated viewer's, removed content serialises, discovery leaks no edit history |
| `ProgressSurvivesContentChangeTest` (5) | above |
| `ContentVersionBackfillMigrationTest` (6) | V7 against pre-existing rows, including the purchase-inflated timestamp |
| `CheckoutProcessorTest` (+3) | the purchase record |
| `CourseMutationMatrixTest` | split into content mutations (announce) and 9 pricing mutations (stay silent) |
| existing suites | unchanged and passing — `CourseUpdateSignalTest`, `CourseAuthoringSafetyTest` (authorization, transactions, concurrency, focused updates), ordering, migrations |

**Frontend — 148 tests, all passing** (`npx vitest run`), typecheck and production build clean.
`course-updated-badge.test.tsx` grew from 7 to 16: course level, lesson level (NEW / UPDATED /
nothing / server wording / quiz fallback / NEW-beats-UPDATED / locked rows), and removed content.

**Manual, against a running application** — real Postgres, real Redis, real HTTP with CSRF and
session cookies, on an isolated port and database: course created, one student enrolled and
back-dated, price raised, lesson edited, lesson added, exam added, second student enrolled after
all of it, lessons reordered, progress checked. Results in §6, §7 and §9.

---

## 9. Edge cases

| Case | Result |
|---|---|
| Enrolled before the change | Updated ✓ |
| Enrolled after the change | Not updated ✓ |
| Two students, one course | different answers ✓ (test + live) |
| Same-instant change and enrollment | not updated — strict `isAfter` ✓ |
| Re-submitting an unchanged course | nothing announced ✓ |
| Reorder to the order already stored | nothing announced ✓ |
| Price-only change | silent; access, enrollment and curriculum untouched ✓ |
| Price then content | first silent, second announced ✓ |
| New lesson / module / quiz / exam | NEW ✓ |
| Edited lesson / module / quiz / exam | UPDATED, siblings UNCHANGED ✓ |
| Edited lesson quiz | quiz UPDATED, lesson UNCHANGED ✓ |
| NEW never also UPDATED | mutually exclusive by construction ✓ |
| Lesson moved between modules | "moved from One to Two" ✓ |
| Lesson reordered within a module | only the lessons that moved ✓ |
| New lesson reordered afterwards | still "New lesson added" — found live, fixed, regression-tested ✓ |
| Removed lesson | listed at course level with its snapshotted title ✓ |
| Removed before enrollment | not shown ✓ |
| Four compound edits | every item's own state correct ✓ |
| Transaction rollback | nothing stamped, nothing logged ✓ |
| Viewing the course repeatedly | badge stays, `enrolledAt` unchanged ✓ |
| Unpublish → republish | instructor badge clears, learner's does not ✓ |
| Draft → published | no log rows before first publication; learners exist only after ✓ |
| Flat course, no modules | handled identically ✓ |
| Course with no content | migration falls back to its own `created_at` ✓ |
| Never-published draft | keeps its null baseline ✓ |
| Non-enrolled visitor | everything UNCHANGED, no timestamp, no removals ✓ |
| Free → paid, paid → free | pricing path; silent ✓ |
| Lifetime purchase / free enrollment | perpetual entitlement, unaffected ✓ |
| Expired subscription | enrollment and `enrolledAt` survive; badge still answers ✓ |
| Timezone | `timestamp without time zone` and `LocalDateTime` from an injected `Clock` throughout — like for like ✓ |
| Concurrent instructor edits | existing `SELECT … FOR UPDATE` + `@DynamicUpdate`; new writes are inside those boundaries ✓ |
| Duplicate requests | journal writes only when something changed ✓ |

Not exercised, and named as such: **refunds** (no refund path exists in the product), **admin
enrollment** (no admin enrollment path exists), and **re-enrollment** (impossible — see below).

---

## 10. Performance

**My Courses** answers the badge from two fields already loaded, via
`Enrollment#hasCourseUpdates()`. No change-log read, no per-card query. `findByStudentIdWithCourse`
fetch-joins course → instructor → user, removing a pre-existing lazy-load per card.

**Course Details** costs **one** additional query: `findSince(courseId, enrolledAt)`, an indexed
range scan over `(course_id, occurred_at)` bounded below by the reader's own enrollment. Per-item
state costs nothing — it is arithmetic on columns `CourseAggregateLoader` already fetched — so a
hundred-lesson course costs the same to annotate as a three-lesson one.

**Writes**: one row per entity actually changed per authoring request, and none at all before a
course's first publication (nobody can have enrolled). Bounded by instructor activity, not by
learner traffic.

---

## 11. Remaining risks

1. **Deleting content still destroys learner history.** `removeStaleLessons` hard-deletes the
   lesson, its `completed_lessons` rows, its quiz, and — by FK cascade — every `quiz_attempt`
   against that quiz. This work makes the removal *visible* rather than silent, which was the
   agreed scope. Soft deletion (`deleted_at` + filtering every read path, retaining attempts)
   would touch the entire course read surface and roughly double this change. **Recommended as
   the next piece of work**, and it matters most for certificates and any reporting issued
   against a curriculum that has since changed.

2. **Lapsed-then-renewed learners.** `enrolledAt` is the first-join instant, so somebody who
   lapsed for six months and renewed sees six months of content as new. Correct by the stated
   rule; possibly not what the product wants. If not, the fix is a separate
   `lastSeenUpdateAt`-style concept — deliberately *not* moving `enrolledAt`, which would rewrite
   when they joined in order to change what they are shown.

3. **Reordering counts as a student-visible change.** Requirement 8 left this open. Current
   behaviour: recorded, and only for the siblings that actually moved. The frontend shows it as a
   normal UPDATED badge. If product judges within-module nudges to be noise, the cheapest change
   is for `CourseUpdateWindow` to treat a latest-change of `REORDERED` as UNCHANGED — one
   condition, no schema change.

4. **Change-log retention.** No policy. Rows are small and bounded by instructor activity, so
   this is not a launch concern, but there is no pruning.

5. **Purchases predating this deploy are unauditable.** Nothing recorded them and nothing can
   reconstruct them. Stated rather than papered over.

6. **`hasUpdatesSincePublish` is still on the learner DTOs**, deprecated, so clients written
   against the previous contract keep working. It should be dropped from the learner responses
   once the frontend is deployed — it is the instructor's answer and has no business on a
   student's card.
