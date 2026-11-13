# Contact form API contract

Anonymous submission of the public "تواصل معنا" (Contact us) form. It is what the frontend
`/contact` page posts to, and it exists so a visitor with no account can reach a person.

- Design source: `Contact.dc.html` (Claude Design canvas, "Form and contact fields pending" round).
- DTO: `contact/dto/ContactRequest`.
- Delivery: `contact/service/ContactService`, `contact/email/ContactEmailFactory`.

## `POST /api/v1/contact`

Public — no session, no CSRF exemption (the standard cookie-based CSRF token is still required,
the same as every other mutating request). Rate limited to 5 requests per 15 minutes per client
(`RateLimitProperties`, rule `contact`), the same budget as `/api/v1/auth/register` and
`/api/v1/auth/forgot-password` — the other endpoints that cause outbound email.

### Request

```json
{
  "name": "سارة أحمد",
  "email": "sara@example.com",
  "topic": "الدورات",
  "message": "أريد معرفة موعد بدء الدورة القادمة."
}
```

| Field | Required | Rule |
| --- | --- | --- |
| `name` | yes | Non-blank, ≤120 characters |
| `email` | yes | Non-blank, valid email, canonicalized the same way `RegisterRequest.email` is |
| `topic` | yes | Must be one of the five values `contact/dto/ContactTopic.DISPLAY_VALUES` lists — the same fixed options the frontend `<select>` offers. Anything else is refused: the value is echoed into the notification email's subject line, so it is validated, not merely trusted. |
| `message` | yes | Non-blank, ≤4000 characters |

### Response

Success — `200`, once the email has actually been handed to the provider, not merely accepted for
later delivery:

```json
{ "status": "success", "data": { "message": "وصلتنا رسالتك." } }
```

Failure:

| Condition | Status | Notes |
| --- | --- | --- |
| Validation failed (missing/invalid field, message/name too long, unrecognised `topic`) | `400` | Standard envelope, localized message |
| Email provider unreachable or rejected the message | `503` | `EmailDeliveryException` → `GlobalExceptionHandler`, the same mapping every other outbound-email endpoint uses. The message is generic; provider detail is only in the server log. |
| `app.contact.recipient-email` unset | `503` (`error.contact.notConfigured`) | Configuration gap, not a caller error |

There is no "queued" state. A `200` means the message was actually sent; anything else means it
was not, and the frontend's failure state — which keeps the typed text in the form — is what a
visitor sees.

## What happens to the message

One email, synchronously, to `app.contact.recipient-email` (defaults to the same mailbox already
configured as `email.reply-to`; see `application.properties`). The visitor's own address is set as
Reply-To, so answering it reaches them directly. Nothing is persisted — there is no contact-message
table. If that changes (a ticket queue, an admin inbox view), it is a new, deliberate piece of work,
not an implicit consequence of this endpoint.

## Content safety

- `topic` is validated against a fixed allowlist before it can reach the subject line.
- `name` and `message` are visitor-supplied free text rendered into an HTML email; both pass
  through `EmailTemplateRenderer`, which HTML-escapes every substitution.
- The Resend provider is called through its structured API (JSON fields), not raw SMTP command
  construction, so there is no mail-header-injection surface from any of these fields.
