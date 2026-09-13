#!/usr/bin/env python3
"""Decide whether the Security Gate passes, and say why.

This is the only place the pass/fail judgement is made. The workflow YAML
collects reports and reports job outcomes; it never decides anything. Keeping
the decision in one reviewable file is what stops the enforcement from drifting
away from .github/security/policy.yml.

Inputs (all produced by .github/workflows/security.yml):

  --reports-dir DIR     one file per scanner, named "<report-id>.<format>.json";
                        plus status/<report-id>.status holding the producing
                        job's result word (success|failure|cancelled|skipped).
  --manifest FILE       intelligence-manifest.json written by the refresh step.
  --event NAME          the GitHub event name driving this run.
  --ref NAME            branch (or PR head ref) being assessed.
  --revision SHA        the exact commit assessed. Findings are recorded against
                        this, because "fixed" is a property of a revision.

Outputs:

  findings.json         merged register: new findings added, existing findings
                        updated in place, first_seen preserved, resolved findings
                        cleared ONLY for the revision that no longer contains them.
  report.md             human-readable summary for the job summary and the PR.
  verdict.json          the same decision, machine-readable and versioned, for
                        consumers that must not scrape Markdown — today that is
                        .github/security/notify.py. See "The verdict" below.
  exit code             0 = pass, 1 = blocked, 2 = the gate could not be evaluated.

Exit 2 matters: "I could not assess this" is not "this is fine". Both are
non-zero, so both block, but they read differently in the log and in the report.

THE VERDICT IS A DESCRIPTION OF THE DECISION, NEVER AN INPUT TO IT.

verdict.json records what was decided and on what evidence. Nothing reads it
back into this program, and adding it changed no exit code and no pass/fail
rule. Its `status` field is derived from the exit code, not the other way round:

    exit 0 -> "PASS"      exit 1 -> "BLOCKED"      exit 2 -> "ERROR"

It is written on EVERY path, including the paths that fail before a policy has
even been read. A run that could not be evaluated still produces a verdict
saying so, because a notifier that receives nothing cannot tell "clean" from
"the evaluator died", and those are the two cases it most needs to separate.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - the workflow pins and installs PyYAML
    print("::error::PyYAML is not installed; the gate cannot read its policy.", file=sys.stderr)
    sys.exit(2)


SEVERITY_ORDER = ["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"]

# Bumped only when a field is removed or its meaning changes. Consumers refuse a
# document whose schema_version they do not know, so adding a field is safe and
# taking one away is not.
VERDICT_SCHEMA_VERSION = 1

# Exit code -> verdict word. One table, so the two can never drift.
STATUS_BY_EXIT = {0: "PASS", 1: "BLOCKED", 2: "ERROR"}


def sev_rank(sev: str) -> int:
    try:
        return SEVERITY_ORDER.index(sev.upper())
    except ValueError:
        return SEVERITY_ORDER.index("MEDIUM")


def utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def parse_ts(value: Any) -> dt.datetime | None:
    """Parse the several timestamp shapes the upstream feeds use."""
    if not value:
        return None
    if isinstance(value, (int, float)):
        return dt.datetime.fromtimestamp(value, dt.timezone.utc)
    text = str(value).strip()
    # CISA prints fractional seconds well past microsecond precision; Python's
    # fromisoformat rejects that, so the fraction is truncated to six digits.
    text = re.sub(r"(\.\d{6})\d+", r"\1", text)
    text = text.replace("Z", "+00:00")
    try:
        parsed = dt.datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


# ---------------------------------------------------------------------------
# Severity normalisation
# ---------------------------------------------------------------------------

CVSS_METRIC = re.compile(r"\b(AV|AC|PR|UI|S|C|I|A):([NALPHURCX])\b")


def cvss31_base_score(vector: str) -> float | None:
    """Compute a CVSS v3.1 base score from a vector string.

    The tools hand us vectors far more often than numbers, and a policy that
    says "block at HIGH" has to turn a vector into a word somehow. Doing the
    arithmetic here — rather than pattern-matching on the vector text — is what
    makes the mapping in policy.yml honest and auditable.
    """
    if not vector or "CVSS:3" not in vector:
        return None
    m = dict(CVSS_METRIC.findall(vector))
    try:
        av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2}[m["AV"]]
        ac = {"L": 0.77, "H": 0.44}[m["AC"]]
        ui = {"N": 0.85, "R": 0.62}[m["UI"]]
        scope_changed = m["S"] == "C"
        pr = {"N": 0.85, "L": 0.68 if scope_changed else 0.62, "H": 0.50 if scope_changed else 0.27}[m["PR"]]
        cia = {"H": 0.56, "L": 0.22, "N": 0.0}
        conf, integ, avail = cia[m["C"]], cia[m["I"]], cia[m["A"]]
    except KeyError:
        return None

    iss = 1 - ((1 - conf) * (1 - integ) * (1 - avail))
    if scope_changed:
        impact = 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02) ** 15
    else:
        impact = 6.42 * iss
    if impact <= 0:
        return 0.0
    exploitability = 8.22 * av * ac * pr * ui
    raw = min((1.08 if scope_changed else 1.0) * (impact + exploitability), 10.0)
    # CVSS rounds UP to one decimal place.
    return float(int(raw * 10 + 0.99999)) / 10


def score_to_word(score: float) -> str:
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    if score > 0.0:
        return "LOW"
    return "INFO"


class Normaliser:
    """Applies policy.yml's severity_mapping. No tool label reaches the policy raw."""

    def __init__(self, mapping: dict):
        self.mapping = mapping or {}

    def normalise(self, tool: str, label: str | None, vector: str | None = None,
                  number: float | None = None) -> tuple[str, str]:
        """Return (normalised_severity, how_it_was_derived)."""
        table = self.mapping.get(tool, {}) or {}

        if table.get("_all"):
            return table["_all"], f"{tool}: all findings fixed at {table['_all']} by policy"

        if table.get("_primary") == "security_severity_number" and number is not None:
            return score_to_word(number), f"{tool}: security-severity {number}"

        if label:
            key = str(label).upper()
            if key in {k.upper() for k in table if not k.startswith("_")}:
                for k, v in table.items():
                    if not k.startswith("_") and k.upper() == key:
                        return v, f"{tool}: label {label} -> {v}"

        if table.get("_fallback") == "cvss_vector" and vector:
            score = cvss31_base_score(vector)
            if score is not None:
                return score_to_word(score), f"{tool}: CVSS {score} from vector"

        if label:
            key = str(label).upper()
            if key in SEVERITY_ORDER:
                return key, f"{tool}: label {label} used directly"

        return "MEDIUM", f"{tool}: no severity available, defaulted to MEDIUM (never dropped)"


# ---------------------------------------------------------------------------
# Findings
# ---------------------------------------------------------------------------

ALIAS_PREFIXES = ("CVE-", "GHSA-", "OSV-", "BIT-", "GO-", "PYSEC-", "RUSTSEC-", "DSA-", "USN-", "ALSA-")


def relativise(path: str, workspace: str) -> str:
    """Strip the checkout prefix so a finding's location is stable.

    Scanners print absolute paths, which differ between a laptop and a runner
    (/Users/... vs /home/runner/work/...). Left alone they become part of the
    finding key, so the same finding would be recorded as a new one every time
    it was seen from a different checkout.
    """
    if not path:
        return path
    text = str(path)
    if workspace:
        ws = workspace.rstrip("/") + "/"
        if text.startswith(ws):
            text = text[len(ws):]
    return text


def same_package(a: str, b: str) -> bool:
    """Compare package coordinates that the tools spell differently.

    A CycloneDX SBOM splits Maven coordinates into `group` and `name`, so
    osv-scanner reading the SBOM reports `tomcat-embed-core`, while the same
    tool reading pom.xml reports `org.apache.tomcat.embed:tomcat-embed-core` —
    and OSV's own advisory records use the fully qualified form. Comparing those
    as plain strings silently finds no match, which loses the affected ranges and
    therefore the fixed version. Matching on the artifact segment, and requiring
    the group to agree only when both sides carry one, keeps them together.
    """
    if not a or not b:
        return False
    a, b = a.strip().lower(), b.strip().lower()
    if a == b:
        return True
    a_group, _, a_art = a.rpartition(":")
    b_group, _, b_art = b.rpartition(":")
    if a_art != b_art:
        return False
    return not (a_group and b_group) or a_group == b_group


def version_parts(version: str) -> list[int]:
    return [int(p) for p in re.findall(r"\d+", version or "")] or [0]


def select_fixed_version(installed: str, candidates: list[str]) -> str:
    """Pick the fix that is actually reachable from the installed version.

    An advisory usually lists a fix on every maintained branch. For
    tomcat-embed-core 11.0.24 that is "11.0.25, 10.1.58, 9.0.121" — and telling
    a Spring Boot 4 service to move to 9.0.121 is telling it to downgrade three
    major versions. The upgrade on the branch already in use is the one that is
    both correct and actionable, so it is the one reported.

    Every candidate is still kept in `all_fixed_versions` on the record, because
    a service pinned to an older branch needs to see its own fix too.
    """
    clean = [c.strip() for c in candidates if c and c.strip()]
    if not clean:
        return ""

    # NO SINGLE-CANDIDATE SHORTCUT.
    #
    # There used to be one — `if len(clean) == 1: return clean[0]` — on the
    # reasoning that one candidate needs no choosing. It does, and this is
    # exactly how a downgrade got printed after the multi-candidate case had
    # already been fixed:
    #
    #   grpc v1.83.1, advisory GHSA-2v4p-qf9q-27wj, scanner reported the single
    #   fix "1.82.2" -> the gate printed "fixed in 1.82.2", a DOWNGRADE.
    #
    # The advisory does fix that branch in 1.83.2; the scanner simply reported
    # one of the three fixed versions. One candidate that is older than what is
    # installed is not an upgrade, however few candidates there are.

    # The lowest fix at or above what is installed. Simply that.
    #
    # An earlier version of this compared only the FIRST version component to
    # decide which release branch a fix belonged to. That works for Tomcat,
    # where the branch is 11.x, and is wrong for anything whose branch is
    # major.minor: for Go stdlib 1.26.3 with fixes "1.25.11, 1.26.4" it treated
    # both as the same branch and reported 1.25.11 — telling the reader to
    # DOWNGRADE, which is worse than saying nothing at all.
    #
    # Ordering by the numeric components and taking the smallest one that is not
    # below the installed version needs no notion of a branch and cannot produce
    # a downgrade.
    here = version_parts(installed)
    above = [c for c in clean if version_parts(c) >= here]
    if above:
        return min(above, key=version_parts)

    # Every listed fix is older than what is installed, so NONE of them is an
    # upgrade and there is nothing here to recommend.
    #
    # Empty is returned rather than the highest candidate, and that is a
    # deliberate reversal of what this used to do. Naming a lower version reads
    # as advice — "fixed in 1.82.2" next to an installed 1.83.1 invites someone
    # to go and install 1.82.2, which would take the service backwards and still
    # leave it vulnerable. The report renders empty as "no verified upgrade
    # available", which is both true and actionable, and the candidates remain
    # on the record in all_fixed_versions for whoever is on the branch they fix.
    #
    # This does NOT change whether the finding blocks. It is still a blocking
    # finding with no available fix, which is the policy's decision to make, not
    # this function's.
    return ""


WORKSPACE = ""


class Finding:
    def __init__(self, *, category: str, detector: str, title: str, severity: str,
                 severity_reason: str, aliases: list[str], component: str = "",
                 version: str = "", fixed_version: str = "", location: str = "",
                 references: list[str] | None = None, raw_severity: str = "",
                 all_fixed_versions: list[str] | None = None):
        self.category = category            # dependency | sast | secret | config | image
        self.detector = detector
        self.title = title
        self.severity = severity
        self.severity_reason = severity_reason
        self.raw_severity = raw_severity
        self.aliases = sorted({a for a in aliases if a})
        self.component = component
        self.version = version
        self.fixed_version = fixed_version
        self.all_fixed_versions = sorted({v for v in (all_fixed_versions or []) if v})
        self.location = relativise(location, WORKSPACE)
        self.references = references or []
        self.kev = False
        self.status = "affected"
        self.exception: dict | None = None

    @property
    def canonical_id(self) -> str:
        """Prefer CVE, then GHSA, then any other advisory id, then a content hash.

        Deduplication hangs off this, so it must be stable across runs and across
        tools that report the same issue under different names.
        """
        for prefix in ("CVE-", "GHSA-", "OSV-"):
            for a in self.aliases:
                if a.startswith(prefix):
                    return a
        if self.aliases:
            return self.aliases[0]
        digest = hashlib.sha256(
            f"{self.category}|{self.detector}|{self.title}|{self.location}".encode()
        ).hexdigest()[:12]
        return f"MANARA-{self.category.upper()}-{digest}"

    @property
    def artifact(self) -> str:
        """The package name with any ecosystem qualifier stripped.

        Tools disagree about how much of a coordinate to print. osv-scanner and
        Trivy's pom.xml analyser both say
        `org.apache.tomcat.embed:tomcat-embed-core`, while Trivy's jar analyser
        — looking at the same library inside the built artefact — says just
        `tomcat-embed-core`. Without this, one library with one CVE is reported
        as two findings, and the register accumulates a duplicate every run.
        """
        name = self.component.rsplit(":", 1)[-1]
        return name.strip().lower()

    @property
    def key(self) -> str:
        """Identity for the register.

        For a dependency, identity is the advisory plus the package and version
        — NOT the file it was noticed in. The same CVE in the same library is
        one finding whether it was spotted in pom.xml, in the SBOM or inside the
        packaged jar.

        For everything else the location IS the identity: the same misconfig
        rule on two different lines is two findings to fix.
        """
        if self.category in ("dependency", "image"):
            return f"{self.canonical_id}|{self.artifact}|{self.version}"
        return f"{self.canonical_id}|{self.component}|{self.version}|{self.location}"

    def to_dict(self) -> dict:
        return {
            "id": self.canonical_id,
            "key": self.key,
            "aliases": self.aliases,
            "category": self.category,
            "detector": self.detector,
            "title": self.title,
            "severity": self.severity,
            "severity_derivation": self.severity_reason,
            "raw_severity": self.raw_severity,
            "component": self.component,
            "version": self.version,
            "fixed_version": self.fixed_version,
            "all_fixed_versions": self.all_fixed_versions,
            "location": self.location,
            "references": self.references[:8],
            "kev": self.kev,
            "status": self.status,
            "exception": self.exception,
        }


# ---------------------------------------------------------------------------
# Parsers — one per report format
# ---------------------------------------------------------------------------

def parse_osv(doc: dict, norm: Normaliser) -> list[Finding]:
    out: list[Finding] = []
    for result in doc.get("results", []) or []:
        source = (result.get("source", {}) or {}).get("path", "")
        for pkg in result.get("packages", []) or []:
            info = pkg.get("package", {}) or {}
            name, version = info.get("name", ""), info.get("version", "")
            for vuln in pkg.get("vulnerabilities", []) or []:
                label = (vuln.get("database_specific", {}) or {}).get("severity")
                vector = ""
                for s in vuln.get("severity", []) or []:
                    if str(s.get("type", "")).startswith("CVSS"):
                        vector = s.get("score", "")
                severity, why = norm.normalise("osv-scanner", label, vector)
                candidates: list[str] = []
                for aff in vuln.get("affected", []) or []:
                    if not same_package((aff.get("package", {}) or {}).get("name", ""), name):
                        continue
                    for rng in aff.get("ranges", []) or []:
                        for ev in rng.get("events", []) or []:
                            if ev.get("fixed"):
                                candidates.append(ev["fixed"])
                fixed = select_fixed_version(version, candidates)
                aliases = [vuln.get("id", "")] + list(vuln.get("aliases", []) or [])
                out.append(Finding(
                    category="dependency", detector="osv-scanner",
                    title=(vuln.get("summary") or vuln.get("id", "")).strip(),
                    severity=severity, severity_reason=why, raw_severity=label or vector,
                    aliases=aliases, component=name, version=version,
                    fixed_version=fixed, all_fixed_versions=candidates, location=source,
                    references=[r.get("url", "") for r in (vuln.get("references") or [])],
                ))
    return out


def parse_trivy(doc: dict, norm: Normaliser, default_category: str) -> list[Finding]:
    out: list[Finding] = []
    for result in doc.get("Results", []) or []:
        target = result.get("Target", "")
        for v in result.get("Vulnerabilities", []) or []:
            vector = ""
            for src in (v.get("CVSS") or {}).values():
                if isinstance(src, dict) and src.get("V3Vector"):
                    vector = src["V3Vector"]
                    break
            severity, why = norm.normalise("trivy", v.get("Severity"), vector)
            out.append(Finding(
                category=default_category, detector="trivy",
                title=(v.get("Title") or v.get("Description") or v.get("VulnerabilityID", ""))[:200],
                severity=severity, severity_reason=why, raw_severity=v.get("Severity", ""),
                aliases=[v.get("VulnerabilityID", "")] + list(v.get("References", []) and [] or []),
                component=v.get("PkgName", ""), version=v.get("InstalledVersion", ""),
                fixed_version=select_fixed_version(
                    v.get("InstalledVersion", ""), str(v.get("FixedVersion", "")).split(",")),
                all_fixed_versions=str(v.get("FixedVersion", "")).split(","),
                location=target,
                references=list(v.get("References", []) or []),
            ))
        for m in result.get("Misconfigurations", []) or []:
            severity, why = norm.normalise("trivy", m.get("Severity"))
            loc = target
            lines = (m.get("CauseMetadata") or {}).get("StartLine")
            if lines:
                loc = f"{target}:{lines}"
            out.append(Finding(
                category="config", detector="trivy-config",
                title=f"{m.get('ID', '')} {m.get('Title', '')}".strip(),
                severity=severity, severity_reason=why, raw_severity=m.get("Severity", ""),
                aliases=[m.get("ID", "")], location=loc,
                references=list(m.get("References", []) or []),
            ))
        for s in result.get("Secrets", []) or []:
            severity, why = norm.normalise("trivy", s.get("Severity"))
            out.append(Finding(
                category="secret", detector="trivy-secret",
                # Never the matched value: Trivy's Match echoes the secret itself.
                title=f"{s.get('RuleID', '')} {s.get('Title', '')}".strip(),
                severity=severity, severity_reason=why, raw_severity=s.get("Severity", ""),
                aliases=[s.get("RuleID", "")],
                location=f"{target}:{s.get('StartLine', '')}",
            ))
    return out


def parse_gitleaks(doc: Any, norm: Normaliser) -> list[Finding]:
    out: list[Finding] = []
    for f in doc or []:
        severity, why = norm.normalise("gitleaks", None)
        out.append(Finding(
            category="secret", detector="gitleaks",
            # RuleID and location only. The Secret and Match fields are never
            # copied into the report or the register.
            title=f"Secret matched rule {f.get('RuleID', 'unknown')}",
            severity=severity, severity_reason=why, raw_severity="",
            aliases=[f"GITLEAKS-{f.get('RuleID', 'unknown')}"],
            location=f"{f.get('File', '')}:{f.get('StartLine', '')}@{str(f.get('Commit', ''))[:12]}",
        ))
    return out


def parse_sarif(doc: dict, norm: Normaliser, detector: str) -> list[Finding]:
    out: list[Finding] = []
    for run in doc.get("runs", []) or []:
        tool = (((run.get("tool") or {}).get("driver")) or {})
        rules = {r.get("id"): r for r in (tool.get("rules") or [])}
        for res in run.get("results", []) or []:
            rule_id = res.get("ruleId", "")
            rule = rules.get(rule_id, {}) or {}
            props = rule.get("properties", {}) or {}
            number = None
            if props.get("security-severity"):
                try:
                    number = float(props["security-severity"])
                except (TypeError, ValueError):
                    number = None
            # A SARIF rule with no security-severity is not a security rule;
            # CodeQL's quality queries land here and must not block a merge.
            if number is None and "security" not in " ".join(
                str(t) for t in (props.get("tags") or [])
            ).lower():
                continue
            severity, why = norm.normalise("sarif", res.get("level") or rule.get("defaultConfiguration", {}).get("level"), number=number)
            loc = ""
            for l in res.get("locations", []) or []:
                phys = (l.get("physicalLocation") or {})
                uri = ((phys.get("artifactLocation") or {}).get("uri")) or ""
                line = ((phys.get("region") or {}).get("startLine")) or ""
                loc = f"{uri}:{line}"
                break
            text = ((res.get("message") or {}).get("text")) or rule_id
            out.append(Finding(
                category="sast", detector=detector, title=text[:200],
                severity=severity, severity_reason=why,
                raw_severity=str(props.get("security-severity", res.get("level", ""))),
                aliases=[f"{detector.upper()}-{rule_id}"], location=loc,
                references=[(rule.get("helpUri") or "")],
            ))
    return out


def parse_dependency_review(doc: Any, norm: Normaliser) -> list[Finding]:
    """actions/dependency-review-action --comment-summary / JSON output.

    The action fails the job itself on a policy hit; this parse exists so the
    findings still reach the register and the report rather than living only in
    that job's log.
    """
    out: list[Finding] = []
    items = doc if isinstance(doc, list) else (doc.get("vulnerabilities") or [])
    for v in items or []:
        if not isinstance(v, dict):
            continue
        adv = v.get("advisory") or v
        label = adv.get("severity") or v.get("severity")
        severity, why = norm.normalise("osv-scanner", (label or "").upper())
        out.append(Finding(
            category="dependency", detector="dependency-review",
            title=(adv.get("summary") or adv.get("advisory_summary") or "")[:200],
            severity=severity, severity_reason=why, raw_severity=str(label or ""),
            aliases=[adv.get("ghsa_id") or adv.get("advisory_ghsa_id") or "",
                     adv.get("cve_id") or ""],
            component=v.get("name") or v.get("package_name") or "",
            version=v.get("version") or "",
            location="dependency-review",
            references=[adv.get("url") or adv.get("advisory_url") or ""],
        ))
    return out


# ---------------------------------------------------------------------------
# Gate
# ---------------------------------------------------------------------------

class Gate:
    def __init__(self, policy: dict, exceptions: dict, args):
        self.policy = policy
        self.args = args
        self.blocking_cfg = policy.get("blocking", {}) or {}
        self.fail_on = self.blocking_cfg.get("fail_on", {}) or {}
        self.norm = Normaliser(policy.get("severity_mapping", {}))
        self.threshold = self.blocking_cfg.get("block_at_or_above", "HIGH")
        self.exceptions = exceptions.get("exceptions", []) or []
        self.errors: list[str] = []          # gate could not be evaluated -> exit 2
        self.blocks: list[str] = []          # policy says block -> exit 1
        self.notes: list[str] = []
        self.findings: list[Finding] = []
        self.kev_ids: set[str] = set()
        self.manifest: dict = {}
        # Bookkeeping for verdict.json only. None of it participates in the
        # decision; it exists so the verdict can say WHY, and so a notifier can
        # state a coverage gap instead of implying full coverage.
        self.report_status: dict[str, dict] = {}
        self.coverage_gaps: list[dict] = []
        self.image_digests: set[str] = set()

    def gap(self, kind: str, detail: str, hard: bool = True) -> None:
        """Record a limit on what this run actually covered.

        `hard` distinguishes "a required assessment did not happen" from "an
        advisory enrichment source was absent". Only the former clears
        coverage_complete; both are reported.
        """
        self.coverage_gaps.append({"kind": kind, "detail": detail, "hard": hard})

    @property
    def coverage_complete(self) -> bool:
        return not any(g["hard"] for g in self.coverage_gaps)

    # -- intelligence -------------------------------------------------------
    def check_intelligence(self) -> None:
        cfg = self.policy.get("intelligence", {}) or {}
        path = Path(self.args.manifest)
        if not path.is_file():
            self.errors.append(
                "Intelligence manifest is missing. No scan result can be trusted without "
                "a record of which data it used."
            )
            self.gap("intelligence", "No intelligence manifest was produced for this target.")
            return
        try:
            self.manifest = json.loads(path.read_text())
        except json.JSONDecodeError as exc:
            self.errors.append(f"Intelligence manifest is not valid JSON: {exc}")
            self.gap("intelligence", f"The intelligence manifest is unreadable: {exc}")
            return

        max_age = float(cfg.get("max_age_hours", 24))
        now = utcnow()
        for src_id in cfg.get("mandatory", []) or []:
            entry = (self.manifest.get("sources") or {}).get(src_id)
            if not entry or entry.get("status") != "ok":
                reason = (entry or {}).get("error", "not retrieved")
                self.errors.append(
                    f"Mandatory intelligence source '{src_id}' unavailable ({reason}). "
                    f"Refusing to report an empty result as success."
                )
                self.gap("intelligence",
                         f"Mandatory source '{src_id}' was not retrieved ({reason}).")
                continue
            retrieved = parse_ts(entry.get("retrieved_at"))
            if retrieved is None:
                self.errors.append(f"Source '{src_id}' recorded no retrieval time.")
                self.gap("intelligence",
                         f"Mandatory source '{src_id}' recorded no retrieval time.")
                continue
            age_h = (now - retrieved).total_seconds() / 3600.0
            if age_h > max_age:
                self.errors.append(
                    f"Source '{src_id}' is {age_h:.1f}h old; policy allows {max_age:.0f}h."
                )
                self.gap("intelligence",
                         f"Mandatory source '{src_id}' is {age_h:.1f}h old; policy allows "
                         f"{max_age:.0f}h.")
            else:
                self.notes.append(
                    f"{src_id}: retrieved {age_h:.1f}h ago"
                    + (f", upstream revision {entry['upstream_revision']}"
                       if entry.get("upstream_revision") else "")
                )

        for src_id in cfg.get("advisory", []) or []:
            entry = (self.manifest.get("sources") or {}).get(src_id)
            if not entry or entry.get("status") != "ok":
                self.notes.append(
                    f"COVERAGE LIMITATION — advisory source '{src_id}' was not retrieved; "
                    f"enrichment from it is absent from this run."
                )
                # Soft: policy does not block on it, so it must not be reported
                # as an incomplete assessment — only as a narrower one.
                self.gap("advisory-source",
                         f"Advisory source '{src_id}' was not retrieved; its enrichment is "
                         f"absent from this run.", hard=False)

        kev = (self.manifest.get("sources") or {}).get("cisa-kev") or {}
        self.kev_ids = {c.upper() for c in (kev.get("kev_cve_ids") or [])}

    # -- reports ------------------------------------------------------------
    def load_reports(self) -> None:
        reports_dir = Path(self.args.reports_dir)
        status_dir = reports_dir / "status"
        event = self.args.event

        for spec in self.policy.get("required_reports", []) or []:
            rid = spec["id"]
            applicable_events = spec.get("events", []) or []
            applicable = event in applicable_events

            status_file = status_dir / f"{rid}.status"
            status = status_file.read_text().strip() if status_file.is_file() else "missing"

            # Recorded for verdict.json before any branch below returns, so a
            # scanner is never silently absent from the verdict's scanner list.
            record = self.report_status[rid] = {
                "report_id": rid,
                "description": spec.get("description", ""),
                "applicable": applicable,
                "applicable_events": applicable_events,
                "job_result": status,
                "status": "unknown",
                "reports": [],
            }

            if not applicable:
                # The single documented non-applicability. It is allowed only
                # because policy.yml lists the events this report applies to —
                # not because the job happened to be skipped.
                self.notes.append(
                    f"{rid}: not applicable to event '{event}' (policy declares "
                    f"events {applicable_events})."
                )
                record["status"] = "not-applicable"
                continue

            if status in ("failure", "cancelled", "timed_out"):
                if self.fail_on.get("scanner_error", True):
                    self.blocks.append(
                        f"{rid}: the scanner job ended '{status}'. A scanner that did not "
                        f"finish has not cleared anything."
                    )
                record["status"] = "did-not-finish"
                self.gap("scanner", f"{rid}: the scanner job ended '{status}', so this run "
                                    f"carries no result from it.")
                continue
            if status == "skipped":
                if self.fail_on.get("unexpected_skip", True):
                    self.blocks.append(
                        f"{rid}: required for event '{event}' but the job was skipped."
                    )
                record["status"] = "skipped"
                self.gap("scanner", f"{rid}: required for event '{event}' but the job was "
                                    f"skipped, so it scanned nothing.")
                continue
            if status == "missing":
                if self.fail_on.get("missing_report", True):
                    self.blocks.append(
                        f"{rid}: no job status was recorded. The gate will not assume success."
                    )
                record["status"] = "no-status"
                self.gap("scanner", f"{rid}: no job status was recorded, so it cannot be shown "
                                    f"to have run.")
                continue

            self._check_scanner_database(rid, reports_dir)

            matches = sorted(reports_dir.glob(f"{rid}.*"))
            if not matches:
                if self.fail_on.get("missing_report", True):
                    self.blocks.append(
                        f"{rid}: the job reported '{status}' but wrote no report. "
                        f"A missing report is a failure, not an empty result."
                    )
                record["status"] = "no-report"
                self.gap("scanner", f"{rid}: the job reported '{status}' but wrote no report.")
                continue

            record["status"] = "reported"
            record["reports"] = [p.name for p in matches]
            for path in matches:
                self._ingest(rid, path)

    def _check_scanner_database(self, rid: str, reports_dir: Path) -> None:
        """Hold each Trivy job to the same freshness window as the manifest.

        The osv-scanner jobs are pointed at the pre-fetched OSV database that
        the intelligence job already recorded, so their provenance is settled by
        the manifest. Trivy ships its own OCI-distributed database and cannot be
        pointed at that snapshot, so each Trivy job writes back the revision it
        actually used. Without this, a Trivy job could quietly scan against a
        week-old database and still report a clean result.
        """
        record = self.report_status.setdefault(rid, {"report_id": rid})
        frag = reports_dir / "intel" / f"{rid}.trivydb.json"
        if not frag.is_file():
            # Only Trivy-backed reports are expected to produce one.
            if rid in ("deps-trivy", "config-trivy", "image-trivy"):
                self.blocks.append(
                    f"{rid}: no database revision was recorded for this scanner job, so "
                    f"the data it used cannot be shown to be fresh."
                )
                self.gap("scanner-database",
                         f"{rid}: recorded no database revision, so the data it used cannot "
                         f"be shown to be fresh.")
            return
        try:
            info = json.loads(frag.read_text())
        except json.JSONDecodeError as exc:
            self.blocks.append(f"{rid}: recorded database revision is unreadable ({exc}).")
            self.gap("scanner-database",
                     f"{rid}: the recorded database revision is unreadable ({exc}).")
            return
        # A configuration scan does not consult the vulnerability database at
        # all — `trivy config` evaluates the misconfiguration CHECK BUNDLE, and
        # `trivy version` on such a run reports a CheckBundle and no
        # VulnerabilityDB. Demanding a vulnerability database from it was wrong,
        # and produced the nonsensical "the scanner reported no vulnerability
        # database" against a config job that was working correctly.
        #
        # So each scanner is held to the provenance of the data it actually
        # used. Both still have to prove they used something identifiable; the
        # difference is which artefact that is.
        if rid.startswith("config"):
            bundle = info.get("CheckBundle") or {}
            digest = bundle.get("Digest")
            fetched = parse_ts(bundle.get("DownloadedAt"))
            if not digest or fetched is None:
                self.blocks.append(
                    f"{rid}: the scanner recorded no misconfiguration check bundle, so the "
                    f"rules it applied cannot be identified."
                )
                self.gap("scanner-database",
                         f"{rid}: recorded no misconfiguration check bundle, so the rules it "
                         f"applied cannot be identified.")
                return
            record["database"] = {"kind": "trivy-check-bundle", "digest": str(digest),
                                  "downloaded_at": bundle.get("DownloadedAt")}
            # The bundle carries no upstream build timestamp, only when it was
            # fetched, so this records provenance rather than asserting an
            # upstream freshness it cannot know. Stated plainly here so nobody
            # reads it as the same guarantee the vulnerability feeds give.
            self.notes.append(
                f"{rid}: Trivy check bundle {str(digest)[:23]} fetched {bundle.get('DownloadedAt')} "
                f"(policy rules, not a vulnerability feed — no upstream build time is published)."
            )
            return

        vdb = info.get("VulnerabilityDB") or {}
        updated = parse_ts(vdb.get("UpdatedAt"))
        if updated is None:
            self.blocks.append(f"{rid}: the scanner reported no vulnerability database.")
            self.gap("scanner-database",
                     f"{rid}: reported no vulnerability database, so what it compared against "
                     f"is unknown.")
            return
        max_age = float((self.policy.get("intelligence", {}) or {}).get("max_age_hours", 24))
        age_h = (utcnow() - updated).total_seconds() / 3600.0
        record["database"] = {"kind": "trivy-vulnerability-db", "version": vdb.get("Version"),
                              "built_at": vdb.get("UpdatedAt"), "age_hours": round(age_h, 2)}
        if age_h > max_age:
            self.blocks.append(
                f"{rid}: scanned against a Trivy database built {age_h:.1f}h ago; "
                f"policy allows {max_age:.0f}h."
            )
            self.gap("scanner-database",
                     f"{rid}: scanned against a Trivy database built {age_h:.1f}h ago; policy "
                     f"allows {max_age:.0f}h.")
        else:
            self.notes.append(
                f"{rid}: Trivy DB v{vdb.get('Version')} built {vdb.get('UpdatedAt')} "
                f"({age_h:.1f}h old)."
            )

    def _ingest(self, rid: str, path: Path) -> None:
        try:
            doc = json.loads(path.read_text() or "null")
        except json.JSONDecodeError as exc:
            if self.fail_on.get("unparseable_report", True):
                self.blocks.append(f"{rid}: {path.name} is not valid JSON ({exc}).")
            self.report_status.setdefault(rid, {"report_id": rid})["status"] = "unreadable"
            self.gap("scanner", f"{rid}: {path.name} is not valid JSON, so its result could "
                                f"not be read.")
            return
        if doc is None:
            if self.fail_on.get("unparseable_report", True):
                self.blocks.append(f"{rid}: {path.name} is empty.")
            self.report_status.setdefault(rid, {"report_id": rid})["status"] = "unreadable"
            self.gap("scanner", f"{rid}: {path.name} is empty, so its result could not be read.")
            return

        self._record_image_digests(rid, doc)

        name = path.name
        try:
            if ".osv." in name:
                self.findings += parse_osv(doc, self.norm)
            elif ".trivy." in name:
                category = "image" if rid.startswith("image") else (
                    "config" if rid.startswith("config") else "dependency")
                self.findings += parse_trivy(doc, self.norm, category)
            elif ".gitleaks." in name:
                self.findings += parse_gitleaks(doc, self.norm)
            elif name.endswith(".sarif") or ".sarif." in name:
                self.findings += parse_sarif(doc, self.norm, rid.split("-")[-1])
            elif ".depreview." in name:
                self.findings += parse_dependency_review(doc, self.norm)
            else:
                self.errors.append(f"{rid}: {name} has no recognised report format.")
        except (KeyError, TypeError, AttributeError) as exc:
            self.errors.append(f"{rid}: {name} could not be parsed ({exc!r}).")
            self.gap("scanner", f"{rid}: {name} could not be parsed ({exc!r}).")

    def _record_image_digests(self, rid: str, doc: Any) -> None:
        """Note which image the container scan actually looked at.

        A verdict that says "the image is clean" is worthless without saying
        WHICH image. Trivy records that in Metadata; anything absent is simply
        left out rather than guessed at.
        """
        if not rid.startswith("image") or not isinstance(doc, dict):
            return
        meta = doc.get("Metadata")
        if not isinstance(meta, dict):
            return
        for value in (meta.get("ImageID"),
                      ((meta.get("ImageConfig") or {}) if isinstance(
                          meta.get("ImageConfig"), dict) else {}).get("digest")):
            if isinstance(value, str) and value.strip():
                self.image_digests.add(value.strip())
        for value in meta.get("RepoDigests") or []:
            if isinstance(value, str) and value.strip():
                self.image_digests.add(value.strip())

    # -- exceptions ---------------------------------------------------------
    def validate_exceptions(self) -> list[dict]:
        cfg = self.policy.get("exceptions", {}) or {}
        required = set(cfg.get("require_fields", []) or [])
        max_days = int(cfg.get("max_duration_days", 90))
        now = utcnow()
        valid = []
        for exc in self.exceptions:
            if not isinstance(exc, dict):
                self.blocks.append("exceptions.yml contains a non-mapping entry.")
                continue
            missing = required - set(exc)
            if missing:
                if self.fail_on.get("invalid_exception", True):
                    self.blocks.append(
                        f"Exception '{exc.get('id', '<no id>')}' is missing required "
                        f"field(s): {', '.join(sorted(missing))}."
                    )
                continue
            expires = parse_ts(exc.get("expires"))
            if expires is None:
                self.blocks.append(
                    f"Exception '{exc['id']}' has an unparseable expiry '{exc.get('expires')}'."
                )
                continue
            if expires < now:
                if self.fail_on.get("expired_exception", True):
                    self.blocks.append(
                        f"Exception '{exc['id']}' expired on {expires.date()}. "
                        f"An expired exception does not silently keep suppressing."
                    )
                continue
            if (expires - now).days > max_days:
                self.blocks.append(
                    f"Exception '{exc['id']}' runs {(expires - now).days} days, beyond the "
                    f"{max_days}-day maximum."
                )
                continue
            scope = exc.get("scope") or {}
            if not isinstance(scope, dict) or not scope.get("finding"):
                self.blocks.append(
                    f"Exception '{exc['id']}' has no scope.finding. Blanket suppressions "
                    f"are not accepted."
                )
                continue
            valid.append(exc)
        return valid

    def match_exception(self, finding: Finding, valid: list[dict]) -> dict | None:
        for exc in valid:
            scope = exc["scope"]
            target = str(scope.get("finding", ""))
            if target not in finding.aliases and target != finding.canonical_id:
                continue
            if scope.get("repository") and scope["repository"] != self.args.repository:
                continue
            if scope.get("component") and not same_package(scope["component"], finding.component):
                continue
            if scope.get("version") and scope["version"] != finding.version:
                continue
            if scope.get("path") and scope["path"] not in finding.location:
                continue
            if scope.get("branch") and scope["branch"] != self.args.ref:
                continue
            return exc
        return None

    # -- decision -----------------------------------------------------------
    def evaluate(self) -> list[Finding]:
        valid_exceptions = self.validate_exceptions()

        # Deduplicate across tools. Two detectors reporting the same advisory in
        # the same place is one finding; the detectors are unioned so the report
        # still shows corroboration.
        merged: dict[str, Finding] = {}
        for f in self.findings:
            existing = merged.get(f.key)
            if existing is None:
                merged[f.key] = f
                continue
            if sev_rank(f.severity) > sev_rank(existing.severity):
                existing.severity = f.severity
                existing.severity_reason = f.severity_reason
            existing.aliases = sorted(set(existing.aliases) | set(f.aliases))
            if f.detector not in existing.detector:
                existing.detector = f"{existing.detector}+{f.detector}"
            if not existing.fixed_version and f.fixed_version:
                existing.fixed_version = f.fixed_version
            existing.all_fixed_versions = sorted(
                set(existing.all_fixed_versions) | set(f.all_fixed_versions))
            # `org.apache.tomcat.embed:tomcat-embed-core` is more useful in a
            # report than `tomcat-embed-core`, so the fuller coordinate wins.
            if len(f.component) > len(existing.component):
                existing.component = f.component
            if len(f.location) > len(existing.location):
                existing.location = f.location

        findings = list(merged.values())

        for f in findings:
            f.kev = any(a.upper() in self.kev_ids for a in f.aliases if a.startswith("CVE-"))

            exc = self.match_exception(f, valid_exceptions)
            if exc:
                f.status = "excepted"
                f.exception = {"id": exc["id"], "owner": exc.get("owner"),
                               "approval": exc.get("approval"), "expires": str(exc.get("expires")),
                               "reason": exc.get("reason")}
                continue

            is_secret = f.category == "secret"
            over_threshold = sev_rank(f.severity) >= sev_rank(self.threshold)

            if is_secret and self.blocking_cfg.get("secrets_always_block", True):
                f.status = "blocking"
                self.blocks.append(
                    f"Secret detected — {f.title} at {f.location}."
                )
            elif f.kev and self.blocking_cfg.get("kev_always_blocks", True):
                f.status = "blocking"
                self.blocks.append(
                    f"{f.canonical_id} is on the CISA KEV catalogue "
                    f"({f.component} {f.version}) — blocking regardless of its "
                    f"'{f.severity}' label."
                )
            elif over_threshold:
                f.status = "blocking"
                fix = f" fixed in {f.fixed_version}" if f.fixed_version else " (no fix available)"
                self.blocks.append(
                    f"{f.severity} {f.canonical_id} in {f.component or f.location} "
                    f"{f.version}{fix}."
                )
            else:
                f.status = "tracked"

        return findings


# ---------------------------------------------------------------------------
# Register + report
# ---------------------------------------------------------------------------

def merge_register(previous: dict, findings: list[Finding], args) -> dict:
    """Update the register in place; never duplicate; never clear another branch.

    `branches` maps a branch name to the revision it was last seen at. A fix on
    develop removes develop from that map and leaves main alone, which is what
    keeps "fixed on develop" from being mistaken for "fixed everywhere".
    """
    now = utcnow().isoformat()
    entries: dict[str, dict] = {e["key"]: e for e in (previous.get("findings") or [])}
    seen_now = {f.key for f in findings}

    for f in findings:
        record = entries.get(f.key)
        payload = f.to_dict()
        if record is None:
            payload["first_seen"] = now
            payload["first_seen_revision"] = args.revision
            payload["branches"] = {args.ref: args.revision}
            payload["owner"] = args.owner
            payload["remediation_pr"] = None
            payload["verification"] = "automated-scan"
            entries[f.key] = payload
        else:
            branches = dict(record.get("branches") or {})
            branches[args.ref] = args.revision
            record.update(payload)
            record["branches"] = branches
        entries[f.key]["last_assessed"] = now
        entries[f.key]["last_assessed_revision"] = args.revision

    # Clear ONLY for the branch actually assessed, and only because this
    # revision no longer contains it.
    for key, record in list(entries.items()):
        if key in seen_now:
            continue
        branches = dict(record.get("branches") or {})
        if args.ref in branches:
            branches.pop(args.ref)
            record["branches"] = branches
            record.setdefault("resolved_on", {})[args.ref] = {
                "revision": args.revision, "at": now,
                "evidence": "absent from the assessed revision's scan",
            }
        record["status"] = "resolved" if not record.get("branches") else record.get("status", "affected")

    return {
        "schema": 1,
        "repository": args.repository,
        "updated_at": now,
        "last_run": {"event": args.event, "ref": args.ref, "revision": args.revision},
        "findings": sorted(entries.values(), key=lambda e: (
            -sev_rank(e.get("severity", "MEDIUM")), e.get("id", ""))),
    }


def write_report(path: Path, gate: Gate, findings: list[Finding], verdict: str, args) -> None:
    blocking = [f for f in findings if f.status == "blocking"]
    tracked = [f for f in findings if f.status == "tracked"]
    excepted = [f for f in findings if f.status == "excepted"]

    lines = [f"## Security Gate — {verdict}", ""]
    lines += [
        f"- **Repository** `{args.repository}`",
        f"- **Event** `{args.event}` · **Ref** `{args.ref}` · **Revision** `{args.revision}`",
        f"- **Assessed at** {utcnow().isoformat()}",
        f"- **Blocking threshold** {gate.threshold} and above; CISA KEV blocks at any severity; secrets always block.",
        "",
    ]

    lines += ["### Intelligence freshness", ""]
    sources = (gate.manifest.get("sources") or {})
    if sources:
        lines += ["| Source | Status | Retrieved | Upstream revision |", "|---|---|---|---|"]
        for sid, entry in sorted(sources.items()):
            lines.append(
                f"| `{sid}` | {entry.get('status', '?')} | {entry.get('retrieved_at', '—')} | "
                f"{entry.get('upstream_revision', '—')} |"
            )
    else:
        lines.append("_No manifest was recorded._")
    lines += [
        "",
        "> Retrieval freshness is not publication completeness. These timestamps show when the "
        "data was fetched and which revision was used. They cannot show that every "
        "vulnerability disclosed in that window has been published upstream or ingested.",
        "",
    ]

    if gate.errors:
        lines += ["### Gate could not be evaluated", ""]
        lines += [f"- {e}" for e in gate.errors] + [""]

    if gate.blocks:
        lines += ["### Blocking", ""]
        lines += [f"- {b}" for b in gate.blocks] + [""]

    def table(rows: list[Finding], title: str) -> list[str]:
        if not rows:
            return []
        out = [f"### {title} ({len(rows)})", "",
               "| Severity | ID | Component | Version | Fixed in | KEV | Where | Detector |",
               "|---|---|---|---|---|---|---|---|"]
        for f in sorted(rows, key=lambda x: -sev_rank(x.severity)):
            out.append(
                f"| {f.severity} | `{f.canonical_id}` | {f.component or '—'} | "
                f"{f.version or '—'} | "
                f"{f.fixed_version or ('no verified upgrade available' if f.all_fixed_versions else '—')} | "
                f"{'yes' if f.kev else 'no'} | `{f.location or '—'}` | {f.detector} |"
            )
        return out + [""]

    lines += table(blocking, "Blocking findings")
    lines += table(tracked, "Tracked findings (visible, not blocking)")
    if excepted:
        lines += [f"### Approved exceptions ({len(excepted)})", "",
                  "| ID | Component | Exception | Owner | Approval | Expires |", "|---|---|---|---|---|---|"]
        for f in excepted:
            e = f.exception or {}
            lines.append(
                f"| `{f.canonical_id}` | {f.component or '—'} | {e.get('id')} | "
                f"{e.get('owner')} | {e.get('approval')} | {e.get('expires')} |")
        lines.append("")

    if gate.notes:
        lines += ["### Coverage notes", ""] + [f"- {n}" for n in gate.notes] + [""]

    lines += [
        "### What this result does and does not mean", "",
        "A passing gate means the required assessments completed against intelligence "
        "inside the freshness window and matched nothing that policy blocks on. It is not "
        "a statement that the revision contains no vulnerabilities: coverage is bounded by "
        "the sources in `.github/security/sources.yml`, and it excludes private, embargoed "
        "and unpublished disclosures entirely.",
        "",
    ]
    path.write_text("\n".join(lines))


# ---------------------------------------------------------------------------
# The verdict — machine-readable, versioned, and never an input to the decision
# ---------------------------------------------------------------------------

def _sha256_of(path: str) -> str:
    """Content digest of a file, or a marker saying why there isn't one.

    Used for policy_revision, which is how a reader of an old verdict can tell
    whether it was judged by today's policy. A missing or unreadable file gets a
    marker rather than an empty string, because "" reads as "nobody looked".
    """
    try:
        return "sha256:" + hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        return "unavailable"


def _none_if_blank(value: Any) -> Any:
    return value if (value is not None and str(value).strip() != "") else None


def _int_or_none(value: Any) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _run_context() -> dict:
    """Where this ran, taken from the runner's own environment.

    Every one of these is set by GitHub Actions itself, not by anything under a
    pull request's control.
    """
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    run_id = _none_if_blank(os.environ.get("GITHUB_RUN_ID"))
    attempt_raw = os.environ.get("GITHUB_RUN_ATTEMPT")
    try:
        attempt = int(attempt_raw) if attempt_raw else None
    except ValueError:
        attempt = None
    url = None
    if run_id and repo:
        url = f"{server}/{repo}/actions/runs/{run_id}"
        if attempt:
            url += f"/attempts/{attempt}"
    return {"run_id": str(run_id) if run_id else None,
            "run_attempt": attempt,
            "run_url": url,
            "workflow": _none_if_blank(os.environ.get("GITHUB_WORKFLOW"))}


def _intelligence_block(manifest: dict) -> dict:
    """Restate the manifest's sources under stable keys.

    The manifest names its sources with the ids policy.yml uses (`trivy-db`,
    `cisa-kev`). The verdict is a published contract, so it uses fixed keys and
    keeps the manifest's own id inside each entry.
    """
    sources = (manifest.get("sources") or {}) if isinstance(manifest, dict) else {}

    def entry(src_id: str, extra_keys: tuple[str, ...] = ()) -> dict:
        raw = sources.get(src_id)
        if not isinstance(raw, dict):
            return {"source": src_id, "status": "absent", "retrieved_at": None,
                    "upstream_revision": None}
        out = {"source": src_id,
               "status": raw.get("status", "unknown"),
               "retrieved_at": _none_if_blank(raw.get("retrieved_at")),
               "upstream_revision": _none_if_blank(raw.get("upstream_revision")),
               "error": _none_if_blank(raw.get("error"))}
        for key in extra_keys:
            out[key] = raw.get(key)
        return out

    kev = entry("cisa-kev", ("entry_count", "upstream_published_at", "provider"))
    # The catalogue itself is thousands of ids; the count is the useful part and
    # the list is already in the manifest for anyone who needs it.
    return {
        "osv": entry("osv"),
        "ghsa": entry("ghsa"),
        "trivy_db": entry("trivy-db"),
        "kev": kev,
        "advisory": {sid: entry(sid) for sid in sources
                     if sid not in ("osv", "ghsa", "trivy-db", "cisa-kev")},
        "manifest_generated_at": _none_if_blank(
            manifest.get("generated_at") if isinstance(manifest, dict) else None),
        "caveat": ("Retrieval freshness is not publication completeness. These timestamps "
                   "record when data was fetched and which revision was used; they cannot "
                   "show that every disclosure in that window was published upstream."),
    }


def build_verdict(args, gate: Gate | None, findings: list[Finding], code: int,
                  extra_errors: list[str] | None = None) -> dict:
    """Describe the decision that was just made. Never influence it.

    `gate` is None on the paths that failed before a Gate could be constructed —
    an unreadable policy, an unparseable exceptions file, an unhandled crash.
    Those still produce a verdict, because a notifier receiving nothing cannot
    distinguish "clean" from "the evaluator died".
    """
    errors = list(extra_errors or [])
    blocks: list[str] = []
    notes: list[str] = []
    scanners: list[dict] = []
    gaps: list[dict] = []
    manifest: dict = {}
    image_digests: list[str] = []
    coverage_complete = False
    if gate is not None:
        errors = list(gate.errors) + errors
        blocks = list(gate.blocks)
        notes = list(gate.notes)
        scanners = [gate.report_status[k] for k in sorted(gate.report_status)]
        gaps = list(gate.coverage_gaps)
        manifest = gate.manifest
        image_digests = sorted(gate.image_digests)
        coverage_complete = gate.coverage_complete

    by_severity = {sev: 0 for sev in SEVERITY_ORDER}
    blocking = tracked = excepted = kev_count = 0
    payload: list[dict] = []
    for f in findings:
        by_severity[f.severity if f.severity in by_severity else "MEDIUM"] += 1
        if f.status == "blocking":
            blocking += 1
        elif f.status == "tracked":
            tracked += 1
        elif f.status == "excepted":
            excepted += 1
        if f.kev:
            kev_count += 1
        item = f.to_dict()
        item["blocking"] = f.status == "blocking"
        payload.append(item)
    payload.sort(key=lambda d: (not d["blocking"], -sev_rank(d.get("severity", "MEDIUM")),
                                d.get("id", "")))

    ctx = _run_context()
    return {
        "schema_version": VERDICT_SCHEMA_VERSION,
        "generated_at": utcnow().isoformat(),

        "repository": args.repository,
        "event": args.event,
        "ref": args.ref,
        "revision": args.revision,
        "target_slug": _none_if_blank(getattr(args, "target_slug", "")),
        "target_name": _none_if_blank(getattr(args, "target_name", "")) or args.ref,

        "run_id": ctx["run_id"],
        "run_attempt": ctx["run_attempt"],
        "run_url": ctx["run_url"],
        "workflow": ctx["workflow"],

        "pr_number": _int_or_none(getattr(args, "pr_number", "")),
        "base_ref": _none_if_blank(getattr(args, "base_ref", "")),

        "image_digests": image_digests,

        "policy_revision": _sha256_of(args.policy),
        "exceptions_revision": _sha256_of(args.exceptions),
        "evaluator_revision": _sha256_of(__file__),

        "intelligence": _intelligence_block(manifest),
        "scanners": scanners,

        "coverage_complete": coverage_complete,
        "coverage_gaps": gaps,

        # Derived from the exit code, never the reverse. See the module docstring.
        "status": STATUS_BY_EXIT.get(code, "ERROR"),
        "exit_code": code,

        "counts": {
            "blocking": blocking,
            "tracked": tracked,
            "excepted": excepted,
            "by_severity": by_severity,
            "kev": kev_count,
        },
        "findings": payload,
        "blocking_reasons": blocks,
        "error_reasons": errors,
        "notes": notes,
    }


def write_verdict(path: Path | None, verdict: dict) -> None:
    """Write it, and never let writing it change the outcome.

    If this raises, the gate's exit code is already decided and must not move,
    so the failure is announced and swallowed. A missing verdict is a problem
    for the notifier — which treats it as ERROR — not a reason to reverse a
    security decision that has already been correctly made.
    """
    if path is None:
        return
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(verdict, indent=2, sort_keys=False, default=str) + "\n")
    except (OSError, TypeError, ValueError) as exc:  # pragma: no cover - defensive
        print(f"::warning title=Security Gate::The verdict could not be written ({exc}). "
              f"The gate's own result is unaffected.", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reports-dir", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--policy", default=".github/security/policy.yml")
    ap.add_argument("--exceptions", default=".github/security/exceptions.yml")
    ap.add_argument("--event", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--revision", required=True)
    ap.add_argument("--repository", required=True)
    ap.add_argument("--owner", default="")
    ap.add_argument("--workspace", default=os.environ.get("GITHUB_WORKSPACE", ""),
                    help="checkout root, stripped from reported paths")
    ap.add_argument("--register-out", default="findings.json")
    ap.add_argument("--report-out", default="report.md")
    # Additive, and optional so that every existing invocation keeps working
    # unchanged. See "THE VERDICT" in the module docstring.
    ap.add_argument("--verdict-out", default="",
                    help="write the machine-readable verdict.json here")
    ap.add_argument("--target-slug", default="",
                    help="the workflow's slug for this assessment target")
    ap.add_argument("--target-name", default="",
                    help="human label for this target, e.g. 'PR #12 (feat/x)' or 'main'")
    ap.add_argument("--pr-number", default="",
                    help="pull request number when this target is one; blank otherwise")
    ap.add_argument("--base-ref", default="",
                    help="the branch the pull request targets; blank when not a PR")
    args = ap.parse_args()

    global WORKSPACE
    WORKSPACE = args.workspace
    verdict_path = Path(args.verdict_out) if args.verdict_out else None

    try:
        policy = yaml.safe_load(Path(args.policy).read_text()) or {}
    except (OSError, yaml.YAMLError) as exc:
        print(f"::error::Cannot read the security policy: {exc}", file=sys.stderr)
        write_verdict(verdict_path, build_verdict(
            args, None, [], 2, [f"Cannot read the security policy: {exc}"]))
        return 2

    exceptions: dict = {}
    exc_path = Path(args.exceptions)
    if exc_path.is_file():
        try:
            exceptions = yaml.safe_load(exc_path.read_text()) or {}
        except yaml.YAMLError as exc:
            print(f"::error::exceptions.yml is not valid YAML: {exc}", file=sys.stderr)
            write_verdict(verdict_path, build_verdict(
                args, None, [], 2, [f"exceptions.yml is not valid YAML: {exc}"]))
            return 2

    gate = Gate(policy, exceptions, args)
    try:
        gate.check_intelligence()
        gate.load_reports()
        findings = gate.evaluate()
    except BaseException as exc:
        # An evaluator that crashed has assessed nothing. The exit code is left
        # exactly as it was before this handler existed — the exception
        # propagates and Python exits non-zero — so no pass/fail behaviour
        # changes here. All that is added is a verdict saying what happened,
        # so the notifier reports an incomplete assessment rather than silence.
        write_verdict(verdict_path, build_verdict(
            args, gate, [], 1,
            [f"The evaluator raised an unhandled {type(exc).__name__}: {exc}. Nothing was "
             f"assessed. The process exit code is 1, which blocks."]))
        raise

    if gate.errors:
        verdict = "COULD NOT BE EVALUATED"
        code = 2
    elif gate.blocks:
        verdict = "FAILED"
        code = 1
    else:
        verdict = "PASSED"
        code = 0

    register_path = Path(args.register_out)
    previous: dict = {}
    if register_path.is_file():
        try:
            previous = json.loads(register_path.read_text())
        except json.JSONDecodeError:
            previous = {}
    # The register is only rewritten when the assessment actually ran. Writing
    # it after a gate that could not be evaluated would record "nothing found"
    # for a scan that never happened.
    if code != 2:
        register_path.write_text(json.dumps(merge_register(previous, findings, args), indent=2) + "\n")

    write_report(Path(args.report_out), gate, findings, verdict, args)
    # Written for every code, including 2. The register is not, and that
    # asymmetry is deliberate: the register is a claim about the code, and a run
    # that could not be evaluated has no claim to make. The verdict is a claim
    # about the RUN, and "this run could not be evaluated" is exactly the claim.
    write_verdict(verdict_path, build_verdict(args, gate, findings, code))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as fh:
            fh.write(Path(args.report_out).read_text())

    for line in gate.errors:
        print(f"::error title=Security Gate::{line}")
    for line in gate.blocks:
        print(f"::error title=Blocking finding::{line}")
    print(f"Security Gate: {verdict} "
          f"({len([f for f in findings if f.status == 'blocking'])} blocking, "
          f"{len([f for f in findings if f.status == 'tracked'])} tracked, "
          f"{len([f for f in findings if f.status == 'excepted'])} excepted)")
    return code


if __name__ == "__main__":
    sys.exit(main())
