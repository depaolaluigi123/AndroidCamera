from __future__ import annotations

import os
import platform
import shutil
import subprocess
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


@dataclass(frozen=True)
class AdbDevice:
    serial: str
    state: str
    model: str = ""
    transport: str = "unknown"


def _get_bin_dir() -> Path:
    """Return the project's bin/ directory for bundled adb."""
    project_root = Path(__file__).resolve().parents[2]
    return project_root / "AndroidCameraLinuxGui" / "android_camera_gui" / "bin"


def _get_adb_name() -> str:
    return "adb.exe" if platform.system().lower() == "windows" else "adb"


def _get_bundled_adb_path() -> Path:
    return _get_bin_dir() / _get_adb_name()


def find_bundled_adb() -> str | None:
    """
    Look for a bundled adb binary in the project's bin/ directory.
    Returns the absolute path if found and executable, otherwise None.
    """
    adb_path = _get_bundled_adb_path()
    if adb_path.is_file() and os.access(adb_path, os.X_OK):
        return str(adb_path)
    return None


def download_adb(log: Callable[[str], None] | None = None) -> str:
    """
    Download and extract adb from Google's official platform-tools into the project's bin/ directory.
    Returns the absolute path to the adb binary.
    Raises RuntimeError on failure.
    """
    bin_dir = _get_bin_dir()
    bin_dir.mkdir(parents=True, exist_ok=True)

    system = platform.system().lower()
    adb_name = _get_adb_name()
    adb_path = bin_dir / adb_name

    urls = {
        "linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
        "darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
        "windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    }

    url = urls.get(system)
    if not url:
        raise RuntimeError(f"Piattaforma non supportata per download automatico adb: {system}")

    zip_path = bin_dir / "platform-tools.zip"
    try:
        if log:
            log("adb non trovato in bin/. Download da Google platform-tools...")

        if log:
            log(f"Scaricamento {url} ...")
        urllib.request.urlretrieve(url, zip_path)

        if log:
            log("Estrazione adb...")

        with zipfile.ZipFile(zip_path, "r") as zf:
            adb_in_zip = None
            for name in zf.namelist():
                if name.endswith(f"/{adb_name}") or name == f"platform-tools/{adb_name}":
                    adb_in_zip = name
                    break
            if not adb_in_zip:
                raise RuntimeError(f"adb non trovato nell'archivio platform-tools")

            zf.extract(adb_in_zip, bin_dir)
            extracted = bin_dir / adb_in_zip
            if extracted != adb_path:
                shutil.move(str(extracted), str(adb_path))
                extracted_parent = extracted.parent
                if extracted_parent != bin_dir:
                    shutil.rmtree(extracted_parent, ignore_errors=True)

        if system != "windows":
            adb_path.chmod(0o755)

        if log:
            log(f"adb installato in {adb_path}")
        return str(adb_path)

    except Exception as e:
        if log:
            log(f"Errore download adb: {e}")
        raise
    finally:
        if zip_path.exists():
            zip_path.unlink(missing_ok=True)


def ensure_adb_available(log: Callable[[str], None] | None = None) -> str:
    """
    Ensure adb is available in the project's bin/ directory.
    If not present, downloads it automatically.
    Returns the absolute path to adb.
    """
    bundled = find_bundled_adb()
    if bundled:
        return bundled
    return download_adb(log)


class AdbClient:
    def __init__(self, log: Callable[[str], None] | None = None) -> None:
        """
        Initialize AdbClient with bundled adb.
        Downloads adb automatically if not present in bin/.
        """
        self.adb_path = ensure_adb_available(log)

    def _run(
        self,
        args: list[str],
        *,
        timeout: float = 8.0,
        check: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.adb_path, *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=check,
        )

    def devices(self) -> list[AdbDevice]:
        proc = self._run(["devices", "-l"], timeout=5)
        result: list[AdbDevice] = []
        for line in proc.stdout.splitlines()[1:]:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            model = ""
            transport = "usb"
            for token in parts[2:]:
                if token.startswith("model:"):
                    model = token.split(":", 1)[1].replace("_", " ")
                if token.startswith("transport_id:"):
                    pass
                if "usb:" in token:
                    transport = "usb"
            if serial.startswith("emulator-"):
                transport = "emulator"
            result.append(
                AdbDevice(serial=serial, state=state, model=model or serial, transport=transport)
            )
        return result

    def shell(self, serial: str, command: str, timeout: float = 8.0) -> str:
        proc = self._run(["-s", serial, "shell", command], timeout=timeout)
        return (proc.stdout or "") + (proc.stderr or "")

    def enable_usb_tethering(self, serial: str) -> None:
        try:
            self._run(
                ["-s", serial, "shell", "svc usb setFunctions rndis,adb"],
                timeout=6,
            )
        except subprocess.TimeoutExpired:
            pass

    def forward(self, serial: str, port: int) -> bool:
        """Map host localhost:port → device localhost:port (USB Device / ACUS)."""
        try:
            proc = self._run(
                ["-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"],
                timeout=6,
            )
            return proc.returncode == 0
        except subprocess.TimeoutExpired:
            return False

    def forward_remove(self, serial: str, port: int) -> None:
        try:
            self._run(
                ["-s", serial, "forward", "--remove", f"tcp:{port}"],
                timeout=6,
            )
        except subprocess.TimeoutExpired:
            pass

    def list_forwards(self, serial: str) -> list[tuple[int, int]]:
        """Return [(host_port, device_port), ...] for all active forwards."""
        try:
            proc = self._run(["forward", "--list"], timeout=5)
        except subprocess.TimeoutExpired:
            return []
        forwards: list[tuple[int, int]] = []
        for line in proc.stdout.splitlines():
            if not line.startswith(serial):
                continue
            # e.g. "84e001b2 tcp:8082 tcp:8081"
            parts = line.strip().split()
            if len(parts) < 2:
                continue
            try:
                host_part = parts[1]  # tcp:8082
                device_part = parts[2] if len(parts) > 2 else parts[1]
                host_port = int(host_part.split(":")[1])
                device_port = int(device_part.split(":")[1])
                forwards.append((host_port, device_port))
            except (ValueError, IndexError):
                continue
        return forwards

    def list_ipv4(self, serial: str) -> list[str]:
        out = self.shell(serial, "ip -4 addr show", timeout=6)
        ips: list[str] = []
        for line in out.splitlines():
            line = line.strip()
            if not line.startswith("inet "):
                continue
            # inet 192.168.95.71/24 ...
            token = line.split()[1]
            ip = token.split("/", 1)[0]
            if ip and not ip.startswith("127."):
                ips.append(ip)
        return ips
    