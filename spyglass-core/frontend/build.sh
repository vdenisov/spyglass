#!/usr/bin/env bash
# Builds the vendored CodeMirror editor bundle in a Node container, so no local Node
# toolchain is required. Run from anywhere: `frontend/build.sh`.
#
# Produces:
#   api-explorer/src/main/resources/META-INF/resources/apidocs/vendor/codemirror.bundle.js
#   frontend/THIRD-PARTY-NOTICES.txt
set -euo pipefail
cd "$(dirname "$0")"

# Docker Desktop needs a host path it understands: a Windows path (C:/...) on Git Bash,
# a POSIX path on Linux/macOS. cygpath -m yields the mixed C:/... form under Git Bash.
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -m "$(cd .. && pwd)")"
else
  ROOT="$(cd .. && pwd)"
fi

IMAGE="node:20-alpine"
echo "Building CodeMirror bundle in ${IMAGE} (repo root: ${ROOT})…"
MSYS_NO_PATHCONV=1 docker run --rm -v "${ROOT}:/work" -w /work/frontend "${IMAGE}" sh -c '
  set -e
  npm ci --no-audit --no-fund --loglevel=error
  node build.mjs
  echo "=== npm audit (production deps, informational) ==="
  npm audit --omit=dev || true
'
echo "Done."
