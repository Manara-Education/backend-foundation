# Production eligible-offer inventory

> **Status: NOT VERIFIED. The production run and owner approval are pending.**
>
> The audit query has not been run against production. Nobody in this workstream holds read-only
> production database access, and no owner-provided, dated production export has been supplied.
> Until the sections below are filled from a real production run and approved, **no downstream task
> may claim the production catalogue is verified**, and no offer list or price may be presented to
> Kashier based on this file.

- Task: [TECH-1 — Audit production catalog for eligible Kashier offers](https://app.notion.com/p/3d99027b821c8191b005e2671ae4b275)
- Query: [`eligible-offers.sql`](eligible-offers.sql) and [`catalog-exclusions.sql`](catalog-exclusions.sql), version `tech-1/v1`
- Method and pricing semantics: [`README.md`](README.md)

## What is needed to complete this

The smallest input that unblocks this task is **either**:

1. an owner-authorised person with read access to the production database runs the two queries
   exactly as `README.md` § *Running it against production* describes, and supplies the CSV output
   with its UTC timestamp and SHA-256; **or**
2. the owner supplies a dated, read-only export of those two queries' output from production.

The owner then approves the resulting list below.

## Production run

| Field | Value |
| --- | --- |
| Source environment | _pending — production (`manara_postgres`)_ |
| Release identity (`scripts/manifest.sh`) | _pending_ |
| Query version | `tech-1/v1` |
| Observed at (UTC) | _pending_ |
| Run by | _pending_ |
| Raw output location (restricted, not in Git) | _pending_ |
| SHA-256 of `eligible-offers` CSV | _pending_ |
| SHA-256 of `catalog-exclusions` CSV | _pending_ |

### Eligible offers

_Pending the production run._ One row per course from `eligible-offers.sql`:

| Course ID | Title | Access type | Offer status | Price (EGP) | Active plans (name · term · EGP) | Blocking findings | Review findings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | |

### Exclusions

_Pending the production run._ Counts only, from `catalog-exclusions.sql`:

| Status | Visibility | Access type | Eligible | Courses |
| --- | --- | --- | --- | --- |
| | | | | |

### Unresolved commercial data

_Pending the production run._ List every row with a blocking finding, and for each, what the owner
must change in the instructor editor. Data is never fixed by writing to the database.

## Owner approval

| Field | Value |
| --- | --- |
| Approved list (course IDs) | _pending_ |
| Approved by (name, role) | _pending_ |
| Approval date | _pending_ |
| Approval evidence (link) | _pending_ |

## Query validation (fixtures, not production)

Before the query touches production, `OfferAuditQueryTest` runs the committed files, not copies of
them, against PostgreSQL 17.10, the version production pins, over synthetic courses only. This
shows the query is correct. **It is not evidence about production's catalogue.**

| Scenario | Expected | Test |
| --- | --- | --- |
| PUBLISHED + PUBLIC, DRAFT + PUBLIC, PUBLISHED + PRIVATE, DRAFT + PRIVATE | Only PUBLISHED + PUBLIC is listed. Every listed row is `Course.isDiscoverable()`. | `onlyDiscoverableCoursesAreIncluded` |
| Report columns | Exactly the commercial columns, with no instructor, learner or account data | `theColumnsAreTheReportAndNothingElse` |
| FREE course | `FREE`, no currency, no price | `freeIsTheAccessTypeNotAPrice` |
| PURCHASE at 450.00 | `PRICED`, EGP 450.00 | `purchaseReportsItsStoredPrice` |
| SUBSCRIPTION with one plan retired | Only the active plan, with its term and price | `subscriptionReportsOnlyActivePlans` |
| Several valid plans | Listed in instructor order, `SUBSCRIPTION_MULTIPLE_PLANS` | `multiplePlansAreOrderedAndFlagged` |
| PURCHASE with null price (legacy) | `UNAVAILABLE`, `PURCHASE_PRICE_MISSING`, never free | `missingPurchasePriceIsFlagged` |
| PURCHASE at 0 (legacy) | `UNAVAILABLE`, `PURCHASE_PRICE_NOT_POSITIVE` | `zeroPurchasePriceIsFlagged` |
| SUBSCRIPTION with every plan retired | `UNAVAILABLE`, `SUBSCRIPTION_NO_ACTIVE_PLAN` | `subscriptionWithoutActivePlansIsFlagged` |
| SUBSCRIPTION whose only plan is priced at 0 | `UNAVAILABLE`, `SUBSCRIPTION_NO_VALID_PLAN` | `subscriptionWithOnlyInvalidPlansIsFlagged` |
| Invalid plan next to a valid one | `PRICED`, `SUBSCRIPTION_HAS_INVALID_PLAN` | `invalidPlanBesideAValidOneIsReviewed` |
| FREE course with a leftover price | Still `FREE`, `STALE_PURCHASE_PRICE` | `stalePriceOnAFreeCourseIsReviewed` |
| No lessons, no description | `NO_LESSONS`, `DESCRIPTION_MISSING` | `incompleteContentIsFlagged` |
| Exclusion summary | Counts per combination, with no excluded course named | `exclusionSummaryIsCountsOnly` |
| Files are read-only | No writing statement in either file | `theFilesOnlyRead` |
| Read-only session guard (negative control) | The runbook's session refuses an `UPDATE` | `theReadOnlySessionRefusesAWrite` |

Result on 2026-09-14: 16/16 passed locally against PostgreSQL 17.10 (Testcontainers). As a negative
control, removing the `visibility = 'PUBLIC'` predicate from `eligible-offers.sql` makes
`onlyDiscoverableCoursesAreIncluded` fail, so that test catches a broken eligibility rule. The pull
request's CI run is the authoritative result for the committed revision.
