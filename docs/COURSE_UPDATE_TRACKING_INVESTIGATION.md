# Course Update Tracking — Investigation

Phase 1 deliverable. No production code was changed to produce this document.

Scope: how Manara currently answers "has this course changed?", why that answer is the wrong
one for an enrolled student, and what has to exist before it can be the right one.

Repositories read at:

| Repository            | Branch                                        | Head      |
|-----------------------|-----------------------------------------------|-----------|
| `backend-foundation`  | `feat/course-publication-content-versioning`   | `d20cd0e` |
| `frontend-foundation` | `feat/course-update-badge-module-reorder`      | `7677fe8` |

Both branches are open pull requests (backend #45, frontend #70) and neither is merged to
`develop`. `develop` contains none of the content-versioning work described below. The new
`feature/course-update-tracking` branches are therefore cut from those two branches rather than
from `develop`, and stack on top of the open PRs — see [Base branch](#base-branch).

---

## 1. Existing behaviour

### 1.1 There is already a content version, and it is a good one

`Course` carries two semantic timestamps, added by the open backend PR:

| Column               | Meaning                                                        | Moved by |
|----------------------|----------------------------------------------------------------|----------|
| `last_published_at`  | When the course last became publicly visible                    | `Course#markPublished` only |
| `content_updated_at` | When an instructor last changed something a learner can see     | `Course#markContentChanged` only |

and derives the current badge from them:

```java
// Course.java
public boolean hasUpdatesSincePublish() {
    return status == CourseStatus.PUBLISHED
            && lastPublishedAt != null
            && contentUpdatedAt != null
            && contentUpdatedAt.isAfter(lastPublishedAt);
}
```

This is already the right *kind* of design. In particular the codebase has already worked out —
and documented in `V5__course_content_versioning_and_module_order.sql` and in
`CourseContentChanges` — that generic `updated_at` is untrustworthy:

* `CheckoutProcessor#ensureEnrolled` increments `courses.students_count` on every purchase,
* `VideoMetadataService` rewrites `courses.duration` from a background thread after a video
  lookup lands.

Both bump Hibernate's `@PreUpdate` stamp. A badge driven by `updated_at` would light up because
somebody else bought the course. **This trap is already avoided.** Requirement "do not use generic
`updatedAt`" is, at course level, already satisfied.

### 1.2 Change detection is precise, and funnels through one place

`CourseContentChanges` is a per-request recorder. Every authoring path compares before it assigns:

```java
changes.set(lesson.getTitle(), request.getTitle().trim(), lesson::setTitle);
```

so re-submitting an unchanged form writes nothing and announces nothing. `QuizService#sync`
returns a `QuizSyncResult` carrying the same signal for the whole quiz aggregate — questions,
options, ordering. The timestamp is written once, at the end, inside the caller's transaction:

```java
// CourseService#markContentChangedIfNeeded
if (!changes.hasChanges()) return;
course.markContentChanged(at);
```

Both authoring surfaces feed it: the aggregate `PUT /courses/{id}` via
`CourseService#updateCourse` → `CourseContentSynchronizer`, and the standalone lesson endpoints
via `LessonService#addLesson / updateLesson / deleteLesson`. There is no third way to change a
course, so there is no path that can change content without recording it.

### 1.3 What the badge actually means today

**Publish-relative, not enrollment-relative.**

`hasUpdatesSincePublish` answers *"has the instructor edited this course since they last published
it?"* — a question about the instructor's workflow. It is:

* the same value for every viewer of a course,
* cleared for everyone at once when the instructor re-publishes,
* true for a student who enrolled five minutes ago if the instructor edited six minutes ago,
* false for a student who enrolled a year ago if the instructor published after their last edit.

The requested rule is a different question entirely:

```
studentHasCourseUpdate = course.contentUpdatedAt > enrollment.enrolledAt
```

which is per-enrollment and can differ between two students of the same course. **No per-student
comparison exists anywhere in the codebase today.** `DashboardMapper` reads
`course.hasUpdatesSincePublish()` straight off the course and never looks at the enrollment it was
handed:

```java
// DashboardMapper#toCourseViewResponse — has the Enrollment, ignores its enrolledAt
.hasUpdatesSincePublish(course.hasUpdatesSincePublish())
```

This is root cause #1.

---

## 2. Root causes

### RC-1 — The badge is publish-relative, not enrollment-relative

As above. The comparison is `contentUpdatedAt > lastPublishedAt`, not
`contentUpdatedAt > enrolledAt`. Two students of one course always see the same badge, which is
exactly what Requirement 1 forbids.

`Enrollment.enrolledAt` is present, `nullable = false`, `updatable = false`, and set in
`@PrePersist`. It is a reliable immutable enrollment instant. Nothing in the codebase writes to
it after creation — verified by grep: the only assignment is in `Enrollment#onCreate`.

### RC-2 — Price changes are recorded as content changes

`CourseService#updateCourse`:

```java
changes.set(course.getAccessType(), settings.accessType(), course::setAccessType);
changes.recordIf(!sameAmount(course.getPurchasePrice(), settings.purchasePrice()));
course.setPurchasePrice(settings.purchasePrice());
```

and `CourseContentSynchronizer#syncSubscriptionPlans`:

```java
changes.recordIf(!sameAmount(plan.getPrice(), planRequest.getPrice()));
```

An instructor who raises the price from 500 to 700 EGP moves `content_updated_at`, and every
enrolled student is told the course was updated. Nothing educational changed. This directly
violates Requirements 2 and 10. Renaming a plan, changing its duration, adding or removing a plan
and flipping `accessType` between FREE / PURCHASE / SUBSCRIPTION all do the same thing.

### RC-3 — There are no item-level timestamps

`CourseModule`, `Lesson` and `Quiz` each have only:

* `createdAt` — `nullable = false, updatable = false`, `@PrePersist`. Trustworthy.
* `updatedAt` — Hibernate `@PreUpdate`. **Not** trustworthy for lessons: `LessonService` sets
  `lesson.setDuration(0)` when a video URL changes, and `VideoMetadataService#refreshAsync`
  writes the real duration back from a background thread afterwards. That background write moves
  `lessons.updated_at` without an instructor doing anything.

So there is nothing to hang Requirements 4, 11, 12 and 16 on. A lesson cannot say when it last
changed in a way a student should care about.

### RC-4 — There is no record of *what* changed

The system knows *that* a course changed. It does not know which lesson, or in what way. There is
no revision table, no audit table, no domain event log, and no outbox — grepped for
`Revision`, `Audit`, `EventListener`, `ApplicationEvent`, `Envers`: nothing. Requirements 3 and 5
have no existing infrastructure to reuse.

Crucially, timestamps alone **cannot** satisfy two of the requirements:

* Requirement 5's *"Lesson moved from Module 1 to Module 2"* needs the previous parent, which is
  gone once the write commits.
* Requirement 20's `REMOVED` needs to describe an entity whose row no longer exists — there is
  nothing left to put a timestamp on.

### RC-5 — Removed content is hard-deleted, learner history included

`CourseContentSynchronizer#removeStaleLessons`:

```java
quizService.deleteByOwners(QuizOwnerType.LESSON, staleIds);
completedLessonRepository.deleteByLessonIdIn(staleIds);
lessonRepository.deleteAll(stale);
```

and the same shape in `LessonService#deleteLesson`. Dropping a lesson from the payload deletes
every student's completion row for it.

Quiz attempts go the same way, one level deeper: `QuizAttempt`'s Javadoc records that *"the foreign
key to `quizzes` cascades on delete"*, so removing a lesson removes its quiz, and the database
removes every attempt at that quiz — scores, answers, pass/fail history. Removing a module removes
its exam and every attempt at it; removing a course, the final exam and its attempts.

So an instructor tidying up a published course silently destroys assessment history. It also
distorts anything computed from it after the fact: `CourseProgressionCalculator` recomputes
percentage from the *current* lesson set, so the number moves rather than breaks, but a certificate
or report issued against the old set can no longer be reconstructed.

### RC-6 — One-off purchases leave no financial record

`CheckoutProcessor#grantPurchase` charges `course.getPurchasePrice()`, receives a
`PaymentReceipt`, hands it to the HTTP response — and persists nothing:

```java
PaymentReceipt receipt = paymentGateway.charge(
        new PaymentCharge(price, course.getTitle(), idempotencyKey(course, student, "purchase")),
        paymentMethodOf(request));
upsertPerpetual(course, student, existing, EntitlementSource.PURCHASE, now);
return receipt;   // never stored
```

Grep confirms `PaymentReceipt` is referenced only by the gateway, the mapper that builds the HTTP
response, and `CheckoutProcessor`. There is no `purchases` / `orders` / `payments` table in
`V1__baseline_schema.sql` or any later migration.

Consequence: after an instructor reprices a course, **what a one-off purchaser actually paid is
unrecoverable.** The only remaining number is the current `courses.price`, which is precisely the
"do not dynamically derive an existing student's paid amount from the current course price" trap
Requirement 2 names.

Subscriptions do not have this problem — `CourseSubscription.pricePaid` is a proper immutable
snapshot, and its Javadoc already explains why.

---

## 3. Existing timestamps, and what moves them

| Entity              | Column               | Trustworthy for students? | Moved by |
|---------------------|----------------------|---------------------------|----------|
| `Course`            | `created_at`         | yes (immutable)           | `@PrePersist` |
| `Course`            | `updated_at`         | **no**                    | `@PreUpdate` — incl. `students_count` on purchase, `duration` from the video thread |
| `Course`            | `last_published_at`  | yes                       | `markPublished` only |
| `Course`            | `content_updated_at` | yes                       | `markContentChanged` only |
| `CourseModule`      | `created_at`         | yes (immutable)           | `@PrePersist` |
| `CourseModule`      | `updated_at`         | partly                    | `@PreUpdate`; no known background writer, but no guarantee either |
| `Lesson`            | `created_at`         | yes (immutable)           | `@PrePersist` |
| `Lesson`            | `updated_at`         | **no**                    | `@PreUpdate` — `VideoMetadataService` writes `duration` asynchronously |
| `Quiz`              | `created_at`         | yes (immutable)           | `@PrePersist` |
| `Quiz`              | `updated_at`         | partly                    | `@PreUpdate` |
| `Enrollment`        | `enrolled_at`        | yes (immutable)           | `@PrePersist`, `updatable = false` |
| `CourseEntitlement` | `starts_at`          | n/a — access window, not membership | checkout / renewal |
| `CourseSubscription`| `created_at`         | yes                       | `@PrePersist` |
| `CompletedLesson`   | `completed_at`       | yes (immutable)           | `@PrePersist` |

`@DynamicUpdate` is already on `Course`, `CourseModule` and `Lesson`, so an UPDATE names only the
columns that actually changed. That is what makes a per-column semantic timestamp meaningful
rather than something every unrelated write drags along.

---

## 4. Enrollment model

Three separate concerns, already cleanly separated — this is a strength to build on, not something
to change:

```
Enrollment          — that the learner joined.  Immutable enrolledAt. Survives everything.
CourseEntitlement   — whether they may open it right now. One row, moved forward on renewal.
CourseSubscription  — what they bought, one immutable row per term, with pricePaid.
```

`Enrollment` is created once by `CheckoutProcessor#ensureEnrolled` and is explicitly *not*
recreated on renewal, so `enrolledAt` is the learner's first-join instant and stays that way.
That is exactly the semantics the update rule needs.

**Re-enrollment does not exist**: the unique constraint `uk_enrollments_course_student` plus the
`existsByCourseIdAndStudentId` guard make a second enrollment row impossible. A lapsed-then-renewed
learner keeps their original `enrolledAt`, so they will see everything added during their lapse as
new. That is a product question, recorded in [Open questions](#8-open-questions).

---

## 5. Pricing behaviour

| Path              | Charged from                        | Snapshotted? |
|-------------------|-------------------------------------|--------------|
| `FREE`            | nothing                             | n/a |
| `PURCHASE`        | `courses.price` (`purchasePrice`)   | **no — RC-6** |
| `SUBSCRIPTION`    | `subscription_plans.price`          | yes, `course_subscriptions.price_paid` |

What is already correct, and must stay correct:

* Nothing in the request decides what is charged — `CheckoutProcessor`'s Javadoc says so and the
  code holds to it.
* A price change does not touch `CourseEntitlement`, so **access is already unaffected**: an
  entitlement row is never re-read against the course price. Verified by reading
  `EntitlementPolicy` and `CourseEntitlement#isActiveAt` — neither mentions price.
* A price change does not touch `Enrollment` or `CompletedLesson`.
* No re-charge, no unsubscribe, no repurchase prompt exists anywhere on the price-change path.

So Requirement 2's *access* half already holds. Its *audit* half does not (RC-6), and its
*"no content Updated badge"* half does not (RC-2).

---

## 6. Content-update propagation

`CourseContentSynchronizer` diffs nested children **by id**, which is the property Requirements 8
and 9 depend on:

* a child carrying an id is updated in place,
* a child without one is created,
* a persisted child the payload no longer names is deleted.

Ordering was recently separated from the aggregate save entirely. `SiblingOrdering` decides only
where *new or re-parented* children land; existing siblings keep their stored positions, and
reordering has three focused commands (`reorderModules`, `reorderLessons`, `reorderModuleLessons`)
that write `order_index` and nothing else, under `SELECT … FOR UPDATE`.

**Update propagation is not module-specific.** The claim that "current update logic only works for
modules" does not hold on this branch: `syncLessons` runs for both `FLAT` and `MODULES` courses,
quizzes are synced for `LESSON`, `MODULE` and `COURSE` owners alike, and `LessonService` records
changes for standalone lesson edits. What is missing is not coverage — it is *granularity*
(RC-3, RC-4).

---

## 7. Affected APIs

| Endpoint | Handler | Today | Needs |
|---|---|---|---|
| `GET /api/dashboard/student/courses` | `DashboardService#getStudentCourses` | `hasUpdatesSincePublish` per card | per-enrollment answer |
| `GET /api/courses/{id}/details` | `CourseService#getCourseDetails` | `course.hasUpdatesSincePublish` only | course-level per-enrollment flag + per-item change state |
| `PUT /api/instructor/courses/{id}` | `CourseService#updateCourse` | records price as content | must not |
| `PUT …/modules/order`, `…/lessons/order`, `…/modules/{id}/lessons/order` | `CourseService#reorder*` | course-level signal only | item-level signal |
| `POST/PUT/DELETE /api/instructor/courses/{id}/lessons/**` | `LessonService` | course-level signal only | item-level signal |
| `POST /api/courses/{id}/checkout` | `CheckoutProcessor` | no purchase record | immutable purchase snapshot |

Security is already correct on every learner path: the student is resolved from the authenticated
`User` via `StudentRepository#findByUserId`, never from a request parameter. `CourseService`,
`DashboardService`, `EnrolledCourseViewResolver` and `DiscoverCourseViewResolver` all do this.
No `studentId` or `enrollmentId` is accepted from a client anywhere. Nothing to fix; something to
preserve.

### Performance baseline

`DashboardService#getStudentCourses` is already N+1: `findByStudentId` returns enrollments with a
`LAZY` course, then the loop issues two count queries per enrollment *and* triggers a lazy load of
each `Course`. Adding per-item change data to this endpoint would make it far worse, which is why
the design below keeps My Courses to a **field comparison on data already loaded** and puts all
item-level detail behind Course Details.

`CourseAggregateLoader` loads a whole course breadth-first in a fixed number of queries — modules,
lessons, the three quiz owner scopes, plans. Item-level change state costs nothing extra there
because the entities are already in hand; only the change-narrative rows are an added query, and
it is one per request.

---

## 8. Progress implications

Progress is keyed by **immutable entity id**, never by position:

```java
// CompletedLesson
@UniqueConstraint(columnNames = {"student_id", "lesson_id"})
```

`CourseProgressionCalculator` counts completed lessons against the course's lesson set. Quiz
attempts (`QuizAttempt`) reference `quiz_id`. Nothing reads `order_index` to identify a student's
progress.

Therefore:

* **Reordering cannot corrupt progress** — `reorderModules` / `reorderLessons` /
  `reorderModuleLessons` write only `order_index`, and `@DynamicUpdate` guarantees they write
  nothing else.
* **Re-parenting cannot corrupt progress** — moving a lesson between modules writes `module_id`;
  `completed_lessons.lesson_id` is untouched.
* **Editing content cannot corrupt progress** — title/description/video writes do not touch
  completion rows.

The one path that *does* destroy progress is deliberate deletion (RC-5), which is a different
thing from an update and is treated as such below.

---

## 9. Architectural risks

| # | Risk | Assessment |
|---|---|---|
| R1 | Backfilling `content_updated_at` lights up badges for every existing student | Real, and worse than it was for the publish-relative rule. V5 back-filled `content_updated_at = COALESCE(updated_at, created_at)` and made it *equal* to `last_published_at`, so `contentUpdatedAt > lastPublishedAt` came out false. Compared against `enrolledAt` instead, that same value has no such protection: a course whose `updated_at` moved because somebody bought it would show as updated to everyone who enrolled earlier. **The new migration must re-derive this column.** |
| R2 | An event log grows without bound | One row per changed entity per authoring request. Bounded by instructor activity, not by student traffic. Read only by Course Details, filtered by `occurred_at > enrolledAt` and indexed on `(course_id, occurred_at)`. Retention is a future concern, not a launch one — noted in Remaining Risks. |
| R3 | Two mechanisms (timestamps + events) drift apart | Mitigated by making timestamps *authoritative for state* and events *descriptive only*. The API's `changeState` is always derived from timestamps; `changeSummary` is a label attached to it. They cannot contradict because only one of them decides anything. |
| R4 | Concurrent instructor edits | Already handled: `@DynamicUpdate` on all three entities, `SELECT … FOR UPDATE` on reorder scopes, one transaction per authoring request, one clock read per request. New writes go inside those same boundaries. |
| R5 | Same-second timestamps | `LocalDateTime` is microsecond-precision on Postgres (`timestamp(6)`). The rule is strict `isAfter`, so an item changed in the same microsecond as an enrollment reads as *not* updated. That is the safe direction — a student who enrolled at the exact instant of a change enrolled into the changed version. |
| R6 | Local vs zoned time | The whole schema is `timestamp without time zone` and the whole codebase is `LocalDateTime` from an injected `Clock`. Comparisons are like-for-like. Introducing `Instant` for one feature would be the actual risk. |
| R7 | Stacking on unmerged PRs | Accepted deliberately — see below. |

### Base branch

`develop` has none of `content_updated_at`, `CourseContentChanges`, the reorder commands, V5/V6,
or `CourseUpdatedBadge`. Branching from `develop` would mean reimplementing all of it, producing a
competing `V5__` migration and a guaranteed conflict the moment #45 or #70 merges. The new
branches therefore stack on the open PRs. If #45/#70 change during review, this branch rebases.

---

## 10. Recommended solution

Four layers, smallest first.

### 10.1 Make the course-level rule enrollment-relative

No new storage. `contentUpdatedAt` already exists and is already trustworthy:

```
hasUpdatesSinceEnrollment = course.contentUpdatedAt > enrollment.enrolledAt
```

Computed in one place (`StudentContentUpdates`), used by both My Courses and Course Details.
`hasUpdatesSincePublish` stays for the instructor-facing meaning it already has.

### 10.2 Take pricing out of the content signal

Delete the three `recordIf(price…)` / `set(accessType…)` calls and treat the whole
`subscription_plans` collection as commerce rather than curriculum. Make the separation
structural, not a convention, so it cannot regress: pricing fields are applied through a path that
has no access to the change recorder.

Add `CoursePurchase` — the one-off twin of `CourseSubscription`, written in the checkout
transaction, carrying `pricePaid`, `currency`, `paymentReference`, `purchasedAt`. This is the
missing audit record from RC-6, and it is what makes "existing enrollments are unaffected by later
price changes" *provable* rather than merely true.

### 10.3 Item-level semantic timestamps

Add `content_updated_at` to `course_modules`, `lessons` and `quizzes`, moved only by the authoring
paths, alongside the `created_at` each already has. Per-item state is then pure arithmetic:

```
createdAt        > enrolledAt  → NEW
contentUpdatedAt > enrolledAt  → UPDATED     (NEW wins; they are mutually exclusive by
                                              construction, since contentUpdatedAt starts
                                              equal to createdAt)
otherwise                      → UNCHANGED
```

Backfill `content_updated_at = created_at`, which is provably a no-op for every existing student:
an item created before a student enrolled gets `contentUpdatedAt <= enrolledAt`, so it reads
`UNCHANGED`; an item created after would read `NEW` on `created_at` alone regardless.

To move these, `CourseContentChanges` grows a target: `changes.set(lesson, current, next, setter)`
records both "the course changed" and "this lesson changed", and the single stamping pass at the
end of the transaction writes one instant to every touched entity plus the course.

### 10.4 A minimal change log, for narrative only

`course_changes(id, course_id, entity_type, entity_id, entity_title, change_type, occurred_at,
detail)` — written in the same transaction, read only by Course Details.

It exists for the two things timestamps provably cannot express: `REMOVED` (no row left to stamp)
and `MOVED` (the old parent is gone after the write). It supplies `changeSummary` text and the
list of removed items; it never decides `changeState`.

### 10.5 API and frontend

Course Details gains `hasUpdatesSinceEnrollment`, `latestContentUpdateAt`, a per-node
`changeState` + `changeSummary`, and a course-level list of removed items. My Courses gains
`hasUpdatesSinceEnrollment` beside the existing field. The frontend renders the backend's answer
and computes nothing — the existing `CourseUpdatedBadge` is reused and given a `NEW` variant.

### 10.6 Deliberately not doing

* **No acknowledgement / `lastSeenUpdateAt`.** Requirement 19 says not to introduce it unless
  existing product behaviour requires it. It does not: today only an instructor re-publishing
  clears the badge. `enrolledAt` is never written after creation and will not start being.
* **No soft deletion.** RC-5 is real but reworking every course read path is a separate change.
  This work records `REMOVED` so students are told, and the risk is carried forward explicitly.
* **No `Instant` migration.** See R6.

---

## 11. Open questions

1. **Lapsed-then-renewed learners.** `enrolledAt` is the first-join instant, so a learner who
   lapsed for six months and renewed sees six months of content as new. Correct by the stated
   rule; possibly not what the product wants.
2. **Reordering as a student-visible change.** Requirement 8 asks whether a student needs to be
   told. Moving a lesson between modules clearly yes; nudging two lessons within one module is
   arguably noise. Current decision: both are recorded, and the frontend suppresses pure
   within-scope reordering at the item level while still counting it at course level.
3. **Change-log retention.** No policy proposed. Rows are small and bounded by instructor
   activity.
