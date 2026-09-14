# Public course API contract

Anonymous, read-only access to Manara's public catalogue: the courses anyone may see, what they
cost, and a page for each. It is what the landing page and the public course-detail page are built
from, and it is what a payment provider reviewing the site sees.

- Task: [TECH-6 — Design public course-summary/detail DTOs](https://app.notion.com/p/3d99027b821c81c28797ca96069d6299).
  The endpoints are implemented by TECH-7 and hardened by TECH-8.
- DTOs: `course/dto/PublicCourseSummaryResponse`, `PublicCourseDetailResponse`,
  `PublicCourseOfferResponse`, `PublicSubscriptionPlanResponse`, `PublicCoursePageResponse`,
  `PublicPricingStatus`.
- The pricing rule: `course/service/PublicOffer`. The TECH-1 offer audit
  (`docs/catalog/offer-audit/`) applies the same rule, so its `offer_status` is what this API
  returns.

## Principles

1. **An allowlist, not a filtered copy.** These are new types, and a field is public only because
   `PublicCourseMapper` writes it. The signed-in shapes (`CourseResponse`, `CourseDetailsResponse`)
   are never reused, so nothing added to them becomes public by accident.
2. **Eligibility is decided by the server.** A course appears only when it is `PUBLISHED` and
   `PUBLIC` (`Course.isDiscoverable()`). This is enforced in the query, before counting and
   pagination.
3. **Free is an access type, not an amount.** A missing or zero price never becomes free. A paid
   course whose price cannot be stated says so explicitly.
4. **Same source as checkout.** Purchase prices and plans come from the same columns and the same
   active-plan rule `CheckoutProcessor` charges by. The plan `id` is the `planId` checkout takes.

## Endpoints

Both endpoints are `GET` only, need no session and no CSRF token, and use the standard envelope
`{"status":"success","data":...}`. Every other method on these paths is refused. Nothing else
becomes public: `/api/v1/student/**`, enrolment and checkout still require authentication.

### `GET /api/v1/public/courses`

One page of eligible courses.

| Query parameter | Default | Rule |
| --- | --- | --- |
| `page` | `0` | Zero-based page number. Must be an integer from 0 upward. |
| `size` | `12` | Page size. Must be an integer from 1 to 50. |

Anything outside those ranges, or not an integer, gets `400` with the envelope's `errors`. No other
parameter has any effect. In particular, there is no client-controlled sort. The order is fixed:
newest course first (`id` descending), which is deterministic across pages.

`data` is a `PublicCoursePageResponse`:

```json
{
  "items": [ /* PublicCourseSummaryResponse … */ ],
  "page": 0,
  "size": 12,
  "totalItems": 3,
  "totalPages": 1
}
```

A page past the end returns `200` with `"items": []` and the real totals.

### `GET /api/v1/public/courses/{courseId}`

One eligible course. `courseId` is the numeric `id` from the list. Courses have no slug column, so
the id is the public identifier.

`data` is a `PublicCourseDetailResponse`.

**Not found.** A draft course, a private course and an id that does not exist all return the same
`404` body:

```json
{ "status": "error", "errors": ["Course not found with id: 991"] }
```

The response never reveals which case it was. A non-numeric or out-of-range id returns `400`
(`error.request.parameterInvalid`), which is the same for every id, eligible or not.

## Fields

Every field is always present. A value that cannot be given is an explicit `null`, never an omitted
key.

### `PublicCourseSummaryResponse`

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | integer | Course id. Also the detail route's path segment. |
| `title` | string | Course title. |
| `subtitle` | string \| null | Short line under the title. |
| `imageUrl` | string \| null | Cover image. Either a Manara upload path (`/uploads/<generated-name>`, same origin) or an absolute `https` URL with a host and no credentials. Any other stored value is dropped to `null`. |
| `instructorName` | string \| null | The teaching instructor's display name, the same one signed-in learners already see on the card. |
| `durationSeconds` | integer \| null | Total video duration in seconds. `null` when it is not yet known. The video lookup fills it in, and `0` meant "not looked up" rather than "empty", so the API sends `null`, never `0`. |
| `lessonCount` | integer | Number of lessons. |
| `offer` | `PublicCourseOfferResponse` | What the course costs. |

### `PublicCourseDetailResponse`

The same fields as the summary, plus:

| Field | Type | Meaning |
| --- | --- | --- |
| `description` | string \| null | The instructor's description of what the course delivers. **Plain text**: render it as text, never as HTML. |

A course's `offer` is identical in the list and in its detail.

### `PublicCourseOfferResponse`

| Field | Type | Meaning |
| --- | --- | --- |
| `accessType` | `"FREE"` \| `"PURCHASE"` \| `"SUBSCRIPTION"` | How the course is sold. |
| `pricingStatus` | `"FREE"` \| `"PRICED"` \| `"UNAVAILABLE"` | Whether a truthful price can be stated. `accessType` is `FREE` exactly when this is `FREE`. |
| `currency` | `"EGP"` \| null | `"EGP"` for every paid course, including unavailable ones. `null` for a free course. |
| `purchasePrice` | number \| null | One-off price. Only for `PURCHASE` + `PRICED`. |
| `plans` | array | Sellable plans. Non-empty only for `SUBSCRIPTION` + `PRICED`, in the instructor's order. |

### `PublicSubscriptionPlanResponse`

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | integer | Plan id. The `planId` checkout accepts. |
| `name` | string | The instructor's name for the plan. |
| `duration` | integer | Number of `unit`s in one term. Always greater than 0. |
| `unit` | `"DAY"` \| `"WEEK"` \| `"MONTH"` | Term unit. |
| `price` | number | Price of one term. Always greater than 0. |

## Money

- **Unit:** Egyptian pounds, not piastres. `450.00` means four hundred and fifty pounds.
- **Precision:** always exactly two decimal places on the wire (`450.00`, `99.50`), matching the
  `numeric(38,2)` columns. A stored amount that would need more places is not a real price, and is
  treated as unavailable rather than rounded.
- **Encoding:** a JSON number, as elsewhere in this API. Clients must check that it is finite and
  greater than 0, and must not derive "free" from it.
- **Currency:** EGP only. The schema has no currency column, and checkout charges and records EGP.

## Pricing semantics

| `accessType` | `pricingStatus` | `currency` | `purchasePrice` | `plans` | When |
| --- | --- | --- | --- | --- | --- |
| `FREE` | `FREE` | `null` | `null` | `[]` | The course is free. Any leftover price or plans are ignored. |
| `PURCHASE` | `PRICED` | `EGP` | > 0 | `[]` | Stored price is above 0. |
| `PURCHASE` | `UNAVAILABLE` | `EGP` | `null` | `[]` | Stored price is missing, 0 or less, or has more than two decimal places. |
| `SUBSCRIPTION` | `PRICED` | `EGP` | `null` | 1 or more | At least one plan is sellable: not retired, with price > 0, duration > 0 and a unit. Plans that are not sellable are left out. |
| `SUBSCRIPTION` | `UNAVAILABLE` | `EGP` | `null` | `[]` | No sellable plan remains. |

Consumer rules:

- Show **free** only when `pricingStatus` is `FREE`.
- For `UNAVAILABLE`, show that the price is unavailable. Never show it as free, and never as
  `0 EGP`.
- Purchase and free access are granted without an end date. Each subscription term lasts
  `duration × unit`. A label such as "one-time purchase" describes the checkout flow; any wider
  promise, such as lifetime access, is a product or legal statement and is not implied by this API.
- With several plans, a "from" price must be the lowest `price` among the returned plans, and must
  be labelled as a starting price.
- Displaying a price does not make the course purchasable. Checkout stays authenticated, prices are
  charged by the server, and the deployment's commerce mode still applies. In `FREE_ONLY` a paid
  checkout is refused with `PAYMENTS_UNAVAILABLE`.

## Examples

A priced one-off purchase (summary):

```json
{
  "id": 42,
  "title": "Algebra for secondary school",
  "subtitle": "From equations to functions",
  "imageUrl": "/uploads/3f2a9c1e-8d7b-4c55-9e1f-0a1b2c3d4e5f.webp",
  "instructorName": "Dr. Amal Hassan",
  "durationSeconds": 5400,
  "lessonCount": 12,
  "offer": {
    "accessType": "PURCHASE",
    "pricingStatus": "PRICED",
    "currency": "EGP",
    "purchasePrice": 450.00,
    "plans": []
  }
}
```

A subscription with two plans (offer only):

```json
{
  "accessType": "SUBSCRIPTION",
  "pricingStatus": "PRICED",
  "currency": "EGP",
  "purchasePrice": null,
  "plans": [
    { "id": 17, "name": "Weekly", "duration": 1, "unit": "WEEK", "price": 40.00 },
    { "id": 18, "name": "Monthly", "duration": 1, "unit": "MONTH", "price": 120.00 }
  ]
}
```

A free course, and a paid course with no stated price. These must render differently:

```json
{ "accessType": "FREE", "pricingStatus": "FREE", "currency": null, "purchasePrice": null, "plans": [] }
```

```json
{ "accessType": "PURCHASE", "pricingStatus": "UNAVAILABLE", "currency": "EGP", "purchasePrice": null, "plans": [] }
```

These examples show the shape only. The titles, names and amounts are illustrative and are not
Manara's offers. The real offers come from the TECH-1 inventory once the owner approves it.

## Never exposed

Lessons, lesson bodies and summaries, video and media URLs, quiz questions and answers, enrolment,
entitlement and progress state, learner counts, publication status and visibility (every course
returned is published and public by construction), revision, timestamps, internal instructor and
user ids, instructor email, bio or any other account data, retired plans, plan `orderIndex`, and
anything about the caller.

## Caching

Responses carry the application's default `Cache-Control: no-cache, no-store`. A course taken off
the catalogue, by making it private or unpublishing it, disappears from both endpoints on the next
request, and no cache holds a stale copy. Any future shared caching must keep that guarantee.
