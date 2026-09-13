#!/usr/bin/env bash
# Install the Security Gate's scanner binaries, pinned and verified.
#
# Why this exists rather than three marketplace actions:
#
#   1. A third-party action is code that runs with the job's token. Pinning it
#      to a SHA bounds what it can be swapped for, but it is still a supply
#      chain we do not need — the tools themselves are single static binaries
#      published with checksums.
#   2. gitleaks-action requires an organisation licence key for org-owned
#      repositories. Installing the released binary sidesteps that entirely,
#      and gitleaks is the same program either way.
#   3. Pinning the VERSION here, in one file, is what makes a scan
#      reproducible. `docs/security/SECURITY_CI.md` tells a developer to run
#      this exact script, so what CI ran and what a laptop runs cannot drift.
#
# Every download is checked against the SHA-256 published in that release's own
# checksums file, which is fetched from the same tag. This is not a substitute
# for signature verification — an attacker who can rewrite a release asset can
# usually rewrite the checksums file beside it — but it does close the far more
# likely failures: a truncated download, a CDN serving a different artefact, or
# a version pin that silently resolves to something else. The pinned version is
# the primary control; the checksum proves we got that version intact.
#
# Nothing here is ever piped from the network into a shell.

set -euo pipefail

# ---------------------------------------------------------------------------
# Pins. Changing a version here changes what every scan runs, so it is a
# reviewed diff, and the checksum below has to be refreshed with it.
# ---------------------------------------------------------------------------
TRIVY_VERSION="0.74.0"
OSV_SCANNER_VERSION="2.5.1"
GITLEAKS_VERSION="8.30.1"

BIN_DIR="${1:-${RUNNER_TEMP:-/tmp}/security-tools}"
shift || true
WANTED=("$@")
[ "${#WANTED[@]}" -gt 0 ] || WANTED=(trivy osv-scanner gitleaks)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$BIN_DIR"

# GitHub runners are linux/amd64; a developer reproducing a scan is usually on
# macOS. The OS/arch is resolved rather than assumed so the documented local
# reproduction is the same script, not a second set of instructions that rots.
case "$(uname -s)" in
  Linux)  OS=linux ;;
  Darwin) OS=darwin ;;
  *) echo "::error::Unsupported OS $(uname -s) for the security scanners." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  arm64|aarch64) ARCH=arm64 ;;
  *) echo "::error::Unsupported architecture $(uname -m)." >&2; exit 1 ;;
esac

# coreutils on Linux, the BSD equivalent on macOS. Both print "<hash>  <name>".
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'
  fi
}

fetch() {
  # --fail so an HTML error page is never mistaken for a binary, and bounded
  # retries so a transient CDN blip does not fail an entire security run.
  curl --fail --silent --show-error --location \
       --retry 3 --retry-delay 5 --retry-all-errors \
       --max-time 300 --output "$2" "$1"
}

# Downloads $url, then refuses to go on unless its SHA-256 matches the entry for
# $asset in $sums_url. An asset that is not listed in its own release's
# checksums file is treated as a failure, not as "nothing to check".
verify_and_fetch() {
  local url="$1" asset="$2" sums_url="$3" dest="$4"
  fetch "$url" "$dest"
  fetch "$sums_url" "$WORK/sums.txt"

  local expected actual
  expected="$(awk -v a="$asset" '$2 == a || $2 == "*" a {print $1}' "$WORK/sums.txt" | head -1)"
  if [ -z "$expected" ]; then
    echo "::error::$asset is not listed in $sums_url; refusing to install an unverifiable binary." >&2
    exit 1
  fi
  actual="$(sha256_of "$dest")"
  if [ "$expected" != "$actual" ]; then
    echo "::error::SHA-256 mismatch for $asset: expected $expected, got $actual." >&2
    exit 1
  fi
  echo "  verified $asset  sha256=$actual"
}

install_trivy() {
  local base="https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}"
  local plat asset
  case "$OS/$ARCH" in
    linux/amd64)  plat="Linux-64bit" ;;
    linux/arm64)  plat="Linux-ARM64" ;;
    darwin/amd64) plat="macOS-64bit" ;;
    darwin/arm64) plat="macOS-ARM64" ;;
  esac
  asset="trivy_${TRIVY_VERSION}_${plat}.tar.gz"
  echo "trivy ${TRIVY_VERSION} (${plat})"
  verify_and_fetch "$base/$asset" "$asset" "$base/trivy_${TRIVY_VERSION}_checksums.txt" "$WORK/$asset"
  tar -xzf "$WORK/$asset" -C "$WORK" trivy
  install -m 0755 "$WORK/trivy" "$BIN_DIR/trivy"
}

install_osv_scanner() {
  # osv-scanner publishes bare binaries plus a single SHA256SUMS covering them all.
  local base="https://github.com/google/osv-scanner/releases/download/v${OSV_SCANNER_VERSION}"
  local asset="osv-scanner_${OS}_${ARCH}"
  echo "osv-scanner ${OSV_SCANNER_VERSION} (${OS}/${ARCH})"
  verify_and_fetch "$base/$asset" "$asset" "$base/osv-scanner_SHA256SUMS" "$WORK/$asset"
  install -m 0755 "$WORK/$asset" "$BIN_DIR/osv-scanner"
}

install_gitleaks() {
  local base="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}"
  local plat asset
  case "$OS/$ARCH" in
    linux/amd64)  plat="linux_x64" ;;
    linux/arm64)  plat="linux_arm64" ;;
    darwin/amd64) plat="darwin_x64" ;;
    darwin/arm64) plat="darwin_arm64" ;;
  esac
  asset="gitleaks_${GITLEAKS_VERSION}_${plat}.tar.gz"
  echo "gitleaks ${GITLEAKS_VERSION} (${plat})"
  verify_and_fetch "$base/$asset" "$asset" "$base/gitleaks_${GITLEAKS_VERSION}_checksums.txt" "$WORK/$asset"
  tar -xzf "$WORK/$asset" -C "$WORK" gitleaks
  install -m 0755 "$WORK/gitleaks" "$BIN_DIR/gitleaks"
}

for tool in "${WANTED[@]}"; do
  case "$tool" in
    trivy)        install_trivy ;;
    osv-scanner)  install_osv_scanner ;;
    gitleaks)     install_gitleaks ;;
    *) echo "::error::Unknown scanner '$tool'." >&2; exit 1 ;;
  esac
done

echo "Installed into $BIN_DIR:"
ls -l "$BIN_DIR"
