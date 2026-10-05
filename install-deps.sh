#!/usr/bin/env bash
# Installs AndroidCamera's apt dependencies (Linux GUI that hosts
# Android phones as /dev/videoN devices via v4l2loopback).
#
# Installs ONLY the packages needed for the app to function. Does NOT load
# v4l2loopback and does NOT configure any automatic loading at OS boot: the module
# is loaded by run.sh (called by run-linux-gui.sh) at each GUI startup
# with at least V4L2_SLOTS slots (read from the .env file; auto-assignment).
set -euo pipefail


usage() {
  cat <<'EOF'
Usage: ./install-deps.sh [options]

Installs the PC dependencies needed to use phones as OBS webcams:
  v4l2loopback-dkms, ffmpeg, curl, python3, python3-tk, adb, linux-headers, …

This script only installs system packages. v4l2loopback is NOT loaded here
and NOT loaded at OS boot: the module is loaded by run.sh (called by
run-linux-gui.sh) at each GUI startup with V4L2_SLOTS slot(s) read from
the .env file.

Options:
  -h, --help       Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown option: ${arg}" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ "$(id -u)" -eq 0 ]]; then
  SUDO=()
else
  if ! command -v sudo >/dev/null 2>&1; then
    echo "Root or sudo is required to install the packages." >&2
    exit 1
  fi
  SUDO=(sudo)
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "This script supports only apt-based systems (Debian/Ubuntu)." >&2
  exit 1
fi

HEADERS="linux-headers-$(uname -r)"
PKGS=(
  v4l2loopback-dkms
  v4l2loopback-utils
  ffmpeg
  curl
  python3
  python3-tk
  adb
  "${HEADERS}"
)

echo "==> apt update"
"${SUDO[@]}" apt-get update

echo "==> Installing: ${PKGS[*]}"
"${SUDO[@]}" DEBIAN_FRONTEND=noninteractive apt-get install -y "${PKGS[@]}"

echo
echo "Done."
echo "  v4l2loopback is NOT loaded by this script and NOT loaded at OS boot."
echo "  It will be loaded by run.sh at GUI startup with V4L2_SLOTS slot(s)."
echo "  GUI:  ./run-linux-gui.sh"
