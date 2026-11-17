"""ADB discovery and small, bounded device operations."""

from __future__ import annotations

from dataclasses import dataclass
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence
from uuid import uuid4


GAME_PACKAGE = "com.palmmud.xyjclient"


class AdbError(RuntimeError):
    pass


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    details: str


def _wsl_adb_command() -> list[str] | None:
    wsl = shutil.which("wsl.exe") or shutil.which("wsl")
    if not wsl:
        return None
    try:
        result = subprocess.run([wsl, "--list", "--quiet"], capture_output=True,
                                timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    raw = result.stdout.decode("utf-16-le", errors="ignore")
    distros = [line.strip().replace("\x00", "") for line in raw.splitlines() if line.strip()]
    distro = next((name for name in distros if "Ubuntu" in name), None)
    if distro:
        return [wsl, "-d", distro, "--exec", "adb"]
    return None


def _windows_adb() -> Path | None:
    import winreg

    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "LOCALAPPDATA"):
        value = os.environ.get(variable)
        if value:
            root = Path(value)
            if variable == "LOCALAPPDATA":
                root = root / "Android" / "Sdk"
            candidate = root / "platform-tools" / "adb.exe"
            if candidate.is_file():
                return candidate

    uninstall = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"
    for hive, view in (
        (winreg.HKEY_CURRENT_USER, 0),
        (winreg.HKEY_LOCAL_MACHINE, winreg.KEY_WOW64_32KEY),
        (winreg.HKEY_LOCAL_MACHINE, winreg.KEY_WOW64_64KEY),
    ):
        try:
            with winreg.OpenKey(hive, uninstall, 0, winreg.KEY_READ | view) as key:
                count = winreg.QueryInfoKey(key)[0]
                for index in range(count):
                    try:
                        with winreg.OpenKey(key, winreg.EnumKey(key, index)) as entry:
                            name = str(winreg.QueryValueEx(entry, "DisplayName")[0]).casefold()
                            if "ldplayer" not in name and "雷电模拟器" not in name:
                                continue
                            icon = str(winreg.QueryValueEx(entry, "DisplayIcon")[0])
                        icon_path = Path(os.path.expandvars(icon.rsplit(",", 1)[0].strip().strip('"')))
                        candidate = icon_path.parent / "adb.exe"
                        if candidate.is_absolute() and candidate.is_file():
                            return candidate
                    except OSError:
                        continue
        except OSError:
            continue
    return None


def adb_command(explicit: str | None = None) -> list[str]:
    configured = explicit or os.environ.get("NITAN_ADB")
    if configured:
        path = shutil.which(configured) or configured
        if Path(path).is_file():
            return [path]
        raise AdbError(f"ADB executable not found: {configured}")
    found = shutil.which("adb")
    if found:
        return [found]
    if sys.platform == "win32":
        native = _windows_adb()
        if native:
            return [str(native)]
        wsl_command = _wsl_adb_command()
        if wsl_command:
            return wsl_command
    raise AdbError("ADB not found. Install Android Platform Tools or set NITAN_ADB.")


class AdbClient:
    def __init__(self, command: Sequence[str], serial: str | None = None, timeout: float = 15):
        self.command = list(command)
        self.serial = serial
        self.timeout = timeout

    def _args(self, args: Sequence[str]) -> list[str]:
        prefix = [*self.command]
        if self.serial:
            prefix.extend(["-s", self.serial])
        return [*prefix, *args]

    def run_bytes(self, *args: str, timeout: float | None = None) -> bytes:
        try:
            result = subprocess.run(self._args(args), capture_output=True,
                                    timeout=timeout or self.timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"ADB command timed out: {' '.join(args)}") from exc
        except OSError as exc:
            raise AdbError(f"Could not start ADB: {exc}") from exc
        if result.returncode != 0:
            stderr = result.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(stderr or f"ADB command failed: {' '.join(args)}")
        return result.stdout

    def run(self, *args: str, timeout: float | None = None) -> str:
        return self.run_bytes(*args, timeout=timeout).decode("utf-8", errors="replace").strip()

    def devices(self) -> list[Device]:
        output = self.run("devices", "-l")
        devices: list[Device] = []
        for line in output.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2:
                devices.append(Device(fields[0], fields[1], " ".join(fields[2:])))
        return devices

    def require_device(self) -> None:
        state = self.run("get-state")
        if state != "device":
            raise AdbError(f"Device {self.serial or '(selected)'} is not authorized and ready: {state}")

    def shell(self, command: str, timeout: float | None = None) -> str:
        return self.run("shell", command, timeout=timeout)

    def game_installed(self, package: str = GAME_PACKAGE) -> bool:
        output = self.shell(f"pm path {package}")
        return output.startswith("package:")

    def foreground(self) -> str:
        window_output = self.shell("dumpsys window windows", timeout=20)
        match = re.search(r"mCurrentFocus=.*?\s([\w.]+)/", window_output)
        if match:
            return match.group(1)
        output = self.shell("dumpsys activity activities", timeout=20)
        for pattern in (r"mResumedActivity:.*?\s([\w.]+)/", r"ResumedActivity:.*?\s([\w.]+)/"):
            match = re.search(pattern, output)
            if match:
                return match.group(1)
        return "unknown"

    def device_info(self) -> dict[str, str]:
        props = {
            "model": "ro.product.model",
            "manufacturer": "ro.product.manufacturer",
            "android_release": "ro.build.version.release",
            "sdk": "ro.build.version.sdk",
        }
        info = {key: self.shell(f"getprop {prop}") for key, prop in props.items()}
        info["screen_size"] = self.shell("wm size").replace("Physical size: ", "")
        info["screen_density"] = self.shell("wm density").replace("Physical density: ", "")
        info["foreground_package"] = self.foreground()
        return info

    def dump_ui(self) -> str:
        path = f"/data/local/tmp/nitan-ui-{uuid4().hex}.xml"
        return self.shell(
            f"uiautomator dump --compressed {path} >&2 && cat {path}; "
            f"dump_status=$?; rm -f {path}; exit $dump_status",
            timeout=30,
        )

    def screenshot(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        image = self.run_bytes("exec-out", "screencap", "-p", timeout=30)
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AdbError("Device did not return a valid PNG screenshot")
        path.write_bytes(image)

    def tap(self, x: int, y: int) -> None:
        if x < 0 or y < 0:
            raise AdbError("Tap coordinates must be non-negative")
        self.shell(f"input tap {x} {y}")


def choose_serial(devices: list[Device], requested: str | None = None) -> str:
    ready = [device for device in devices if device.state == "device"]
    if requested:
        if any(device.serial == requested for device in ready):
            return requested
        states = ", ".join(f"{d.serial} ({d.state})" for d in devices) or "none"
        raise AdbError(f"Requested device {requested} is not ready. Detected: {states}")
    if len(ready) == 1:
        return ready[0].serial
    if not ready:
        states = ", ".join(f"{d.serial} ({d.state})" for d in devices) or "none"
        raise AdbError(f"No authorized Android devices found. Detected: {states}")
    choices = ", ".join(d.serial for d in ready)
    raise AdbError(f"Multiple devices found; specify one with --serial: {choices}")
