# Phase 02 handoff — profile API, avatar and account data

**State:** implemented and verified (backend + frontend), except export-level visual parity of the photo modal (X1).

| Item | Backend | Frontend |
|---|---|---|
| Branch | `feat/manara-p02-profile-api` | `feat/manara-p02-avatar-ui` (stacked on `feat/manara-p01-settings-shell`) |
| Commit | `10e0007` on `ac72672` | `8422566` on `3a1cd39` |
| Migration | `V17__user_avatar_and_password_changed_at.sql` | — |

## Contract (final)
- `GET /api/v1/profile` → `{fullName, email, role, createdAt, avatarUrl|null, emailVerified, passwordChangedAt|null}`.
- `PUT /api/v1/profile` `{fullName}` → same `ProfileResponse` (was `MessageResponse`). Server strips the name (JSON creator), `@NotBlank`, `@Size(max=70)`; same limit added to registration. Deployed frontend ignores the old body, so this is compatible; the new frontend falls back to `GET /profile` if an older server answers with a message.
- `GET /api/v1/auth/me` adds `avatarUrl`.
- `POST /api/v1/profile/avatar` (multipart `file`, any signed-in role): JPEG/PNG decoded server-side (GIF and undecodable/WebP refused), ≥ 100×100, ≤ 5 MB, centre-square re-encoded to 512×512 with no metadata, single-segment `/uploads/<uuid>.{jpg|png}`, ownership recorded; previous file released after commit (reference count now includes `users.avatar_url`). Rate limit `avatar` 20 / 10 min. Errors: 400 localised (`error.file.*`, new `error.file.imageTooSmall`).
- `DELETE /api/v1/profile/avatar` idempotent → `ProfileResponse`.
- `password_changed_at` written by registration, change-password and reset-password; existing rows NULL (D5).
- Generic `POST /uploads` stays INSTRUCTOR-only (tested).

## Validation
- Backend: `./mvnw -o clean verify` → 1365 tests, 0 failures, 1 skipped. New `ProfileAccountDataTest` (11), updated `AuthServiceTest`, `AuthMapperTest`. A first run caught the strip-in-setter not being used by Jackson; fixed with a `@JsonCreator` constructor.
- Frontend: typecheck ✔; `npm test` 53 files / 673 tests ✔; build ✔. New: crop geometry tests, photo dialog flows, multipart header test (negative control: removing the header fails it).
- Live integration (P02 jar on 8091 + Vite 5180 via proxy + headless Chrome): registration stamps `passwordChangedAt`; avatar upload stored as 512×512; rename trimmed; browser photo upload succeeded after fixing a **415** (the shared client's JSON default) found only in the live run; replaced file released from disk; sidebar and card show the photo; verified badge and "آخر تحديث" render from live data.

## Rollout / rollback
Backend first (V17 is additive). Rollback: redeploy the previous backend image; V17 columns are unused by it. The frontend PR must not deploy before the backend.

## Limitations
- Photo modal visuals follow the report, not the export (X1).
- O1 (student generic upload ends the session) is pre-existing and out of scope.
