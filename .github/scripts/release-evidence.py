#!/usr/bin/env python3
"""Release evidence for one production deployment: build it, and prove it resolves.

    release-evidence.py collect --out DIR ...   gather the evidence and write
                                                DIR/release-manifest.json plus a bundle
    release-evidence.py verify  --manifest FILE re-resolve every reference in a manifest

Called by deploy-production.yml after every deployment attempt, and identical in
backend-foundation and frontend-foundation. See docs/security/RELEASE_EVIDENCE.md.

Two kinds of evidence, and they are never mixed:

  build-time  what was assessed, built and approved: the Security Gate check run,
              the assessment run and its verdict, the findings register, the
              exceptions in force, the build checks, the image digest.
  runtime     what the production host REPORTED after the deployment, from
              manara-infrastructure's scripts/manifest.sh: the image each service
              is running, the commerce mode it received, and secret presence.

A passing gate says the commit was assessed; it does not say the host runs it.
A running container says what runs; it does not say it was assessed. The
manifest records both, and `verify` checks that they name the same artifact.

Nothing here reads, prints or stores a secret value. The runtime section carries
secret NAMES and whether each is set, as manifest.sh reports them.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
GATE = "Security Gate"
EVIDENCE_PATTERNS = ("security-gate-verdict", "report-*", "sbom-*", "intel-*")


class EvidenceError(Exception):
    """A reference that must resolve did not. The manifest cannot vouch for it."""


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(list(args), capture_output=True, text=True, timeout=600)
    if check and proc.returncode != 0:
        raise EvidenceError(f"{' '.join(args[:3])} ...: {proc.stderr.strip()[:300]}")
    return proc


def gh_api(path: str, *, allow_404: bool = False) -> Any:
    proc = run("gh", "api", "-H", "Accept: application/vnd.github+json", path, check=False)
    if proc.returncode != 0:
        if allow_404 and "404" in proc.stderr:
            return None
        raise EvidenceError(f"GET {path}: {proc.stderr.strip()[:300]}")
    return json.loads(proc.stdout or "null")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return "sha256:" + h.hexdigest()


def latest_check_run(repo: str, sha: str, name: str) -> dict | None:
    """The newest completed check run of that name produced by GitHub Actions itself."""
    doc = gh_api(f"repos/{repo}/commits/{sha}/check-runs?check_name={name.replace(' ', '%20')}&per_page=100")
    runs = [c for c in (doc or {}).get("check_runs", [])
            if c.get("name") == name and (c.get("app") or {}).get("slug") == "github-actions"
            and c.get("status") == "completed"]
    return max(runs, key=lambda c: (c.get("completed_at") or "", c["id"])) if runs else None


def check_summary(c: dict | None, name: str) -> dict:
    if not c:
        return {"name": name, "conclusion": "missing"}
    return {"name": name, "check_run_id": c["id"], "conclusion": c.get("conclusion"),
            "head_sha": c.get("head_sha"), "completed_at": c.get("completed_at"),
            "url": c.get("html_url")}


def load_json(path: str | None) -> Any:
    if not path:
        return None
    p = Path(path)
    if not p.is_file() or p.stat().st_size == 0:
        return None
    try:
        return json.loads(p.read_text())
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# collect
# ---------------------------------------------------------------------------

def find_verdict(root: Path, sha: str) -> tuple[dict | None, Path | None]:
    for p in sorted(root.rglob("verdict.json")):
        try:
            doc = json.loads(p.read_text())
        except (OSError, ValueError):
            continue
        if doc.get("revision") == sha:
            return doc, p
    return None, None


def find_register(root: Path) -> dict | None:
    candidates = sorted(root.rglob("findings.json"),
                        key=lambda p: (p.parent.name != "register", str(p)))
    for p in candidates:
        try:
            doc = json.loads(p.read_text())
        except (OSError, ValueError):
            continue
        if isinstance(doc.get("findings"), list):
            return doc
    return None


def findings_at(register: dict | None, ref: str, verdict: dict | None) -> dict:
    """What the register says was open on the released branch, with owners and deadlines."""
    if register is None:
        return {"status": "unavailable",
                "reason": "the assessment run's artifact carried no findings register"}
    open_here = [e for e in register["findings"] if ref in (e.get("branches") or {})]
    by_state: dict[str, int] = {}
    for e in open_here:
        state = (e.get("sla") or {}).get("state", e.get("status", "unknown"))
        by_state[state] = by_state.get(state, 0) + 1
    return {
        "status": "recorded",
        "ref": ref,
        "open": len(open_here),
        "by_sla_state": by_state,
        "entries": [{k: e.get(k) for k in ("id", "severity", "status", "component", "version",
                                            "owner", "remediation_due", "kev")} | {
                        "sla_state": (e.get("sla") or {}).get("state")} for e in open_here],
        "verdict_counts": (verdict or {}).get("counts"),
    }


def exceptions_at(repo: str, sha: str, verdict: dict | None) -> dict:
    doc = gh_api(f"repos/{repo}/contents/.github/security/exceptions.yml?ref={sha}", allow_404=True)
    if not doc:
        return {"status": "absent", "reason": "the released commit carries no exceptions.yml"}
    import base64
    raw = base64.b64decode(doc.get("content", ""))
    file_sha = "sha256:" + hashlib.sha256(raw).hexdigest()
    entries: Any
    try:
        import yaml  # installed by the workflow step
        parsed = yaml.safe_load(raw) or {}
        entries = [{"id": e.get("id"), "finding": (e.get("scope") or {}).get("finding"),
                    "owner": e.get("owner"), "approval": e.get("approval"),
                    "expires": str(e.get("expires"))} for e in (parsed.get("exceptions") or [])]
    except Exception as exc:  # recorded, not fatal: the gate already judged the file
        entries = f"unparsed: {type(exc).__name__}"
    applied = sorted({(f.get("exception") or {}).get("id") for f in (verdict or {}).get("findings", [])
                      if f.get("status") == "excepted"} - {None})
    return {"status": "recorded", "file_sha256": file_sha,
            "matches_verdict": (verdict or {}).get("exceptions_revision") in (None, file_sha)
            if verdict else None,
            "entries": entries, "applied_to_this_release": applied}


def runtime_section(runtime: dict | None, service: str, image: str, digest: str, sha: str,
                    reason: str) -> dict:
    if not isinstance(runtime, dict) or not isinstance(runtime.get("services"), dict):
        return {"status": "unavailable", "reason": reason or "the host reported nothing"}
    services = runtime["services"]
    mine = services.get(service) or {}
    reference = f"{image}@{digest}"
    running_digests = list(mine.get("repo_digests") or [])
    running_ref = str(mine.get("image") or "")
    counterpart = {name: facts for name, facts in services.items() if name != service}
    return {
        "status": "observed",
        "observed_at": runtime.get("generated"),
        "schema": runtime.get("schema", 1),
        "services": services,
        "config": runtime.get("config"),
        "infrastructure": runtime.get("infrastructure"),
        "counterpart": counterpart,
        "consistency": {
            "deployed_digest_is_running": (reference in running_digests
                                           or running_ref.endswith("@" + digest)),
            "deployed_revision_is_running": mine.get("sha") == sha,
        },
    }


def collect(a: argparse.Namespace) -> int:
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    problems: list[str] = []

    gate = latest_check_run(a.repository, a.sha, GATE)
    if not gate:
        raise EvidenceError(f"no completed '{GATE}' check run exists on {a.sha}; "
                            "an unassessed commit has no security evidence to record")
    if gate.get("conclusion") != "success":
        problems.append(f"the newest '{GATE}' on {a.sha} concluded {gate.get('conclusion')!r}")

    suite = (gate.get("check_suite") or {}).get("id")
    runs = (gh_api(f"repos/{a.repository}/actions/runs?check_suite_id={suite}") or {}).get("workflow_runs", [])
    if not runs:
        raise EvidenceError(f"check suite {suite} of the gate has no workflow run")
    srun = runs[0]
    artifacts = []
    page = 1
    while True:
        doc = gh_api(f"repos/{a.repository}/actions/runs/{srun['id']}/artifacts?per_page=100&page={page}")
        batch = doc.get("artifacts", [])
        artifacts += batch
        if len(batch) < 100:
            break
        page += 1

    raw = out / "security-evidence"
    raw.mkdir(exist_ok=True)
    wanted = [x["name"] for x in artifacts if not x.get("expired")
              and any(Path(x["name"]).match(p) for p in EVIDENCE_PATTERNS)]
    for name in wanted:
        run("gh", "run", "download", str(srun["id"]), "-R", a.repository, "-n", name, "-D", str(raw / name))

    verdict, verdict_path = find_verdict(raw, a.sha)
    if not verdict:
        problems.append(f"no verdict.json for revision {a.sha} in run {srun['id']}")
    elif verdict.get("status") != "PASS":
        problems.append(f"the verdict for {a.sha} is {verdict.get('status')!r}, not PASS")
    register = find_register(raw)
    ref = (verdict or {}).get("ref") or srun.get("head_branch") or ""

    bundle = out / f"security-evidence-{a.service}-{a.tag}-run{srun['id']}.tar.gz"
    with tarfile.open(bundle, "w:gz") as tar:
        tar.add(raw, arcname=bundle.name[:-len(".tar.gz")])
    bundle_sha = sha256_file(bundle)

    release = gh_api(f"repos/{a.repository}/releases/tags/{a.tag}", allow_404=True)
    image_scan = Path(a.image_scan) if a.image_scan else None
    manifest_name = f"release-evidence-{a.service}-{a.tag}-deploy{a.deploy_run_id}.{a.deploy_run_attempt}.json"
    assets_base = f"https://github.com/{a.repository}/releases/download/{a.tag}"

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "kind": "manara.release-evidence",
        "generated_at": now(),
        "repository": a.repository,
        "service": a.service,
        "environment": {"name": a.environment, "url": a.environment_url},
        "release": {
            "tag": a.tag,
            "commit": a.sha,
            "github_release": None if not release else {
                "id": release["id"], "url": release.get("html_url"),
                "published_at": release.get("published_at"), "draft": release.get("draft")},
        },
        "artifact": {
            "image": a.image,
            "digest": a.digest,
            "reference": f"{a.image}@{a.digest}",
            "verified_before_deploy": [
                "the version tag and the sha-<commit> build resolve to this digest",
                "the image's org.opencontainers.image.revision label is the released commit",
            ],
        },
        "security": {
            "gate": check_summary(gate, GATE),
            "assessment_run": {"id": srun["id"], "attempt": srun.get("run_attempt"),
                               "event": srun.get("event"), "head_branch": srun.get("head_branch"),
                               "head_sha": srun.get("head_sha"), "url": srun.get("html_url"),
                               "created_at": srun.get("created_at")},
            "verdict": None if not verdict else {
                k: verdict.get(k) for k in (
                    "status", "exit_code", "generated_at", "ref", "revision", "event", "counts",
                    "policy_revision", "exceptions_revision", "evaluator_revision",
                    "coverage_complete", "coverage_gaps", "image_digests", "run_id", "run_attempt")
            } | {"intelligence": {sid: {"retrieved_at": s.get("retrieved_at"),
                                        "upstream_revision": s.get("upstream_revision")}
                                  for sid, s in ((verdict.get("intelligence") or {}).get("sources") or {}).items()},
                 "path_in_bundle": str(verdict_path.relative_to(raw)) if verdict_path else None},
            "findings_at_release": findings_at(register, ref, verdict),
            "exceptions": exceptions_at(a.repository, a.sha, verdict),
            "pre_deploy_image_scan": {
                "tool": "trivy image", "target": f"{a.image}@{a.digest}",
                "blocks_at": ["HIGH", "CRITICAL"],
                "result": a.image_scan_result,
                "report_sha256": sha256_file(image_scan) if image_scan and image_scan.is_file() else None,
            },
            "artifacts": [{"name": x["name"], "id": x["id"], "size_bytes": x.get("size_in_bytes"),
                           "digest": x.get("digest"), "created_at": x.get("created_at"),
                           "expires_at": x.get("expires_at"), "expired": x.get("expired"),
                           "in_bundle": x["name"] in wanted}
                          for x in artifacts],
        },
        "build_checks": [check_summary(latest_check_run(a.repository, a.sha, n), n)
                         for n in a.required_check],
        "deployment": {
            "status": a.deploy_status,
            "deployed_at": a.deployed_at or None,
            "workflow_run": {"id": int(a.deploy_run_id), "attempt": int(a.deploy_run_attempt),
                             "url": f"https://github.com/{a.repository}/actions/runs/{a.deploy_run_id}"
                                    f"/attempts/{a.deploy_run_attempt}"},
            "post_deploy_verification": [item for f in a.verification
                                         for item in (load_json(f) or [])],
        },
        "runtime_observation": runtime_section(load_json(a.runtime), a.service, a.image, a.digest,
                                               a.sha, a.runtime_reason),
        "evidence": {
            "manifest_asset": f"{assets_base}/{manifest_name}",
            "bundle": {"name": bundle.name, "sha256": bundle_sha, "size_bytes": bundle.stat().st_size,
                       "url": f"{assets_base}/{bundle.name}"},
            "retention": {
                "release_assets": "kept with the GitHub Release; removed only if the release is deleted",
                "workflow_artifacts": "per artifact expires_at above (30-90 days)",
            },
        },
        "problems": problems,
        "limits": [
            "A passing Security Gate means the required assessments completed against fresh "
            "intelligence and matched nothing the policy blocks on. It is not a statement that "
            "the release is free of vulnerabilities.",
            "runtime_observation is what the host reported after this deployment, not a "
            "continuous guarantee; it can drift if someone changes the host afterwards.",
            "The evidence is engineering evidence. It is not a certification or a regulatory approval.",
        ],
    }
    (out / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out / "asset-names.txt").write_text(f"{manifest_name}\n{bundle.name}\n")
    print(json.dumps({"manifest": str(out / "release-manifest.json"), "manifest_asset_name": manifest_name,
                      "bundle": bundle.name, "bundle_sha256": bundle_sha, "problems": problems}, indent=2))
    return 1 if problems else 0


# ---------------------------------------------------------------------------
# verify
# ---------------------------------------------------------------------------

def verify(a: argparse.Namespace) -> int:
    m = json.loads(Path(a.manifest).read_text())
    repo, sha = m["repository"], m["release"]["commit"]
    results: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append((name, bool(ok), detail))

    check("schema", m.get("schema_version") == SCHEMA_VERSION and m.get("kind") == "manara.release-evidence")

    gate = m["security"]["gate"]
    live = gh_api(f"repos/{repo}/check-runs/{gate['check_run_id']}", allow_404=True)
    check("gate check run resolves, passed, on the released commit",
          bool(live) and live.get("conclusion") == "success" and live.get("head_sha") == sha
          and live.get("name") == GATE,
          f"{(live or {}).get('conclusion')} @ {(live or {}).get('head_sha')}")

    srun = m["security"]["assessment_run"]
    live_run = gh_api(f"repos/{repo}/actions/runs/{srun['id']}", allow_404=True)
    check("assessment run resolves to the released commit",
          bool(live_run) and live_run.get("head_sha") == sha, str((live_run or {}).get("head_sha")))

    verdict = m["security"].get("verdict") or {}
    check("verdict is PASS for the released revision",
          verdict.get("status") == "PASS" and verdict.get("revision") == sha,
          f"{verdict.get('status')} @ {verdict.get('revision')}")

    for c in m.get("build_checks", []):
        check(f"build check '{c['name']}' passed on the released commit",
              c.get("conclusion") == "success" and c.get("head_sha") == sha, str(c.get("conclusion")))

    rel = gh_api(f"repos/{repo}/releases/tags/{m['release']['tag']}", allow_404=True)
    names = {x["name"]: x for x in (rel or {}).get("assets", [])}
    manifest_name = m["evidence"]["manifest_asset"].rsplit("/", 1)[-1]
    bundle = m["evidence"]["bundle"]
    check("manifest is attached to the GitHub Release", manifest_name in names)
    check("evidence bundle is attached to the GitHub Release", bundle["name"] in names)
    if bundle["name"] in names and not a.skip_download:
        with tempfile.TemporaryDirectory() as tmp:
            run("gh", "release", "download", m["release"]["tag"], "-R", repo, "-p", bundle["name"], "-D", tmp)
            got = sha256_file(Path(tmp) / bundle["name"])
        check("attached bundle matches the recorded sha256", got == bundle["sha256"], got)

    if not a.skip_image:
        ref = m["artifact"]["reference"]
        proc = run("docker", "buildx", "imagetools", "inspect", ref, "--format", "{{json .Image}}", check=False)
        label = ""
        if proc.returncode == 0:
            try:
                label = (json.loads(proc.stdout).get("config") or {}).get("Labels", {}).get(
                    "org.opencontainers.image.revision", "")
            except ValueError:
                label = ""
        check("image digest resolves and is labelled with the released commit", label == sha,
              label or proc.stderr.strip()[:120])

    rt = m.get("runtime_observation") or {}
    if m["deployment"]["status"] == "success":
        check("host reported what it is running", rt.get("status") == "observed", rt.get("reason", ""))
        cons = rt.get("consistency") or {}
        check("host runs the deployed digest", cons.get("deployed_digest_is_running") is True)
        check("host runs the released commit", cons.get("deployed_revision_is_running") is True)

    width = max(len(n) for n, _, _ in results)
    for name, ok, detail in results:
        print(f"  {'ok  ' if ok else 'FAIL'}  {name.ljust(width)}  {detail if not ok else ''}".rstrip())
    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


# ---------------------------------------------------------------------------
# preflight / check-runtime: assertions over the host's own report
# ---------------------------------------------------------------------------

# Production never grants simulated paid access. FREE_ONLY refuses paid
# checkout; LIVE takes real payments and refuses to start without a provider.
# DEMONSTRATION grants paid courses against simulated receipts, and an absent
# mode is not a decision anyone made.
ALLOWED_PRODUCTION_MODES = ("FREE_ONLY", "LIVE")


def _result(name: str, ok: bool, observed: Any, expected: str) -> dict:
    return {"name": name, "ok": bool(ok), "observed": observed, "expected": expected}


def _emit(results: list[dict], out: str) -> int:
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text(json.dumps(results, indent=2) + "\n")
    for r in results:
        print(f"  {'ok  ' if r['ok'] else 'FAIL'}  {r['name']}  (observed {r['observed']!r}, "
              f"expected {r['expected']})")
    return 0 if all(r["ok"] for r in results) else 1


def preflight(a: argparse.Namespace) -> int:
    """Before a backend deployment: will the new backend receive an allowed commerce mode?"""
    rt = load_json(a.runtime)
    rt = rt if isinstance(rt, dict) else {}
    mode = ((rt.get("config") or {}).get("commerce_mode")) or {}
    results = [
        _result("the host reports its configuration (manifest.sh schema 2 or later)",
                int(rt.get("schema") or 1) >= 2, rt.get("schema"), ">= 2"),
        _result("the commerce mode .env asks for is allowed in production",
                mode.get("intended") in ALLOWED_PRODUCTION_MODES, mode.get("intended"),
                " or ".join(ALLOWED_PRODUCTION_MODES)),
        _result("the compose file passes the commerce mode to the backend",
                mode.get("compose_passes") == "yes", mode.get("compose_passes"), "yes"),
    ]
    code = _emit(results, a.out)
    if code:
        print("::error title=Host not ready for this backend::This backend refuses to start in "
              "production without an explicit commerce mode, and production must never grant "
              "simulated paid access. Before deploying: update /opt/manara to a "
              "manara-infrastructure revision that passes MANARA_COMMERCE_MODE to the backend "
              "and reports it (scripts/manifest.sh schema 2), and set MANARA_COMMERCE_MODE to "
              "FREE_ONLY in /opt/manara/.env unless a real payment provider is live. "
              "Production has NOT been touched.")
    return code


def check_runtime(a: argparse.Namespace) -> int:
    """After a deployment: is the host running what was approved, configured as required?"""
    rt = load_json(a.runtime)
    if not isinstance(rt, dict) or not isinstance(rt.get("services"), dict):
        return _emit([_result("the host reported what it is running", False, None, "a manifest")], a.out)
    svc = rt["services"].get(a.service) or {}
    cfg = rt.get("config") or {}
    env_file = cfg.get("env_file") or {}
    mode = str(env_file.get("mode") or "")
    reference = f"{a.image}@{a.digest}"
    results = [
        _result(f"the {a.service} runs the approved digest",
                reference in (svc.get("repo_digests") or []) or str(svc.get("image", "")).endswith("@" + a.digest),
                svc.get("repo_digests") or svc.get("image"), reference),
        _result(f"the {a.service}'s image is labelled with the released commit",
                svc.get("sha") == a.sha, svc.get("sha"), a.sha),
        _result(".env grants no permission to other users",
                mode.isdigit() and mode.endswith("0"), mode or None, "mode ending in 0, e.g. 600"),
        _result(".env is not tracked by git", env_file.get("git") in ("untracked", "not-a-git-checkout"),
                env_file.get("git"), "untracked"),
    ]
    if a.service == "backend":
        effective = (cfg.get("commerce_mode") or {}).get("effective")
        results.append(_result("the backend received an allowed commerce mode",
                               effective in ALLOWED_PRODUCTION_MODES, effective,
                               " or ".join(ALLOWED_PRODUCTION_MODES)))
        secrets = cfg.get("backend_secrets") or {}
        results.append(_result("every backend secret is injected (set, value not read)",
                               bool(secrets) and all(v == "set" for v in secrets.values()),
                               secrets, "all set"))
    return _emit(results, a.out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("preflight")
    p.add_argument("--runtime", required=True)
    p.add_argument("--out", required=True)
    r = sub.add_parser("check-runtime")
    r.add_argument("--runtime", required=True)
    r.add_argument("--service", required=True, choices=["backend", "frontend"])
    r.add_argument("--image", required=True)
    r.add_argument("--digest", required=True)
    r.add_argument("--sha", required=True)
    r.add_argument("--out", required=True)
    c = sub.add_parser("collect")
    c.add_argument("--repository", required=True)
    c.add_argument("--service", required=True, choices=["backend", "frontend"])
    c.add_argument("--tag", required=True)
    c.add_argument("--sha", required=True)
    c.add_argument("--image", required=True)
    c.add_argument("--digest", required=True)
    c.add_argument("--environment", default="Production")
    c.add_argument("--environment-url", default="https://manara-edu.com")
    c.add_argument("--deploy-run-id", required=True)
    c.add_argument("--deploy-run-attempt", default="1")
    c.add_argument("--deploy-status", required=True)
    c.add_argument("--deployed-at", default="")
    c.add_argument("--required-check", action="append", default=[])
    c.add_argument("--verification", action="append", default=[])
    c.add_argument("--runtime", default="")
    c.add_argument("--runtime-reason", default="")
    c.add_argument("--image-scan", default="")
    c.add_argument("--image-scan-result", default="unknown")
    c.add_argument("--out", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--manifest", required=True)
    v.add_argument("--skip-image", action="store_true")
    v.add_argument("--skip-download", action="store_true")
    a = ap.parse_args()
    try:
        return {"collect": collect, "verify": verify, "preflight": preflight,
                "check-runtime": check_runtime}[a.cmd](a)
    except EvidenceError as exc:
        print(f"::error title=Release evidence::{exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
