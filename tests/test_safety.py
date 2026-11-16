import unittest

from nitan_assistant.safety import wait_for_manual_verification


class ManualVerificationTests(unittest.TestCase):
    def test_waits_for_human_and_rechecks_before_continuing(self):
        states = iter(("任务列表",))
        prompts = []
        messages = []

        result = wait_for_manual_verification(
            "验证码",
            lambda: next(states),
            prompt=lambda message: prompts.append(message) or "",
            logger=messages.append,
        )

        self.assertEqual("任务列表", result)
        self.assertEqual(1, len(prompts))
        self.assertTrue(any("paused" in message.lower() for message in messages))

    def test_keeps_paused_if_verification_remains(self):
        states = iter(("人工验证", "人工验证", "已完成"))
        messages = []

        result = wait_for_manual_verification(
            "人工验证",
            lambda: next(states),
            prompt=lambda _message: "",
            logger=messages.append,
        )

        self.assertEqual("已完成", result)
        self.assertTrue(any("still visible" in message.lower() for message in messages))

    def test_stops_if_manual_input_is_unavailable(self):
        with self.assertRaises(ValueError):
            wait_for_manual_verification(
                "验证码",
                lambda: "验证码",
                prompt=lambda _message: (_ for _ in ()).throw(EOFError()),
            )


if __name__ == "__main__":
    unittest.main()
