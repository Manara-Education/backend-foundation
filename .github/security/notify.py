#!/usr/bin/env python3
"""Email the blocking security report, or say that the assessment did not finish.

Run from .github/workflows/security-notify.yml, which is a SEPARATE workflow
triggered by `workflow_run`. That separation is the whole design, and it is
worth stating plainly:

  * This program holds the mail credential. The scanning workflow never does,
    so a fork pull request — whose code the scanners execute — cannot reach it.
  * This program runs from the DEFAULT BRANCH. Nothing here is checked out from,
    executed from, or sourced from the assessed revision.
  * Everything it reads out of the assessment is UNTRUSTED DATA. A pull request
    author controls file names, package names, advisory titles and its own
    branch name; all of them end up in an email. So nothing read here is ever
    interpolated into a shell, into a URL, or into HTML without escaping, and
    the archive is expanded by this file's own extractor rather than by anything
    that would honour a path in it.
  * Whether the merge is blocked was already decided, by evaluate.py, in the run
    that produced this artifact. NOTHING in this file can change that. If the
    mail fails, this job fails and the security verdict stands exactly as it
    was. That is the correct direction: a broken notifier must be loud, and it
    must not be able to turn a red gate green.

Inputs
------
  --run-metadata FILE   resolved from the GitHub API by the workflow: run id,
                        attempt, conclusion, head sha, and the pull request the
                        API associates with that commit. The API is the only
                        accepted source for any of this. Values that also appear
                        inside the artifact are compared and any difference is
                        REPORTED, never silently preferred.
  --artifact-zip FILE   the `security-gate-verdict` artifact for exactly that
                        run id and attempt, still zipped.
  --artifact-dir DIR    an already-expanded tree (used by the fixture suite).

Outputs
-------
  <out-dir>/result.json    what was decided, what was sent, and what failed.
  <out-dir>/request.json   the provider request as built, minus the credential.
  <out-dir>/body.txt|.html the rendered mail.
  <out-dir>/summary.md     the job summary.
  exit code                0 = handled (sent, or correctly silent).
                           1 = the notification failed and a human must look.

When it sends
-------------
  BLOCKED   any usable verdict reports blocking findings.
  ERROR     the assessment failed, was cancelled, timed out, or produced no
            usable verdict — INCLUDING when the artifact is missing, stale,
            malformed, or belongs to a different run.
  nothing   every target passed, coverage was complete, and the run succeeded.

Blocking beats error. If one leg of a matrix failed and another still reports
blockers, the blocking mail goes out and the coverage gap is stated inside it.
Losing a real blocker because a neighbouring leg broke would be the worst
possible failure mode for this program.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

# The only verdict layout this program understands. A document announcing
# anything else is refused rather than guessed at.
SUPPORTED_SCHEMA_VERSIONS = frozenset({1})

RESEND_ENDPOINT = "https://api.resend.com/emails"

# Resend accepts up to 40 MB per message including attachments. The default here
# is far below that on purpose: an attachment big enough to matter is better
# linked than mailed, and the body says so explicitly rather than truncating.
DEFAULT_MAX_ATTACHMENT_BYTES = 6 * 1024 * 1024

SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"]


def sev_rank(sev: str) -> int:
    try:
        return SEVERITY_ORDER.index(str(sev).upper())
    except ValueError:
        return SEVERITY_ORDER.index("MEDIUM")


def utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


# ---------------------------------------------------------------------------
# Redaction — applied to every string that leaves this program
# ---------------------------------------------------------------------------
#
# A secret-scanning finding is a report ABOUT a credential. Mailing the
# credential to make that point would be worse than the leak it describes, and
# an inbox is a far less controlled place than a private artifact. So the value
# is never rendered: the rule, the file and the line are, and that is enough to
# act on.
#
# Applied identically to the body and to the attachment. There is no path
# through this file where an unredacted string reaches the provider.

_REDACTIONS: tuple[tuple[re.Pattern[str], str], ...] = (
    # Whole PEM blocks, before anything else gets a chance to partially match.
    (re.compile(r"-----BEGIN[A-Z ]*PRIVATE KEY-----.*?-----END[A-Z ]*PRIVATE KEY-----",
                re.DOTALL), "[redacted: private key]"),
    # Vendor-prefixed credentials, which are unambiguous by construction.
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{16,}"), "[redacted: github token]"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"), "[redacted: github token]"),
    (re.compile(r"\bre_[A-Za-z0-9_\-]{16,}"), "[redacted: resend key]"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"), "[redacted: slack token]"),
    (re.compile(r"\bsk-[A-Za-z0-9_\-]{20,}"), "[redacted: api key]"),
    # Stripe and the several vendors that copied its shape. gitleaks reports
    # these constantly, and its rule id — which is what actually gets rendered —
    # is not enough on its own if the surrounding text quotes the match.
    (re.compile(r"\b[sprk]k_(?:live|test)_[A-Za-z0-9]{10,}"), "[redacted: api key]"),
    (re.compile(r"\bglpat-[A-Za-z0-9_\-]{16,}"), "[redacted: gitlab token]"),
    (re.compile(r"\bnpm_[A-Za-z0-9]{30,}"), "[redacted: npm token]"),
    (re.compile(r"\bSG\.[A-Za-z0-9_\-]{16,}\.[A-Za-z0-9_\-]{16,}"), "[redacted: sendgrid key]"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "[redacted: aws key id]"),
    (re.compile(r"\bASIA[0-9A-Z]{16}\b"), "[redacted: aws key id]"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"), "[redacted: google api key]"),
    (re.compile(r"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"),
     "[redacted: jwt]"),
    # `.env`-shaped assignments, wherever they appear. The NAME is kept because
    # knowing which variable leaked is the actionable half.
    (re.compile(r"(?i)\b([A-Z0-9_]*(?:SECRET|PASSWORD|PASSWD|TOKEN|API[_-]?KEY|PRIVATE[_-]?KEY"
                r"|CREDENTIAL|ACCESS[_-]?KEY)[A-Z0-9_]*)\s*[:=]\s*[\"']?[^\s\"'<>,;]{4,}"),
     r"\1=[redacted]"),
    (re.compile(r"(?i)\bauthorization\s*:\s*\S+"), "authorization: [redacted]"),
    (re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._\-]{12,}"), "bearer [redacted]"),
    # Credentials inside URLs, and signed-URL query parameters.
    (re.compile(r"://[^/\s:@]+:[^/\s@]+@"), "://[redacted]@"),
    (re.compile(r"(?i)([?&](?:token|key|api_key|apikey|sig|signature|password|secret)=)"
                r"[^&\s\"'<>]+"), r"\1[redacted]"),
)

# Personal data, handled separately because the addresses this run is configured
# to use must survive — the mail would be unreadable if its own From and To were
# scrubbed out of the body. Scan output routinely carries committer and
# maintainer addresses, and none of those belong in an alert inbox.
EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")

# Control characters would let a finding title rewrite a terminal or forge a log
# line. Newlines survive only in the attachment, which uses `block` below;
# everything rendered inline is single-line by construction.
_CTRL_INLINE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_CTRL_BLOCK = re.compile(r"[\x00-\x08\x0b\x0e-\x1f\x7f]")


class Redactor:
    """Scrubs every rendered string. Configured addresses survive; nothing else does."""

    def __init__(self, keep: list[str] | None = None):
        self.keep = {k.strip().lower() for k in (keep or []) if k and k.strip()}

    def _scrub(self, text: str) -> str:
        for pattern, replacement in _REDACTIONS:
            text = pattern.sub(replacement, text)
        return EMAIL_RE.sub(
            lambda m: m.group(0) if m.group(0).lower() in self.keep else "[redacted: email]",
            text)

    def __call__(self, value: Any) -> str:
        text = "" if value is None else str(value)
        if not text:
            return ""
        return _CTRL_INLINE.sub("", self._scrub(text))

    def block(self, value: Any) -> str:
        """Same rules, but newlines and tabs are preserved (for the attachment)."""
        return _CTRL_BLOCK.sub("", self._scrub("" if value is None else str(value)))


def esc(value: Any) -> str:
    """HTML-escape, quotes included. Every dynamic value in the HTML goes through this."""
    return html.escape("" if value is None else str(value), quote=True)


# ---------------------------------------------------------------------------
# Untrusted archive handling
# ---------------------------------------------------------------------------

class ArtifactError(Exception):
    """The artifact could not be used. Never fatal on its own — it becomes an ERROR mail."""


SAFE_MEMBER = re.compile(r"^[A-Za-z0-9._\-/]+$")


def safe_extract(zip_path: Path, dest: Path, *, max_total_bytes: int,
                 max_entries: int, max_ratio: int = 200) -> list[Path]:
    """Expand an archive written by code we do not trust.

    `actions/download-artifact` would expand this for us, and that is exactly
    why it is not used here: the extraction rules ARE the security boundary, so
    they are written down where they can be read and tested.

    Refused outright: absolute paths, any `..` segment, backslashes, symlinks,
    device nodes, names outside a conservative character set, more entries than
    the cap, more decompressed bytes than the cap, and any single entry whose
    compression ratio suggests a zip bomb.
    """
    extracted: list[Path] = []
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    total = 0
    try:
        with zipfile.ZipFile(zip_path) as zf:
            infos = zf.infolist()
            if len(infos) > max_entries:
                raise ArtifactError(
                    f"the archive holds {len(infos)} entries, above the {max_entries} cap")
            for info in infos:
                name = info.filename
                if info.is_dir():
                    continue
                if name.startswith("/") or name.startswith("\\") or "\\" in name:
                    raise ArtifactError(f"refused an absolute or backslashed member: {name!r}")
                if any(part in ("..", "") for part in name.split("/")):
                    raise ArtifactError(f"refused a path-traversing member: {name!r}")
                if not SAFE_MEMBER.match(name):
                    raise ArtifactError(f"refused a member with an unsafe name: {name!r}")
                mode = info.external_attr >> 16
                if mode and not (mode & 0o170000) in (0o100000, 0):
                    raise ArtifactError(f"refused a non-regular member: {name!r}")
                if info.file_size > max_total_bytes:
                    raise ArtifactError(f"member {name!r} alone exceeds the size cap")
                if info.compress_size > 0 and info.file_size / info.compress_size > max_ratio:
                    raise ArtifactError(
                        f"member {name!r} decompresses {info.file_size // max(info.compress_size, 1)}x, "
                        f"above the {max_ratio}x cap")
                total += info.file_size
                if total > max_total_bytes:
                    raise ArtifactError(
                        f"the archive decompresses to more than the {max_total_bytes} byte cap")

                target = (dest / name).resolve()
                if not str(target).startswith(str(dest) + os.sep):
                    raise ArtifactError(f"refused a member escaping the destination: {name!r}")
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, open(target, "wb") as out:
                    out.write(src.read(info.file_size + 1))
                extracted.append(target)
    except (zipfile.BadZipFile, OSError) as exc:
        raise ArtifactError(f"the archive could not be read: {exc}") from exc
    return extracted


def read_json(path: Path, max_bytes: int) -> Any:
    if path.stat().st_size > max_bytes:
        raise ArtifactError(f"{path.name} is larger than the {max_bytes} byte cap")
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (json.JSONDecodeError, OSError) as exc:
        raise ArtifactError(f"{path.name} is not readable JSON: {exc}") from exc


# ---------------------------------------------------------------------------
# One assessed target
# ---------------------------------------------------------------------------

REQUIRED_VERDICT_KEYS = ("schema_version", "status", "repository", "event", "ref",
                         "revision", "counts", "findings")
VALID_STATUS = frozenset({"PASS", "BLOCKED", "ERROR"})
SHA_RE = re.compile(r"^[0-9a-fA-F]{7,40}$")
REPO_RE = re.compile(r"^[A-Za-z0-9._\-]+/[A-Za-z0-9._\-]+$")


class Leg:
    """One target's verdict, plus everything we concluded about trusting it."""

    def __init__(self, source: str):
        self.source = source
        self.usable = False
        self.problems: list[str] = []
        self.discrepancies: list[str] = []
        self.doc: dict = {}
        self.register: dict = {}
        self.report_md: str = ""

    # -- accessors, all defensive: this document is untrusted --------------
    def get(self, key: str, default: Any = None) -> Any:
        value = self.doc.get(key, default)
        return default if value is None else value

    @property
    def status(self) -> str:
        return str(self.doc.get("status", "ERROR")).upper()

    @property
    def counts(self) -> dict:
        c = self.doc.get("counts")
        return c if isinstance(c, dict) else {}

    @property
    def blocking_count(self) -> int:
        try:
            return int(self.counts.get("blocking", 0))
        except (TypeError, ValueError):
            return 0

    @property
    def findings(self) -> list[dict]:
        f = self.doc.get("findings")
        return [x for x in f if isinstance(x, dict)] if isinstance(f, list) else []

    @property
    def blocking_findings(self) -> list[dict]:
        return sorted((f for f in self.findings if f.get("blocking")),
                      key=lambda f: (sev_rank(f.get("severity", "MEDIUM")), str(f.get("id", ""))))

    @property
    def coverage_gaps(self) -> list[dict]:
        g = self.doc.get("coverage_gaps")
        return [x for x in g if isinstance(x, dict)] if isinstance(g, list) else []


def validate_verdict(doc: Any) -> list[str]:
    """Structural checks only. A document that fails ANY of these is not used.

    The point is not to detect a hostile artifact — a pull request cannot forge
    the run id the API reports — but to refuse to render something whose shape
    we have not agreed on, rather than crash halfway through composing an email
    and send nothing at all.
    """
    problems: list[str] = []
    if not isinstance(doc, dict):
        return ["the verdict is not a JSON object"]
    version = doc.get("schema_version")
    if not isinstance(version, int) or isinstance(version, bool):
        problems.append(f"schema_version is {version!r}, not an integer")
    elif version not in SUPPORTED_SCHEMA_VERSIONS:
        problems.append(
            f"schema_version {version} is not one this notifier understands "
            f"({sorted(SUPPORTED_SCHEMA_VERSIONS)})")
    for key in REQUIRED_VERDICT_KEYS:
        if key not in doc:
            problems.append(f"required field '{key}' is missing")
    status = doc.get("status")
    if status is not None and str(status).upper() not in VALID_STATUS:
        problems.append(f"status {status!r} is not one of {sorted(VALID_STATUS)}")
    if "counts" in doc and not isinstance(doc.get("counts"), dict):
        problems.append("counts is not an object")
    if "findings" in doc and not isinstance(doc.get("findings"), list):
        problems.append("findings is not a list")
    repo = doc.get("repository")
    if repo is not None and not REPO_RE.match(str(repo)):
        problems.append(f"repository {str(repo)[:80]!r} is not owner/name shaped")
    rev = doc.get("revision")
    if rev is not None and not SHA_RE.match(str(rev)):
        problems.append(f"revision {str(rev)[:80]!r} is not a commit sha")
    return problems


def reconcile(leg: Leg, meta: dict) -> None:
    """Hold the artifact to what the API says, and record every difference.

    The API is authoritative for identity. A verdict claiming a different run,
    attempt or repository is not this run's verdict and is not used — that is
    the check that stops a stale or re-pointed artifact from being reported as
    the current assessment. Everything else that differs is recorded and shown,
    because a discrepancy is itself information a reader needs.
    """
    doc = leg.doc
    api_repo = str(meta.get("repository", ""))
    doc_repo = str(doc.get("repository", ""))
    if doc_repo and doc_repo != api_repo:
        leg.problems.append(
            f"the verdict names repository '{doc_repo}' but the API reports '{api_repo}'")

    api_run = str(meta.get("run_id", ""))
    doc_run = str(doc.get("run_id") or "")
    if not doc_run:
        leg.discrepancies.append(
            "the verdict recorded no run id, so it cannot be tied to this run by its own content")
    elif doc_run != api_run:
        leg.problems.append(
            f"the verdict was produced by run {doc_run}, not by run {api_run}")

    api_attempt = meta.get("run_attempt")
    doc_attempt = doc.get("run_attempt")
    if doc_attempt is not None and api_attempt is not None and int(api_attempt) != _as_int(doc_attempt):
        leg.problems.append(
            f"the verdict is from attempt {doc_attempt}, not attempt {api_attempt}")

    api_sha = str(meta.get("head_sha", ""))
    doc_sha = str(doc.get("revision", ""))
    if api_sha and doc_sha and doc_sha != api_sha and meta.get("event") != "schedule":
        # Not fatal: a scheduled run legitimately assesses two branches, neither
        # of which is the run's head. It IS reported.
        leg.discrepancies.append(
            f"the verdict assessed {doc_sha[:12]} while the run's head commit is {api_sha[:12]}")

    api_pr = meta.get("pull_request") or {}
    doc_pr = doc.get("pr_number")
    if api_pr and doc_pr is not None and _as_int(doc_pr) != _as_int(api_pr.get("number")):
        leg.discrepancies.append(
            f"the verdict names PR #{doc_pr} but the API associates this commit with "
            f"PR #{api_pr.get('number')}; the API value is used")
    api_base = (api_pr or {}).get("base_ref")
    doc_base = doc.get("base_ref")
    if api_base and doc_base and str(api_base) != str(doc_base):
        leg.discrepancies.append(
            f"the verdict names base branch '{doc_base}' but the API reports '{api_base}'; "
            f"the API value is used")


def _as_int(value: Any) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def load_legs(root: Path, meta: dict, *, max_json_bytes: int, max_legs: int) -> list[Leg]:
    """Find every verdict in the expanded artifact and decide which are usable."""
    paths = sorted(p for p in root.rglob("verdict.json") if p.is_file())
    legs: list[Leg] = []
    if len(paths) > max_legs:
        raise ArtifactError(f"the artifact holds {len(paths)} verdicts, above the {max_legs} cap")
    for path in paths:
        leg = Leg(str(path.relative_to(root)))
        try:
            doc = read_json(path, max_json_bytes)
        except ArtifactError as exc:
            leg.problems.append(str(exc))
            legs.append(leg)
            continue
        problems = validate_verdict(doc)
        leg.doc = doc if isinstance(doc, dict) else {}
        if problems:
            leg.problems.extend(problems)
            legs.append(leg)
            continue
        reconcile(leg, meta)
        leg.usable = not leg.problems

        # History, when it is there. The register is not written for a run that
        # could not be evaluated, so its absence is normal and never inferred
        # from as if it meant "nothing was seen before".
        reg = path.parent / "findings.json"
        if reg.is_file():
            try:
                loaded = read_json(reg, max_json_bytes)
                if isinstance(loaded, dict):
                    leg.register = loaded
            except ArtifactError:
                leg.discrepancies.append("the findings register alongside this verdict is unreadable")
        rpt = path.parent / "report.md"
        if rpt.is_file():
            try:
                if rpt.stat().st_size <= max_json_bytes:
                    leg.report_md = rpt.read_text(encoding="utf-8", errors="replace")
                else:
                    leg.discrepancies.append("the Markdown report is above the size cap and was not attached")
            except OSError:
                leg.discrepancies.append("the Markdown report could not be read")
        legs.append(leg)
    return legs


# ---------------------------------------------------------------------------
# Scope
# ---------------------------------------------------------------------------

def scope_of(leg: Leg, meta: dict) -> str:
    """What this leg actually assessed, in words a reader can act on.

    Built from API metadata wherever identity is involved. The verdict's own ref
    is used only as a label, and it is redacted and escaped like any other
    attacker-controlled string.
    """
    event = str(meta.get("event", "")) or str(leg.get("event", ""))
    pr = meta.get("pull_request") or {}
    parts: list[str] = []
    if event == "pull_request" and pr.get("number"):
        base = pr.get("base_ref") or leg.get("base_ref") or "?"
        parts.append(f"PR #{pr['number']} candidate -> {base}")
    else:
        ref = str(leg.get("ref", "")) or str(meta.get("head_branch", "")) or "unknown ref"
        if event == "schedule":
            parts.append(f"scheduled assessment of {ref}")
        else:
            parts.append(ref)
    digests = leg.get("image_digests", [])
    if isinstance(digests, list):
        for d in digests:
            if isinstance(d, str) and "@sha256:" in d:
                parts.append(f"image {d.split('@', 1)[1][:19]}")
                break
    return " / ".join(parts)


def scope_key_of(leg: Leg) -> str:
    """Stable, non-display identity for the idempotency key."""
    return "|".join([str(leg.get("target_slug", "") or leg.source),
                     str(leg.get("ref", "")), str(leg.get("revision", ""))])


# ---------------------------------------------------------------------------
# Decision
# ---------------------------------------------------------------------------

class Decision:
    def __init__(self, kind: str, reasons: list[str]):
        self.kind = kind          # "blocked" | "error" | "none" | "test"
        self.reasons = reasons


def decide(legs: list[Leg], meta: dict, artifact_problem: str | None) -> Decision:
    """Blocking beats error; error beats silence; silence needs a complete pass.

    Ordering matters more than anything else here. A run whose gate blocked has
    conclusion 'failure', so testing the run's conclusion first would classify
    every genuine block as an infrastructure error and bury the findings.
    """
    usable = [leg for leg in legs if leg.usable]
    blocking = [leg for leg in usable if leg.status == "BLOCKED" or leg.blocking_count > 0]
    if blocking:
        reasons = [f"{sum(leg.blocking_count for leg in blocking)} blocking finding(s) across "
                   f"{len(blocking)} assessed target(s)"]
        rejected = [leg for leg in legs if not leg.usable]
        if rejected:
            reasons.append(f"{len(rejected)} verdict(s) in this artifact could not be used")
        if artifact_problem:
            reasons.append(artifact_problem)
        return Decision("blocked", reasons)

    reasons = []
    conclusion = str(meta.get("conclusion", "") or "").lower()
    if artifact_problem:
        reasons.append(artifact_problem)
    if not legs:
        reasons.append("the assessment produced no verdict at all")
    for leg in legs:
        if not leg.usable:
            reasons.append(f"{leg.source}: " + "; ".join(leg.problems))
        elif leg.status == "ERROR":
            reasons.append(f"{scope_key_of(leg)}: the gate could not be evaluated")
    if conclusion and conclusion != "success":
        reasons.append(f"the assessment run concluded '{conclusion}'")
    if reasons:
        return Decision("error", reasons)

    hard_gaps = [g for leg in usable for g in leg.coverage_gaps if g.get("hard")]
    if hard_gaps:
        return Decision("error", [f"coverage was incomplete: {len(hard_gaps)} required "
                                  f"assessment(s) did not produce a result"])
    return Decision("none", ["every assessed target passed and the run concluded success"])


# ---------------------------------------------------------------------------
# Links — built only from validated API metadata
# ---------------------------------------------------------------------------

class Links:
    def __init__(self, meta: dict):
        server = str(meta.get("server_url") or "https://github.com").rstrip("/")
        if not server.startswith("https://"):
            server = "https://github.com"
        repo = str(meta.get("repository", ""))
        self.ok = bool(REPO_RE.match(repo))
        self.server = server
        self.repo = repo if self.ok else ""
        self.run_id = _as_int(meta.get("run_id"))
        self.attempt = _as_int(meta.get("run_attempt")) or 1
        self.head_sha = str(meta.get("head_sha", ""))
        pr = meta.get("pull_request") or {}
        self.pr_number = _as_int(pr.get("number"))
        self.artifact_id = _as_int((meta.get("artifact") or {}).get("id"))

    def _base(self) -> str:
        return f"{self.server}/{self.repo}"

    @property
    def run(self) -> str:
        if not (self.ok and self.run_id):
            return ""
        return f"{self._base()}/actions/runs/{self.run_id}/attempts/{self.attempt}"

    @property
    def commit(self) -> str:
        if not (self.ok and SHA_RE.match(self.head_sha)):
            return ""
        return f"{self._base()}/commit/{self.head_sha}"

    @property
    def pull_request(self) -> str:
        if not (self.ok and self.pr_number):
            return ""
        return f"{self._base()}/pull/{self.pr_number}"

    @property
    def artifact(self) -> str:
        if not (self.ok and self.run_id):
            return ""
        # The artifact download itself requires authentication; this is the page
        # a signed-in reader lands on. Stated as such in the body.
        return f"{self._base()}/actions/runs/{self.run_id}#artifacts"


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

FIX_UNKNOWN = "no verified upgrade available"


def fixed_version_text(finding: dict) -> str:
    """Say what to upgrade to, or say plainly that there is nothing to upgrade to.

    evaluate.py's select_fixed_version never reports a version below the
    installed one, so a blank here means the advisory listed no fix reachable
    from what is installed. Printing '—' for that reads as "unknown"; it is not
    unknown, it is absent, and the difference decides what a reader does next.
    """
    fixed = str(finding.get("fixed_version") or "").strip()
    if not fixed:
        return FIX_UNKNOWN
    installed = str(finding.get("version") or "").strip()
    others = [v for v in (finding.get("all_fixed_versions") or [])
              if isinstance(v, str) and v.strip() and v.strip() != fixed]
    text = fixed
    if installed:
        text = f"{fixed} (from {installed})"
    if others:
        text += f"; advisory also lists {', '.join(sorted(others))}"
    return text


def history_of(leg: Leg, finding: dict) -> str:
    """New in this revision, or carried over. Only claimed when the register says so."""
    entries = leg.register.get("findings") if isinstance(leg.register, dict) else None
    if not isinstance(entries, list):
        return "history unavailable"
    key = finding.get("key")
    for e in entries:
        if isinstance(e, dict) and e.get("key") == key:
            first = str(e.get("first_seen_revision") or "")
            if first and first == str(leg.get("revision", "")):
                return "new in this revision"
            if e.get("first_seen"):
                return f"continuing since {str(e['first_seen'])[:10]}"
            return "continuing"
    return "history unavailable"


def counts_line(counts: dict) -> str:
    by = counts.get("by_severity") if isinstance(counts.get("by_severity"), dict) else {}
    parts = [f"{sev} {int(by.get(sev, 0) or 0)}" for sev in SEVERITY_ORDER
             if int(by.get(sev, 0) or 0)]
    return ", ".join(parts) or "none"


class Rendered:
    def __init__(self, subject: str, text: str, html_body: str, attachment: dict | None,
                 attachment_note: str):
        self.subject = subject
        self.text = text
        self.html = html_body
        self.attachment = attachment
        self.attachment_note = attachment_note


def render(decision: Decision, legs: list[Leg], meta: dict, links: Links, red: Redactor,
           *, repository: str, max_findings: int, max_attachment_bytes: int,
           test_mode: bool) -> Rendered:
    usable = [leg for leg in legs if leg.usable]
    scopes = [scope_of(leg, meta) for leg in usable] or ["no usable target"]
    scope_label = "; ".join(dict.fromkeys(scopes))
    if len(scope_label) > 120:
        scope_label = scope_label[:117] + "..."
    total_blocking = sum(leg.blocking_count for leg in usable)

    tag = "SECURITY BLOCKED" if decision.kind == "blocked" else "SECURITY SCAN ERROR / INCOMPLETE"
    prefix = "[Manara][TEST]" if test_mode else "[Manara]"
    if decision.kind == "blocked":
        subject = (f"{prefix}[{tag}][{repository}][{red(scope_label)}] "
                   f"{total_blocking} blocking findings")
    else:
        subject = (f"{prefix}[{tag}][{repository}][{red(scope_label)}] "
                   f"assessment did not complete")

    t: list[str] = []
    h: list[str] = []

    def head(level: int, title: str) -> None:
        t.append("")
        t.append(title)
        t.append(("=" if level == 1 else "-") * len(title))
        h.append(f"<h{level+1} style=\"font-family:system-ui,sans-serif;margin:22px 0 8px\">"
                 f"{esc(title)}</h{level+1}>")

    def para(line: str, mono: bool = False) -> None:
        t.append(line)
        style = ("font-family:ui-monospace,Menlo,monospace;font-size:13px"
                 if mono else "font-family:system-ui,sans-serif;font-size:14px")
        h.append(f"<p style=\"{style};margin:4px 0;line-height:1.5\">{esc(line)}</p>")

    def bullets(items: list[str]) -> None:
        if not items:
            para("(none)")
            return
        h.append("<ul style=\"font-family:system-ui,sans-serif;font-size:14px;line-height:1.6\">")
        for item in items:
            t.append(f"  - {item}")
            h.append(f"<li>{esc(item)}</li>")
        h.append("</ul>")

    def link(label: str, url: str) -> None:
        if not url:
            t.append(f"  {label}: (unavailable — the API did not return enough metadata)")
            h.append(f"<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:2px 0\">"
                     f"{esc(label)}: (unavailable)</p>")
            return
        t.append(f"  {label}: {url}")
        h.append(f"<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:2px 0\">"
                 f"{esc(label)}: <a href=\"{esc(url)}\">{esc(url)}</a></p>")

    if test_mode:
        para("*** TEST MESSAGE. Synthetic data. No assessment was run and no findings "
             "register was written. ***")
    t.append(subject)
    h.insert(0, f"<div style=\"max-width:900px\"><h1 style=\"font-family:system-ui,sans-serif;"
                f"font-size:19px\">{esc(subject)}</h1>")

    head(1, "What this is")
    if decision.kind == "blocked":
        para(f"The Security Gate blocked {total_blocking} finding(s) in {repository}. "
             f"The merge or deployment this run gates is already stopped by the required "
             f"check; this mail exists so nobody has to be watching the run to find out.")
    else:
        para(f"The security assessment of {repository} did not produce a usable result. "
             f"This is not a pass. The required check reflects the same outcome.")
    bullets(decision.reasons)

    head(1, "Run")
    para(f"  repository   {repository}", mono=True)
    para(f"  event        {red(meta.get('event', '?'))}", mono=True)
    para(f"  workflow     {red(meta.get('workflow_name', '?'))}", mono=True)
    para(f"  run/attempt  {links.run_id or '?'} attempt {links.attempt}", mono=True)
    para(f"  conclusion   {red(meta.get('conclusion', '?'))}", mono=True)
    para(f"  head commit  {red(meta.get('head_sha', '?'))}", mono=True)
    para(f"  head branch  {red(meta.get('head_branch', '?'))}", mono=True)
    pr = meta.get("pull_request") or {}
    if pr.get("number"):
        para(f"  pull request #{pr['number']} -> {red(pr.get('base_ref', '?'))} "
             f"(title: {red(pr.get('title', ''))[:120]})", mono=True)
    else:
        para("  pull request none associated with this commit", mono=True)
    para(f"  scan time    {red(meta.get('run_started_at', '?'))} UTC (run started)", mono=True)
    para(f"  mailed at    {utcnow().replace(microsecond=0).isoformat()} UTC", mono=True)

    head(2, "Links")
    link("run", links.run)
    link("commit", links.commit)
    if links.pull_request:
        link("pull request", links.pull_request)
    link("artifact (sign-in required)", links.artifact)

    head(1, "Targets assessed")
    if not legs:
        para("None. The assessment produced no verdict document.")
    for leg in legs:
        scope = scope_of(leg, meta)
        if not leg.usable:
            head(2, f"{scope} — VERDICT NOT USED")
            bullets(leg.problems)
            if leg.blocking_count:
                para(f"This rejected document claimed {leg.blocking_count} blocking finding(s). "
                     f"That claim is NOT treated as this run's result; open the run and check.")
            continue

        head(2, f"{scope} — {leg.status}")
        para(f"  target       {red(leg.get('target_name', leg.get('ref', '?')))}", mono=True)
        para(f"  revision     {red(leg.get('revision', '?'))}", mono=True)
        para(f"  verdict      {leg.status} (evaluator exit {leg.get('exit_code', '?')})", mono=True)
        c = leg.counts
        para(f"  blocking     {leg.blocking_count}", mono=True)
        para(f"  tracked      {c.get('tracked', 0)}   excepted {c.get('excepted', 0)}", mono=True)
        para(f"  by severity  {counts_line(c)}", mono=True)
        para(f"  CISA KEV     {c.get('kev', 0)} finding(s) on the known-exploited catalogue",
             mono=True)
        para(f"  policy       {red(leg.get('policy_revision', '?'))}", mono=True)
        digests = leg.get("image_digests", [])
        if isinstance(digests, list) and digests:
            para(f"  image        {red(', '.join(str(d) for d in digests[:3]))}", mono=True)

        intel = leg.get("intelligence", {})
        if isinstance(intel, dict):
            rows = []
            for key in ("osv", "ghsa", "trivy_db", "kev"):
                e = intel.get(key)
                if isinstance(e, dict):
                    rows.append(f"{key}: {red(e.get('status', '?'))} at "
                                f"{red(e.get('retrieved_at', '?'))}"
                                + (f" (rev {red(e.get('upstream_revision'))})"
                                   if e.get("upstream_revision") else ""))
            if rows:
                t.append("  intelligence:")
                h.append("<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:6px 0 0\">"
                         "intelligence:</p>")
                bullets(rows)

        gaps = leg.coverage_gaps
        if gaps:
            t.append("  coverage gaps:")
            h.append("<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:6px 0 0\">"
                     "<strong>coverage gaps:</strong></p>")
            bullets([f"{'REQUIRED ASSESSMENT MISSING' if g.get('hard') else 'narrower coverage'}"
                     f" — {red(g.get('detail', ''))}" for g in gaps])
        else:
            para("  coverage     complete for every required assessment", mono=True)

        errs = leg.get("error_reasons", [])
        if isinstance(errs, list) and errs:
            t.append("  could not be evaluated:")
            h.append("<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:6px 0 0\">"
                     "<strong>could not be evaluated:</strong></p>")
            bullets([red(e) for e in errs[:40]])

        if leg.discrepancies:
            t.append("  metadata discrepancies (API is authoritative):")
            h.append("<p style=\"font-family:system-ui,sans-serif;font-size:14px;margin:6px 0 0\">"
                     "<strong>metadata discrepancies (API is authoritative):</strong></p>")
            bullets([red(d) for d in leg.discrepancies])

        blockers = leg.blocking_findings
        if blockers:
            shown = blockers[:max_findings]
            head(2, f"Blocking findings — {scope} ({len(blockers)})")
            if len(shown) < len(blockers):
                para(f"NOTE: {len(blockers) - len(shown)} further blocking finding(s) are not "
                     f"listed inline because the message would not render. They are all in the "
                     f"attached report and in the artifact. Nothing has been dropped.")
            cols = ["Severity", "ID", "Component", "Installed", "Fix", "Where",
                    "KEV", "History", "Why it blocks"]
            h.append("<div style=\"overflow-x:auto\"><table style=\"border-collapse:collapse;"
                     "font-family:ui-monospace,Menlo,monospace;font-size:12px\"><thead><tr>"
                     + "".join(f"<th style=\"border:1px solid #ccc;padding:4px 7px;"
                               f"text-align:left;background:#f3f3f3\">{esc(c)}</th>"
                               for c in cols)
                     + "</tr></thead><tbody>")
            for f in shown:
                why = ("on the CISA KEV catalogue — exploited in the wild"
                       if f.get("kev") else
                       "secret detected" if f.get("category") == "secret" else
                       f"{red(f.get('severity', '?'))} at or above the blocking threshold")
                cells = [red(f.get("severity", "?")), red(f.get("id", "?")),
                         red(f.get("component") or f.get("location") or "-"),
                         red(f.get("version") or "-"), red(fixed_version_text(f)),
                         red(f.get("location") or "-"),
                         "yes" if f.get("kev") else "no",
                         history_of(leg, f), why]
                t.append("  | " + " | ".join(cells))
                h.append("<tr>" + "".join(
                    f"<td style=\"border:1px solid #ddd;padding:4px 7px;vertical-align:top\">"
                    f"{esc(c)}</td>" for c in cells) + "</tr>")
            h.append("</tbody></table></div>")

            head(2, f"Recommended action — {scope}")
            actions: list[str] = []
            for f in shown:
                fix = fixed_version_text(f)
                comp = red(f.get("component") or f.get("location") or "this component")
                if fix == FIX_UNKNOWN:
                    actions.append(
                        f"{red(f.get('id', '?'))} in {comp}: {FIX_UNKNOWN}. Remove or replace the "
                        f"component, or file a time-boxed, approved exception in "
                        f".github/security/exceptions.yml.")
                else:
                    actions.append(f"{red(f.get('id', '?'))} in {comp}: upgrade to {fix}.")
            bullets(actions)

        reasons = leg.get("blocking_reasons", [])
        if isinstance(reasons, list) and reasons:
            head(2, f"Every reason this target blocked — {scope} ({len(reasons)})")
            bullets([red(r) for r in reasons[:max_findings]])
            if len(reasons) > max_findings:
                para(f"NOTE: {len(reasons) - max_findings} further reason(s) are in the attached "
                     f"report and the artifact. Nothing has been dropped.")

    # ---- attachment -----------------------------------------------------
    attachment = None
    attachment_note = ""
    if test_mode:
        attachment_note = "No report is attached: this is a synthetic test message."
    else:
        chunks = []
        for leg in legs:
            if leg.report_md:
                chunks.append(f"\n\n{'=' * 78}\n{scope_of(leg, meta)}\n{'=' * 78}\n\n"
                              + red.block(leg.report_md))
        if chunks:
            blob = ("Manara security assessment — redacted copy of the gate's own report.\n"
                    f"repository {repository}  run {links.run_id} attempt {links.attempt}\n"
                    + "".join(chunks)).encode("utf-8")
            if len(blob) <= max_attachment_bytes:
                attachment = {
                    "filename": f"security-report-{repository.replace('/', '-')}"
                                f"-{links.run_id}-{links.attempt}.md",
                    "content": base64.b64encode(blob).decode("ascii"),
                }
                attachment_note = (f"The full redacted report is attached "
                                   f"({len(blob)} bytes).")
            else:
                attachment_note = (
                    f"THE FULL REPORT IS NOT ATTACHED. It is {len(blob)} bytes, above this "
                    f"notifier's {max_attachment_bytes} byte limit. It has NOT been truncated "
                    f"and no finding has been dropped from it — the complete report is in the "
                    f"run artifact linked above. Everything summarised in this mail is "
                    f"complete as shown.")
        else:
            attachment_note = ("No Markdown report was found in the artifact, so nothing is "
                               "attached. The counts and findings above come from the verdict "
                               "documents themselves.")

    head(1, "Attachment")
    para(attachment_note)

    head(1, "What a blocked or failed gate does not mean")
    para("Coverage is bounded by the sources in .github/security/sources.yml. A clean scan is "
         "not proof of absence: private, embargoed and unpublished disclosures are outside it "
         "entirely, and retrieval freshness is not publication completeness. Secret values, "
         "credentials, .env contents and personal data are redacted from this message and from "
         "its attachment; open the artifact if you need the unredacted evidence.")
    para("This mail is a notification. It did not decide anything, and no failure to deliver it "
         "can change the Security Gate's result.")

    h.append("</div>")
    return Rendered(subject, "\n".join(t) + "\n", "\n".join(h), attachment, attachment_note)


# ---------------------------------------------------------------------------
# Providers
# ---------------------------------------------------------------------------

class ProviderResult:
    def __init__(self, *, accepted: bool, message_id: str, status: int, detail: str,
                 attempts: list[dict], permanent_failure: bool = False):
        self.accepted = accepted
        self.message_id = message_id
        self.status = status
        self.detail = detail
        self.attempts = attempts
        self.permanent_failure = permanent_failure


class Provider:
    name = "abstract"

    def send(self, request: dict, key: str) -> ProviderResult:  # pragma: no cover
        raise NotImplementedError


class DryRunProvider(Provider):
    """Builds and records the request, and sends nothing. The default everywhere.

    Tests assert against the request this produces. Nothing in the fixture suite
    is allowed to reach a real mailbox, so a test that forgets to pass
    --provider is inert rather than noisy.
    """

    name = "dry-run"

    def send(self, request: dict, key: str) -> ProviderResult:
        digest = hashlib.sha256(key.encode()).hexdigest()[:16]
        return ProviderResult(accepted=True, message_id=f"dry-run-{digest}", status=0,
                              detail="dry run: the request was built and not sent",
                              attempts=[{"attempt": 1, "status": 0, "outcome": "dry-run"}])


class MockProvider(Provider):
    """Replays a scripted sequence of provider responses, for the fixture suite."""

    name = "mock"

    def __init__(self, script: list[dict], record: Path | None = None):
        self.script = list(script)
        self.record = record
        self.calls: list[dict] = []

    def send(self, request: dict, key: str) -> ProviderResult:
        attempts: list[dict] = []
        for n in range(1, len(self.script) + 1):
            step = self.script[n - 1]
            self.calls.append({"attempt": n, "idempotency_key": key, "request": request})
            status = int(step.get("status", 200))
            attempts.append({"attempt": n, "status": status,
                             "outcome": step.get("outcome", "scripted")})
            if 200 <= status < 300:
                if self.record:
                    self.record.write_text(json.dumps(self.calls, indent=2) + "\n")
                return ProviderResult(accepted=True,
                                      message_id=str(step.get("id", "mock-message-id")),
                                      status=status, detail="mock: accepted", attempts=attempts)
            if status in (401, 403):
                if self.record:
                    self.record.write_text(json.dumps(self.calls, indent=2) + "\n")
                return ProviderResult(accepted=False, message_id="", status=status,
                                      detail=f"mock: permanent failure {status}",
                                      attempts=attempts, permanent_failure=True)
        if self.record:
            self.record.write_text(json.dumps(self.calls, indent=2) + "\n")
        return ProviderResult(accepted=False, message_id="", status=attempts[-1]["status"]
                              if attempts else 0,
                              detail="mock: retries exhausted", attempts=attempts)


class ResendProvider(Provider):
    """Resend, over stdlib HTTP. No third-party client, so no supply chain here.

    Bounded exponential backoff on 429, 5xx and timeouts; Retry-After honoured
    when the provider sends one; 4xx other than 429 is permanent and fails the
    job immediately rather than retrying into a rate limit.
    """

    name = "resend"

    def __init__(self, api_key: str, *, timeout: float, retries: int, sleep_scale: float):
        self.api_key = api_key
        self.timeout = timeout
        self.retries = max(1, retries)
        self.sleep_scale = sleep_scale

    def send(self, request: dict, key: str) -> ProviderResult:
        payload = json.dumps(request).encode("utf-8")
        attempts: list[dict] = []
        delay = 2.0
        for n in range(1, self.retries + 1):
            req = urllib.request.Request(
                RESEND_ENDPOINT, data=payload, method="POST",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                    # Resend deduplicates on this for 24 hours. Not permanent —
                    # see the dedup section of docs/security/SECURITY_CI.md.
                    "Idempotency-Key": key,
                    "User-Agent": "manara-security-notify/1",
                })
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    body = resp.read(65536).decode("utf-8", errors="replace")
                    status = resp.status
                    try:
                        message_id = str((json.loads(body) or {}).get("id", ""))
                    except json.JSONDecodeError:
                        message_id = ""
                    attempts.append({"attempt": n, "status": status, "outcome": "accepted"})
                    return ProviderResult(accepted=True, message_id=message_id, status=status,
                                          detail="the provider accepted the message for "
                                                 "delivery; that is not proof of delivery",
                                          attempts=attempts)
            except urllib.error.HTTPError as exc:
                status = exc.code
                detail = exc.read(4096).decode("utf-8", errors="replace")[:400]
                retry_after = exc.headers.get("Retry-After") if exc.headers else None
                permanent = 400 <= status < 500 and status != 429
                attempts.append({"attempt": n, "status": status,
                                 "outcome": "permanent" if permanent else "retryable",
                                 "detail": detail[:200]})
                if permanent:
                    return ProviderResult(accepted=False, message_id="", status=status,
                                          detail=f"HTTP {status}: {detail}", attempts=attempts,
                                          permanent_failure=True)
                wait = _retry_after_seconds(retry_after, delay)
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                attempts.append({"attempt": n, "status": 0, "outcome": "transport",
                                 "detail": str(exc)[:200]})
                wait = delay
            if n == self.retries:
                break
            time.sleep(min(wait, 60.0) * self.sleep_scale)
            delay = min(delay * 2, 60.0)
        return ProviderResult(accepted=False, message_id="",
                              status=attempts[-1]["status"] if attempts else 0,
                              detail="every attempt failed", attempts=attempts)


def _retry_after_seconds(header: str | None, fallback: float) -> float:
    if not header:
        return fallback
    try:
        return max(0.0, float(header.strip()))
    except ValueError:
        return fallback


# ---------------------------------------------------------------------------
# Idempotency
# ---------------------------------------------------------------------------

def idempotency_key(repository: str, run_id: Any, attempt: Any, scope_keys: list[str],
                    kind: str) -> str:
    """Stable for one run/attempt/scope set, and different for anything else.

    Deliberately NOT keyed on the findings. Two runs with identical findings are
    two events, and a scheduled assessment that keeps finding the same blocker
    every night is exactly what somebody needs to keep seeing. Suppressing that
    is how a blocker becomes background noise and then becomes permanent.
    """
    material = "|".join([
        "manara-security-notify/1", str(repository), str(run_id), str(attempt), kind,
        "||".join(sorted(scope_keys)),
    ])
    return "manara-sec-" + hashlib.sha256(material.encode()).hexdigest()[:40]


# ---------------------------------------------------------------------------
# Synthetic fixture for the manual test path
# ---------------------------------------------------------------------------

def synthetic_verdict(repository: str) -> dict:
    """Entirely invented. Never derived from a real assessment.

    Used only by --mode test. It writes no register, touches no gate, and its
    subject carries TEST so it can never be mistaken for a real alert.
    """
    now = utcnow().isoformat()
    return {
        "schema_version": 1, "generated_at": now,
        "repository": repository, "event": "workflow_dispatch",
        "ref": "synthetic-test", "revision": "0" * 40,
        "target_slug": "synthetic", "target_name": "synthetic test target",
        "run_id": None, "run_attempt": None, "run_url": None, "workflow": "Security",
        "pr_number": None, "base_ref": None,
        "image_digests": ["example.invalid/synthetic@sha256:" + "0" * 64],
        "policy_revision": "sha256:" + "0" * 64,
        "intelligence": {
            "osv": {"source": "osv", "status": "ok", "retrieved_at": now,
                    "upstream_revision": "synthetic"},
            "ghsa": {"source": "ghsa", "status": "ok", "retrieved_at": now,
                     "upstream_revision": "synthetic"},
            "trivy_db": {"source": "trivy-db", "status": "ok", "retrieved_at": now,
                         "upstream_revision": "synthetic"},
            "kev": {"source": "cisa-kev", "status": "ok", "retrieved_at": now,
                    "upstream_revision": "synthetic", "entry_count": 0},
            "advisory": {},
        },
        "scanners": [], "coverage_complete": True, "coverage_gaps": [],
        "status": "BLOCKED", "exit_code": 1,
        "counts": {"blocking": 2, "tracked": 1, "excepted": 0,
                   "by_severity": {"CRITICAL": 1, "HIGH": 1, "MEDIUM": 1,
                                   "LOW": 0, "INFO": 0},
                   "kev": 1},
        "findings": [
            {"id": "CVE-0000-00001", "key": "CVE-0000-00001|synthetic-lib|1.0.0",
             "severity": "CRITICAL", "category": "dependency", "detector": "synthetic",
             "title": "Synthetic finding — this is a delivery test",
             "component": "example.invalid:synthetic-lib", "version": "1.0.0",
             "fixed_version": "1.0.1", "all_fixed_versions": ["1.0.1"],
             "location": "pom.xml", "kev": True, "status": "blocking", "blocking": True},
            {"id": "CVE-0000-00002", "key": "CVE-0000-00002|synthetic-nofix|2.3.4",
             "severity": "HIGH", "category": "dependency", "detector": "synthetic",
             "title": "Synthetic finding with no reachable upgrade",
             "component": "example.invalid:synthetic-nofix", "version": "2.3.4",
             "fixed_version": "", "all_fixed_versions": [],
             "location": "package.json", "kev": False, "status": "blocking", "blocking": True},
        ],
        "blocking_reasons": [
            "CRITICAL CVE-0000-00001 in example.invalid:synthetic-lib 1.0.0 fixed in 1.0.1.",
            "HIGH CVE-0000-00002 in example.invalid:synthetic-nofix 2.3.4 (no fix available).",
        ],
        "error_reasons": [], "notes": ["synthetic fixture"],
    }


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def build_request(rendered: Rendered, sender: str, recipients: list[str]) -> dict:
    req = {
        "from": sender,
        "to": recipients,
        "subject": rendered.subject,
        "text": rendered.text,
        "html": rendered.html,
    }
    if rendered.attachment:
        req["attachments"] = [rendered.attachment]
    return req


def summarise(result: dict, rendered: Rendered | None) -> str:
    lines = ["### Security notification", ""]
    lines.append(f"- decision: **{result['decision']}**")
    lines.append(f"- mail sent: **{'yes' if result['sent'] else 'no'}**")
    lines.append(f"- provider: `{result['provider']}`")
    lines.append(f"- idempotency key: `{result['idempotency_key']}`")
    if result.get("message_id"):
        lines.append(f"- provider message id: `{result['message_id']}`")
    lines.append(f"- provider outcome: {result.get('provider_detail', '—')}")
    lines.append("- delivery: **not confirmed**. The provider accepting a message means it was "
                 "queued, not that it reached the mailbox. Delivery is only observable in the "
                 "provider dashboard.")
    if rendered:
        lines.append(f"- subject: `{rendered.subject}`")
        lines.append(f"- attachment: {rendered.attachment_note}")
    if result.get("reasons"):
        lines += ["", "**Why:**", ""] + [f"- {r}" for r in result["reasons"]]
    if result.get("discrepancies"):
        lines += ["", "**Metadata discrepancies (the API is authoritative):**", ""]
        lines += [f"- {d}" for d in result["discrepancies"]]
    if result.get("errors"):
        lines += ["", "**Failures:**", ""] + [f"- {e}" for e in result["errors"]]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-metadata", required=True,
                    help="JSON resolved from the GitHub API by the workflow")
    ap.add_argument("--artifact-zip", default="")
    ap.add_argument("--artifact-dir", default="")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--mode", choices=("report", "test"), default="report")
    ap.add_argument("--provider", choices=("dry-run", "resend", "mock"), default="dry-run",
                    help="dry-run is the default so a misconfigured caller sends nothing")
    ap.add_argument("--mock-script", default="", help="JSON list of scripted provider responses")
    ap.add_argument("--to", required=True)
    ap.add_argument("--from", dest="sender", required=True)
    ap.add_argument("--api-key-env", default="SECURITY_ALERT_RESEND_API_KEY",
                    help="NAME of the variable holding the key; the value never appears in argv")
    ap.add_argument("--max-artifact-bytes", type=int, default=64 * 1024 * 1024)
    ap.add_argument("--max-artifact-entries", type=int, default=10000)
    ap.add_argument("--max-json-bytes", type=int, default=16 * 1024 * 1024)
    ap.add_argument("--max-legs", type=int, default=32)
    ap.add_argument("--max-findings", type=int, default=250)
    ap.add_argument("--max-attachment-bytes", type=int, default=DEFAULT_MAX_ATTACHMENT_BYTES)
    ap.add_argument("--retries", type=int, default=5)
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--sleep-scale", type=float, default=1.0,
                    help="multiplier on backoff waits; 0 in the fixture suite")
    args = ap.parse_args()

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    errors: list[str] = []

    try:
        meta = json.loads(Path(args.run_metadata).read_text())
        if not isinstance(meta, dict):
            raise ValueError("run metadata is not a JSON object")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        # Nothing can be built without this, and it comes from the API rather
        # than from the assessed code, so a failure here is a real defect.
        print(f"::error title=Security notification::Run metadata is unusable: {exc}",
              file=sys.stderr)
        (out / "result.json").write_text(json.dumps(
            {"decision": "error", "sent": False, "provider": args.provider,
             "idempotency_key": "", "errors": [f"run metadata unusable: {exc}"]}, indent=2) + "\n")
        return 1

    repository = str(meta.get("repository", ""))
    if not REPO_RE.match(repository):
        print(f"::error title=Security notification::repository {repository!r} is not "
              f"owner/name shaped.", file=sys.stderr)
        return 1
    # The workflow checks this too. Checked again here because it is the one
    # assertion that makes 'this artifact belongs to this repository' true.
    event_repo = str(meta.get("event_repository", repository))
    if event_repo != repository:
        print(f"::error title=Security notification::the triggering run belongs to "
              f"{event_repo!r}, not to {repository!r}. Refusing to report on it.",
              file=sys.stderr)
        return 1

    red = Redactor(keep=[args.to, args.sender])
    links = Links(meta)
    test_mode = args.mode == "test"

    artifact_problem: str | None = None
    legs: list[Leg] = []
    if test_mode:
        leg = Leg("synthetic")
        leg.doc = synthetic_verdict(repository)
        leg.usable = True
        legs = [leg]
        decision = Decision("blocked", ["synthetic test message; no assessment was run"])
    else:
        root = Path(args.artifact_dir) if args.artifact_dir else out / "artifact"
        try:
            if args.artifact_zip:
                safe_extract(Path(args.artifact_zip), root,
                             max_total_bytes=args.max_artifact_bytes,
                             max_entries=args.max_artifact_entries)
            elif not args.artifact_dir:
                raise ArtifactError("no artifact was supplied to this notifier")
            if not root.is_dir():
                raise ArtifactError(f"the artifact directory {root} does not exist")
            legs = load_legs(root, meta, max_json_bytes=args.max_json_bytes,
                             max_legs=args.max_legs)
            if not legs:
                artifact_problem = ("the artifact contains no verdict.json, so this run's "
                                    "result cannot be read")
        except ArtifactError as exc:
            # Never fatal here. An unusable artifact is precisely the condition
            # the ERROR mail exists to report; failing out would make the
            # notifier silent in the case it matters most.
            artifact_problem = f"the assessment artifact could not be used: {exc}"
        decision = decide(legs, meta, artifact_problem)

    rendered: Rendered | None = None
    result: dict = {
        "decision": decision.kind,
        "reasons": decision.reasons,
        "sent": False,
        "provider": args.provider,
        "idempotency_key": "",
        "message_id": "",
        "repository": repository,
        "run_id": meta.get("run_id"),
        "run_attempt": meta.get("run_attempt"),
        "scopes": [scope_of(leg, meta) for leg in legs if leg.usable],
        "usable_legs": sum(1 for leg in legs if leg.usable),
        "rejected_legs": [{"source": leg.source, "problems": leg.problems}
                          for leg in legs if not leg.usable],
        "discrepancies": [d for leg in legs for d in leg.discrepancies],
        "blocking_total": sum(leg.blocking_count for leg in legs if leg.usable),
        "errors": errors,
    }

    if decision.kind == "none":
        result["provider_detail"] = "no message was required"
        (out / "result.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
        (out / "summary.md").write_text(summarise(result, None))
        _emit_summary(out / "summary.md")
        print("Security notification: every target passed and the run succeeded; no mail sent.")
        return 0

    rendered = render(decision, legs, meta, links, red, repository=repository,
                      max_findings=args.max_findings,
                      max_attachment_bytes=args.max_attachment_bytes, test_mode=test_mode)
    key = idempotency_key(repository, meta.get("run_id"), meta.get("run_attempt"),
                          [scope_key_of(leg) for leg in legs] or ["no-target"],
                          "test" if test_mode else decision.kind)
    result["idempotency_key"] = key
    request = build_request(rendered, args.sender, [args.to])

    (out / "body.txt").write_text(rendered.text)
    (out / "body.html").write_text(rendered.html)
    redacted_request = dict(request)
    if "attachments" in redacted_request:
        redacted_request["attachments"] = [
            {"filename": a["filename"], "content_bytes": len(a["content"])}
            for a in request["attachments"]]
    (out / "request.json").write_text(json.dumps(
        {"idempotency_key": key, "endpoint": RESEND_ENDPOINT, "request": redacted_request},
        indent=2) + "\n")

    provider: Provider
    if args.provider == "resend":
        api_key = os.environ.get(args.api_key_env, "")
        if not api_key:
            msg = (f"the mail credential is not configured: environment variable "
                   f"{args.api_key_env} is empty. The report was built but could not be "
                   f"sent. Set the repository secret and re-run this workflow. The security "
                   f"verdict is unaffected.")
            errors.append(msg)
            result["provider_detail"] = msg
            (out / "result.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
            (out / "summary.md").write_text(summarise(result, rendered))
            _emit_summary(out / "summary.md")
            print(f"::error title=Security notification::{msg}", file=sys.stderr)
            return 1
        provider = ResendProvider(api_key, timeout=args.timeout, retries=args.retries,
                                  sleep_scale=args.sleep_scale)
    elif args.provider == "mock":
        try:
            script = json.loads(Path(args.mock_script).read_text())
        except (OSError, json.JSONDecodeError) as exc:
            print(f"::error::--provider mock needs a readable --mock-script: {exc}",
                  file=sys.stderr)
            return 1
        provider = MockProvider(script, record=out / "provider-calls.json")
    else:
        provider = DryRunProvider()

    sent = provider.send(request, key)
    result["sent"] = sent.accepted
    result["message_id"] = sent.message_id
    result["provider_status"] = sent.status
    result["provider_detail"] = sent.detail
    result["attempts"] = sent.attempts
    result["subject"] = rendered.subject
    result["attachment_note"] = rendered.attachment_note

    if not sent.accepted:
        errors.append(
            f"the notification was NOT sent ({sent.detail}). The security verdict for this run "
            f"is unchanged and still authoritative — read it in the run linked in the job log. "
            f"Fix the mail configuration and re-run this workflow; re-running with the same "
            f"run id, attempt and scope reuses idempotency key {key}, so a message the provider "
            f"already accepted within the last 24 hours will not be duplicated.")
        result["errors"] = errors
        (out / "result.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
        (out / "summary.md").write_text(summarise(result, rendered))
        _emit_summary(out / "summary.md")
        print(f"::error title=Security notification::{errors[-1]}", file=sys.stderr)
        return 1

    result["errors"] = errors
    (out / "result.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
    (out / "summary.md").write_text(summarise(result, rendered))
    _emit_summary(out / "summary.md")
    print(f"Security notification: {decision.kind} — provider accepted "
          f"(id {sent.message_id or 'n/a'}, key {key}). Acceptance is not delivery.")
    return 0


def _emit_summary(path: Path) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target and path.is_file():
        try:
            with open(target, "a", encoding="utf-8") as fh:
                fh.write(path.read_text())
        except OSError:  # pragma: no cover - a summary failure is not a mail failure
            pass


if __name__ == "__main__":
    sys.exit(main())
