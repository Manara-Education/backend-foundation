# Security remediation — verification

Remediation of the **Manara Vulnerability Report of 2026-09-09** across
`backend-foundation`, `frontend-foundation` and `manara-infrastructure`.

Every number below is read out of a findings register produced by the Security
Gate itself. Nothing here is asserted from a Dockerfile or from a scanner's
console output.

---

## 1. Outcome

| | Backend | Frontend |
|---|---|---|
| **Baseline blocking** | 5 HIGH | 1 CRITICAL + 40 HIGH |
| **Baseline tracked** | 11 MEDIUM + 18 LOW | 65 MEDIUM + 12 LOW |
| **Baseline total** | **34** | **118** |
| **Post-fix blocking** | **0** | **0** |
| **Post-fix total** | **0** | **2 MEDIUM** |
| Gate verdict | **PASS** | **PASS** |

152 findings at baseline — 46 blocking, 106 tracked. **151 resolved.** One
remains, plus one newly surfaced; both are MEDIUM, neither blocks, and both are
accounted for individually in §5.

### Assessed revisions

| Repository | Baseline | Baseline run | Post-fix run on `develop` |
|---|---|---|---|
| backend-foundation | `d0c1465d` (`develop`) | [34338239340](https://github.com/Manara-Education/backend-foundation/actions/runs/34338239340) | [34447109736](https://github.com/Manara-Education/backend-foundation/actions/runs/34447109736) — PASS |
| frontend-foundation | `abd7f23f` (`develop`) | [34338198206](https://github.com/Manara-Education/frontend-foundation/actions/runs/34338198206) | [34447616538](https://github.com/Manara-Education/frontend-foundation/actions/runs/34447616538) — PASS |
| manara-infrastructure | `ffe5175` (`develop`) | — (no scanned artefact) | merged; no scanner target |

The post-fix column is deliberately a `push` run on `develop` and not the
pull-request head that produced it. A green pull request says the branch would
be clean if merged; only the branch's own run says it is. This repository has
had that difference bite before.

**A merged fix is still not a deployed fix.** `develop` is green in both
repositories, but `main` and the running production images are unchanged: a
Manara release needs a tag push, and none was made.

---

## 2. Backend — 34 → 0

All five blocking findings and all 29 tracked ones were Alpine packages in
`eclipse-temurin:25-jre-alpine`. Nothing in Manara's Java, its Maven
dependencies, its configuration or its full-history secret scan was implicated.

| Package | Installed | Now | Occurrences cleared |
|---|---|---|---|
| `openssl` | 3.5.7-r0 | 3.5.8-r0 | 10 (1 HIGH, 3 MEDIUM, 6 LOW) |
| `libssl3` | 3.5.7-r0 | 3.5.8-r0 | 10 (1 HIGH, 3 MEDIUM, 6 LOW) |
| `libcrypto3` | 3.5.7-r0 | 3.5.8-r0 | 10 (1 HIGH, 3 MEDIUM, 6 LOW) |
| `libexpat` | 2.8.3-r0 | 2.8.4-r0 | 4 (2 HIGH, 2 MEDIUM) |

The three reported CVEs — CVE-2026-14456, CVE-2026-76956, CVE-2026-76957 —
account for the five blocking occurrences; the remaining 29 are further
advisories against the same two package families, which the same upgrade
clears.

**Fix:** `apk upgrade --no-cache` in the runtime stage. Every fixed version is
published in the *same* Alpine 3.24 branch the base already uses, so this is an
in-release upgrade from the supported repository — not a jump to another Alpine
and not a mix of branches. apk resolves the three OpenSSL packages together,
which is what keeps them consistent.

Both stages are now pinned by digest, and a `docker` Dependabot ecosystem was
added because a digest pin discovers nothing on its own — `docker build --pull`
does not help when there is no tag left to re-resolve.

**Preserved:** `<tomcat.version>11.0.25</tomcat.version>` is untouched and still
required. It fixes GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6 and
GHSA-h3x4-894j-xpx5, and nothing here supersedes it.

---

## 3. Frontend — 118 → 2

### 3.1 The compiled Caddy binary (17 findings)

**No newer tag would have fixed this, and both alternatives were checked:**

* `caddy:2-alpine` and `caddy:2.11.4-alpine` resolve to the **same manifest
  digest** `sha256:5f5c8640aae0…`. Bumping the tag changes nothing.
* v2.11.4 is the newest Caddy release (2026-06-03) and its `go.mod` still
  requires **exactly** the vulnerable versions.

Caddy is therefore built from source against a Go 1.26.8 toolchain — which
alone clears the 11 stdlib advisories — with the module floors raised
explicitly.

| Module | Was | Now | Cleared |
|---|---|---|---|
| Go `stdlib` | v1.26.3 | 1.26.8 | 13 (11 HIGH, 2 MEDIUM) |
| `golang.org/x/crypto` | v0.52.0 | v0.56.0 | 4 (1 CRITICAL, 3 MEDIUM) |
| `google.golang.org/grpc` | v1.81.0 | v1.83.2 | 4 (3 HIGH, 1 MEDIUM) |
| `golang.org/x/net` | v0.55.0 | v0.58.0 | 1 HIGH |
| `golang.org/x/text` | v0.37.0 | v0.41.0 | 1 HIGH |

### 3.2 Alpine packages (23 findings)

| Package | Installed | Now | Occurrences cleared |
|---|---|---|---|
| `curl` | 8.19.0-r0 | 8.22.0-r0 | 36 (10 HIGH, 26 MEDIUM) |
| `libcurl` | 8.19.0-r0 | 8.22.0-r0 | 36 (10 HIGH, 26 MEDIUM) |
| `libssl3` | 3.5.7-r0 | 3.5.8-r0 | 10 |
| `libcrypto3` | 3.5.7-r0 | 3.5.8-r0 | 10 |
| `c-ares` | 1.34.6-r0 | 1.34.8-r0 | 1 HIGH |

Same in-release `apk upgrade` as the backend; every fix is published in the
Alpine 3.23 branch the base already uses.

### 3.3 DS-0002 — running as root (1 finding)

The Dockerfile comment claiming the upstream image "already runs its server as
a non-root user" was **false**. `caddy:2-alpine` runs as root, so any
code-execution flaw in the process terminating TLS for the site started with
uid 0. Caddy now runs as `1001:1001`, matching the backend image's `app` user.

---

## 4. Two errors in the original report

Both were found by asking OSV what each *proposed fix version* itself carries,
rather than trusting the report's "fixed version" column.

**grpc.** The report gives CVE-2026-84445 as fixed in **v1.82.2**. That is
correct for the 1.82 branch and does **not** fix 1.83.x. GHSA-2v4p-qf9q-27wj
has three affected ranges — `0 → 1.82.2`, `1.83.0 → 1.83.2`,
`1.84.0-dev → 1.85.0-dev`. A build pinned to v1.83.1 is still affected, which
the Security Gate caught and blocked on. Correct floor: **v1.83.2**.

**x/crypto.** The report gives v0.55.0 as the fix for the CRITICAL
CVE-2026-56854, and it is. But v0.55.0 carries **two further HIGH advisories**
the report never mentions — CVE-2026-78662 and CVE-2026-56855 — both fixed in
v0.56.0. Correct floor: **v0.56.0**.

---

## 5. What remains, individually

**1. GO-2026-5932 — `golang.org/x/crypto`, MEDIUM, tracked, no fix exists.**

Affects every released version of x/crypto and has **no fixed version at all**:
the `x/crypto/openpgp` package is unmaintained and unsafe by design. It is
import-scoped, and Caddy imports no part of openpgp, so it cannot reach this
binary.

It is left **visible on the register, not suppressed with an exception**.
"No fix exists" is a fact about the module, not a reason to stop looking at it.
It does not block under current policy.

*Owner:* frontend runtime. *Next action:* re-evaluate if Caddy ever pulls in
an openpgp-importing dependency; the gate will surface it either way.

**2. GHSA-gcjh-h69q-9w9g / GO-2026-6094 — `cel-go` v0.28.1, MEDIUM, tracked,
no *safe* fix available.**

JSON private fields exposed through NativeTypes and ParseStructTag. The gate
tracks it rather than blocking, so the image is green either way.

**The upgrade was attempted and rejected on evidence.** Pinning v0.30.0 — the
version both databases agree is clean, the GitHub advisory saying v0.29.0 and
the Go database v0.30.0 — builds cel-go fine and then fails to compile Caddy:

```
caddy/v2@v2.11.4/modules/caddyhttp/celmatcher.go:506:5:
    cannot use []interpreter.Interpretable ...
```

cel-go changed that interface between the release Caddy pins and the fixed one.
Clearing this finding would therefore need a patched `celmatcher.go` — a fork of
Caddy's HTTP matcher carried indefinitely, which is a materially worse security
position than one tracked MEDIUM: a forked matcher is exactly where a subtle
routing bug would live, and it would silently miss upstream's own fixes.

It stays on the register, unfixed and unsuppressed.

*Owner:* frontend runtime. *Next action:* resolves itself when Caddy moves to
cel-go ≥ v0.30.0 upstream; the gate will show it.

**Nothing was baselined, ignored without a fix, or cleared by weakening a
rule.** `exceptions.yml` remains empty in both repositories, and no severity
threshold was changed.

---

## 6. Non-root ingress, ports and volumes

Verified by `scripts/verify-container-runtime.sh`, run by the **Verify
container runtime** workflow on every PR and push. It exercises the image under
the *same* `no-new-privileges:true` plus sysctl hardening that
`docker-compose.prod.yml` applies — a run without that would prove the image
works in a configuration production does not use.

Confirmed on the built image:

* the **serving process** runs as uid 1001, read from `/proc/1/status` — not
  from `docker exec id`, which reports the shell you just started;
* it binds **80 and 443** and serves the SPA, deep links, hashed assets with
  the immutable cache header, `index.html` with `no-cache`, and the `/api`
  reverse proxy;
* all five security headers are present, the `Server` header is stripped, and
  the admin API is not served publicly (port 2019 unpublished);
* `/data` and `/config` are writable on a **fresh** volume and a certificate is
  issued;
* an **existing root-owned volume** — the production case — is correctly *not*
  writable before migration, is writable after, and **the pre-existing
  certificate survives intact**;
* the store persists across a restart and the healthcheck runs as the non-root
  user.

### What the negative control changed

The control removed the compose sysctl expecting the bind to fail. **It did
not fail.** Docker already sets `ip_unprivileged_port_start=0` inside
containers by default, so a non-root process gets low ports for free on this
daemon.

So the honest claim is narrower than first written: deleting that line would
very likely leave the site up *today*. What it buys is that the value is
**stated rather than inherited**, so a differently configured daemon, an older
Docker, or a change to that default cannot silently take the ingress down. The
control now proves the narrower claim — set the floor to 1024 and the bind
fails — and the compose comment was corrected to match.

### Rollout order — this matters

1. **manara-infrastructure** — compose sysctl + migration script.
2. Stop the frontend, run `scripts/migrate-caddy-volumes-nonroot.sh`, start it.
3. **Then** promote the new frontend image.

An image running as 1001 against an un-migrated root-owned volume starts and
then cannot renew its certificate — a delayed failure, which is the worse kind.

**Rollback:** re-deploy the previous image and run the migration with
`--to-root`. Certificates survive both directions, which matters because
re-issuing is rate limited to five duplicate certificates per week per
hostname. The sysctl is harmless to a root Caddy and can stay.

---

## 6b. Enforcement status — the gate reports, it does not yet block

Read from the live rulesets, not from intent:

| Repository | Ruleset | Enforcement | Bypass actors | Required checks |
|---|---|---|---|---|
| backend-foundation | `develop_branch_rule` | active | **0** | `Build and test`, `Build the container image` |
| frontend-foundation | `develop_branch_rule` | active | **0** | `Install, type-check and build`, `Build the container image` |

**`Security Gate` is not in either list.** It runs on every pull request and
every push to `develop`/`main`, and it reports pass or fail — but a pull request
can still be merged while it is red.

### Why it has deliberately NOT been added yet

Adding it today would be actively harmful, not merely premature. `develop` is
currently red in both repositories — that is the 46-blocking baseline this whole
report is about — and every pull request is assessed against a tree that
inherits the same container base images. So making the check required while
`develop` is red would block **every** pull request in both repositories,
including the pull requests that fix the problem. The gate would be enforcing a
state nobody could merge their way out of.

### The order that works — steps 1 and 2 are now done

1. ~~Merge the runtime PRs~~ — **done 2026-09-10.** backend-foundation#71
   (06:51 UTC), frontend-foundation#102 (06:57 UTC), manara-infrastructure#11.
2. ~~Confirm the push assessment of `develop` is green in both
   repositories~~ — **done**, read from the merge result rather than from the
   pull-request heads, which is the distinction that matters:

   | Repository | `develop` run | Security Gate |
   |---|---|---|
   | backend-foundation | [34447109736](https://github.com/Manara-Education/backend-foundation/actions/runs/34447109736) | **PASSED** — 0 blocking, 0 tracked, 0 excepted |
   | frontend-foundation | [34447616538](https://github.com/Manara-Education/frontend-foundation/actions/runs/34447616538) | **PASSED** — 0 blocking, 2 tracked, 0 excepted |

   Both were push events on `develop` after the merges, so this is the state of
   the branch itself, not of a pull-request head that had not yet met it.
3. **Still open, and deliberately so.** Add `Security Gate` to the required
   checks of `develop_branch_rule` and `main_release_rule` in both
   repositories.

The blocker for step 3 is gone: the reason it would have been harmful was that
`develop` was red and every pull request would have been unmergeable, including
the fixes. `develop` is green in both repositories now, so making the check
required would block only genuinely-failing work.

It is still a repository-settings change, it is outward-facing, and it is left
for a human to make deliberately. It is not something this work performed on
its own.

`bypass_actors` is already **0** in both rulesets, so once the check is
required there is no admin override — which is why step 2 matters rather than
being a nicety.

### Merge queue

`merge_group` is **not** configured in either repository — neither ruleset
carries a `merge_queue` rule — so the workflows do not subscribe to that event.
If a merge queue is enabled later, `merge_group` must be added to the `on:`
block of `security.yml`, or queued merges would bypass assessment entirely.

---

## 7. Checks not performed

Stated rather than implied.

* **No local `docker build` or `docker run`.** The Docker VM on the authoring
  machine corrupted its ext4 filesystem after an out-of-space crash
  (`EXT4-fs (vda1): failed to convert unwritten extents … I/O error`) and could
  not boot. Repairing it means resetting the disk image, which destroys the
  user's local images *and volumes*. **All container evidence therefore comes
  from CI**, which is the real build path.
* **Certificate renewal against a public ACME service is not exercised.** The
  checks use Caddy's internal CA for `localhost`. Caddy starting is not proof
  that production renewal works.
* **The volume migration has not been run against production volumes** — only
  against a reproduction seeded to match them.
* **No email has actually been delivered.** See §8.
* **Deployed production images were not scanned.** They are unchanged from the
  baseline and still carry every baseline finding until a release is cut.
* Scanner coverage limits are unchanged and documented in `SECURITY_CI.md` §9.

---

## 8. Blocking-report email alerts

Recipient: **`hamedarfat9@gmail.com`**.

| Stage | State |
|---|---|
| Implemented | **yes** — notifier, verdict artifact, 65 fixture checks |
| Activated | **no** — requires the notifier on the default branch (`main`) |
| Delivery verified | **no** — `SECURITY_ALERT_RESEND_API_KEY` does not exist |

Configuration status **by name only**:

| Setting | Kind | Exists today |
|---|---|---|
| `SECURITY_ALERT_EMAIL_TO` | repo variable | ✓ set 2026-09-10 — `hamedarfat9@gmail.com` |
| `SECURITY_ALERT_EMAIL_FROM` | repo variable | ✓ set 2026-09-10 — `no-reply@manara-edu.com` |
| `SECURITY_ALERT_RESEND_API_KEY` | repo secret | ✗ **still missing** |

The backend's `Production` environment holds the application's own
`RESEND_API_KEY`, which is deliberately **not** reused — it is gated behind a
deployment environment, and handing a production credential to a workflow that
processes untrusted pull-request output buys nothing. The alert key should be a
separate send-only Resend key.

### What is proved, and what is not

Proved on 2026-09-10, from CI rather than by assertion:

| | Evidence |
|---|---|
| `workflow_run` fires from the default branch | backend run [34448421662](https://github.com/Manara-Education/backend-foundation/actions/runs/34448421662), triggered by the `develop` Security run completing |
| The trust boundary holds | that run validated the source repository, resolved metadata through the API, and downloaded the verdict artifact by id — it checked out nothing |
| A clean assessment sends nothing | its decision: *"every target passed and the run succeeded; no mail sent"* |
| A blocking assessment renders a real message | dispatch run [34448501847](https://github.com/Manara-Education/backend-foundation/actions/runs/34448501847), `mode=dry-run`: subject `[Manara][TEST][SECURITY BLOCKED]…2 blocking findings`, to `hamedarfat9@gmail.com`, from `no-reply@manara-edu.com`, idempotency key issued |
| A missing key fails loudly | `notify.py --provider resend` with an empty key exits **1** with `::error title=Security notification::the mail credential is not configured…` |

**Not proved: that any real email has ever arrived.** No message has been sent
to `hamedarfat9@gmail.com`. The dry-run provider accepts and delivers nothing,
and the notifier's own wording is deliberate — *"Acceptance is not delivery."*
That stays false until the secret is set and a `mode=test` dispatch is run.

Until then blocking assessments still block exactly as they do now, and the
notifier job goes red with the report attached as an artifact rather than
sending it. The notifier cannot affect the required check in either direction.

---

## 9. Pull requests

All merged on 2026-09-10, in dependency order, each verified against the merge
result rather than the pull-request head.

| PR | Repository | → | Contents | Merged |
|---|---|---|---|---|
| #71 | backend-foundation | `develop` | Alpine upgrade, digest pins, Dependabot docker | 06:51 |
| #102 | frontend-foundation | `develop` | Caddy from source, non-root, runtime verification (28/28) | 06:57 |
| #11 | manara-infrastructure | `develop` | sysctl, volume migration + rollback | ✓ |
| #72 | backend-foundation | `develop` | notifier, verdict, fixed-version fixes | 07:03 |
| #103 | frontend-foundation | `develop` | same, frontend | 07:10 |
| #73 | backend-foundation | `main` | activation (see below) | 06:49 |
| #104 | frontend-foundation | `main` | activation (see below) | 06:38 |

The order was not cosmetic. #72 and #103 both had a **failing** `Security Gate`
while they sat on a `develop` that still carried the vulnerable base images —
the gate was correctly blocking them on findings their own branch did not
introduce. Merging #71 and #102 first, then updating the branches, turned both
green without touching policy.

**#73 and #104 activated nothing.** They put the notifier on `main`, and the
default branch of both repositories is `develop` — `main` is the release
branch. `workflow_run` never dispatched from it, and the workflow did not even
appear in `gh api …/actions/workflows`. Merging #72 and #103 to `develop` is
what registered it and made it fire. Those two pull requests are left in place
anyway, so a future change of default branch does not silently switch the
alerts back off.

No production deployment was performed: a Manara release needs a tag push, and
none was made.
