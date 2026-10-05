"""Tiny env loader: read KEY=VALUE pairs from the repo-root .env file.

The AndroidCameraLinuxGui depends on a couple of values the user sets in
the repo-root ``.env`` (e.g. ACUS_MAX_RETRIES for the ACUS preview hub).
This helper keeps that side-effect isolated so the rest of the GUI does
not have to know where the file lives.
"""

from __future__ import annotations

import os
from pathlib import Path


def env_file_path() -> Path:
    # android_camera_gui/ -> AndroidCameraLinuxGui/ -> repo root
    return Path(__file__).resolve().parents[2] / ".env"


def load_root_env() -> None:
    """Load KEY=VALUE pairs from repo-root .env into os.environ (no overwrite)."""
    path = env_file_path()
    if not path.is_file():
        raise FileNotFoundError(f"File .env mancante: {path}")
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip("'").strip('"')
        if key and key not in os.environ:
            os.environ[key] = value
