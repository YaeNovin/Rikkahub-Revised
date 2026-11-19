"""Verify real process recreation, first visible palette, input bounds and repeated cold starts."""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import time
from PIL import Image
from android_qa import Adb, bounds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--conversation", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    package = "me.rerere.rikkahub"
    command = ["shell", "am", "start", "-W", "-n", f"{package}/.RouteActivity", "--es", "conversationId", args.conversation]
    timeout = adb.run("shell", "settings", "get", "system", "screen_off_timeout")

    def app_pid():
        try:
            return adb.run("shell", "pidof", "-s", package)
        except RuntimeError:
            return ""

    def capture(name):
        tree = adb.ui(out / f"{name}.xml")
        text_input = next(n for n in tree.iter("node") if n.get("resource-id") == "chat_input")
        button = next(n for n in tree.iter("node") if n.get("resource-id") == "chat_open_drawer")
        adb.screenshot(out / f"{name}.png")
        with Image.open(out / f"{name}.png") as image:
            _, top, _, bottom = bounds(text_input)
            assert top >= 0 and bottom <= image.height and bottom > top
            pixels = list(image.convert("RGB").crop(bounds(button)).getdata())
        accent = Counter(pixel for pixel in pixels if (max(pixel) - min(pixel) > 65 and max(pixel) > 120)
            or min(pixel) > 210).most_common(1)
        return {"input_visible": True, "header_accent_rgb": accent[0][0] if accent else None}

    try:
        adb.run("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
        # Also works after a natural LMK. Then explicitly recreate once more from background.
        (out / "reopen.txt").write_text(adb.run(*command), encoding="utf-8")
        adb.wait_node(lambda n: n.get("resource-id") == "chat_input", out / "reopened.xml", package, timeout=40)
        reopened = capture("reopened")
        previous = app_pid()
        adb.run("shell", "input", "keyevent", "3")
        time.sleep(2)
        adb.run("shell", "am", "kill", package)
        for _ in range(30):
            if not app_pid():
                break
            time.sleep(.2)
        assert not app_pid(), "System did not permit cached process recreation"
        (out / "recreate.txt").write_text(adb.run(*command), encoding="utf-8")
        first = capture("first")
        time.sleep(2)
        settled = capture("settled")
        assert app_pid() and app_pid() != previous
        assert first["header_accent_rgb"] is not None
        assert first["header_accent_rgb"] == settled["header_accent_rgb"], "Header palette changed after first visible UI"
        timings = []
        for index in range(5):
            adb.run("shell", "am", "force-stop", package)
            launch = adb.run(*command)
            (out / f"cold-{index}.txt").write_text(launch, encoding="utf-8")
            timings.append(int(re.search(r"TotalTime:\s*(\d+)", launch)[1]))
            time.sleep(.5)
        capture("cold-final")
        result = {"reopened": reopened, "first": first, "settled": settled,
            "cached_process_recreated": True, "cold_start_ms": timings, "cold_median_ms": sorted(timings)[2]}
        (out / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
    finally:
        adb.run("shell", "settings", "put", "system", "screen_off_timeout", timeout)


if __name__ == "__main__":
    main()
