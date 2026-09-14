# Eligible-offer audit (TECH-1)

Which Manara courses can be shown to an anonymous visitor (and to Kashier), at what price, and
what is wrong with the ones that cannot yet be shown truthfully.

| File | What it is |
| --- | --- |
| [`eligible-offers.sql`](eligible-offers.sql) | One row per eligible course with its commercial fields and findings. |
| [`catalog-exclusions.sql`](catalog-exclusions.sql) | Counts of every status × visibility × access-type combination, so exclusions are recorded without naming an excluded course. |
| [`PRODUCTION_OFFER_INVENTORY.md`](PRODUCTION_OFFER_INVENTORY.md) | The dated, sanitized inventory and its approval record. |
| `src/test/java/.../course/integration/OfferAuditQueryTest.java` | Runs both committed files against PostgreSQL 17.10 over every catalogue state, including legacy rows the current validator refuses. |

Query version: **tech-1/v1**. Change the `query_version` literal in both files whenever their
meaning changes, and re-run the test.

## Eligibility

A course is eligible when `status = 'PUBLISHED'` and `visibility = 'PUBLIC'`, exactly the rule in
`Course.isDiscoverable()` and `CourseRepository.findAllDiscoverableWithInstructor()`. Drafts,
private courses and private drafts are all excluded, and they appear in the exclusion summary only as
counts.

Nothing else affects eligibility. The audit never repairs, infers or defaults a price, and it never
changes publication, visibility, prices or plans to make a row pass.

## Pricing semantics

These are the rules the public course API (TECH-6/TECH-7) uses. The audit applies the same rules,
so a row's `offer_status` is what a visitor will be shown.

| `access_type` | Price source | `offer_status` |
| --- | --- | --- |
| `FREE` | None. The access type alone decides that the course is free. | `FREE` |
| `PURCHASE` | `courses.price`, the same column `CheckoutProcessor.grantPurchase` charges. | `PRICED` when the price is above 0, otherwise `UNAVAILABLE` |
| `SUBSCRIPTION` | Active plans (`subscription_plans.retired_at IS NULL`) with `price > 0` and `duration > 0`, each with its own term (`duration` × `unit` of `DAY`, `WEEK` or `MONTH`). These are the plans `CheckoutProcessor.requirePlanOfCourse` accepts. | `PRICED` when at least one valid active plan exists, otherwise `UNAVAILABLE` |

- **Currency** is EGP. The schema has no currency column; `CheckoutProcessor` charges and records
  EGP only. Free courses carry no currency.
- **Amounts** are Egyptian pounds (not piastres), stored as `numeric(38,2)`, so they have at most two
  decimal places.
- **Zero or null never means free.** A paid course with no usable price is `UNAVAILABLE`. The UI must
  say its price is unavailable, not show it as free or as 0 EGP.
- **Retired plans** are no longer on sale and are never counted. Their rows stay in the table only
  for subscriptions already bought against them.
- **Several valid plans** are all on offer, in the instructor's `order_index` order. The lowest
  price can be described as a "from" price only if it is labelled as such.

## Findings

`blocking_findings` means the row cannot be shown with a truthful price until the owner fixes the
data through the instructor editor. `review_findings` means the row can be shown, but the owner must
confirm what it offers.

| Code | Kind | Meaning |
| --- | --- | --- |
| `PURCHASE_PRICE_MISSING` | blocking | A `PURCHASE` course has no price. |
| `PURCHASE_PRICE_NOT_POSITIVE` | blocking | A `PURCHASE` course's price is 0 or less. |
| `SUBSCRIPTION_NO_ACTIVE_PLAN` | blocking | Every plan of a `SUBSCRIPTION` course is retired, or it never had one. |
| `SUBSCRIPTION_NO_VALID_PLAN` | blocking | The course has active plans, but none has a price and duration above 0. |
| `NO_LESSONS` | blocking | The course is published with no lessons, so it has nothing to deliver. |
| `SUBSCRIPTION_HAS_INVALID_PLAN` | review | At least one active plan is unusable. The public API leaves it out and shows the valid ones. |
| `SUBSCRIPTION_MULTIPLE_PLANS` | review | More than one valid plan is on offer. The owner confirms each one is intended. |
| `STALE_PURCHASE_PRICE` | review | A non-purchase course still stores an old one-off price. It is ignored, but the data is ambiguous. |
| `ACTIVE_PLANS_ON_NON_SUBSCRIPTION` | review | A non-subscription course still has active plans. They are ignored, but the data is ambiguous. |
| `DESCRIPTION_MISSING` | review | Nothing describes what the course delivers. |
| `PUBLICATION_DATE_MISSING` | review | A legacy row published before the baseline column existed. |

The current validator refuses to create most blocking states. The audit still looks for them
because production data predates those rules.

## Running it against production

The production run must be **read-only** and must be done by someone the owner has authorised to
read the production database. Nobody should run it from a laptop against a copied credential.

1. Record the release identity: `scripts/manifest.sh` in `manara-infrastructure` reports the running
   image digests and source SHAs.
2. On the production host, run each file in a session where the server itself refuses writes:

   ```bash
   docker exec -i \
     -e PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=30s' \
     manara_postgres \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 --csv' \
     < docs/catalog/offer-audit/eligible-offers.sql \
     > eligible-offers-$(date -u +%Y%m%dT%H%MZ).csv
   ```

   Repeat with `catalog-exclusions.sql`. `default_transaction_read_only=on` is the guard: if the file
   were ever edited into a write, PostgreSQL would reject it. `OfferAuditQueryTest` checks both that
   the committed files contain no writing statement and that this session setting refuses one.
3. Keep the raw CSVs outside Git, in the owner's restricted evidence store. Never commit them.
4. Copy the eligible rows into `PRODUCTION_OFFER_INVENTORY.md`: course ID, title, access type,
   offer status, price or plans, and findings. Titles of eligible courses are public by definition.
   Nothing about an excluded course goes in except the counts.
5. Record the SHA-256 of each raw CSV, the observation timestamp, the query version, the runner, and
   the release identity from step 1.
6. The owner reviews the list and records their approval, with name and date, in the inventory. A
   blocking finding is fixed in the instructor editor, never by writing to the database, and then the
   audit is run again.

A run against local or fixture data is labelled as such and never counts as production
verification.
