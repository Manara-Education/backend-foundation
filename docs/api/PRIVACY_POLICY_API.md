# Privacy Policy API

The backend is the canonical source of the privacy policy: its text, its version and its
publication metadata. The frontend's `/privacy` page renders what this endpoint returns and holds
no copy of the policy.

## Endpoint

| Route | Who | Behaviour |
| --- | --- | --- |
| `GET /api/v1/privacy-policy/current` | Anyone, signed in or not (`PrivacyPolicySecurityConfig`) | `200` with the published version in force. `404 PRIVACY_POLICY_NOT_PUBLISHED` while none is published. Never returns a candidate, and never falls back to an older version. |

Nothing else under `/api/v1/privacy-policy` is public. Writes are refused (`403` without a CSRF
token). There are no write operations, because versions are published by deploying.

### `200` response

```json
{
  "status": "success",
  "data": {
    "policyId": "privacy-policy",
    "language": "ar",
    "version": "1.0",
    "effectiveDate": "2026-10-05",
    "title": "سياسة الخصوصية لمنصة منارة",
    "summary": "توضح هذه السياسة …",
    "operator": { "name": "Manara EDU", "owner": "Hamed Mohamed", "privacyEmail": "privecy@manara-edu.com" },
    "sections": [
      {
        "id": "retention",
        "number": "8",
        "title": "مدة الاحتفاظ بالبيانات",
        "blocks": [
          { "type": "paragraph", "text": "…" },
          { "type": "list", "items": ["…", "…"] },
          { "type": "email", "label": "…", "address": "privecy@manara-edu.com" }
        ]
      }
    ]
  }
}
```

The `effectiveDate` above is illustrative: 1.0 has none until it is published. `supersedes`
appears once a version replaces an earlier one. Fields without a value are omitted.

- **Plain text only.** No field carries HTML or Markdown. The client renders every string as text.
- **Stable section ids** (`retention`, `contact`, …) are the anchors behind `/privacy#retention`.
- **`version` is opaque.** Display it; do not parse or compare it.

### `404` response

```json
{ "status": "error", "errors": ["The privacy policy has not been published yet."], "code": "PRIVACY_POLICY_NOT_PUBLISHED" }
```

## Storage and versioning

| Piece | Where |
| --- | --- |
| Text of each version | `src/main/resources/legal/privacy/privacy-policy-<version>.<lang>.json` |
| Status, effective date, hash pin, `supersedes`, current pointer | `PrivacyPolicyCatalog` |
| Loading and checks at startup | `PrivacyPolicyRegistry`, `PrivacyPolicyContentValidator` |

This follows the Terms convention: a version is a value published by deploying, not a database
row. No migration is involved. The texts live in git and in the image, so every release can say
exactly which text it served.

At startup every version is loaded and checked, and the application **refuses to start** if:

- a file does not match its catalogue entry (id, language), or has an unknown field;
- a section id is malformed or duplicated, a block is empty or of an unknown type, or an `email`
  block differs from `operator.privacyEmail`;
- any text contains drafting residue (`قيد التحديد`, `مسودة`, `TODO`, `{{`, …);
- a **published** version lacks an effective date or a pinned SHA-256, or its file no longer
  matches the pin. **A published text cannot be edited in place**;
- a candidate claims an effective date, the current pointer names an unknown version, or
  `supersedes` points nowhere.

### Publishing a version

1. Clear the publication blockers below.
2. In one PR, change the version's entry in `PrivacyPolicyCatalog`: set `status` to `PUBLISHED`,
   set `effectiveDate` to the day the release goes live, and set `contentSha256`. The startup
   failure message prints the hash, or run `shasum -a 256 <file>`.
3. Release the backend that day (tag push). Then release the frontend whose
   `legalLinks.privacyPolicy` is `approved`, so the footer and contact form link to it.
4. Verify from outside: `curl -s https://manara-edu.com/api/v1/privacy-policy/current` shows the
   version and date, and `/privacy` renders them.

### Changing a published policy

Add a new file and a new entry (`"1.1"`, `supersedes: "1.0"`), move `CURRENT_ID` to it, and publish
it the same way. Superseded entries stay, so "which policy applied on date X" always has an answer.

## Version 1.0: held as a candidate

1.0 is written and reconciled with the 2026-10-02 evidence. It ships as `CANDIDATE`, so the
endpoint answers `404` until these blockers are cleared. Each one would make a sentence in the
text untrue on the day it went live:

| # | Blocker | Why it blocks | What clears it |
| --- | --- | --- | --- |
| 1 | `manara-edu.com` has **no MX record** (re-checked 2026-10-02 against 1.1.1.1 and 8.8.8.8; port 25 on the A record is closed) | Sections 10–11 tell people to write to `privecy@manara-edu.com`, and that mail cannot be delivered | The owner sets up a mailbox for the domain, adds the MX records, and sends a test message that arrives |
| 2 | Backups: 7-day retention not yet live | Section 9 says every backup is deleted after 7 days. Production runs the old 7 daily / 4 weekly / 6 monthly scheme. Off-server (restic) and Hetzner snapshot state are unknown | Deploy manara-infrastructure's 7-day `backup.sh` to the host. The operator confirms `RESTIC_REPOSITORY` is unset, **or** a daily `--prune-offsite` plus a ≤1-day lifecycle runs. Hetzner Cloud backups (7 slots) are fine; any manual snapshots holding user data are deleted |
| 3 | No operational account-deletion procedure | Section 9 promises to act on a deletion request and to re-apply deletions after a restore. No deletion code or runbook exists, and `course_purchases` rows block a plain delete of a student | The owner decides what is kept (for example purchase records) and an operator procedure exists, including a log of deletion requests to re-apply after a restore. If the owner prefers, the text is reworded first, which is a candidate edit and is allowed |
| 4 | OTP 7-day purge and Caddy access-log removal not deployed | Sections 4, 8 state both | Release this backend (`OtpRetentionPurger`) and the frontend image whose Caddyfile has no `log` block. Both ship with the publication releases themselves |

Blocker 4 is satisfied by the publication release itself. Blockers 1–3 need the owner or an
operator.

## Related retention behaviour in this repository

- `OtpRetentionPurger` deletes `otps` rows older than `otp.record-retention-days` (7) every hour
  (`otp.record-retention-purge-cron`). Code validity (`otp.expiration-minutes`, 10) is separate
  and unchanged.
