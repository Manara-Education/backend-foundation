# API contracts — Figma integration programme

Status key: **existing** (observed on `backend-foundation@ac72672`), **agreed** (contract fixed for
implementation; changes need a note here), **proposed** (outline for a later phase; revisit when that
phase starts). The "Implemented in" column is updated with the commit that makes a row true.

## Conventions (existing)

- Base path `/api/v1`. Envelope `ApiResponse<T>`: `{status: "success", data}` or
  `{status: "error", errors: [localised message], code?: ErrorCode}` (`common/dto/ApiResponse`).
- Auth is a server session cookie plus CSRF (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header).
  `authVersion` is compared on every request; bumping it ends other sessions.
- Messages resolve through `MessageService` from `messages.properties` / `messages_ar.properties`.
- Paging: `{items, page, size, totalItems, totalPages}` (`PublicCoursePageResponse`).
- Money is `BigDecimal` serialised as a JSON number with its `currency` (ISO 4217, currently always
  `EGP`, hard-coded in `CheckoutProcessor.CURRENCY`). A `null` amount means unknown, never zero.
- Timestamps are `LocalDateTime` serialised without offset, server time (Africa/Cairo on the host).
  New billing fields keep that convention; date filters are inclusive `from` / exclusive `to`
  calendar days in server time.
- Owner-scoped resources answer **404** for another user's id (never 403), matching existing
  course/lesson behaviour.

## Phase 01–02 — account

| Method & path | Status | Contract | Implemented in |
|---|---|---|---|
| `GET /profile` | existing → extended (02) | `ProfileResponse {fullName, email, role, createdAt}` **+** `avatarUrl: string\|null`, `emailVerified: boolean`, `passwordChangedAt: datetime\|null` (null = unknown, see D5). | |
| `PUT /profile` | existing → changed (02) | Request `{fullName}`: trimmed, `@NotBlank`, `@Size(max=70)`. Response changes from `MessageResponse` to `ProfileResponse`. **Compatibility:** the deployed frontend reads nothing from the body of this call beyond success (verify in phase 02), so returning the profile is additive for it. | |
| `GET /auth/me` | existing → extended (02) | `AuthResponse {fullName, email, role, requiresPasswordReset}` **+** `avatarUrl`. | |
| `POST /auth/change-password` | existing, reused (01) | `{currentPassword, newPassword}` with `@ValidPassword` (15+ chars, upper, digit, symbol; not personal). Success rotates the caller's session and ends others. Phase 02 additionally stamps `password_changed_at` here and in `reset-password`. | |
| `POST /profile/avatar` | agreed (02) | `multipart/form-data`, part `file`. JPEG/PNG/WebP by content sniffing, ≤ 5 MB, ≥ 100×100. Decoded and re-encoded server-side to 512×512 (metadata stripped) through the existing `UploadedImageReencoder`; stored at a **single-segment** `/uploads/{name}` path. Students and instructors, caller only. Rate-limited. Returns `ProfileResponse`. Replacing deletes the previous avatar file. | |
| `DELETE /profile/avatar` | agreed (02) | Caller only; idempotent; returns `ProfileResponse` with `avatarUrl: null`. | |

## Phase 03 — email change

| Method & path | Status | Contract |
|---|---|---|
| `POST /profile/email/change-requests` | agreed | `{currentPassword, newEmail}`. Wrong password → 400 field error (counted against the existing login-style throttle). Same address or invalid → 400. **Taken address answers exactly like success** (no enumeration) but sends nothing that could change the account. Success: `{expiresAt, resendAvailableAt}`; a 6-digit code is hashed and stored as an `OtpType.EMAIL_CHANGE` row (one active per user, V16 invariant) bound to the pending address; mail goes to `newEmail`. A new request supersedes the previous one. |
| `POST /profile/email/change-requests/verify` | agreed | `{code}`. Bounded attempts (existing `OtpAttemptRecorder`), then lockout (429). Success atomically re-checks uniqueness, sets email, `emailVerified=true`, bumps `authVersion`, rotates the caller's session, notifies the **old** address. Returns `ProfileResponse`. |
| `POST /profile/email/change-requests/resend` | agreed | No body. Server-enforced 60 s cooldown (429 with `resendAvailableAt`). |

## Phase 04 — public catalogue

| Method & path | Status | Contract |
|---|---|---|
| `GET /public/courses` | existing → extended | Each summary adds `category: {id, name, color} \| null`. Optional `?category={id}` filter. |
| `GET /public/courses/{id}` | existing → extended | Adds `category`, `outline: [{moduleTitle: string\|null, lessons: [{title, durationSeconds\|null}]}]` from the **published** revision only (no URLs, bodies, quiz data), and `instructor: {name, avatarUrl\|null, headline\|null}`. |
| `GET /course-categories` (public) | proposed | Active categories for the instructor editor and optional filters. Final path chosen in phase 04 against existing routes. |
| Instructor course create/update | existing → extended | `categoryId: number\|null` on the course request. |
| Instructor headline | proposed | Short headline on `instructors` (≤ 120 chars) edited from the instructor profile. Exact route chosen in phase 04. |

## Phase 05 — checkout (existing contract, reused)

`POST /student/courses/{id}/checkout` `{planId?, paymentMethod?: {token?, name?, email?}}` →
`CheckoutResponse {enrollmentId, courseId, accessType, access, paymentReference, simulated}`.
In `FREE_ONLY` a paid course is refused with code `PAYMENTS_UNAVAILABLE` before anything is written.
Idempotency: an already-active entitlement returns the same answer without charging; the gateway key
is `course-{id}:student-{id}:{purpose}`.

## Phase 06 — ledger, history, receipts, quote

| Method & path | Status | Contract |
|---|---|---|
| `POST /student/courses/{id}/checkout/quote` | agreed | `{planId?}` → `{courseId, planId\|null, accessType, subtotal, discount (0), amount, currency, access: {kind: PERPETUAL\|FIXED_TERM, duration?, unit?}, renewalMode: "FIXED"\|null, commerceMode, payable: boolean}`. `payable=false` with a reason code when checkout would be refused (e.g. `PAYMENTS_UNAVAILABLE`). No quote id/expiry yet: checkout re-derives the price. |
| `POST /student/courses/{id}/checkout` | existing → additive | Adds `transactionId`, `transactionStatus`, `amount`, `currency`, `paidAt`, `receiptNumber\|null`. |
| `GET /student/subscriptions` | agreed | Owner-scoped, paginated. Item: `{id, course{id,title,imageUrl,instructorName}, plan{id,name,duration,unit}, pricePaid, currency, startsAt, expiresAt, status: ACTIVE\|EXPIRED, displayStatus: FIXED_ACCESS\|EXPIRED, renewal{mode: "FIXED"}, courseAccess: ACTIVE\|NONE, provenance}`. |
| `GET /student/transactions` | agreed | `?q=&status=&from=&to=&page=&size=` (size default 10, max 50). Item: `{id, reference, purpose: PURCHASE\|SUBSCRIPTION, course{id,title}, amount, currency, status, provenance: LIVE\|SIMULATED\|LEGACY, createdAt, receiptNumber\|null}` plus `summary: [{currency, confirmedPaidTotal, count}]` over LIVE + PAID rows only. |
| `GET /student/transactions/{id}` | agreed | Detail with line items, `courseAccess`, related subscription id, receipt number. |
| `GET /student/receipts/{number}` and `…/{number}.pdf` | agreed | Immutable snapshot, owner-only, issued for PAID transactions; `simulated: true` receipts are labelled non-fiscal. Path name uses "receipts" rather than "invoices" until X6 is resolved. |

Transaction status wire values: `PAID`, `AWAITING_PAYMENT`, `PROCESSING`, `FAILED`, `CANCELLED`,
`REFUNDED`, `PARTIALLY_REFUNDED`. UI mapping: `AWAITING_PAYMENT → awaiting`, `PROCESSING → processing`;
**unknown/processing is never shown as failed.** Payment status, refund status, entitlement
(`courseAccess`) and renewal state are separate fields.

## Phase 07 — capabilities (implemented, `f4c4c1d`; `refundRequests` added in `012b4b9`)

| Method & path | Status | Contract |
|---|---|---|
| `GET /student/billing/capabilities` | implemented | `{commerceMode, provider: null\|name, oneTimeCheckout, simulated, methodTypes: [], savedMethods, recurringCharges, statusRefresh, refunds, refundRequests}`. With no provider every provider-backed flag is `false`. |

## Phase 10 — refund requests (implemented, `012b4b9`; intake off by default)

| Method & path | Status | Contract |
|---|---|---|
| `POST /student/transactions/{reference}/refund-requests` | implemented | `{reason?, note? ≤ 1000}` → 201. No amount accepted. `400 REFUND_REQUESTS_UNAVAILABLE` / `400 REFUND_NOT_ELIGIBLE` / `409 REFUND_REQUEST_OPEN` / 404 for others. |
| `GET /student/transactions/{reference}/refund-requests` | implemented | Owner's requests, newest first; `status` SUBMITTED/APPROVED/REJECTED is review state only. |
| `GET /student/transactions/{reference}` | extended | `refundEligibility`. |
| Staff review queue and decisions | blocked (X4) | Needs a named reviewer authority and surface. |

## Phases 07–10 — still provider-dependent (proposed, outline only)

- `POST /student/payment-methods/setup`, `GET/POST /student/payment-methods`, `PUT /{id}/default`, `DELETE /{id}` (409 when linked to a renewal without replacement). Provider tokens only; never PAN/CVV. (D12)
- Checkout `paymentMethodId`; `POST …/refresh-status`; `POST …/retry` (only after a definitive retryable outcome); authenticated, deduplicated provider webhook.
- `POST /student/subscriptions/{id}/cancel-renewal`, `…/reactivate-renewal`, `PUT …/renewal-method`.
- Provider refund operations and settlement.

These stay unimplemented until X3–X5 are resolved; see `decisions-and-blockers.md`.
