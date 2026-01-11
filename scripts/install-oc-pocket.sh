#!/bin/sh
set -eu

REPO="${REPO:-ratulsarna/opencode-pocket}"
API_RELEASES="https://api.github.com/repos/${REPO}/releases"
API_RELEASE_LATEST="https://api.github.com/repos/${REPO}/releases/latest"
API_RELEASE_TAG="https://api.github.com/repos/${REPO}/releases/tags"

usage() {
  cat <<'EOF'
install-oc-pocket.sh

Installs the latest `oc-pocket` macOS universal binary from GitHub Releases.

Usage:
  curl -fsSL https://raw.githubusercontent.com/ratulsarna/opencode-pocket/main/scripts/install-oc-pocket.sh | sh
  curl -fsSL https://raw.githubusercontent.com/ratulsarna/opencode-pocket/main/scripts/install-oc-pocket.sh | sh -s -- oc-pocket-v0.1.0

Options (env vars):
  INSTALL_DIR   Install directory (default: /usr/local/bin)
  NO_VERIFY     Set to 1 to skip sha256 verification (default: 0)
  REPO          GitHub repo in owner/name format (default: ratulsarna/opencode-pocket)
  GITHUB_TOKEN  Optional: GitHub token (helps for private repos / rate limits)

Examples:
  INSTALL_DIR="$HOME/.local/bin" sh install-oc-pocket.sh
  NO_VERIFY=1 sh install-oc-pocket.sh
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
NO_VERIFY="${NO_VERIFY:-0}"
TAG_ARG="${1:-}"

os="$(uname -s 2>/dev/null || echo unknown)"
if [ "$os" != "Darwin" ]; then
  echo "error: oc-pocket installer currently supports macOS only (uname: $os)" >&2
  echo "If you want to build from source, see companion/oc-pocket/README.md." >&2
  exit 1
fi

if command -v curl >/dev/null 2>&1; then
  :
else
  echo "error: curl is required" >&2
  exit 1
fi

if command -v tar >/dev/null 2>&1; then
  :
else
  echo "error: tar is required" >&2
  exit 1
fi

if command -v shasum >/dev/null 2>&1; then
  :
else
  echo "error: shasum is required" >&2
  exit 1
fi

tmpdir="$(mktemp -d 2>/dev/null || mktemp -d -t oc-pocket-install)"
cleanup() {
  rm -rf "$tmpdir"
}
trap cleanup EXIT INT TERM

curl_api() {
  url="$1"
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" "$url"
  else
    curl -fsSL "$url"
  fi
}

curl_download() {
  url="$1"
  out="$2"
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    # Prefer authenticated asset downloads via the GitHub API when a token is available.
    # Direct github.com release download URLs often return 404 for private repos even
    # with an Authorization header.
    curl -fsSL -L -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/octet-stream" -o "$out" "$url"
  else
    curl -fsSL -L -o "$out" "$url"
  fi
}

validate_tag() {
  case "${1:-}" in
    oc-pocket-v*) return 0 ;;
    *) return 1 ;;
  esac
}

extract_tag_name() {
  json="$1"
  if command -v jq >/dev/null 2>&1; then
    printf "%s" "$json" | jq -r '.tag_name // empty' 2>/dev/null
    return 0
  fi

  printf "%s" "$json" \
    | sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' \
    | head -n 1
}

extract_latest_tag() {
  json="$1"
    if command -v jq >/dev/null 2>&1; then
      printf "%s" "$json" \
        | jq -r '.[] | select(.draft == false and .prerelease == false) | .tag_name' 2>/dev/null \
        | grep '^oc-pocket-v' \
        | head -n 1
      return 0
    fi

    # Minimal dependency fallback: try to filter out draft/prerelease releases by
    # splitting top-level objects and matching `"draft":false` and `"prerelease":false`.
    #
    # Note: this is best-effort without a JSON parser. If you need full fidelity,
    # install jq or pass a specific tag: sh install-oc-pocket.sh oc-pocket-v0.1.0
    printf "%s" "$json" \
      | tr '\n' ' ' \
      | sed 's/},{/}\n{/g' \
      | grep -E '"draft"[[:space:]]*:[[:space:]]*false' \
      | grep -E '"prerelease"[[:space:]]*:[[:space:]]*false' \
      | sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' \
      | grep '^oc-pocket-v' \
      | head -n 1
}

extract_release_asset_url() {
  json="$1"
  name="$2"
  if command -v jq >/dev/null 2>&1; then
    printf "%s" "$json" | jq -r --arg name "$name" '.assets[] | select(.name == $name) | .url' 2>/dev/null | head -n 1
    return 0
  fi

  # Best-effort fallback without jq: split objects and find the asset whose "name" matches.
  printf "%s" "$json" \
    | tr '\n' ' ' \
    | sed 's/},{/}\n{/g' \
    | grep -F "\"name\":\"${name}\"" \
    | head -n 1 \
    | sed -nE 's/.*"url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p'
}

tag="$TAG_ARG"
if [ -n "$tag" ]; then
  if ! validate_tag "$tag"; then
    echo "error: tag must look like oc-pocket-v0.1.0 (got: ${tag})" >&2
    echo "usage: sh install-oc-pocket.sh oc-pocket-v0.1.0" >&2
    exit 1
  fi
else
  echo "Fetching latest oc-pocket release tag…"

  # Prefer /releases/latest so we don’t accidentally pick draft/prerelease releases.
  set +e
  latest_json="$(curl_api "$API_RELEASE_LATEST" 2>/dev/null)"
  latest_status=$?
  set -e

  if [ $latest_status -eq 0 ] && [ -n "${latest_json:-}" ]; then
    tag="$(extract_tag_name "$latest_json" || true)"
  fi

  if ! validate_tag "${tag:-}"; then
    set +e
    releases_json="$(curl_api "$API_RELEASES" 2>/dev/null)"
    curl_status=$?
    set -e

    if [ $curl_status -ne 0 ] || [ -z "${releases_json:-}" ]; then
      echo "error: could not query GitHub releases for ${REPO}." >&2
      echo "If this repo is private or you're rate-limited, set GITHUB_TOKEN and retry." >&2
      echo "Or pass a specific tag: sh install-oc-pocket.sh oc-pocket-v0.1.0" >&2
      exit 1
    fi

    tag="$(extract_latest_tag "$releases_json" || true)"
  fi
fi

if ! validate_tag "${tag:-}"; then
  echo "error: could not find an oc-pocket release tag (expected tag_name starting with oc-pocket-v)" >&2
  exit 1
fi

version="${tag#oc-pocket-}"
archive="oc-pocket-${version}-darwin-universal.tar.gz"
checksum="${archive}.sha256"

echo "Downloading ${archive}…"
if [ -n "${GITHUB_TOKEN:-}" ]; then
  release_json="$(curl_api "${API_RELEASE_TAG}/${tag}")"
  archive_url="$(extract_release_asset_url "$release_json" "$archive")"
  checksum_url="$(extract_release_asset_url "$release_json" "$checksum")"

  if [ -z "${archive_url:-}" ] || [ -z "${checksum_url:-}" ]; then
    echo "error: could not locate release assets for tag ${tag} in ${REPO}" >&2
    echo "Expected assets: ${archive} and ${checksum}" >&2
    echo "Tip: verify the release exists and is not a draft/prerelease, or pass a different tag." >&2
    exit 1
  fi
else
  base="https://github.com/${REPO}/releases/download/${tag}"
  archive_url="${base}/${archive}"
  checksum_url="${base}/${checksum}"
fi

curl_download "$archive_url" "${tmpdir}/${archive}"
curl_download "$checksum_url" "${tmpdir}/${checksum}"

if [ "$NO_VERIFY" != "1" ]; then
  echo "Verifying checksum…"
  checksum_line="$(head -n 1 "${tmpdir}/${checksum}" 2>/dev/null || true)"
  checksum_hash="$(printf "%s" "$checksum_line" | awk '{print $1}')"
  checksum_rest="$(printf "%s" "$checksum_line" | awk '{print $2}')"

  case "$checksum_hash" in
    [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]*)
      ;;
    *)
      echo "error: checksum file does not look like sha256" >&2
      exit 1
      ;;
  esac

  if [ -n "${checksum_rest:-}" ]; then
    # Standard shasum -c format: "<hash>  <filename>"
    (cd "$tmpdir" && shasum -a 256 -c "$checksum")
  else
    # Hash-only format: "<hash>"
    actual_hash="$(shasum -a 256 "${tmpdir}/${archive}" | awk '{print $1}')"
    if [ "$actual_hash" != "$checksum_hash" ]; then
      echo "error: checksum mismatch" >&2
      exit 1
    fi
  fi
else
  echo "Skipping checksum verification (NO_VERIFY=1)."
fi

echo "Extracting…"
# Extract only the expected member to avoid archive path traversal surprises.
# Use -O to stream the file contents to stdout and write it ourselves, so we don't
# create symlinks or unexpected paths from the archive.
if tar -xOzf "${tmpdir}/${archive}" oc-pocket > "${tmpdir}/oc-pocket" 2>/dev/null; then
  :
elif tar -xOzf "${tmpdir}/${archive}" ./oc-pocket > "${tmpdir}/oc-pocket" 2>/dev/null; then
  :
else
  echo "error: failed to extract oc-pocket from archive" >&2
  exit 1
fi

if [ ! -s "${tmpdir}/oc-pocket" ]; then
  echo "error: extracted oc-pocket is empty or missing" >&2
  exit 1
fi

chmod +x "${tmpdir}/oc-pocket"

echo "Installing to ${INSTALL_DIR}…"
if [ ! -d "$INSTALL_DIR" ]; then
  if mkdir -p "$INSTALL_DIR" 2>/dev/null; then
    :
  else
    echo "Install directory is not writable; trying with sudo…" >&2
    if ! command -v sudo >/dev/null 2>&1; then
      echo "error: sudo not found. Set INSTALL_DIR to a user-writable directory (e.g. \$HOME/.local/bin) and retry." >&2
      exit 1
    fi
    sudo mkdir -p "$INSTALL_DIR"
  fi
fi

if [ -w "$INSTALL_DIR" ]; then
  mv "${tmpdir}/oc-pocket" "${INSTALL_DIR}/oc-pocket"
else
  echo "Install directory is not writable; trying with sudo…" >&2
  if ! command -v sudo >/dev/null 2>&1; then
    echo "error: sudo not found. Set INSTALL_DIR to a user-writable directory (e.g. \$HOME/.local/bin) and retry." >&2
    exit 1
  fi
  sudo mv "${tmpdir}/oc-pocket" "${INSTALL_DIR}/oc-pocket"
fi

echo ""
echo "Installed: ${INSTALL_DIR}/oc-pocket"
echo "Next: run 'oc-pocket setup'"
