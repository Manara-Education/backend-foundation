# Release evidence

What every production deployment leaves behind, where it is kept, for how long,
and how to check it. This file is identical in `backend-foundation` and
`frontend-foundation`.

Every deployment attempt, successful or not, ends with the `Record the release
evidence` job in `deploy-production.yml`. That job writes one machine-readable
manifest and attaches it to the GitHub Release of the tag being deployed,
together with the security evidence the manifest cites.
`.github/scripts/release-evidence.py` builds and verifies it, and is identical in
both repositories.

---

## 1. What is recorded

One file per attempt, `release-evidence-<service>-<tag>-deploy<run>.<attempt>.json`:

| Section | What it says | Where it comes from |
|---|---|---|
| `release` | tag, exact commit, the GitHub Release | the tag, resolved to a commit |
| `artifact` | image, **digest**, `image@digest` | GHCR; the version tag and `:sha-<commit>` must resolve to the same digest, and the image's revision label must be the commit |
| `security.gate` | the `Security Gate` check run on that commit: id, conclusion, time, URL | GitHub Checks |
| `security.assessment_run` | the Security workflow run that produced it | GitHub Actions |
| `security.verdict` | status, counts, coverage, policy/exceptions/evaluator revisions, intelligence retrieval times | the run's `verdict.json` for that exact commit |
| `security.findings_at_release` | what was open on the released branch, with owner, deadline and SLA state | the carried findings register |
| `security.exceptions` | the `exceptions.yml` in force at that commit, its sha256, and which exceptions the verdict applied | the repository at that commit |
| `security.pre_deploy_image_scan` | Trivy's HIGH/CRITICAL scan of the **published** image by digest | the deploy job |
| `security.artifacts` | every artifact of the assessment run, with id, digest and expiry | GitHub Actions |
| `build_checks` | the required CI checks on that commit | GitHub Checks |
| `deployment` | status, time, workflow run and attempt, and the post-deployment checks | the deploy job |
| `runtime_observation` | what the host **reported** after deploying (§2) | `manara-infrastructure` `scripts/manifest.sh --json` |
| `evidence` | the bundle's name, sha256 and URL, and the retention of each part | this job |
| `problems`, `limits` | anything that could not be established, and what the evidence does not claim | this job |

The **security evidence bundle**, `security-evidence-<service>-<tag>-run<id>.tar.gz`,
holds the assessment run's `security-gate-verdict` (verdict, report and findings
register), every raw scanner report, the SBOM and the intelligence manifest. Its
sha256 is recorded in the manifest.

## 2. Build-time evidence versus what is running

These are kept apart and never inferred from each other:

- **Build-time** evidence says what was assessed, built and approved. It
  describes a commit and an artifact.
- **Runtime** observation is the host's own report after the deployment: for
  **both** services, the image, the digests it resolves to and the revision
  label; the manara-infrastructure revision the host runs; the commerce mode
  `.env` asks for, the one the backend actually received, and whether compose
  passes it; `.env`'s permissions and whether git tracks it; and, for each
  backend secret, only whether it is set. No secret value leaves the host.

`runtime_observation.consistency` records whether the host runs the approved
digest and commit. `runtime_observation.counterpart` records what the *other*
service was running at that moment. The two repositories release independently,
so a backend manifest and a frontend manifest together identify the production
combination, and each names the infrastructure revision beneath it.

## 3. What stops a deployment, and what only records

These fail the deployment before production is touched:

1. no fresh, passing `Security Gate` on the exact commit (a stale pass is re-assessed);
2. promotion validation: the tag names a commit on `main`, the GitHub Release exists, and CI passed on that commit;
3. the version tag and `:sha-<commit>` resolve to different digests, or the revision label disagrees;
4. HIGH/CRITICAL findings in the published image, scanned by digest;
5. **backend only:** the host is not ready. It must report its configuration,
   `.env` must ask for `FREE_ONLY` or `LIVE`, and compose must pass the mode to the
   backend. Production never grants simulated paid access, so `DEMONSTRATION`, or
   no mode at all, is refused.

These run after deploying and fail the run visibly:

6. the site root must return 200 and `/api/v1/auth/csrf` must return 204;
7. the host must run the approved digest and commit, `.env` must grant nothing to
   other users and be untracked, and for the backend the effective commerce mode
   must be allowed and every secret injected.

A post-deployment failure does **not** roll back automatically. That is
`scripts/deploy.sh`'s contract, because a rollback has database implications.
It leaves a red run, and the evidence records the failed attempt and what the
host was running afterwards. Rolling back is `./scripts/rollback.sh <service>`
on the host, as an operator's decision.

## 4. Retention

| What | Where | Kept |
|---|---|---|
| Release manifest and security evidence bundle | assets of the tag's GitHub Release | until that release is deleted; nothing expires them |
| `release-evidence` and `deployment-observation` artifacts | the deploy run | 90 days |
| `security-gate-verdict` (verdict, report, findings register) | the Security run | 90 days |
| raw `report-*` scanner reports | the Security run | 30 days; the copy in the bundle outlives them |
| SBOM | the Security run | 30–90 days; the copy in the bundle outlives it |
| workflow logs | GitHub | the repository's log-retention setting |

Both repositories are private, so release assets are readable by repository
members only. Deleting a GitHub Release deletes its evidence. Nothing technical
prevents a repository administrator from doing that, so do not delete releases.

## 5. Checking a release

```bash
tag=v2.1.0; repo=Manara-Education/backend-foundation
gh release download "$tag" -R "$repo" -p 'release-evidence-*' -D evidence
echo "$(gh auth token)" | docker login ghcr.io -u "$(gh api user --jq .login)" --password-stdin
python3 .github/scripts/release-evidence.py verify --manifest evidence/release-evidence-*.json
```

`verify` re-resolves every reference instead of trusting the file:

- the gate check run exists, passed, and is on the released commit;
- the assessment run is on that commit;
- the verdict is PASS for that commit;
- every required build check passed on it;
- the manifest and bundle are attached to the release, and the bundle's sha256
  matches;
- the image digest resolves and is labelled with the commit;
- for a successful deployment, the host ran that digest and commit.

The deploy workflow runs the same `verify` right after attaching the evidence.

## 6. What this does not establish

- A passing gate means the required assessments completed against fresh
  intelligence and matched nothing the policy blocks on. It does not mean the
  release is free of vulnerabilities; see `SECURITY_CI.md` §9.
- The Security Gate scans an image built from the commit in CI. The deploy job
  separately scans the *published* image by digest. Both are recorded; neither
  stands in for the other.
- The runtime observation is a point in time. The host can drift afterwards,
  and the next deployment's manifest is what would show it.
- Releases cut before this existed (backend `v2.0.0` and earlier, frontend
  `v1.2.0` and earlier) have no manifest. They predate the Security Gate itself.
- This is engineering evidence, not a certification or a regulatory approval.
