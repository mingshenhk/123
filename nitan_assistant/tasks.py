"""Validated, bounded rule-task execution."""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import time
from typing import Callable

from .adb import AdbClient, GAME_PACKAGE
from .safety import blocked_control, human_check_terms, risky_action_terms, wait_for_manual_verification
from .ui import UiNode, node_matches, parse_hierarchy


class TaskError(RuntimeError):
    pass


@dataclass(frozen=True)
class Step:
    action: str
    text: str
    expect_text: str | None = None
    timeout_seconds: int = 15
    retries: int = 0


@dataclass(frozen=True)
class Task:
    name: str
    package: str
    steps: tuple[Step, ...]


def load_task(path: Path) -> Task:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise TaskError(f"Could not read task file {path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("steps"), list):
        raise TaskError("Task must be an object with a steps array")
    name = data.get("name")
    package = data.get("package", GAME_PACKAGE)
    if not isinstance(name, str) or not name.strip():
        raise TaskError("Task name is required")
    if package != GAME_PACKAGE:
        raise TaskError(f"This script is restricted to package {GAME_PACKAGE}")
    steps: list[Step] = []
    if len(data["steps"]) > 20:
        raise TaskError("A task is limited to 20 steps")
    for index, raw in enumerate(data["steps"], 1):
        if not isinstance(raw, dict):
            raise TaskError(f"Step {index} must be an object")
        action, text = raw.get("action"), raw.get("text")
        if action not in ("wait_text", "tap_text") or not isinstance(text, str) or not text.strip():
            raise TaskError(f"Step {index}: action must be wait_text or tap_text, with nonempty text")
        expect = raw.get("expect_text")
        if action == "tap_text" and (not isinstance(expect, str) or not expect.strip()):
            raise TaskError(f"Step {index}: tap_text requires nonempty expect_text")
        if action == "wait_text" and expect is not None:
            raise TaskError(f"Step {index}: expect_text is only valid for tap_text")
        timeout = raw.get("timeout_seconds", 15)
        retries = raw.get("retries", 0)
        if type(timeout) is not int or not 1 <= timeout <= 120:
            raise TaskError(f"Step {index}: timeout_seconds must be between 1 and 120")
        if type(retries) is not int or not 0 <= retries <= 2:
            raise TaskError(f"Step {index}: retries must be between 0 and 2")
        if action == "tap_text" and retries:
            raise TaskError(f"Step {index}: tap_text cannot be retried because a repeated tap may cause a second action")
        if action == "tap_text" and (blocked_control(text) or human_check_terms(text)):
            raise TaskError(f"Step {index}: this action is reserved for manual use")
        steps.append(Step(action, text, expect, timeout, retries))
    if not steps:
        raise TaskError("Task must contain at least one step")
    return Task(name.strip(), package, tuple(steps))


def _require_foreground(client: AdbClient) -> None:
    if client.foreground() != GAME_PACKAGE:
        raise TaskError("Game is not in the foreground; automation stopped")


def _observe(client: AdbClient, logger: Callable[[str], None]) -> list[UiNode]:
    latest_nodes: list[UiNode] = []

    def reread_text() -> str:
        nonlocal latest_nodes
        _require_foreground(client)
        latest_nodes = parse_hierarchy(client.dump_ui())
        return "\n".join(f"{node.text}\n{node.content_desc}" for node in latest_nodes)

    wait_for_manual_verification(reread_text(), reread_text, logger=logger)
    _require_foreground(client)
    game_nodes = [node for node in latest_nodes if node.package == GAME_PACKAGE]
    if not game_nodes:
        raise TaskError("No labeled game nodes were visible in the UI hierarchy")
    return game_nodes


def _has_text(nodes: list[UiNode], text: str) -> bool:
    return any(node.text == text and node.visible for node in nodes)


def _wait_text(client: AdbClient, text: str, timeout: int, logger: Callable[[str], None],
               sleeper: Callable[[float], None], *, after_tap: bool = False) -> bool:
    deadline = time.monotonic() + timeout
    while True:
        nodes = _observe(client, logger)
        if after_tap and any(risky_action_terms(f"{node.text}\n{node.content_desc}") for node in nodes):
            raise TaskError("Risk-related screen text detected after tap; automation stopped")
        if _has_text(nodes, text):
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        sleeper(min(0.5, remaining))


def tap_and_verify(client: AdbClient, selector: str, value: str, expect: str, timeout: int,
                   *, logger: Callable[[str], None] = print,
                   sleeper: Callable[[float], None] = time.sleep) -> None:
    if not value.strip() or not expect.strip():
        raise TaskError("Tap selector and expected text must be nonempty")
    if blocked_control(value) or human_check_terms(value) or risky_action_terms(expect) or human_check_terms(expect):
        raise TaskError("Manual-only action or expected result cannot be used for an automated tap")
    nodes = _observe(client, logger)
    if any(risky_action_terms(f"{node.text}\n{node.content_desc}") for node in nodes):
        raise TaskError("Risk-related screen text detected; automation stopped")
    if _has_text(nodes, expect):
        raise TaskError(f"Expected text {expect!r} is already visible before the tap")
    matches = [node for node in nodes if node.visible and node_matches(node, **{selector: value})]
    if len(matches) != 1:
        raise TaskError(f"Expected exactly one matching game node; found {len(matches)}")
    node = matches[0]
    if blocked_control(f"{node.text}\n{node.content_desc}"):
        raise TaskError("Matched control is reserved for manual use")
    point = node.center
    if not node.enabled or not point or (not node.clickable and not node.clickable_ancestor_bounds):
        raise TaskError("Matched node is not in an enabled, visible clickable control")
    _require_foreground(client)
    client.tap(*point)
    logger(f"Tap sent at {point}; waiting for {expect!r}")
    if not _wait_text(client, expect, timeout, logger, sleeper, after_tap=True):
        raise TaskError(f"Expected text {expect!r} did not appear; no second tap was sent")
    logger(f"Expected result observed: {expect!r}")


def execute_task(client: AdbClient, task: Task, *, sleeper: Callable[[float], None] = time.sleep,
                 logger: Callable[[str], None] = print) -> None:
    client.require_device()
    if task.package != GAME_PACKAGE or not client.game_installed(task.package):
        raise TaskError("Target game is not installed or is outside the supported package")
    logger(f"Task started: {task.name}")
    for step_number, step in enumerate(task.steps, 1):
        logger(f"Step {step_number}/{len(task.steps)}: {step.action} {step.text!r}")
        if step.action == "tap_text" and step.expect_text:
            tap_and_verify(client, "text", step.text, step.expect_text, step.timeout_seconds,
                           logger=logger, sleeper=sleeper)
            continue
        if step.action != "wait_text":
            raise TaskError(f"Invalid step: {step.action!r}")
        for attempt in range(step.retries + 1):
            if _wait_text(client, step.text, step.timeout_seconds, logger, sleeper):
                logger(f"Found expected text: {step.text!r}")
                break
            if attempt < step.retries:
                logger(f"Observation timed out for {step.text!r}; retrying")
        else:
            raise TaskError(f"Timed out waiting for text {step.text!r}")
    logger("Task completed")
