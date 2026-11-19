"""Pair one bounded Android scroll flow with an on-device Perfetto trace."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys
import time
from android_qa import Adb, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="me.rerere.rikkahub")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    name = f"message-boundary-{time.strftime('%Y%m%d-%H%M%S')}.pftrace"
    remote = f"/data/misc/perfetto-traces/{name}"
    started = time.monotonic()
    adb.run("shell", "perfetto", "--background-wait", "-o", remote, "-t", "25s", "--app", args.package,
            "sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "hal", "dalvik")
    flow = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/capture_android_flow.py"),
        "--serial", args.serial, "--adb", args.adb, "--package", args.package, "--flow", "scroll",
        "--output", str(args.output)], check=False)
    time.sleep(max(0, 26 - (time.monotonic() - started)))
    previous = None
    for _ in range(6):
        size = adb.run("shell", "stat", "-c", "%s", remote)
        if size == previous and size.isdigit() and int(size) > 0:
            break
        previous = size
        time.sleep(1)
    else:
        raise RuntimeError("Perfetto trace did not finish flushing")
    adb.run("pull", remote, str(args.output / name))
    print(f"Perfetto trace saved: {args.output / name}")
    return flow.returncode


if __name__ == "__main__":
    sys.exit(main())
