"""Validated, bounded rule-task execution."""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import time
from typing import Callable

from .adb import AdbClient, GAME_PACKAGE
from .safety import human_check_terms, risky_action_terms, wait_for_manual_verification
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
        if action not in {"wait_text", "tap_text"} or not isinstance(text, str) or not text:
            raise TaskError(f"Step {index}: action must be wait_text or tap_text, with nonempty text")
        expect = raw.get("expect_text")
        if action == "tap_text" and (not isinstance(expect, str) or not expect):
            raise TaskError(f"Step {index}: tap_text requires nonempty expect_text")
        if action == "wait_text" and expect is not None:
            raise TaskError(f"Step {index}: expect_text is only valid for tap_text")
        timeout = raw.get("timeout_seconds", 15)
        retries = raw.get("retries", 0)
        if not isinstance(timeout, int) or not 1 <= timeout <= 120:
            raise TaskError(f"Step {index}: timeout_seconds must be between 1 and 120")
        if not isinstance(retries, int) or not 0 <= retries <= 2:
            raise TaskError(f"Step {index}: retries must be between 0 and 2")
        if action == "tap_text" and retries:
            raise TaskError(f"Step {index}: tap_text cannot be retried because a repeated tap may cause a second action")
        if action == "tap_text" and risky_action_terms(text):
            raise TaskError(f"Step {index}: risky action text is blocked")
        steps.append(Step(action, text, expect, timeout, retries))
    if not steps:
        raise TaskError("Task must contain at least one step")
    return Task(name.strip(), package, tuple(steps))


def _observe(client: AdbClient, *, prompt: Callable[[str], str] = input,
             logger: Callable[[str], None] = print) -> list[UiNode]:
    raw_xml = client.dump_ui()
    raw = parse_hierarchy(raw_xml)
    all_text = "\n".join(f"{node.text}\n{node.content_desc}" for node in raw)
    resolved_text = wait_for_manual_verification(
        all_text,
        lambda: "\n".join(
            f"{node.text}\n{node.content_desc}" for node in parse_hierarchy(client.dump_ui())
        ),
        prompt=prompt,
        logger=logger,
    )
    if resolved_text != all_text:
        raw = parse_hierarchy(client.dump_ui())
    if human_check_terms(resolved_text):
        raise TaskError("Human verification remains visible; automation stopped")
    return raw


def _unique_text_node(nodes: list[UiNode], text: str) -> UiNode:
    matches = [node for node in nodes if node_matches(node, text=text)]
    if len(matches) != 1:
        raise TaskError(f"Expected one visible node with exact text {text!r}; found {len(matches)}")
    node = matches[0]
    if not node.center:
        raise TaskError(f"Text {text!r} has no usable bounds")
    if not node.clickable and not node.clickable_ancestor_bounds:
        raise TaskError(f"Text {text!r} is not inside a clickable UI node")
    return node


def execute_task(client: AdbClient, task: Task, *, sleeper: Callable[[float], None] = time.sleep,
                 logger: Callable[[str], None] = print) -> None:
    client.require_device()
    if client.foreground() != task.package:
        raise TaskError("Game is not the foreground app; refusing to interact")
    if not client.game_installed(task.package):
        raise TaskError("Target game is not installed")
    logger(f"Task started: {task.name}")
    for step_number, step in enumerate(task.steps, 1):
        logger(f"Step {step_number}/{len(task.steps)}: {step.action} {step.text!r}")
        if step.action == "wait_text":
            deadline = time.monotonic() + step.timeout_seconds
            while True:
                nodes = _observe(client)
                if any(node_matches(node, text=step.text) for node in nodes):
                    logger(f"Found expected text: {step.text!r}")
                    break
                if time.monotonic() >= deadline:
                    raise TaskError(f"Timed out waiting for text {step.text!r}")
                sleeper(0.5)
            continue

        if risky_action_terms(step.text):
            raise TaskError("Risky action text is blocked")
        if step.expect_text and risky_action_terms(step.expect_text):
            raise TaskError("Risky expected-result text is blocked")
        if client.foreground() != task.package:
            raise TaskError("Game left the foreground; automation stopped")
        nodes = _observe(client, logger=logger)
        all_text = "\n".join(f"{node.text}\n{node.content_desc}" for node in nodes)
        if risky_action_terms(all_text):
            raise TaskError("Risk-related screen text detected; automation stopped")
        node = _unique_text_node(nodes, step.text)
        x, y = node.center or (0, 0)
        client.tap(x, y)
        logger(f"Tap sent at ({x}, {y}); waiting for {step.expect_text!r}")
        deadline = time.monotonic() + step.timeout_seconds
        observed = False
        while time.monotonic() < deadline:
            nodes_after = _observe(client, logger=logger)
            if any(node_matches(item, text=step.expect_text) for item in nodes_after):
                logger(f"Expected result observed: {step.expect_text!r}")
                observed = True
                break
            sleeper(0.5)
        if not observed:
            raise TaskError(f"Expected text {step.expect_text!r} did not appear; no second tap was sent")
    logger("Task completed")
