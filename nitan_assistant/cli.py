"""Command line interface for the rule-based ADB helper."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from .adb import AdbClient, AdbError, GAME_PACKAGE, adb_command, choose_serial
from .safety import human_check_terms, risky_action_terms, wait_for_manual_verification
from .tasks import TaskError, execute_task, load_task
from .ui import UiNode, node_matches, parse_hierarchy


DEFAULT_CAPTURE_DIR = Path(".local")


def _client(args: argparse.Namespace) -> AdbClient:
    command = adb_command(args.adb)
    discovery = AdbClient(command)
    serial = choose_serial(discovery.devices(), args.serial)
    return AdbClient(command, serial=serial)


def _save_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _operation_log(message: str) -> None:
    print(message)
    log_path = DEFAULT_CAPTURE_DIR / "nitan-assistant.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as log_file:
        log_file.write(message + "\n")


def _wait_for_manual_check(client: AdbClient, nodes: list[UiNode]) -> list[UiNode]:
    latest_nodes = nodes

    def reread_text() -> str:
        nonlocal latest_nodes
        latest_nodes = parse_hierarchy(client.dump_ui())
        return "\n".join(f"{node.text}\n{node.content_desc}" for node in latest_nodes)

    initial_text = "\n".join(f"{node.text}\n{node.content_desc}" for node in nodes)
    wait_for_manual_verification(initial_text, reread_text, logger=_operation_log)
    return latest_nodes


def _inspect(client: AdbClient, package: str) -> int:
    client.require_device()
    info = client.device_info()
    info["game_installed"] = str(client.game_installed(package)).lower()
    for key, value in info.items():
        print(f"{key}: {value or '(unavailable)'}")
    if info["foreground_package"] != package:
        print("Game is not in the foreground; no action was taken.")
    return 0


def _tap(client: AdbClient, selector: str, value: str, expect: str, timeout: int) -> int:
    client.require_device()
    if client.foreground() != GAME_PACKAGE:
        raise TaskError("Game is not the foreground app; refusing to interact")
    if risky_action_terms(value) or risky_action_terms(expect):
        raise TaskError("Risk-related action or expected text is blocked")
    nodes = _wait_for_manual_check(client, parse_hierarchy(client.dump_ui()))
    all_text = "\n".join(f"{node.text}\n{node.content_desc}" for node in nodes)
    if risky_action_terms(all_text):
        raise TaskError("Risk-related screen text detected; automation stopped")
    matches = [node for node in nodes if node_matches(node, **{selector: value})]
    if len(matches) != 1:
        raise TaskError(f"Expected exactly one matching node; found {len(matches)}")
    node = matches[0]
    if not node.center or (not node.clickable and not node.clickable_ancestor_bounds):
        raise TaskError("Matched UI node is not in a clickable control")
    x, y = node.center
    client.tap(x, y)
    _operation_log(f"Tap sent at ({x}, {y}); waiting up to {timeout}s for {expect!r}.")
    import time
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes_after = _wait_for_manual_check(client, parse_hierarchy(client.dump_ui()))
        text_after = "\n".join(f"{item.text}\n{item.content_desc}" for item in nodes_after)
        if risky_action_terms(text_after):
            raise TaskError("Risk-related screen text detected after tap; automation stopped")
        if any(node_matches(item, text=expect) for item in nodes_after):
            _operation_log("Expected result observed.")
            return 0
        time.sleep(0.5)
    raise TaskError(f"Timed out waiting for expected text {expect!r}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="nitan-assistant", description="Rule-based ADB helper (no AI control loop)")
    parser.add_argument("--adb", help="ADB executable path; defaults to PATH or WSL ADB")
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
        client = _client(args)
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
            selector_name = "resource_id" if args.resource_id is not None else ("text" if args.text is not None else "desc")
            selector_value = args.resource_id if args.resource_id is not None else (args.text if args.text is not None else args.desc)
            return _tap(client, selector_name, selector_value, args.expect, args.timeout)
        if args.command == "run":
            task = load_task(args.task)
            execute_task(client, task, logger=_operation_log)
            return 0
    except (AdbError, TaskError, OSError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Stopped by user.", file=sys.stderr)
        return 130
    return 2
