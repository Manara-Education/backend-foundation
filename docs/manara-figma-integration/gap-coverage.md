# Gap coverage — every report ID

Evidence SHAs: backend `ac72672`, frontend `d4094cf` (both `origin/develop`, 2026-09-26). The
frontend branch the report compared (`fix/public-site-history-and-landing-cleanup`) has an identical
tree to `d4094cf`, so no unmerged frontend work exists there.

"PSP?" = needs a payment provider or product decision (see `decisions-and-blockers.md`).

| ID | Existing evidence | Frontend action | API | Schema | Phase | Acceptance evidence | PSP? |
|---|---|---|---|---|---|---|---|
| A1 Avatar | `POST /uploads` is INSTRUCTOR-only (`UploadSecurityConfig`); `User` has no avatar | Photo editor modal, crop to 512² | new `POST/DELETE /profile/avatar` | `users.avatar_url` | 02 | BE tests (type sniff, size, owner, re-encode), FE editor tests | No |
| A2 Password last changed | No column | "آخر تحديث" row, omitted when null | `passwordChangedAt` on profile | `users.password_changed_at` (NULL for existing rows, D5) | 02 | stamped on change + reset; null legacy | No |
| A3 Verified badge | `User.emailVerified` exists, not exposed | "موثّق" badge only when true | `emailVerified` on profile | none | 02 | DTO test | No |
| A4 Email change OTP | OTP rows per user/type (V16), `OtpService`, Resend email provider | 5-step flow | 3 new endpoints | `OtpType.EMAIL_CHANGE` + pending email storage | 03 | BE lifecycle + enumeration tests; FE flow tests | No |
| A5 In-place password | `POST /auth/change-password` exists; FE routes to `/reset-password` | Password panel with shared policy checklist | reuse | none | 01 | FE state tests, live call | No |
| A6 Name ≤ 70 | `@NotBlank` only | Editor validation | `@Size(max=70)` + trim | none | 01 (FE), 02 (BE) | validation tests both sides | No |
| A7 PUT returns profile | returns `MessageResponse` | Refresh header/sidebar from response | `PUT /profile` → `ProfileResponse` | none | 02 (01 uses `refreshUser`) | contract test | No |
| B1 My subscriptions | `course_subscriptions` rows, no endpoint | Subscriptions tab (read-only) | `GET /student/subscriptions` | none | 06 | owner-scope tests | No |
| B2 Status display | `ACTIVE\|EXPIRED` only | FIXED_ACCESS / EXPIRED only | `displayStatus` | recurring columns later | 06, 09 | no renewal cadence shown | 09: Yes |
| B3 Cancel/reactivate | none | hidden until capability | proposed | agreement state | 09 | — | Yes |
| B4 Change renewal method | none | hidden | proposed | method link | 07, 09 | — | Yes |
| B5 Renewal processing | none | — | job | periods | 09 | — | Yes |
| B6 Saved methods | none; `PaymentMethodRequest.token` placeholder | Methods tab shows "unavailable" honestly | proposed capability + methods | payment_method | 07 | capability=false in tests | Yes |
| B7 Ledger | only `payment_reference` on purchase/subscription rows | Invoices & payments list/detail | `GET /student/transactions[/{id}]` | `payment_transactions` | 06 (+08, 10) | provenance separation tests | No (read-only) |
| B8 Receipts/invoices | none | Receipt drawer + PDF | `GET /student/receipts/{n}[.pdf]` | `payment_receipts` | 06 | non-fiscal marking for simulated | Formal invoice: X6 |
| B9 Refresh/retry | none | hidden | proposed | attempt state | 08 | — | Yes |
| B10 Refund requests | none | hidden | proposed | refund_requests | 10 | — | Yes (X4) |
| B11 Access vs payment | `CourseEntitlement` implicit | separate access pill | `courseAccess` field | none | 06 | field present, separate from status | No |
| C1 Appearance | none | Exact port of `AppearanceSettings.tsx`, no persistence | none | none | 01 | blocked on X1 | No |
| P1 Category chip | no `Course.category` | chip on card + hero | category on public DTOs; editor field | `course_categories`, `courses.category_id` | 04 | public DTO + editor tests | No |
| P2 Public outline | none | "محتوى الدورة" only from real data | `outline` on public detail | none (published revision) | 04 | no private fields leak (test) | No |
| P3 Instructor card | `instructorName` only; `instructors.bio`, `specialization` exist | Instructor card | `instructor {name, avatarUrl, headline}` | `instructors.headline` | 02, 04 | public-safe fields only | No |
| P4 Card discounts | not in Figma | none | none | none | excluded | — | — |
| K1 Saved method at checkout | none | method list hidden | proposed | — | 07, 08 | — | Yes |
| K2 Server summary | price only from course DTO client-side | order summary from quote | `POST …/checkout/quote` | none | 06 (05 shows DTO price) | quote = charged amount test | No |
| K3 Rich success | `paymentReference`, `simulated` | result screen | additive checkout fields | ledger | 05 (reference), 06 | — | No |
| K4 Pending external | none; synchronous | hidden | proposed | — | 08 | — | Yes |
| K5 Uncertain / idempotency | idempotency key per (course, student, purpose); active entitlement short-circuits | "uncertain" state for network loss, no automatic retry | later refresh-status | — | 05, 08 | FE test for lost response | 08: Yes |
| K6 Recurring eligibility | plans fixed-term | no cadence copy | — | — | 09 | — | Yes (X5) |
| K7 Invoice download at checkout | none | receipt link after success | reuse receipts | — | 06 | — | No |
| K8 Refund-guarantee copy | line at `payment-cta-section.tsx:225` | remove | none | none | 05 | copy gone (test) | policy X4 |

Figma-only behaviours with no real counterpart (not built as specified): demo OTP `123456`, mock
timers, seeded cards/invoices, Unsplash images, `PaymentStore` local store, "illustrative" curriculum
placeholder, renewal cadence derived from plan days, "30 minutes" hard-coded wallet expiry.
