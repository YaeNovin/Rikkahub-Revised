"""Exercise the production root theme and global materials in an isolated debug app."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import time
from PIL import Image
from android_qa import ROOT, Adb, bounds
from verify_dark_appearance import contrast

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--materials", nargs="+", default=["LIQUID_GLASS", "MICA"])
    parser.add_argument("--backdrops", nargs="+", default=["dark", "none", "bright"])
    parser.add_argument("--amoled", action="store_true")
    parser.add_argument("--provided-wallpaper", action="store_true", help="Analyze fixture original as the system wallpaper source without changing the device wallpaper")
    parser.add_argument("--output", type=Path, default=ROOT / "build/qa-name-colors/appearance")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    awake = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    results = []
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        for material in args.materials:
            for backdrop in args.backdrops:
                adb.run("shell", "am", "start", "-W", "-S", "-n", f"{PACKAGE}/me.rerere.rikkahub.AppearanceModeRegressionActivity",
                    "--es", "material", material, "--es", "backdrop", backdrop, "--ez", "amoled", str(args.amoled).lower(),
                    "--ez", "providedWallpaper", str(args.provided_wallpaper).lower())
                for mode in ("THEME", "APP_BACKGROUND", "SYSTEM_WALLPAPER", "AUTO_CLEAR", "APP_BACKGROUND"):
                    step = f"{material}-{backdrop}-{len(results)}-{mode}"
                    button = adb.wait_node(lambda n: n.get("text") == mode, args.output / f"{step}-before.xml", PACKAGE)
                    adb.tap(button)
                    time.sleep(.8)  # Allow asynchronous wallpaper metadata / bitmap samples to arrive.
                    tree = adb.ui(args.output / f"{step}.xml")
                    path = args.output / f"{step}.png"
                    adb.screenshot(path)
                    roles = []
                    with Image.open(path) as image:
                        image = image.convert("RGB")
                        for role in ("page", "inherited", "card", "card-token", "setting-token", "message-user", "message-body"):
                            marker = next(n for n in tree.iter("node") if n.get("content-desc", "").startswith(f"actual:{role}:{mode}:"))
                            fields = marker.get("content-desc").split(":")
                            value = int(fields[3], 16)
                            sampled_minimum = float(fields[4])
                            shadow = fields[5] == "true"
                            weight = int(fields[6])
                            retention = float(fields[7])
                            foreground = ((value >> 16) & 255, (value >> 8) & 255, value & 255)
                            crop = image.crop(bounds(marker))
                            pixels = list(crop.get_flattened_data() if hasattr(crop, "get_flattened_data") else crop.getdata())
                            background = Counter(pixels).most_common(1)[0][0]
                            ratio = contrast(foreground, background)
                            matching = sum(max(abs(a - b) for a, b in zip(pixel, foreground)) <= 5 for pixel in pixels)
                            result = dict(role=role, foreground=foreground, background=background, contrast=round(ratio, 3),
                                sampled_minimum=round(sampled_minimum, 3), shadow=shadow, weight=weight,
                                retention=round(retention, 4), pixels=matching)
                            roles.append(result)
                            # THEME intentionally preserves the user's unmodified theme on a bright wallpaper.
                            # Mixed images, vertical overlays and glass can make a
                            # universal 4.5 impossible. Report their measured floor
                            # and enforce the bounded contrast policy without shadows.
                            bounded_surface = sampled_minimum < 4.5 and retention >= .8499
                            if shadow or (mode != "THEME" and weight != 440):
                                raise ValueError(f"Shadow or compounded text weight in {step}: {result}")
                            if mode != "THEME" and (matching < 12 or (backdrop != "real" and ratio < 4.5 and not bounded_surface)):
                                raise ValueError(f"Unreadable actual pixels in {step}: {result}")
                            if mode != "THEME" and sampled_minimum < 4.5 and not bounded_surface:
                                raise ValueError(f"Mixed surface exceeded its contrast-loss bound in {step}: {result}")
                            if backdrop not in ("bright", "real") and mode in ("APP_BACKGROUND", "SYSTEM_WALLPAPER") and max(foreground) < 80:
                                raise ValueError(f"Dark page acquired near-black text in {step}: {result}")
                            if backdrop == "real" and mode in ("APP_BACKGROUND", "SYSTEM_WALLPAPER") and max(foreground) - min(foreground) < 16:
                                raise ValueError(f"Wallpaper hue disappeared in {step}: {result}")
                    results.append(dict(material=material, backdrop=backdrop, mode=mode, roles=roles, screenshot=path.name))
                    (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
                if backdrop == "real":
                    selected = results[-5:]
                    app, system, repeated_app = selected[1], selected[2], selected[4]
                    for a, s, again in zip(app["roles"], system["roles"], repeated_app["roles"]):
                        if (not args.provided_wallpaper and a["foreground"] == s["foreground"]) or a["foreground"] != again["foreground"]:
                            raise ValueError(f"Color source did not switch and restore: {a} / {s} / {again}")
        print(f"PASS {len(results)} actual theme/material mode transitions; {len(results) * 7} rendered text regions checked")
    finally:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", awake) if awake != "null" else \
            adb.run("shell", "settings", "delete", "global", "stay_on_while_plugged_in")


if __name__ == "__main__":
    main()
