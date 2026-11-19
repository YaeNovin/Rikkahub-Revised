"""Capture bounded scroll/IME frame statistics from the current adb app screen."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time
from android_qa import ROOT, Adb, bounds, frame_summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="me.rerere.rikkahub")
    parser.add_argument("--flow", choices=("scroll", "keyboard"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-fixture", action="store_true", help="Allow a fixed-content scroll fixture without a composer")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    args.output.mkdir(parents=True, exist_ok=True)
    focus = adb.run("shell", "dumpsys", "window")
    if not re.search(r"mCurrentFocus=.*" + re.escape(args.package) + r"/", focus):
        raise RuntimeError("Expected app is not foreground; unlock and navigate to the target flow first")
    tree = adb.ui(args.output / "before.xml")
    nodes = [n for n in tree.iter("node") if n.get("package") == args.package]
    editors = [n for n in nodes if n.get("class") == "android.widget.EditText"]
    if not editors and not (args.allow_fixture and args.flow == "scroll"):
        raise RuntimeError("Chat composer is not visible")
    scrollers = [n for n in nodes if n.get("scrollable") == "true"]
    if args.flow == "scroll" and (not scrollers or sum(len(n.get("text", "")) for n in nodes) < 200):
        raise RuntimeError("No populated scrollable conversation; refusing to profile an empty screen")
    awake = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        adb.run("shell", "dumpsys", "gfxinfo", args.package, "reset")
        if args.flow == "scroll":
            target = max(scrollers, key=lambda n: (bounds(n)[3] - bounds(n)[1]) * (bounds(n)[2] - bounds(n)[0]))
            left, top, right, bottom = bounds(target)
            x, low, high = (left + right) // 2, top + (bottom - top) * 4 // 5, top + (bottom - top) // 5
            for start, end in [(low, high)] * 3 + [(high, low)] * 3:
                adb.run("shell", "input", "swipe", x, start, x, end, "350")
                time.sleep(.25)
        else:
            for _ in range(2):
                adb.tap(editors[-1])
                time.sleep(1.2)
                if not re.search(r"mInputShown=true|isInputViewShown=true", adb.run("shell", "dumpsys", "input_method")):
                    raise RuntimeError("Keyboard did not open")
                adb.run("shell", "input", "keyevent", "4")
                time.sleep(1.2)
        time.sleep(.5)
        raw = adb.run("shell", "dumpsys", "gfxinfo", args.package)
        (args.output / "gfxinfo.txt").write_text(raw, encoding="utf-8")
        (args.output / "framestats.txt").write_text(adb.run("shell", "dumpsys", "gfxinfo", args.package, "framestats"), encoding="utf-8")
        adb.ui(args.output / "after.xml")
        adb.screenshot(args.output / "after.png")
        result = dict(flow=args.flow, package=args.package, summary=frame_summary(raw))
        (args.output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
    finally:
        if awake == "null":
            adb.run("shell", "settings", "delete", "global", "stay_on_while_plugged_in")
        else:
            adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", awake)


if __name__ == "__main__":
    main()
