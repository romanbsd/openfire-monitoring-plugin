#!/usr/bin/env bash
# Run IM search e2e tests against the docker compose stack.
#
# Usage:
#   ./e2e/run.sh              # build stack + run tests
#   ./e2e/run.sh --no-up      # assume stack is already running
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="${ROOT_DIR}/e2e"

export SKIP_DOCKER_UP=0
export NODE_TLS_REJECT_UNAUTHORIZED=0
export OPENFIRE_XMPP_PORT="${OPENFIRE_XMPP_PORT:-15222}"
export OPENFIRE_ADMIN_PORT="${OPENFIRE_ADMIN_PORT:-19090}"
export OPENFIRE_S2S_PORT="${OPENFIRE_S2S_PORT:-15269}"
export OPENFIRE_BOSH_PORT="${OPENFIRE_BOSH_PORT:-17070}"
export OPENFIRE_SECURE_BOSH_PORT="${OPENFIRE_SECURE_BOSH_PORT:-17443}"
export XMPP_PORT="${XMPP_PORT:-$OPENFIRE_XMPP_PORT}"
export ADMIN_URL="${ADMIN_URL:-http://127.0.0.1:${OPENFIRE_ADMIN_PORT}}"

if [[ "${1:-}" == "--no-up" ]]; then
  export SKIP_DOCKER_UP=1
  shift
else
  echo "==> Starting docker compose stack (OpenFire ports: xmpp=${OPENFIRE_XMPP_PORT}, admin=${OPENFIRE_ADMIN_PORT})"
  (cd "$ROOT_DIR" && docker compose up -d --build)
fi

preflight_http() {
  local url="$1"
  local name="$2"
  if curl -sfS --max-time 3 "$url" >/dev/null 2>&1; then
    return 0
  fi
  echo "ERROR: Cannot reach ${name} at ${url}" >&2
  if [[ "${SKIP_DOCKER_UP}" == "1" ]]; then
    cat >&2 <<EOF
The stack may be running on different host ports than the e2e defaults.
Plain 'docker compose up' uses ports from the repo .env file (admin=${OPENFIRE_ADMIN_PORT}, xmpp=${OPENFIRE_XMPP_PORT}).
If you started the stack earlier without .env, recreate it:
  docker compose down
  docker compose up -d --build
Or run tests via ./e2e/run.sh (without --no-up).
See e2e/README.md for details.
EOF
  fi
  exit 1
}

echo "==> Preflight: opensearch=${OPENSEARCH_URL:-http://127.0.0.1:${OPENSEARCH_PORT:-9200}} admin=${ADMIN_URL}"
preflight_http "${OPENSEARCH_URL:-http://127.0.0.1:${OPENSEARCH_PORT:-9200}}/_cluster/health" "OpenSearch"
preflight_http "${ADMIN_URL}/login.jsp" "OpenFire admin"

cd "${E2E_DIR}"
if [[ ! -d node_modules ]]; then
  npm install
fi
npm test
