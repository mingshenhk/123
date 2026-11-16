import unittest

from nitan_assistant.ui import node_matches, parse_hierarchy


XML = '''<hierarchy><node text="任务" class="android.view.View" clickable="true" bounds="[10,20][110,70]">
  <node text="日常" class="android.widget.TextView" clickable="false" bounds="[20,30][80,60]" resource-id="game:id/daily" />
</node></hierarchy>'''


class UiTests(unittest.TestCase):
    def test_parse_uses_clickable_ancestor_for_text(self):
        nodes = parse_hierarchy(XML)
        child = next(n for n in nodes if n.text == "日常")
        self.assertEqual((10, 20, 110, 70), child.tap_bounds)
        self.assertEqual((60, 45), child.center)

    def test_exact_text_selector(self):
        nodes = parse_hierarchy(XML)
        self.assertTrue(node_matches(nodes[1], text="日常"))
        self.assertFalse(node_matches(nodes[1], text="日"))

    def test_requires_one_selector(self):
        with self.assertRaises(ValueError):
            node_matches(parse_hierarchy(XML)[0], text="任务", desc="任务")


if __name__ == "__main__":
    unittest.main()
