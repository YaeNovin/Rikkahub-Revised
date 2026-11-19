"""Host-only regressions: malformed UI must never cause guessed device input."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from android_qa import Adb, parse_ui, center, frame_summary
from verify_custom_search_ui import verify


class AndroidQaTest(unittest.TestCase):
    def test_multidisplay_screencap_warning_cannot_corrupt_saved_png(self):
        adb = Adb("unused-adb", "fixture")
        png = b"\x89PNG\r\n\x1a\nfixture-image"
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "screen.png"
            with patch.object(adb, "run", return_value=b"[Warning] Multiple displays\n" + png):
                adb.screenshot(output)
            self.assertEqual(png, output.read_bytes())
            with patch.object(adb, "run", return_value=b"capture failed"):
                with self.assertRaisesRegex(RuntimeError, "no PNG"):
                    adb.screenshot(output)

    def test_adb_preamble_and_trailing_status_are_not_xml(self):
        tree = parse_ui('noise\n<?xml version="1.0"?><hierarchy><node text="中文"/></hierarchy>UI dumped')
        self.assertEqual("中文", tree.find("node").get("text"))
        for invalid in ("", "<hierarchy>", "</hierarchy>"):
            with self.assertRaises(ValueError):
                parse_ui(invalid)

    def test_coordinates_only_come_from_nonempty_bounds(self):
        self.assertEqual((30, 60), center(ET.Element("node", bounds="[10,20][50,100]")))
        for invalid in ("", "[50,10][20,40]", "[0,0][0,0]", "1,2,3,4"):
            with self.assertRaises(ValueError):
                center(ET.Element("node", bounds=invalid))

    def test_empty_performance_capture_fails_instead_of_claiming_smoothness(self):
        for raw in ("", "Total frames rendered: 0"):
            with self.assertRaises(ValueError):
                frame_summary(raw)
        result = frame_summary("Total frames rendered: 250\nJanky frames: 15 (6.00%)\n95th percentile: 22ms")
        self.assertEqual(6, result["jank_percent"])
        self.assertEqual(22, result["p95_ms"])

    def test_search_capture_validator_rejects_enabled_default_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "default.xml").write_text('<hierarchy><node class="android.widget.EditText"/>'
                '<node class="android.widget.EditText" text=""/>'
                '<node class="android.widget.EditText" enabled="true"/></hierarchy>', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "API Key must start disabled"):
                verify(root)


if __name__ == "__main__":
    unittest.main()
