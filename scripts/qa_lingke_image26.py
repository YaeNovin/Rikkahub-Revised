"""Verify the production GPT Image settings sheet using isolated fixture data."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from android_qa import Adb, ROOT, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"
ACTIVITY = f"{PACKAGE}/me.rerere.rikkahub.LingkeImage26PreviewActivity"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/lingke-image26/device")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def text_is(value):
        return lambda n: n.get("text") == value

    def swipe(tree):
        _, top, right, bottom = bounds(tree.find("node"))
        adb.run("shell", "input", "swipe", right // 2, int(bottom * .84), right // 2,
                max(top + 100, int(bottom * .37)), 400)

    def advanced_controls(label):
        adb.tap(adb.wait_node(text_is("高级选项"), output / f"{label}-tab.xml", PACKAGE))
        found = set()
        for page in range(5):
            tree = adb.ui(output / f"{label}-advanced-{page}.xml")
            found.update(n.get("text") for n in tree.iter("node") if n.get("package") == PACKAGE and
                         n.get("text") in {"内容审核", "输出压缩率", "背景"})
            if {"内容审核", "输出压缩率"} <= found:
                adb.screenshot(output / f"{label}-advanced.png")
                return found
            swipe(tree)
        raise RuntimeError(f"Missing native options: {label}: {found}")

    adb.run("shell", "am", "force-stop", PACKAGE)
    adb.run("shell", "am", "start", "-W", "-n", ACTIVITY)
    sunburst = adb.wait_node(text_is("Sunburst（增强版）"), output / "media-basic.xml", PACKAGE)
    adb.screenshot(output / "media-basic.png")
    adb.tap(sunburst)
    adb.wait_node(lambda n: n.get("text", "").startswith("version=sunburst;"), output / "sunburst.xml", PACKAGE)
    adb.tap(adb.wait_node(text_is("Flare（标准版）"), output / "flare-before.xml", PACKAGE))
    adb.wait_node(lambda n: n.get("text", "").startswith("version=flare;"), output / "flare.xml", PACKAGE)
    adb.tap(adb.wait_node(text_is("Sunburst（增强版）"), output / "sunburst-before.xml", PACKAGE))
    adb.wait_node(lambda n: n.get("text", "").startswith("version=sunburst;"), output / "sunburst-selected.xml", PACKAGE)
    adb.screenshot(output / "sunburst-selected.png")
    media_controls = advanced_controls("media")

    adb.run("shell", "am", "force-stop", PACKAGE)
    adb.run("shell", "am", "start", "-W", "-n", ACTIVITY, "--ez", "native", "true")
    adb.wait_node(text_is("高级选项"), output / "native-basic.xml", PACKAGE)
    tree = adb.ui()
    assert not any("Sunburst" in n.get("text", "") for n in tree.iter("node")), "Supplier version leaked to native model"
    native_controls = advanced_controls("native")
    result = {"serial": args.serial, "version_switches": ["sunburst", "flare", "sunburst"],
              "media_controls": sorted(media_controls), "native_controls": sorted(native_controls),
              "native_has_no_supplier_version": True, "paid_generation_calls": False}
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
