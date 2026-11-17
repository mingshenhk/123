import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from nitan_assistant.adb import AdbClient, GAME_PACKAGE
from nitan_assistant.tasks import TaskError, load_task, tap_and_verify


def hierarchy(*labels, package=GAME_PACKAGE):
    nodes = "".join(
        f'<node package="{package}" text="{label}" bounds="[10,20][30,40]" clickable="true"/>'
        for label in labels
    )
    return f"<hierarchy>{nodes}</hierarchy>"


class TaskTests(unittest.TestCase):
    def load_data(self, data):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "task.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            return load_task(path)

    def test_loads_bounded_observe_task(self):
        task = self.load_data({"name": "observe", "steps": [
            {"action": "wait_text", "text": "状态", "timeout_seconds": 5}
        ]})
        self.assertEqual(GAME_PACKAGE, task.package)
        self.assertEqual("wait_text", task.steps[0].action)

    def test_rejects_tap_without_expected_result(self):
        with self.assertRaises(TaskError):
            self.load_data({"name": "bad", "steps": [
                {"action": "tap_text", "text": "任务"}
            ]})

    def test_rejects_risky_tap_and_unbounded_retry(self):
        with self.assertRaises(TaskError):
            self.load_data({"name": "bad", "steps": [
                {"action": "tap_text", "text": "购买", "expect_text": "成功"}
            ]})
        with self.assertRaises(TaskError):
            self.load_data({"name": "bad", "steps": [
                {"action": "wait_text", "text": "任务", "retries": 50}
            ]})

    def test_rejects_tap_retries(self):
        with self.assertRaises(TaskError):
            self.load_data({"name": "bad", "steps": [
                {"action": "tap_text", "text": "任务", "expect_text": "列表", "retries": 1}
            ]})

    def test_invalid_action_type_is_a_task_error(self):
        with self.assertRaises(TaskError):
            self.load_data({"name": "bad", "steps": [{"action": [], "text": "任务"}]})

    def client_with_screens(self, *screens):
        client = Mock(spec=AdbClient)
        client.foreground.return_value = GAME_PACKAGE
        client.dump_ui.side_effect = screens
        return client

    def test_clicks_once_and_verifies_new_page(self):
        client = self.client_with_screens(hierarchy("游戏设定"), hierarchy("客户端设定"))
        tap_and_verify(client, "text", "游戏设定", "客户端设定", 1, logger=Mock())
        client.tap.assert_called_once_with(20, 30)

    def test_refuses_already_visible_result(self):
        client = self.client_with_screens(hierarchy("游戏设定", "客户端设定"))
        with self.assertRaisesRegex(TaskError, "already visible"):
            tap_and_verify(client, "text", "游戏设定", "客户端设定", 1, logger=Mock())
        client.tap.assert_not_called()

    def test_refuses_foreign_nodes_and_focus_loss_at_click(self):
        client = self.client_with_screens(hierarchy("游戏设定", package="other.app"))
        with self.assertRaisesRegex(TaskError, "No labeled game nodes"):
            tap_and_verify(client, "text", "游戏设定", "客户端设定", 1, logger=Mock())
        client.tap.assert_not_called()
        client = self.client_with_screens(hierarchy("游戏设定"))
        client.foreground.side_effect = [GAME_PACKAGE, GAME_PACKAGE, "other.app"]
        with self.assertRaisesRegex(TaskError, "not in the foreground"):
            tap_and_verify(client, "text", "游戏设定", "客户端设定", 1, logger=Mock())
        client.tap.assert_not_called()

    def test_result_timeout_does_not_repeat_click(self):
        client = self.client_with_screens(hierarchy("游戏设定"), hierarchy("游戏设定"))
        with patch("nitan_assistant.tasks.time.monotonic", side_effect=[0, 2]):
            with self.assertRaisesRegex(TaskError, "no second tap"):
                tap_and_verify(client, "text", "游戏设定", "客户端设定", 1, logger=Mock())
        client.tap.assert_called_once_with(20, 30)


if __name__ == "__main__":
    unittest.main()
