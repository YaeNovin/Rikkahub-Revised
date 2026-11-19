"""Check dark/AMOLED palette switching in the isolated debug fixture (Pillow required)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time
from PIL import Image
from android_qa import ROOT, Adb, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"
MODES = ("THEME", "AUTO_CLEAR", "APP_BACKGROUND", "SYSTEM_WALLPAPER")


def contrast(first, second):
    def luminance(rgb):
        linear = [v / 255 / 12.92 if v / 255 <= .04045 else ((v / 255 + .055) / 1.055) ** 2.4 for v in rgb]
        return sum(v * weight for v, weight in zip(linear, (.2126, .7152, .0722)))
    a, b = sorted((luminance(first), luminance(second)), reverse=True)
    return (a + .05) / (b + .05)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=ROOT / "build/qa-parameters/dark")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    args.output.mkdir(parents=True, exist_ok=True)
    original_awake = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    results = []
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        for amoled in (False, True):
            adb.run("shell", "am", "start", "-W", "-S", "-n", f"{PACKAGE}/me.rerere.rikkahub.RichTextRegressionActivity",
                    "--ez", "amoled", str(amoled).lower(), "--es", "mode", "THEME")
            for mode in (*MODES, "APP_BACKGROUND", "THEME"):
                step = f"{'amoled' if amoled else 'dark'}-{len(results)}-{mode}"
                button = adb.wait_node(lambda n: n.get("text") == mode, args.output / f"{step}-before.xml", PACKAGE)
                adb.tap(button)
                marker = adb.wait_node(lambda n: n.get("content-desc", "").startswith(f"palette:{mode}:"),
                                       args.output / f"{step}.xml", PACKAGE)
                time.sleep(.25)
                output = args.output / f"{step}.png"
                adb.screenshot(output)
                foreground = int(marker.get("content-desc").split(":")[-1], 16)
                rgb = ((foreground >> 16) & 255, (foreground >> 8) & 255, foreground & 255)
                background = (0, 0, 0) if amoled else (18, 19, 22)
                ratio = contrast(rgb, background)
                if ratio < 4.5 or rgb == (0, 0, 0):
                    raise ValueError(f"Unreadable foreground in {step}: {rgb}, contrast {ratio}")
                with Image.open(output) as screenshot:
                    crop = screenshot.convert("RGB").crop(bounds(marker))
                    pixels = list(crop.get_flattened_data() if hasattr(crop, "get_flattened_data") else crop.getdata())
                matching = sum(max(abs(a - b) for a, b in zip(pixel, rgb)) <= 4 for pixel in pixels)
                if matching < 12:
                    raise ValueError(f"Calculated foreground was not rendered in {step}: {matching} matching pixels")
                results.append(dict(amoled=amoled, mode=mode, foreground=hex(foreground), contrast=round(ratio, 3),
                                    rendered_pixels=matching, screenshot=output.name))
        for amoled in (False, True):
            group = [r for r in results if r["amoled"] == amoled]
            by_mode = {r["mode"]: r["foreground"] for r in group}
            if len({by_mode[m] for m in ("AUTO_CLEAR", "APP_BACKGROUND", "SYSTEM_WALLPAPER")}) != 3:
                raise ValueError("Mode switching did not change the foreground hue")
        (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"PASS {len(results)} dark/AMOLED palette switches; contrast and actual screenshot pixels checked")
    finally:
        if original_awake == "null":
            adb.run("shell", "settings", "delete", "global", "stay_on_while_plugged_in")
        else:
            adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", original_awake)


if __name__ == "__main__":
    main()
