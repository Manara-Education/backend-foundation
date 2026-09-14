# Terms and Conditions API: access boundary and open decision (TECH-14)

- Task: [TECH-14 — Validate backend Terms API remains anonymously readable](https://app.notion.com/p/3d99027b821c81b3a008ffc1f9cf17af)
- Verified on 2026-09-14 against `origin/develop` `8b14271`, by
  `TermsVersionEndpointTest`, `StaleAndUnavailableTermsTest`, `RegistrationTermsConsentTest` and
  the new `TermsApiAccessBoundaryTest`.

## What exists

| Route | Who | Behaviour |
| --- | --- | --- |
| `GET /api/v1/terms/current` | Anyone. It is the only public route the terms feature contributes (`TermsSecurityConfig`). | `200 {"status":"success","data":{"version":"1.0","effectiveDate":"2026-09-07"}}`. Deterministic: identical on every read, anonymous or signed in. `503 TERMS_UNAVAILABLE` if the build cannot name a current version; it never falls back to an older one. |
| Registration (`POST /api/v1/auth/register`) | Anonymous | Consent must name the current version, otherwise `409 TERMS_VERSION_OUTDATED`. The acceptance is stored with a server timestamp (`V13__terms_acceptance.sql`). |

The text of each version ships with the frontend, keyed by the version id. Versions are published
by deploying, not by writing rows, so the terms API has no write operations at all.

## Boundary (verified)

| Check | Result |
| --- | --- |
| Anonymous `POST`/`PUT`/`PATCH`/`DELETE` on `/current` with a CSRF token | `401`: authorization refuses, since only `GET` is public |
| The same without a CSRF token | `403`: CSRF refuses first |
| Signed-in `POST`/`PUT`/`PATCH`/`DELETE` on `/current` | `405`, and the current version is unchanged |
| Anonymous `GET` on `/api/v1/terms`, `/api/v1/terms/1.0`, `/api/v1/terms/versions/1.0`, `/api/v1/terms/current/1.0` | `401`. Nothing else under the prefix is open. |
| Public-endpoint contribution | Exactly `GET /api/v1/terms/current`. No wildcard, and no auth relaxation elsewhere. |
| Anonymous body | Only `version` and `effectiveDate`, with nothing about an account or an acceptance |

## Open decision: version-specific reads

TECH-14's acceptance criterion reads "Anonymous **active/versioned** Terms reads work". Only the
**active** (current) read exists. There is **no** version-specific read, such as
`GET /api/v1/terms/versions/{id}`. Signed in, those paths return `404`.
`versionSpecificReadDoesNotExist` asserts this so the gap cannot be silently papered over. `/current`
does not prove historical reads.

The task is therefore **not complete**. One of these needs an explicit decision from the accountable
product or legal owner:

| Option | What it means | Work |
| --- | --- | --- |
| **A. Not required** | Customers read only the version in force. Superseded texts stay in the frontend bundle so existing acceptance records remain interpretable, but they are not served as a public route. | Record the owner's clarification (name, date, link) on TECH-14 and amend its acceptance criterion to "active Terms read". No code. |
| **B. Required** | A customer, support agent or reviewer must be able to fetch the metadata of a specific past version, for example the one they accepted. | Add anonymous `GET /api/v1/terms/versions/{id}` → `200 {version, effectiveDate}` for any version in `TermsVersionRegistry` (the registry's `find(id)` already exists), `404` for anything else. Contribute that exact route and add tests. Rendering a superseded text would also need a frontend route. |

Today only version 1.0 has been published, so no superseded version exists yet, and option A has no
effect on current users. Whichever option is chosen, keep this document and
`TermsApiAccessBoundaryTest` in step with it.
