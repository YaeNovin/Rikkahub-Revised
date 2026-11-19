"""Open every real prompt editor in the isolated debug fixture over wireless ADB."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from android_qa import Adb, ROOT, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"
EXPECTED_TITLES = {"决策模型评估提示词", "翻译", "标题生成", "聊天回复建议", "OCR识别", "上下文压缩"}


def is_prompt_row(node) -> bool:
    return node.get("package") == PACKAGE and node.get("text", "").split("\n", 1)[0] in {"提示词", "Prompt"}


def editor_cancel(adb: Adb, output: Path):
    for _ in range(8):
        tree = adb.ui(output)
        cancel = next((n for n in tree.iter("node") if n.get("package") == PACKAGE and n.get("text") in {"取消", "Cancel"}), None)
        if cancel is not None:
            return cancel
        # Swipe in the variable-reference area, below the editable text field.
        _, top, right, bottom = bounds(tree.find("node"))
        adb.run("shell", "input", "swipe", right // 2, int(bottom * .88), right // 2, max(top + 100, int(bottom * .62)), 400)
    raise RuntimeError("Prompt editor actions remain unreachable after scrolling")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/model-api-fixes/device")
    args = parser.parse_args()
    if ":" not in args.serial and "_adb-tls-connect._tcp" not in args.serial:
        parser.error("This regression uses wireless ADB only")
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    activity = f"{PACKAGE}/me.rerere.rikkahub.ModelApiRegressionActivity"
    adb.run("shell", "am", "force-stop", PACKAGE)
    adb.run("shell", "am", "start", "-W", "-n", activity)
    marker = adb.wait_node(lambda n: n.get("text") == "Android ICU prompt checks: PASS", output / "fixture.xml", PACKAGE)
    adb.screenshot(output / "fixture.png")
    checked = set()
    # Prompt rows have stable semantic text; scroll only when all visible rows are tested.
    for page in range(5):
        tree = adb.ui(output / f"page-{page}.xml")
        nodes = list(tree.iter("node"))
        rows = []
        for index, node in enumerate(nodes):
            if not is_prompt_row(node):
                continue
            prior = [n.get("text") for n in nodes[:index] if n.get("text")]
            title = prior[-1] if prior else f"row-{index}"
            if title in EXPECTED_TITLES and title not in checked:
                rows.append((title, node))
        if rows:
            title, node = rows[0]
            adb.tap(node)
            cancel = editor_cancel(adb, output / f"editor-{len(checked)}.xml")
            adb.screenshot(output / f"editor-{len(checked)}.png")
            # Cancel using UI semantics; never save fixture values into settings.
            adb.tap(cancel)
            checked.add(title)
            # Keep processing this viewport until each visible editor has opened.
            while True:
                tree = adb.ui()
                nodes = list(tree.iter("node"))
                remaining = []
                for index, row in enumerate(nodes):
                    if is_prompt_row(row):
                        prior = [n.get("text") for n in nodes[:index] if n.get("text")]
                        label = prior[-1] if prior else f"row-{index}"
                        if label in EXPECTED_TITLES and label not in checked:
                            remaining.append((label, row))
                if not remaining:
                    break
                label, row = remaining[0]
                adb.tap(row)
                cancel = editor_cancel(adb, output / f"editor-{len(checked)}.xml")
                adb.screenshot(output / f"editor-{len(checked)}.png")
                adb.tap(cancel)
                checked.add(label)
        if checked == EXPECTED_TITLES:
            break
        tree = adb.ui()
        _, top, right, bottom = bounds(tree.find("node"))
        adb.run("shell", "input", "swipe", right // 2, int(bottom * .82), right // 2, max(top + 100, int(bottom * .3)), 400)
    log = adb.run("logcat", "-d", "-t", "1000", "ModelApiRegression:I", "AndroidRuntime:E", "*:S")
    (output / "logcat.txt").write_text(log, encoding="utf-8")
    result = {"wireless_serial": args.serial, "editors_opened": sorted(checked),
              "android_icu_passed": marker.get("text") == "Android ICU prompt checks: PASS"}
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if checked != EXPECTED_TITLES or not result["android_icu_passed"]:
        raise RuntimeError("Not all six real prompt editors were verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
