#!/usr/bin/env bash
# Starts the AndroidCamera GUI to connect phones to OBS.
#
# This script:
#   1. Compiles (if missing) the USB Device ACUS -> V4L2 driver.
#   2. Loads v4l2loopback with at least V4L2_SLOTS slots (read from .env).
#      Uses auto-assignment (modprobe devices=N exclusive_caps=0 max_buffers=8,
#      without video_nr/card_label: the dkms in use has a fixed-size 8-entry
#      video_nr array, and pinning N>8 would fail with EINVAL). If the module
#      already has >= N of our slots it does nothing; if it has fewer it
#      unloads and reloads with N; if it cannot be unloaded (OBS/VLC holds
#      a /dev/videoN open) it blocks with an error, because without the
#      required slots the program cannot function.
#      Requires root privileges (sudo), but the "already enough slots" path
#      does not request elevation. "Our" = device whose driver in
#      /sys/class/video4linux is v4l2loopback (real webcams do not count).
#   3. Prepares the venv and launches the Python GUI.
#
# V4L2_SLOTS comes from the environment (run-linux-gui.sh sources .env) or
# is read directly from .env safely (without sourcing it).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${DIR}/.." && pwd)"

# Prefer Android SDK platform-tools if present (adb is often not on PATH).
if [[ -z "${ANDROID_HOME:-}" && -d "${HOME}/Android/Sdk" ]]; then
  export ANDROID_HOME="${HOME}/Android/Sdk"
fi
if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/platform-tools" ]]; then
  export PATH="${ANDROID_HOME}/platform-tools:${PATH}"
fi

# Compile the USB Device driver (ACUS → V4L2) if missing.
DRIVER_DIR="${ROOT}/AndroidCameraUsbDriver"
if [[ -f "${DRIVER_DIR}/Makefile" && ! -x "${DRIVER_DIR}/bin/acus_driver" ]]; then
  echo "Building AndroidCameraUsbDriver…"
  make -C "${DRIVER_DIR}"
fi
export ACUS_DRIVER="${ACUS_DRIVER:-${DRIVER_DIR}/bin/acus_driver}"

# ── v4l2loopback: at least V4L2_SLOTS slots (auto-assignment) ─────────────────
# Ensures that the v4l2loopback module is loaded with at least N of our
# slots (N = V4L2_SLOTS read from .env; "our" = device whose driver in
# /sys/class/video4linux is v4l2loopback, see loaded_v4l2_count) BEFORE
# the Python GUI starts. The GUI (bridge.py::_ensure_enough_v4l2_slots)
# assumes the slots already exist at startup and never runs modprobe -r/reload
# at runtime (it would fail as soon as a consumer opens a /dev/videoN).
#
# Loading via auto-assignment: modprobe devices=N exclusive_caps=0
# max_buffers=8. We do NOT pass video_nr/card_label: the dkms in use has a
# FIXED-SIZE 8-entry video_nr array, and pinning N>8 would fail with EINVAL.
# With auto-assignment it is the /dev/videoN indices that the kernel chooses
# (may include /dev/video0): the GUI does not depend on pinned indices
# because it reallocates the /dev/videoN per-phone via mknod. See README.md
# and comandi-linux.md.
#
# Logic of ensure_v4l2_slots:
#   * have >= want → no-op (no sudo prompt): the happy path.
#   * have == 0    → modprobe devices=N.
#   * 0 < have < want → modprobe -r then modprobe devices=N; if unloading
#                      fails (module busy) → error + block.
# The "have" count is aligned with bridge.py::_loaded_v4l2_device_count
# (devices → driver-symlink in sysfs → name not empty) so run.sh and the GUI
# cannot disagree.

# Reads V4L2_SLOTS from the environment (if present and valid) or from .env
# in the repo root, safely (without sourcing it: .env may contain other
# variables not safe to export to the Python child). Validates integer >= 1.
read_v4l2_slots_from_env() {
  local _val=""
  if [[ -n "${V4L2_SLOTS:-}" && "${V4L2_SLOTS}" =~ ^[1-9][0-9]*$ ]]; then
    return                  # already valid in the environment (exported by run-linux-gui.sh)
  fi
  local _env_file=""
  for _candidate in "${ROOT}/.env" "${DIR}/.env"; do
    if [[ -f "${_candidate}" ]]; then
      _env_file="${_candidate}"
      break
    fi
  done
  if [[ -n "${_env_file}" ]]; then
    local _line _k
    while IFS= read -r _line || [[ -n "${_line}" ]]; do
      [[ -z "${_line}" || "${_line}" =~ ^[[:space:]]*# ]] && continue
      _k="${_line%%=*}"
      if [[ "${_k}" == "V4L2_SLOTS" ]]; then
        _val="${_line#*=}"
        _val="${_val%%#*}"
        _val="${_val#"${_val%%[![:space:]]*}"}"
        _val="${_val%"${_val##*[![:space:]]}"}"
        case "${_val}" in \'*) _val="${_val#\'}"; _val="${_val%\'}" ;; \"*) _val="${_val#\"}"; _val="${_val%\"}" ;; esac
        break
      fi
    done < "${_env_file}"
    unset _line _k
  fi
  if ! [[ "${_val}" =~ ^[1-9][0-9]*$ ]]; then
    echo "Warning: V4L2_SLOTS='${_val}' is not a valid integer >= 1; using 10." >&2
    V4L2_SLOTS=10
  else
    V4L2_SLOTS="${_val}"
  fi
  export V4L2_SLOTS        # the Python child sees the same N with which we created the slots
}

# Counts how many v4l2loopback devices are currently active (NOT real
# webcams like uvcvideo & similar). No root needed: sysfs reads only.
#
# Fallback order:
#  1. /sys/module/v4l2loopback/parameters/devices  (integer) -- dkms
#     versions that expose this parameter (the value is the module's total
#     count).
#  2. Count the /sys/class/video4linux/videoN nodes whose symlink
#     device/driver points to v4l2loopback -- this is the source of truth
#     even with auto-assignment (video_nr all -1), because it distinguishes
#     our devices from real webcams. Aligned with bridge.py::_loaded_v4l2_device_count.
#  3. Last resort: count videoN with non-empty name (imprecise, but better
#     than 0).
#
# NOTE: the old branch that counted >=0 in parameters/video_nr has been
# removed. On many dkms video_nr is a fixed-size array (here 8 entries)
# filled with -1 when the module uses auto-assignment: counting non-negatives
# would yield 0 even with 10 devices active.
loaded_v4l2_count() {
  if [[ ! -d /sys/module/v4l2loopback ]]; then
    echo 0; return
  fi
  local _f
  _f="/sys/module/v4l2loopback/parameters/devices"
  if [[ -r "${_f}" ]]; then
    local _d
    _d="$(cat "${_f}" 2>/dev/null || echo '')"
    _d="${_d:-0}"
    if [[ "${_d}" =~ ^[0-9]+$ ]]; then
      echo "${_d}"; return
    fi
  fi
  # Source of truth: count videoN whose driver is v4l2loopback.
  local _base="/sys/class/video4linux"
  if [[ -d "${_base}" ]]; then
    local _n=0 _e _drv
    for _e in "${_base}"/*; do
      [[ -e "${_e}" ]] || continue
      [[ "${_e##*/}" =~ ^video[0-9]+$ ]] || continue
      _drv=""
      if _drv="$(readlink -f "${_e}/device/driver" 2>/dev/null)"; then
        _drv="${_drv##*/}"
        if [[ "${_drv}" == "v4l2loopback" ]]; then
          _n=$((_n + 1))
          continue
        fi
      fi
    done
    if (( _n > 0 )); then
      echo "${_n}"; return
    fi
    # Last resort: count videoN with non-empty name.
    local _name
    for _e in "${_base}"/*; do
      [[ -e "${_e}" ]] || continue
      [[ "${_e##*/}" =~ ^video[0-9]+$ ]] || continue
      _name=""
      if [[ -r "${_e}/name" ]]; then
        _name="$(cat "${_e}/name" 2>/dev/null || echo '')"
      fi
      if [[ -n "${_name}" ]]; then
        _n=$((_n + 1))
      fi
    done
    echo "${_n}"; return
  fi
  echo 0
}

# NOTE: We do NOT pin /dev/videoN with modprobe video_nr= / card_label=.
# On the dkms in use the video_nr array has FIXED size (8 entries):
# passing a list of N>V4L2_SLOTS_VIDEONR_MAX elements would fail modprobe
# with "could not insert 'v4l2loopback': Invalid argument" (EINVAL from
# array overflow), as verified with V4L2_SLOTS=10. So we let v4l2loopback
# auto-assign free indices (modprobe with only devices=N exclusive_caps=0
# max_buffers=8 accepts loading even with N>8). The downside is we don't
# control which /dev/videoN are assigned (might include /dev/video0): the
# GUI then reallocates the /dev/videoN per-phone by reading
# /sys/class/video4linux and using mknod, so it does not depend on pinned
# indices from run.sh. See comandi-linux.md for details.

# Builds the SUDO[] array used to prefix privileged commands.
# Pattern: if already root → no prefix; if a valid sudo ticket exists
# (sudo -n) → reuse it; otherwise a one-time interactive prompt (sudo -v)
# so subsequent modprobes use the freshly created ticket. No pkexec.
acquire_root_helper() {
  SUDO=()
  if [[ "$(id -u)" -eq 0 ]]; then
    return                       # already root (sudo ./run-linux-gui.sh)
  fi
  if sudo -n true 2>/dev/null; then
    SUDO=(sudo)                  # sudo ticket already valid (no prompt)
    return
  fi
  # No valid ticket: ask for the password now (interactive sudo -v), so
  # subsequent modprobes use the freshly created ticket.
  echo "Root privileges are required to load v4l2loopback…" >&2
  if ! sudo -v; then
    cat >&2 <<'MSG'
Error: root privileges are required to modprobe v4l2loopback, but no
elevation method is available. Run `sudo -v` in the terminal first, or
launch with `sudo ./run-linux-gui.sh`.
MSG
    exit 1
  fi
  SUDO=(sudo)
}

# The orchestrator: ensures at least V4L2_SLOTS of our v4l2loopback slots.
# Exits non-zero (with an English message) if it cannot guarantee them,
# blocking the GUI. "Our" = device whose driver in /sys/class/video4linux
# is v4l2loopback (see loaded_v4l2_count): real webcams and third-party
# loopback devices do not count.
ensure_v4l2_slots() {
  local want="${V4L2_SLOTS}"
  local have
  have="$(loaded_v4l2_count)"

  if (( have >= want )); then
    echo "v4l2loopback already loaded with ${have} slot(s) (>= ${want}): nothing to do."
    return                          # HAPPY PATH: no sudo prompt
  fi

  acquire_root_helper              # root needed only in branches that modprobe

  if (( have == 0 )); then          # loading from scratch
    echo "Loading v4l2loopback: devices=${want} exclusive_caps=0 max_buffers=8"
    if ! "${SUDO[@]}" modprobe v4l2loopback \
        devices="${want}" \
        exclusive_caps=0 \
        max_buffers=8; then
      cat >&2 <<'MSG'
Error: modprobe v4l2loopback failed (see the “Loading v4l2loopback: …” line above
for the exact parameters). Make sure v4l2loopback-dkms is installed (run
./install-deps.sh) and compiled for the running kernel.
MSG
      exit 1
    fi
    sleep 0.3
    local after
    after="$(loaded_v4l2_count)"
    if (( after < want )); then
      echo "Error: loaded only ${after} slot(s) of ${want} requested." >&2
      exit 1
    fi
    echo "v4l2loopback loaded with ${after} slot(s)."
    return
  fi

  # have > 0 and != want → reload
  echo "v4l2loopback loaded with ${have} slot(s); need ${want} — unloading and reloading…" >&2
  if ! "${SUDO[@]}" modprobe -r v4l2loopback; then
    cat >&2 <<'MSG'
Error: cannot unload v4l2loopback to recreate the slots.
A consumer (typically OBS, VLC, or a browser) holds /dev/videoN open, so the
kernel refuses to remove the module. Close OBS/VLC and any other program using
the webcam, then relaunch the GUI via ./run-linux-gui.sh.
MSG
    exit 1
  fi
  sleep 0.2
  echo "Reloading v4l2loopback: devices=${want} exclusive_caps=0 max_buffers=8"
  if ! "${SUDO[@]}" modprobe v4l2loopback \
      devices="${want}" \
      exclusive_caps=0 \
      max_buffers=8; then
    cat >&2 <<'MSG'
Error: modprobe v4l2loopback failed after unloading (see the “Reloading
v4l2loopback: …” line above for the exact parameters). Make sure
v4l2loopback-dkms is installed and compatible with the running kernel
(run ./install-deps.sh).
MSG
    exit 1
  fi
  sleep 0.3
  local after
  after="$(loaded_v4l2_count)"
  if (( after < want )); then
    echo "Error: reloaded only ${after} slot(s) of ${want} requested." >&2
    exit 1
  fi
  echo "v4l2loopback reloaded with ${after} slot(s)."
}

read_v4l2_slots_from_env
ensure_v4l2_slots

# ── venv + dependencies ─────────────────────────────────────────────────────────
# 1) Verify/create a local venv. If ``python3 -m venv`` fails
#    because the venv module is not installed, we report it to the user
#    with a clear message (on Debian/Ubuntu the package is
#    ``python3-venv``).
VENV_DIR="${DIR}/.venv"
VENV_PY="${VENV_DIR}/bin/python3"
if [[ ! -x "${VENV_PY}" ]]; then
  if ! command -v python3 >/dev/null 2>&1; then
    echo "Error: python3 not found in PATH" >&2
    exit 1
  fi
  echo "Creating venv in ${VENV_DIR}…"
  if ! python3 -m venv "${VENV_DIR}" 2>/tmp/androidcamera-venv.err; then
    cat /tmp/androidcamera-venv.err >&2
    echo "" >&2
    echo "Error: could not create the venv." >&2
    echo "Install the venv module (Debian/Ubuntu: sudo apt install python3-venv)" >&2
    exit 1
  fi
fi

# 2) Upgrade pip in the venv (necessary on Debian/Ubuntu where pip is
#    old and blocks installing recent wheels like Pillow).
echo "Upgrading pip in the venv…"
"${VENV_PY}" -m pip install --quiet --disable-pip-version-check --upgrade pip \
  || echo "Warning: pip upgrade failed, proceeding with dependency install anyway."

# 3) Install ALL dependencies listed in requirements.txt. Without
#    Pillow the "In-app preview" window shows a black Canvas; here
#    Pillow is installed automatically on first GUI launch
#    (or at every launch, idempotently).
REQ_FILE="${DIR}/requirements.txt"
if [[ -f "${REQ_FILE}" ]]; then
  echo "Installing Python dependencies from ${REQ_FILE}…"
  if ! "${VENV_PY}" -m pip install --disable-pip-version-check -r "${REQ_FILE}" 2>/tmp/androidcamera-pip.err; then
    cat /tmp/androidcamera-pip.err >&2
    echo "" >&2
    echo "Error: could not install the Python dependencies." >&2
    echo "Check the network connection and permissions, then restart." >&2
    exit 1
  fi
else
  echo "Error: ${REQ_FILE} missing, cannot install dependencies." >&2
  exit 1
fi

# 4) Verify that Pillow is actually importable. Without this check,
#    a silently failed pip (e.g. due to network limits, proxy,
#    unreachable mirror) would start the GUI without a working
#    "In-app preview". If Pillow is still missing, we retry
#    the explicit installation of Pillow with full output to
#    help with debugging.
if ! "${VENV_PY}" -c "from PIL import Image, ImageTk" >/dev/null 2>&1; then
  echo "Pillow not importable in the venv, retrying the install…"
  if ! "${VENV_PY}" -m pip install --disable-pip-version-check Pillow 2>/tmp/androidcamera-pillow.err; then
    cat /tmp/androidcamera-pillow.err >&2
    echo "" >&2
    echo "Error: could not install Pillow." >&2
    echo "The «In-app preview» window will not work without Pillow." >&2
    echo "Check the network connection and permissions, then restart." >&2
    exit 1
  fi
  # Re-verify post-install.
  if ! "${VENV_PY}" -c "from PIL import Image, ImageTk" >/dev/null 2>&1; then
    echo "Error: Pillow installed but not importable. Check the venv permissions." >&2
    exit 1
  fi
fi

export PYTHONPATH="${DIR}${PYTHONPATH:+:$PYTHONPATH}"

# Clean the GUI's __pycache__ bytecode cache before every startup: a stale
# .pyc (e.g. bridge.cpython-311.pyc from a previous version of bridge.py)
# would cause Python to load missing/obsolete methods instead of the current
# .py files, generating errors like
# "name 'watchdog_disable_if_stopped' is not defined" repeated on every
# watchdog tick. The removal here guarantees the GUI starts from the current
# sources.
find "${DIR}/android_camera_gui" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true

# Second line of defense against stale .pyc files: -B tells Python to NOT
# use or write bytecode cache for this process, and PYTHONDONTWRITEBYTECODE=1
# does the same for every child process (e.g. subprocesses that import the
# GUI). So even if a .pyc slipped past the find above, Python would never
# load it — it always recompiles from current .py sources. The ~1s startup
# cost is acceptable compared to a NameError spread by the watchdog.
export PYTHONDONTWRITEBYTECODE=1
exec "${VENV_PY}" -B -m android_camera_gui "$@"
