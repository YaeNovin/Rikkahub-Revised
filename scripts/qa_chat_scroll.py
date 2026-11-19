"""Profile fast alternating and repeated one-direction swipes on the current conversation."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import time
from android_qa import Adb, bounds, frame_summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    package = "me.rerere.rikkahub"
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    tree = adb.ui(out / "initial.xml")
    assert any(n.get("resource-id") == "chat_input" for n in tree.iter("node")), "Current page is not a chat"
    scrollers = [n for n in tree.iter("node") if n.get("package") == package and n.get("scrollable") == "true"]
    assert scrollers, "Current conversation has no scrollable messages; capture was not started"
    target = max(scrollers, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))
    left, top, right, bottom = bounds(target)
    assert sum(len(n.get("text", "")) for n in tree.iter("node")) > 200, "Conversation is empty"
    x = (left + right)//2
    high, low = int(top+(bottom-top)*.22), int(top+(bottom-top)*.78)
    pid = adb.run("shell", "pidof", "-s", package)
    adb.screenshot(out / "initial.png")
    awake = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    trace = f"scroll-{int(time.time())}.pftrace"
    remote = f"/data/misc/perfetto-traces/{trace}"
    phases = []
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        (out/"exit-before.txt").write_text(adb.run("shell", "dumpsys", "activity", "exit-info", package),encoding="utf-8")
        (out/"memory-before.txt").write_text(adb.run("shell", "dumpsys", "meminfo", package),encoding="utf-8")
        adb.run("shell", "perfetto", "--background-wait", "-o", remote, "-t", "50s", "--app", package,
                "sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "dalvik")
        started = time.monotonic()
        for name, gestures in (
            ("rapid-alternating", [(low,high,120),(high,low,120)]*8),
            ("toward-older", [(high,low,220)]*12),
            ("toward-newer", [(low,high,220)]*12),
        ):
            adb.run("shell", "dumpsys", "gfxinfo", package, "reset")
            for start,end,duration in gestures:
                adb.run("shell", "input", "swipe", x,start,x,end,duration)
            time.sleep(.8)
            assert adb.run("shell", "pidof", "-s", package) == pid, "Application process changed"
            current = adb.ui(out/f"{name}.xml")
            assert any(n.get("resource-id")=="chat_input" for n in current.iter("node"))
            raw = adb.run("shell", "dumpsys", "gfxinfo", package)
            (out/f"{name}-gfxinfo.txt").write_text(raw,encoding="utf-8")
            (out/f"{name}-framestats.txt").write_text(adb.run("shell","dumpsys","gfxinfo",package,"framestats"),encoding="utf-8")
            adb.screenshot(out/f"{name}.png")
            phases.append({"phase":name,"gestures":len(gestures),**frame_summary(raw)})
        remaining = 51-(time.monotonic()-started)
        if remaining > 0: time.sleep(remaining)
        adb.run("pull",remote,str(out/trace))
        (out/"memory-after.txt").write_text(adb.run("shell","dumpsys","meminfo",package),encoding="utf-8")
        (out/"exit-after.txt").write_text(adb.run("shell","dumpsys","activity","exit-info",package),encoding="utf-8")
        (out/"crash-log.txt").write_text(adb.run("logcat", "-b", "crash", "-d", "--pid", pid), encoding="utf-8")
        result={"pid":int(pid),"phases":phases,"same_process":True}
        (out/"results.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
        print(json.dumps(result))
    finally:
        adb.run("shell","settings","delete" if awake=="null" else "put","global","stay_on_while_plugged_in",*(() if awake=="null" else (awake,)))


if __name__ == "__main__":
    main()
