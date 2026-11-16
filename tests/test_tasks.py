import json
from pathlib import Path
import tempfile
import unittest

from nitan_assistant.adb import GAME_PACKAGE
from nitan_assistant.tasks import TaskError, load_task


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


if __name__ == "__main__":
    unittest.main()
