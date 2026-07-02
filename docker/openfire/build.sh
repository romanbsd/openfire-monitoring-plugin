#!/usr/bin/env bash
# Build the OpenFire + monitoring plugin image.
# Context MUST be the repository root (not this directory).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_NAME="${1:-openfire-monitoring-plugin-openfire}"
OPENFIRE_VERSION="${OPENFIRE_VERSION:-5.1.0}"

exec docker build \
  -f "${ROOT_DIR}/docker/openfire/Dockerfile" \
  --build-arg "OPENFIRE_VERSION=${OPENFIRE_VERSION}" \
  -t "${IMAGE_NAME}" \
  "${ROOT_DIR}"
