"""Verify production native/HTML table rendering on an isolated Debug device app."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import time

from android_qa import Adb, bounds, center

PACKAGE = "me.rerere.rikkahub.revised.renderperf"
ACTIVITY = "me.rerere.rikkahub.TableRegressionActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--large-only", action="store_true", help="Resume only the 130% cases, retaining completed 100% results")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/output-metadata.json").read_text(encoding="utf-8"))
    assert metadata["applicationId"] == PACKAGE, "Build with --performance-probe first"
    installed = re.search(r"versionName=(\S+)", adb.run("shell", "dumpsys", "package", PACKAGE))
    assert installed and installed[1] == metadata["elements"][0]["versionName"], "Installed Debug version differs from the APK"

    def state():
        for attempt in range(5):
            try:
                return json.loads(adb.run("exec-out", "run-as", PACKAGE, "cat", "files/table-regression/state.json"))
            except json.JSONDecodeError:
                if attempt == 4:
                    raise
                time.sleep(.05)

    def wait_state(predicate, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = state()
            if predicate(current):
                return current
            time.sleep(.2)
        raise AssertionError(f"Expected fixture state was not reached: {current}")

    def tree(label):
        return adb.ui(output / f"{label}.xml")

    def named(hierarchy, text):
        matches = [n for n in hierarchy.iter("node") if n.get("package") == PACKAGE and n.get("text") == text]
        assert matches, f"Missing visible cell: {text}"
        return matches[0]

    def click(description, label):
        hierarchy = tree(label)
        found = next((n for n in hierarchy.iter("node") if n.get("content-desc") == description), None)
        assert found is not None, f"Missing action: {description}"
        parents = {c: p for p in hierarchy.iter() for c in p}
        while found.get("clickable") != "true" and found in parents:
            found = parents[found]
        assert found.get("clickable") == "true"
        adb.tap(found)
        time.sleep(.3)

    def vertical(hierarchy):
        return next(n for n in hierarchy.iter("node") if n.get("scrollable") == "true" and n.get("class") == "android.widget.ScrollView")

    def scroll_top(label):
        hierarchy = tree(label)
        region = vertical(hierarchy)
        left, top, right, bottom = bounds(region)
        for _ in range(3):
            adb.run("shell", "input", "swipe", (left+right)//2, int(top+(bottom-top)*.25),
                    (left+right)//2, int(top+(bottom-top)*.8), "180")

    def horizontal_edge(label, end=False):
        hierarchy = tree(label)
        region = next((n for n in hierarchy.iter("node") if n.get("class") == "android.widget.HorizontalScrollView"), None)
        if region is None:
            return hierarchy
        left, top, right, bottom = bounds(region)
        y = int(top+(bottom-top)*.35)
        low, high = int(left+(right-left)*.15), int(left+(right-left)*.85)
        for _ in range(3):
            adb.run("shell", "input", "swipe", high if end else low, y, low if end else high, y, "200")
        return tree(label+"-edge")

    results = []
    saved_results = output/"device-results.json"
    if args.large_only and saved_results.exists():
        results = [r for r in json.loads(saved_results.read_text(encoding="utf-8")) if r["fontScale"] == 1]
    original = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        adb.run("shell", "am", "start", "-S", "-W", "-n", f"{PACKAGE}/{ACTIVITY}")
        time.sleep(3)
        pid = adb.run("shell", "pidof", "-s", PACKAGE)
        assert pid
        for html, dark, large in [(False, True, False), (False, False, False), (True, False, False), (True, True, False), (False, True, True), (True, True, True)]:
            if args.large_only and not large:
                continue
            label = f'{"html" if html else "native"}-{"dark" if dark else "light"}-{"130" if large else "100"}'
            scroll_top(label+"-top")
            for key, expected, action in [("html", html, "table_renderer"), ("dark", dark, "table_theme"), ("large", large, "table_scale"), ("wide", False, "table_case")]:
                if state()[key] != expected:
                    click(action, label+"-"+key)
                    wait_state(lambda s: s[key] == expected)
            time.sleep(1.5)
            hierarchy = horizontal_edge(label+"-align")
            head = [bounds(named(hierarchy, text)) for text in ("LEFT", "CENTER", "RIGHT")]
            body = [bounds(named(hierarchy, text)) for text in ("L", "C")]
            if not any(n.get("text") == "R" for n in hierarchy.iter("node")):
                hierarchy = horizontal_edge(label+"-align-right", end=True)
                head[2] = bounds(named(hierarchy, "RIGHT"))
            body.append(bounds(named(hierarchy, "R")))
            tolerance = 20
            assert abs(head[0][0] - body[0][0]) <= tolerance, (head, body)
            assert abs((head[1][0]+head[1][2]) - (body[1][0]+body[1][2])) <= tolerance*2, (head, body)
            assert abs(head[2][2] - body[2][2]) <= tolerance, (head, body)
            adb.screenshot(output / f"{label}-align.png")

            click("table_case", label+"-wide-action")
            wait_state(lambda s: s["wide"])
            time.sleep(1.5)
            hierarchy = horizontal_edge(label+"-wide")
            named(hierarchy, "COL_A")
            long_cell = next(n for n in hierarchy.iter("node") if n.get("text", "").startswith("长内容换行验证"))
            long_bounds = bounds(long_cell)
            assert long_bounds[3]-long_bounds[1] > body[0][3]-body[0][1], "Long content did not wrap"
            if not any(n.get("text") == "ROW_END" for n in hierarchy.iter("node")):
                left, top, right, bottom = bounds(vertical(hierarchy))
                for index in range(3):
                    adb.run("shell", "input", "swipe", (left+right)//2, int(top+(bottom-top)*.75),
                            (left+right)//2, int(top+(bottom-top)*.45), "240")
                    hierarchy = tree(label+f"-wrapped-bottom-{index}")
                    if any(n.get("text") == "ROW_END" for n in hierarchy.iter("node")):
                        break
                long_cell = next(n for n in hierarchy.iter("node") if n.get("text", "").startswith("长内容换行验证"))
                long_bounds = bounds(long_cell)
            end_bounds = bounds(named(hierarchy, "ROW_END"))
            assert end_bounds[1] >= long_bounds[3], "Wrapped content overlaps the next row"
            adb.screenshot(output / f"{label}-wrapped.png")
            scroll_top(label+"-wide-top")
            hierarchy = tree(label+"-wide-visible")
            horizontal = next(n for n in hierarchy.iter("node") if n.get("class") == "android.widget.HorizontalScrollView")
            left, top, right, bottom = bounds(horizontal)
            y = int(top + (bottom-top)*.35)
            for _ in range(3):
                adb.run("shell", "input", "swipe", int(left+(right-left)*.85), y,
                        int(left+(right-left)*.15), y, "240")
            hierarchy = tree(label+"-right")
            named(hierarchy, "COL_F")
            click("引用 1", label+"-citation")
            wait_state(lambda s: s["clicked"] == "table_source")
            adb.screenshot(output / f"{label}-right.png")
            click("table_stream", label+"-stream-action")
            wait_state(lambda s: s["streaming"], 5)
            wait_state(lambda s: not s["streaming"] and s["chars"] == s["complete_chars"])
            time.sleep(1.5)
            hierarchy = tree(label+"-stream-complete")
            # Completed streaming can retain the user's horizontal anchor.
            horizontal = next(n for n in hierarchy.iter("node") if n.get("class") == "android.widget.HorizontalScrollView")
            left, top, right, bottom = bounds(horizontal)
            y = int(top+(bottom-top)*.35)
            for _ in range(3):
                adb.run("shell", "input", "swipe", int(left+(right-left)*.15), y,
                        int(left+(right-left)*.85), y, "200")
            hierarchy = tree(label+"-stream-start")
            named(hierarchy, "COL_A")
            adb.screenshot(output / f"{label}-stream-complete.png")
            left, top, right, bottom = bounds(vertical(hierarchy))
            x = int(left+(right-left)*.8)
            for index in range(6):
                high, low = int(top+(bottom-top)*.2), int(top+(bottom-top)*.8)
                adb.run("shell", "input", "swipe", x, low if index % 2 == 0 else high,
                        x, high if index % 2 == 0 else low, "160")
            assert adb.run("shell", "pidof", "-s", PACKAGE) == pid, "Process restarted"
            record = dict(renderer="html" if html else "native", dark=dark, fontScale=1.3 if large else 1,
                          alignment=True, wrappedWithoutOverlap=True, horizontalScroll=True,
                          exactCitation=True, streamedCompletion=True, processStable=True)
            results.append(record)
            (output/"device-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(record), flush=True)
        log = adb.run("logcat", "-d", "--pid", pid)
        (output/"device-logcat.txt").write_text(log, encoding="utf-8")
        assert "FATAL EXCEPTION" not in log
    except Exception:
        adb.screenshot(output/"failure.png")
        raise
    finally:
        adb.run("shell", "settings", "delete" if original == "null" else "put", "global",
                "stay_on_while_plugged_in", *(() if original == "null" else (original,)))


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    main()
