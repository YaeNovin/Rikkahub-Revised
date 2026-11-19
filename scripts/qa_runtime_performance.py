"""Capture repeatable USB startup, navigation, background recovery and memory evidence."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from android_qa import Adb, bounds, frame_summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--conversation")
    parser.add_argument("--warm", action="store_true", help="Keep the process alive for pressure and endurance runs")
    parser.add_argument("--overlays", action="store_true", help="Also open and cancel the real menu/title dialog")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    package = "me.rerere.rikkahub"
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    timeout = adb.run("shell", "settings", "get", "system", "screen_off_timeout")
    records = []
    (out / "exit-info-before.txt").write_text(adb.run("shell", "dumpsys", "activity", "exit-info", package), encoding="utf-8")
    prefs = ET.fromstring(adb.run("exec-out", "run-as", package, "cat", "shared_prefs/rikkahub.preferences.xml"))
    conversation = args.conversation or next((n.text for n in prefs if n.get("name") == "lastConversationId"), None)
    launch = ["shell", "am", "start", "-W", "-n", f"{package}/.RouteActivity"]
    if conversation:
        launch.extend(["--es", "conversationId", conversation])

    def snapshot(name):
        mem = adb.run("shell", "dumpsys", "meminfo", package)
        system = adb.run("shell", "cat", "/proc/meminfo")
        (out / f"{name}-meminfo.txt").write_text(mem, encoding="utf-8")
        (out / f"{name}-system.txt").write_text(system, encoding="utf-8")
        pss = re.search(r"TOTAL PSS:\s*(\d+)", mem) or re.search(r"TOTAL\s+(\d+)", mem)
        return {"pss_kb": int(pss[1]) if pss else None,
                "available_kb": int(re.search(r"MemAvailable:\s*(\d+)", system)[1])}

    def node(predicate, name):
        return adb.wait_node(predicate, out / f"{name}.xml", package, timeout=40)

    trace_name = f"runtime-{int(time.time())}.pftrace"
    remote = f"/data/misc/perfetto-traces/{trace_name}"
    try:
        adb.run("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
        if not args.warm:
            adb.run("shell", "am", "force-stop", package)
        adb.run("shell", "perfetto", "--background-wait", "-o", remote, "-t", "55s", "--app", package,
                "sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "dalvik")
        trace_started = time.monotonic()
        start = adb.run(*launch)
        (out / "cold-start.txt").write_text(start, encoding="utf-8")
        node(lambda n: n.get("resource-id") == "chat_input", "chat")
        adb.screenshot(out / "chat.png")
        records.append({"phase": "initial", **snapshot("cold")})
        adb.run("shell", "dumpsys", "gfxinfo", package, "reset")
        for cycle in range(args.cycles):
            drawer = node(lambda n: n.get("resource-id") == "chat_open_drawer" or n.get("content-desc") == "打开侧边栏", f"chat-{cycle}")
            started = time.monotonic()
            adb.tap(drawer)
            node(lambda n: n.get("content-desc") == "助手设置", f"drawer-{cycle}")
            tree = adb.ui(out / f"drawer-{cycle}.xml")
            setting = next((n for n in tree.iter("node") if n.get("text") == "设置"), None)
            if setting is None:
                height = max(bounds(n)[3] for n in tree.iter("node") if n.get("package") == package)
                setting = max((n for n in tree.iter("node") if n.get("clickable") == "true"
                    and bounds(n)[1] > height * .9), key=lambda n: bounds(n)[2])
            drawer_seconds = time.monotonic() - started
            started = time.monotonic()
            adb.tap(setting)
            node(lambda n: n.get("text") == "偏好设置", f"settings-{cycle}")
            setting_seconds = time.monotonic() - started
            started = time.monotonic()
            adb.run("shell", "input", "keyevent", "4")
            node(lambda n: n.get("resource-id") == "chat_input", f"return-{cycle}")
            back_seconds = time.monotonic() - started
            if args.overlays and cycle in (0, args.cycles - 1):
                adb.tap(node(lambda n: n.get("resource-id") == "chat_open_menu", f"menu-button-{cycle}"))
                edit = node(lambda n: n.get("text") == "编辑标题", f"menu-{cycle}")
                adb.tap(edit)
                cancel = node(lambda n: n.get("text") == "取消", f"dialog-{cycle}")
                adb.tap(cancel)
                node(lambda n: n.get("resource-id") == "chat_input", f"dialog-return-{cycle}")
            records.append({"phase": f"navigation-{cycle}", "drawer_observed_s": drawer_seconds,
                "settings_observed_s": setting_seconds, "back_observed_s": back_seconds,
                **snapshot(f"navigation-{cycle}")})
        adb.run("shell", "input", "keyevent", "3")
        time.sleep(3)
        records.append({"phase": "background", **snapshot("background")})
        (out / "warm-start.txt").write_text(adb.run(*launch), encoding="utf-8")
        node(lambda n: n.get("resource-id") == "chat_input", "resumed")
        records.append({"phase": "resumed", **snapshot("resumed")})
        gfx = adb.run("shell", "dumpsys", "gfxinfo", package)
        (out / "gfxinfo.txt").write_text(gfx, encoding="utf-8")
        (out / "framestats.txt").write_text(adb.run("shell", "dumpsys", "gfxinfo", package, "framestats"), encoding="utf-8")
        remaining = 56 - (time.monotonic()-trace_started)
        if remaining > 0:
            time.sleep(remaining)
        adb.run("pull", remote, str(out / trace_name))
        summary = {"records": records, "frames": frame_summary(gfx), "conversation": conversation,
                   "observed_timing_note": "Includes adb input and UIAutomator dump overhead; use trace for frame timings."}
        (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        adb.run("shell", "settings", "put", "system", "screen_off_timeout", timeout)
        (out / "exit-info-after.txt").write_text(adb.run("shell", "dumpsys", "activity", "exit-info", package), encoding="utf-8")


if __name__ == "__main__":
    main()
