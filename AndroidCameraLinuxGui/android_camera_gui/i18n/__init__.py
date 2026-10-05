"""Locale registry: pick a Strings table by language code (no UI branching)."""

from __future__ import annotations

import json
from pathlib import Path

from . import en, it
from .strings_base import Strings

_LOCALES: dict[str, Strings] = {
    en.STRINGS.language_code: en.STRINGS,
    it.STRINGS.language_code: it.STRINGS,
}

DEFAULT_LANGUAGE = "it"
CONFIG_DIR = Path.home() / ".config" / "androidcamera-gui"
CONFIG_FILE = CONFIG_DIR / "config.json"


def available_languages() -> tuple[str, ...]:
    return tuple(_LOCALES.keys())


def get_strings(language_code: str | None = None) -> Strings:
    code = (language_code or DEFAULT_LANGUAGE).lower()
    return _LOCALES.get(code, _LOCALES[DEFAULT_LANGUAGE])


def load_saved_language() -> str:
    try:
        data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        code = str(data.get("language", DEFAULT_LANGUAGE)).lower()
        if code in _LOCALES:
            return code
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        pass
    return DEFAULT_LANGUAGE


def save_language(language_code: str) -> None:
    code = language_code.lower()
    if code not in _LOCALES:
        code = DEFAULT_LANGUAGE
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(
        json.dumps({"language": code}, indent=2) + "\n",
        encoding="utf-8",
    )


__all__ = [
    "Strings",
    "available_languages",
    "get_strings",
    "load_saved_language",
    "save_language",
    "DEFAULT_LANGUAGE",
]
