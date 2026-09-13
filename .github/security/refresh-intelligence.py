#!/usr/bin/env python3
"""Refresh vulnerability intelligence and record exactly what was retrieved.

Run once per Security Gate run, before any scanner. It produces:

  <out>/intelligence-manifest.json   what was fetched, when, and at which revision
  <out>/kev.json                     the CISA KEV catalogue
  <out>/osv-db/...                   OSV offline databases for this repo's ecosystems

The manifest is the run's evidence. The scanner jobs then consume these files
rather than fetching their own copies, so the data the gate reasons about is the
same data whose freshness it recorded. Where a scanner cannot be pointed at a
pre-fetched database (Trivy ships its own OCI-distributed DB), that job records
its actual database revision alongside its report instead, and evaluate.py holds
it to the same window.

Failure is the point of this script. If a mandatory source cannot be retrieved,
it writes the failure into the manifest and exits non-zero. It never writes an
empty database and calls it a successful refresh.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

KEV_URL = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
GHSA_PROBE = "https://api.github.com/advisories?per_page=1"

# Ecosystem -> the osv-scalibr subdirectory the database lands in.
ECOSYSTEM_DIRS = {"npm": "npm", "maven": "Maven"}


def utcnow_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def fetch(url: str, *, timeout: int, attempts: int, backoff: int,
          headers: dict | None = None) -> bytes:
    """GET with bounded retries. Raises on final failure — never returns empty."""
    last: Exception | None = None
    req_headers = {"User-Agent": "manara-security-gate"}
    if headers:
        req_headers.update(headers)
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(url, headers=req_headers)
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
                if resp.status != 200:
                    raise urllib.error.HTTPError(url, resp.status, "unexpected status", resp.headers, None)
                return resp.read()
        except Exception as exc:  # noqa: BLE001 - any transport failure is retryable here
            last = exc
            if attempt < attempts:
                print(f"  attempt {attempt}/{attempts} for {url} failed ({exc}); retrying")
                time.sleep(backoff * attempt)
    raise RuntimeError(f"{url}: {last}")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd: list[str], *, timeout: int, env: dict | None = None) -> subprocess.CompletedProcess:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=merged)


def refresh_osv(out: Path, ecosystems: list[str], probe_file: Path, opts) -> dict:
    """Download the OSV offline databases for the ecosystems this repo actually has.

    osv-scanner only downloads the databases it needs for the manifest it is
    given, so the real lockfile/SBOM is passed as the probe. The scan result is
    discarded here — this call exists to populate and timestamp the cache.
    """
    db_dir = out / "osv-db"
    db_dir.mkdir(parents=True, exist_ok=True)
    started = utcnow_iso()

    flag = "--sbom" if probe_file.name.endswith((".cdx.json", "bom.json")) else "--lockfile"
    cmd = ["osv-scanner", "scan", "source", f"{flag}={probe_file}",
           "--offline-vulnerabilities", "--download-offline-databases",
           "--format=json"]
    proc = run(cmd, timeout=opts.timeout,
               env={"OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY": str(db_dir)})

    # osv-scanner exits 1 when it FINDS vulnerabilities. That is a successful
    # refresh. Only a failure to produce a database is a refresh failure.
    zips = sorted(db_dir.rglob("*.zip"))
    if not zips:
        return {"status": "failed", "retrieved_at": started,
                "error": f"no database was written (exit {proc.returncode}): "
                         f"{(proc.stderr or '')[-400:]}"}

    databases = []
    for z in zips:
        databases.append({
            "ecosystem": z.parent.name,
            "path": str(z.relative_to(out)),
            "bytes": z.stat().st_size,
            "sha256": sha256_file(z),
            "mtime": dt.datetime.fromtimestamp(z.stat().st_mtime, dt.timezone.utc).isoformat(),
        })

    expected = {ECOSYSTEM_DIRS.get(e, e).lower() for e in ecosystems}
    got = {d["ecosystem"].lower() for d in databases}
    missing = expected - got
    if missing:
        return {"status": "failed", "retrieved_at": started,
                "error": f"no OSV database for ecosystem(s): {', '.join(sorted(missing))}"}

    return {
        "status": "ok",
        "retrieved_at": utcnow_iso(),
        "upstream_revision": ",".join(f"{d['ecosystem']}@{d['sha256'][:12]}" for d in databases),
        "provider": "osv.dev offline database via osv-scanner",
        "databases": databases,
        "local_db_path": str(db_dir),
    }


def refresh_trivy_db(opts) -> dict:
    """Pull Trivy's DB and read back the revision it actually holds."""
    started = utcnow_iso()
    pull = run(["trivy", "image", "--download-db-only"], timeout=opts.timeout)
    if pull.returncode != 0:
        return {"status": "failed", "retrieved_at": started,
                "error": f"download failed: {(pull.stderr or '')[-400:]}"}
    ver = run(["trivy", "version", "--format", "json"], timeout=60)
    if ver.returncode != 0:
        return {"status": "failed", "retrieved_at": started,
                "error": "could not read the Trivy database revision back"}
    try:
        info = json.loads(ver.stdout)
    except json.JSONDecodeError as exc:
        return {"status": "failed", "retrieved_at": started, "error": f"unreadable version output: {exc}"}

    vdb = info.get("VulnerabilityDB") or {}
    if not vdb.get("UpdatedAt"):
        return {"status": "failed", "retrieved_at": started,
                "error": "Trivy reported no vulnerability database"}
    return {
        "status": "ok",
        # UpdatedAt is when upstream BUILT the database. That, not the download
        # time, is what the freshness window has to be measured against —
        # re-downloading a stale database does not make it fresh.
        "retrieved_at": vdb["UpdatedAt"],
        "downloaded_at": vdb.get("DownloadedAt"),
        "upstream_revision": f"trivy-db v{vdb.get('Version')} built {vdb.get('UpdatedAt')}",
        "next_update": vdb.get("NextUpdate"),
        "scanner_version": info.get("Version"),
        "check_bundle": (info.get("CheckBundle") or {}).get("Digest"),
        "provider": "ghcr.io/aquasecurity/trivy-db",
    }


def refresh_kev(out: Path, opts) -> dict:
    started = utcnow_iso()
    try:
        raw = fetch(KEV_URL, timeout=opts.timeout, attempts=opts.attempts, backoff=opts.backoff)
    except RuntimeError as exc:
        return {"status": "failed", "retrieved_at": started, "error": str(exc)}
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as exc:
        return {"status": "failed", "retrieved_at": started, "error": f"catalogue is not JSON: {exc}"}

    ids = sorted({v["cveID"] for v in doc.get("vulnerabilities", []) if v.get("cveID")})
    if not ids:
        return {"status": "failed", "retrieved_at": started,
                "error": "catalogue parsed but contained no CVE entries"}

    (out / "kev.json").write_bytes(raw)
    return {
        "status": "ok",
        "retrieved_at": utcnow_iso(),
        # dateReleased is CISA's own publication timestamp for this catalogue
        # version, which is the upstream revision this run reasoned from.
        "upstream_published_at": doc.get("dateReleased"),
        "upstream_revision": str(doc.get("catalogVersion")),
        "entry_count": len(ids),
        "provider": "CISA Known Exploited Vulnerabilities catalogue",
        "kev_cve_ids": ids,
    }


def probe_ghsa(opts) -> dict:
    """Confirm the GitHub Advisory Database is actually reachable for this run.

    dependency-review queries it at job time, so the gate must not claim GHSA
    coverage it could not have had. A token is used when present purely for rate
    limiting; the endpoint is public.
    """
    started = utcnow_iso()
    headers = {"Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN", "")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        raw = fetch(GHSA_PROBE, timeout=opts.timeout, attempts=opts.attempts,
                    backoff=opts.backoff, headers=headers)
        doc = json.loads(raw)
    except (RuntimeError, json.JSONDecodeError) as exc:
        return {"status": "failed", "retrieved_at": started, "error": str(exc)}
    if not isinstance(doc, list) or not doc:
        return {"status": "failed", "retrieved_at": started,
                "error": "advisory endpoint returned no records"}
    newest = doc[0].get("published_at") or doc[0].get("updated_at")
    return {
        "status": "ok",
        "retrieved_at": utcnow_iso(),
        "upstream_revision": f"newest advisory {doc[0].get('ghsa_id')} published {newest}",
        "provider": "GitHub Advisory Database (GitHub-reviewed)",
        "note": "Consumed by dependency-review and by the GHSA records embedded in OSV.",
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--ecosystems", required=True,
                    help="comma-separated, e.g. 'npm' or 'maven'")
    ap.add_argument("--probe-file", required=True,
                    help="the repo's real lockfile or CycloneDX SBOM")
    ap.add_argument("--repository", required=True)
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--attempts", type=int, default=3)
    ap.add_argument("--backoff", type=int, default=5)
    args = ap.parse_args()

    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    ecosystems = [e.strip() for e in args.ecosystems.split(",") if e.strip()]
    probe = Path(args.probe_file)
    if not probe.is_file():
        print(f"::error::Probe file {probe} does not exist; cannot refresh OSV databases.")
        return 1

    print("Refreshing vulnerability intelligence")
    sources: dict[str, dict] = {}

    print("- OSV offline databases")
    sources["osv"] = refresh_osv(out, ecosystems, probe, args)
    print("- Trivy database")
    sources["trivy-db"] = refresh_trivy_db(args)
    print("- CISA KEV catalogue")
    sources["cisa-kev"] = refresh_kev(out, args)
    print("- GitHub Advisory Database reachability")
    sources["ghsa"] = probe_ghsa(args)

    manifest = {
        "schema": 1,
        "repository": args.repository,
        "generated_at": utcnow_iso(),
        "ecosystems": ecosystems,
        "tool_versions": {},
        "sources": sources,
        "caveat": (
            "Retrieval freshness is not publication completeness. This manifest records "
            "when each source was fetched and which revision was used. It does not and "
            "cannot show that every vulnerability disclosed in that window has been "
            "published upstream or ingested into these databases."
        ),
    }

    for tool, cmd in (("osv-scanner", ["osv-scanner", "--version"]),
                      ("trivy", ["trivy", "--version"]),
                      ("gitleaks", ["gitleaks", "version"])):
        try:
            proc = run(cmd, timeout=60)
            manifest["tool_versions"][tool] = (proc.stdout or proc.stderr).strip().splitlines()[0]
        except (OSError, subprocess.SubprocessError, IndexError):
            manifest["tool_versions"][tool] = "unavailable"

    (out / "intelligence-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    failed = [k for k, v in sources.items() if v.get("status") != "ok"]
    for name in failed:
        print(f"::error title=Intelligence refresh::Source '{name}' failed: "
              f"{sources[name].get('error')}")

    print(f"\nManifest written to {out / 'intelligence-manifest.json'}")
    for name, entry in sources.items():
        print(f"  {name:12} {entry.get('status'):8} {entry.get('upstream_revision', '')}")

    # A failure here is deliberately fatal. The alternative — carrying on with a
    # missing source — is exactly how a scan with no data becomes a green check.
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
