#!/usr/bin/env bash
# End-to-end smoke test: archive -> OpenSearch indexing -> search
#
# Prerequisites:
#   - docker compose stack (OpenFire + OpenSearch)
#   - curl, python3, slixmpp (pip install slixmpp)
#
# Usage:
#   ./scripts/smoke-test-plugin-indexing-search.sh
#   ./scripts/smoke-test-plugin-indexing-search.sh --no-up
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
XMPP_HELPER="${ROOT_DIR}/scripts/smoke-test-plugin-indexing-search-xmpp.py"
SMOKE_VENV="${ROOT_DIR}/.venv-smoke-test"
PYTHON="${PYTHON:-}"

ADMIN_URL="${ADMIN_URL:-http://127.0.0.1:9090}"
OPENSEARCH_URL="${OPENSEARCH_URL:-http://127.0.0.1:9200}"
XMPP_HOST="${XMPP_HOST:-127.0.0.1}"
XMPP_PORT="${XMPP_PORT:-5222}"
XMPP_DOMAIN="${XMPP_DOMAIN:-localhost}"

ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
SMOKE_USER1="${SMOKE_USER1:-smoke1}"
SMOKE_PASS1="${SMOKE_PASS1:-smoke1}"
SMOKE_USER2="${SMOKE_USER2:-smoke2}"
SMOKE_PASS2="${SMOKE_PASS2:-smoke2}"

INDEX_PREFIX="${INDEX_PREFIX:-monitoring}"
ARCHIVE_SETTLE_SECONDS="${ARCHIVE_SETTLE_SECONDS:-10}"
REBUILD_TIMEOUT_SECONDS="${REBUILD_TIMEOUT_SECONDS:-180}"
IDLE_WAIT_SECONDS="${IDLE_WAIT_SECONDS:-30}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-300}"

DOCKER_UP=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-up) DOCKER_UP=0; shift ;;
    -h|--help)
      sed -n '1,20p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

COOKIE_JAR="$(mktemp)"
TMP_DIR="$(mktemp -d)"
trap 'rm -f "$COOKIE_JAR"; rm -rf "$TMP_DIR"' EXIT

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

wait_for_http() {
  local url="$1"
  local label="$2"
  local deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  step "Waiting for ${label} (${url})"
  while (( SECONDS < deadline )); do
    if curl -sf "$url" >/dev/null 2>&1; then
      pass "${label} is reachable"
      return 0
    fi
    sleep 3
  done
  fail "${label} did not become reachable within ${STARTUP_TIMEOUT_SECONDS}s"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

require_slixmpp() {
  if [[ -z "$PYTHON" ]]; then
    if [[ -x "${SMOKE_VENV}/bin/python" ]] && "${SMOKE_VENV}/bin/python" -c "import slixmpp" >/dev/null 2>&1; then
      PYTHON="${SMOKE_VENV}/bin/python"
    elif python3 -c "import slixmpp" >/dev/null 2>&1; then
      PYTHON="python3"
    else
      step "Creating smoke-test virtualenv and installing slixmpp"
      python3 -m venv "$SMOKE_VENV"
      "${SMOKE_VENV}/bin/python" -m pip install -q slixmpp
      PYTHON="${SMOKE_VENV}/bin/python"
    fi
  fi
  "$PYTHON" -c "import slixmpp" >/dev/null 2>&1 || fail "Unable to import slixmpp"
}

csrf_from_cookie() {
  awk '$6 == "csrf" { print $7; exit }' "$COOKIE_JAR"
}

admin_login() {
  local html="$TMP_DIR/login.html"
  local csrf
  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${ADMIN_URL}/login.jsp" -o "$html"
  csrf="$(grep -o 'name="csrf" value="[^"]*"' "$html" | head -1 | cut -d'"' -f4)"
  [[ -n "$csrf" ]] || csrf="$(csrf_from_cookie)"
  [[ -n "$csrf" ]] || return 1

  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X POST "${ADMIN_URL}/login.jsp" \
    --data-urlencode "username=${ADMIN_USER}" \
    --data-urlencode "password=${ADMIN_PASS}" \
    --data-urlencode "login=true" \
    --data-urlencode "csrf=${csrf}" \
    -o /dev/null

  local code
  code="$(
    curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
      -o "$TMP_DIR/index.html" \
      -w '%{http_code}' \
      "${ADMIN_URL}/index.jsp"
  )"
  [[ "$code" == "200" ]] && grep -qi "logout" "$TMP_DIR/index.html"
}

wait_for_admin() {
  local deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  step "Waiting for OpenFire admin login"
  while (( SECONDS < deadline )); do
    if admin_login; then
      pass "Admin login succeeded"
      return 0
    fi
    sleep 5
  done
  fail "Admin login did not succeed within ${STARTUP_TIMEOUT_SECONDS}s (expected ${ADMIN_USER}/${ADMIN_PASS})"
}

fetch_plugin_csrf() {
  local page="$1"
  local html="$TMP_DIR/page.html"
  local csrf
  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${ADMIN_URL}${page}" -o "$html"
  csrf="$(grep -o 'name="csrf" value="[^"]*"' "$html" | head -1 | cut -d'"' -f4)"
  if [[ -n "$csrf" ]]; then
    echo "$csrf"
    return 0
  fi
  csrf_from_cookie
}

admin_user_exists() {
  local username="$1"
  local html="$TMP_DIR/users.html"
  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "${ADMIN_URL}/user-summary.jsp?search=${username}&searchType=Username" \
    -o "$html"
  grep -Fq ">${username}<" "$html"
}

admin_create_user() {
  local username="$1"
  local password="$2"
  local html="$TMP_DIR/user-create.html"
  local csrf

  admin_login || fail "Admin session is required to create users"

  if admin_user_exists "$username"; then
    pass "User ${username} already exists"
    return 0
  fi

  step "Creating user ${username}"
  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${ADMIN_URL}/user-create.jsp" -o "$html"
  csrf="$(grep -o 'name="csrf" value="[^"]*"' "$html" | head -1 | cut -d'"' -f4)"
  [[ -n "$csrf" ]] || csrf="$(csrf_from_cookie)"
  [[ -n "$csrf" ]] || fail "Unable to read CSRF token from user-create.jsp"

  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X POST "${ADMIN_URL}/user-create.jsp" \
    --data-urlencode "csrf=${csrf}" \
    --data-urlencode "username=${username}" \
    --data-urlencode "name=${username}" \
    --data-urlencode "email=${username}@localhost" \
    --data-urlencode "password=${password}" \
    --data-urlencode "passwordConfirm=${password}" \
    --data-urlencode "create=Create User" \
    -o "$TMP_DIR/user-create-result.html"

  admin_user_exists "$username" || fail "Failed to create user ${username}"
  pass "Created user ${username}"
}

wait_for_index_idle() {
  local progress idle_since=-1 deadline=$((SECONDS + IDLE_WAIT_SECONDS))
  step "Waiting for any in-progress index rebuild to finish"
  while (( SECONDS < deadline )); do
    progress="$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${ADMIN_URL}/plugins/monitoring/api/buildprogress" | tr -d '"[:space:]')"
    if [[ "$progress" =~ ^[0-9]+$ && "$progress" -lt 100 ]]; then
      echo "  existing rebuild progress: ${progress}%"
      idle_since=-1
    elif [[ "$progress" == "-1" ]]; then
      if [[ "$idle_since" -lt 0 ]]; then
        idle_since=$SECONDS
      elif (( SECONDS - idle_since >= 3 )); then
        pass "OpenSearch index rebuild is idle"
        return 0
      fi
    fi
    sleep 2
  done
  echo "WARN: rebuild still in progress after ${IDLE_WAIT_SECONDS}s; continuing"
}

admin_rebuild_indexes() {
  local csrf
  step "Triggering OpenSearch index rebuild"
  admin_login || fail "Admin session is required to rebuild indexes"
  wait_for_index_idle
  csrf="$(fetch_plugin_csrf "/plugins/monitoring/archiving-settings.jsp")"
  [[ -n "$csrf" ]] || fail "Unable to read CSRF token from archiving-settings.jsp"

  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X POST "${ADMIN_URL}/plugins/monitoring/archiving-settings.jsp" \
    --data-urlencode "csrf=${csrf}" \
    --data-urlencode "rebuild=Rebuild Index" \
    -o "$TMP_DIR/rebuild.html"
  if grep -qi 'CSRF Failure' "$TMP_DIR/rebuild.html"; then
    fail "Index rebuild request was rejected (CSRF Failure)"
  fi

  local progress deadline=$((SECONDS + 60)) rebuild_started=0
  while (( SECONDS < deadline )); do
    progress="$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${ADMIN_URL}/plugins/monitoring/api/buildprogress" | tr -d '"[:space:]')"
    if [[ "$progress" =~ ^[0-9]+$ ]]; then
      rebuild_started=1
      echo "  rebuild progress: ${progress}%"
      if [[ "$progress" -ge 100 ]]; then
        break
      fi
    elif [[ "$progress" == "-1" && "$rebuild_started" -eq 1 ]]; then
      break
    elif [[ "$progress" == "-1" && $((SECONDS + 60 - deadline)) -ge -15 ]]; then
      # Rebuild may finish before the first progress poll.
      break
    fi
    sleep 2
  done
  pass "Index rebuild triggered"
}

wait_for_opensearch_token() {
  local index="$1"
  local field="$2"
  local token="$3"
  local deadline=$((SECONDS + REBUILD_TIMEOUT_SECONDS))
  step "Waiting for ${index} to contain token ${token}"
  while (( SECONDS < deadline )); do
    if [[ "$(opensearch_count "$index" "$field" "$token")" -ge 1 ]]; then
      pass "OpenSearch ${index} contains the token"
      return 0
    fi
    sleep 3
  done
  fail "Timed out waiting for token ${token} in OpenSearch index ${index}"
}

admin_search_archive() {
  local token="$1"
  local html="$TMP_DIR/archive-search.html"
  local deadline=$((SECONDS + REBUILD_TIMEOUT_SECONDS))
  step "Searching admin archive UI for token ${token}"
  while (( SECONDS < deadline )); do
    admin_login || fail "Admin session is required for archive search"
    curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
      -X POST "${ADMIN_URL}/plugins/monitoring/archive-search.jsp" \
      --data-urlencode "submitForm=Search" \
      --data-urlencode "keywords=${token}" \
      -o "$html"
    if grep -Fq "$token" "$html"; then
      pass "Admin archive search returned the token"
      return 0
    fi
    sleep 3
  done
  fail "Admin archive search did not return token ${token}"
}

opensearch_count() {
  local index="$1"
  local field="$2"
  local token="$3"
  curl -sS "${OPENSEARCH_URL}/${index}/_search" \
    -H 'Content-Type: application/json' \
    -d "{\"query\":{\"match\":{\"${field}\":\"${token}\"}}}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("hits", {}).get("total", {}).get("value", 0))'
}

opensearch_assert_hits() {
  local index="$1"
  local field="$2"
  local token="$3"
  local count
  step "Searching OpenSearch index ${index} for token ${token}"
  count="$(opensearch_count "$index" "$field" "$token")"
  if [[ "$count" -ge 1 ]]; then
    pass "OpenSearch ${index} returned ${count} hit(s)"
    return 0
  fi
  fail "OpenSearch ${index} returned no hits for token ${token}"
}

verify_opensearch_cluster() {
  step "Checking OpenSearch cluster health"
  curl -sS "${OPENSEARCH_URL}/_cluster/health?wait_for_status=yellow&timeout=60s" | \
    python3 -c "import json,sys; h=json.load(sys.stdin); sys.exit(0 if h.get('status') in ('yellow','green') else 1)" \
    || fail "OpenSearch cluster is not healthy"
  pass "OpenSearch cluster is healthy"
}

verify_monitoring_plugin() {
  step "Checking monitoring plugin API"
  curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "${ADMIN_URL}/plugins/monitoring/api/buildprogress" >/dev/null \
    || fail "Monitoring plugin API is not reachable"
  pass "Monitoring plugin API is reachable"
}

generate_tokens() {
  DM_TOKEN="smoke-dm-$(date +%s)-$RANDOM"
  MUC_TOKEN="smoke-muc-$(date +%s)-$RANDOM"
}

main() {
  require_command docker
  require_command curl
  require_command python3
  require_slixmpp

  if [[ "$DOCKER_UP" -eq 1 ]]; then
    step "Starting docker compose stack"
    (cd "$ROOT_DIR" && docker compose up -d --build)
  fi

  wait_for_http "${OPENSEARCH_URL}" "OpenSearch"
  verify_opensearch_cluster
  wait_for_admin
  verify_monitoring_plugin

  admin_create_user "$SMOKE_USER1" "$SMOKE_PASS1"
  admin_create_user "$SMOKE_USER2" "$SMOKE_PASS2"

  generate_tokens
  step "Sending archived XMPP traffic (DM + MUC)"
  "$PYTHON" "$XMPP_HELPER" \
    --host "$XMPP_HOST" \
    --port "$XMPP_PORT" \
    --domain "$XMPP_DOMAIN" \
    --user1 "$SMOKE_USER1" \
    --pass1 "$SMOKE_PASS1" \
    --user2 "$SMOKE_USER2" \
    --pass2 "$SMOKE_PASS2" \
    --dm-token "$DM_TOKEN" \
    --muc-token "$MUC_TOKEN"

  step "Waiting ${ARCHIVE_SETTLE_SECONDS}s for messages to be persisted"
  sleep "$ARCHIVE_SETTLE_SECONDS"

  admin_rebuild_indexes

  wait_for_opensearch_token "${INDEX_PREFIX}-messages" "body" "$DM_TOKEN"
  wait_for_opensearch_token "${INDEX_PREFIX}-conversations" "text" "$DM_TOKEN"
  wait_for_opensearch_token "${INDEX_PREFIX}-muc-messages" "body" "$MUC_TOKEN"

  admin_search_archive "$DM_TOKEN"

  echo
  echo "Smoke test completed successfully."
  echo "  DM token:  ${DM_TOKEN}"
  echo "  MUC token: ${MUC_TOKEN}"
}

main "$@"
