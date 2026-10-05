#!/usr/bin/env bash
# Starts the Python GUI (AndroidCameraLinuxGui) reading .env from the root.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
  set +a
fi
exec "${ROOT}/AndroidCameraLinuxGui/run.sh" "$@"
