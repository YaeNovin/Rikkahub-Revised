"""Compare old/new chat loading layouts while statuses change during real list flings."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import time
import uuid

from android_qa import Adb, ROOT, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"
ACTIVITY = f"{PACKAGE}/me.rerere.rikkahub.ChatSendLayoutRegressionActivity"


def exercise(adb: Adb, output: Path, legacy: bool, rabbit: bool, cycles: int) -> dict:
    label = f"{'legacy' if legacy else 'fixed'}-{'rabbit' if rabbit else 'material'}"
    run_id = uuid.uuid4().hex
    adb.run("shell", "am", "force-stop", PACKAGE)
    launch = adb.run("shell", "am", "start", "-W", "-n", ACTIVITY,
                     "--ez", "legacy", str(legacy).lower(), "--ez", "rabbit", str(rabbit).lower(),
                     "--ei", "cycles", str(cycles), "--es", "run", run_id)
    (output / f"{label}-launch.txt").write_text(launch, encoding="utf-8")
    adb.wait_node(lambda n: n.get("text", "").startswith("READY "), output / f"{label}-ready.xml", PACKAGE)
    tree = adb.ui()
    viewport = next(n for n in tree.iter("node") if n.get("content-desc") == "压力测试消息列表")
    left, top, right, bottom = bounds(viewport)
    x = (left + right) // 2
    low = int(top + (bottom - top) * .82)
    high = int(top + (bottom - top) * .25)
    pid = adb.run("shell", "pidof", "-s", PACKAGE)
    button = next(n for n in tree.iter("node") if n.get("text") == "开始压力测试")
    adb.tap(button)
    started = time.monotonic()
    flings = 0
    deadline = started + 100
    passed = False
    completed = None
    crash = False
    while time.monotonic() < deadline:
        processes = adb.run("shell", "ps", "-A", "-o", "PID,NAME")
        if not re.search(rf"^\s*{re.escape(pid)}\s+", processes, re.MULTILINE):
            crash = True
            break
        try:
            completed = json.loads(adb.run("shell", "run-as", PACKAGE, "cat", f"files/chat-layout-{run_id}.json"))
        except (RuntimeError, ValueError):
            completed = None
        if completed and completed.get("cycles") == cycles and completed.get("legacy") == legacy and completed.get("rabbit") == rabbit:
            passed = completed.get("pass") is True
            break
        start, end = (high, low) if flings % 2 == 0 else (low, high)
        adb.run("shell", "input", "swipe", x, start, x, end, 110)
        flings += 1
    logs = adb.run("logcat", "-d", "--pid", pid, "ChatSendLayoutRegression:I", "AndroidRuntime:E", "*:S")
    crashlog = adb.run("logcat", "-b", "crash", "-d", "--pid", pid)
    (output / f"{label}-logcat.txt").write_text(logs, encoding="utf-8")
    (output / f"{label}-crash.txt").write_text(crashlog, encoding="utf-8")
    if not crash:
        adb.screenshot(output / f"{label}-passed.png")
        adb.ui(output / f"{label}-passed.xml")
    return {"case": label, "cycles_requested": cycles, "pass": passed, "process_exited": crash,
            "flings": flings, "seconds": round(time.monotonic() - started, 2),
            "reported_lookahead_crash": "LookaheadDelegate has not been measured yet" in crashlog,
            "completion": completed}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/chat-send-crash/device")
    parser.add_argument("--cycles", type=int, default=1800)
    parser.add_argument("--case", choices=["all", "legacy-material", "legacy-rabbit", "fixed-material", "fixed-rabbit"], default="all")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for legacy, rabbit in [(True, False), (True, True), (False, False), (False, True)]:
        label = f"{'legacy' if legacy else 'fixed'}-{'rabbit' if rabbit else 'material'}"
        if args.case != "all" and args.case != label:
            continue
        result = exercise(adb, output, legacy, rabbit, args.cycles)
        results.append(result)
        (output / "result.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False), flush=True)
    if any(not r["pass"] for r in results if r["case"].startswith("fixed")):
        raise RuntimeError("Fixed chat status layout did not complete the stress workload")
    return 0


if __name__ == "__main__":
    sys.exit(main())
