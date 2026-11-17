"""Command line interface for the rule-based ADB helper."""

from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
import sys

from .adb import AdbClient, AdbError, GAME_PACKAGE, adb_command, choose_serial
from .safety import human_check_terms
from .tasks import TaskError, execute_task, load_task, tap_and_verify
from .ui import parse_hierarchy


DEFAULT_CAPTURE_DIR = Path(".local")


def _save_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _operation_log(message: str, level: str = "INFO") -> None:
    output = sys.stderr if level == "ERROR" else sys.stdout
    print(message, file=output)
    log_path = DEFAULT_CAPTURE_DIR / "nitan-assistant.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as log_file:
        timestamp = datetime.now().astimezone().isoformat(timespec="seconds")
        log_file.write(f"{timestamp} [{level}] {message}\n")


def _inspect(client: AdbClient, package: str) -> int:
    client.require_device()
    info = client.device_info()
    info["game_installed"] = str(client.game_installed(package)).lower()
    for key, value in info.items():
        print(f"{key}: {value or '(unavailable)'}")
    if info["foreground_package"] != package:
        print("Game is not in the foreground; no action was taken.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="nitan-assistant", description="Rule-based ADB helper (no AI control loop)")
    parser.add_argument("--adb", help="ADB executable path; auto-detects SDK, LDPlayer or WSL when needed")
    parser.add_argument("--serial", help="ADB serial for a specific authorized device")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("devices", help="List connected ADB devices")
    inspect = subparsers.add_parser("inspect", help="Show Android and target-game status")
    inspect.add_argument("--package", default=GAME_PACKAGE)
    dump = subparsers.add_parser("dump-ui", help="Save and summarize the current UIAutomator hierarchy")
    dump.add_argument("--output", type=Path, default=DEFAULT_CAPTURE_DIR / "window.xml")
    screenshot = subparsers.add_parser("screenshot", help="Save a screenshot from the connected device")
    screenshot.add_argument("--output", type=Path, default=DEFAULT_CAPTURE_DIR / "screen.png")
    tap = subparsers.add_parser("tap", help="Tap one uniquely matched, safe UI node")
    selector = tap.add_mutually_exclusive_group(required=True)
    selector.add_argument("--text")
    selector.add_argument("--desc")
    selector.add_argument("--resource-id")
    tap.add_argument("--expect", required=True, help="Exact UI text that must appear after the tap")
    tap.add_argument("--timeout", type=int, choices=range(1, 121), default=15)
    run = subparsers.add_parser("run", help="Run a bounded JSON task")
    run.add_argument("--task", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        command = adb_command(args.adb)
        if args.command == "devices":
            client = AdbClient(command)
            devices = client.devices()
            if not devices:
                print("No Android devices detected.")
                return 0
            for item in devices:
                print(f"{item.serial}\t{item.state}\t{item.details}")
            return 0
        serial = choose_serial(AdbClient(command).devices(), args.serial)
        client = AdbClient(command, serial=serial)
        if args.command == "inspect":
            return _inspect(client, args.package)
        if args.command == "dump-ui":
            raw = client.dump_ui()
            _save_text(args.output, raw)
            nodes = parse_hierarchy(raw)
            print(f"Saved {len(nodes)} labeled UI nodes to {args.output}")
            for node in nodes:
                label = node.label
                if label:
                    print(f"{label}\tclickable={node.clickable}\tbounds={node.bounds}")
            all_text = "\n".join(f"{n.text}\n{n.content_desc}" for n in nodes)
            if human_check_terms(all_text):
                print("WARNING: possible human-verification text detected; do not automate through it.")
            return 0
        if args.command == "screenshot":
            client.screenshot(args.output)
            print(f"Saved screenshot to {args.output}")
            return 0
        if args.command == "tap":
            client.require_device()
            selector, value = next((name, getattr(args, name)) for name in ("text", "desc", "resource_id")
                                   if getattr(args, name) is not None)
            tap_and_verify(client, selector, value, args.expect, args.timeout, logger=_operation_log)
            return 0
        if args.command == "run":
            task = load_task(args.task)
            execute_task(client, task, logger=_operation_log)
            return 0
    except (AdbError, TaskError, OSError, ValueError) as exc:
        message = f"ERROR: {exc}"
        if args.command in {"tap", "run"}:
            try:
                _operation_log(message, "ERROR")
            except OSError:
                pass  # Error was already printed before the log-file write.
        else:
            print(message, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Stopped by user.", file=sys.stderr)
        return 130
    return 2
