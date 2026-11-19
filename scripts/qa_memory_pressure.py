"""Run the bounded native pressure helper around the real QA navigation flow."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from android_qa import Adb, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    remote = "/data/local/tmp/rikka-runtime-memory-pressure"
    adb.run("push", str(args.helper.resolve()), remote)
    adb.run("shell", "chmod", "755", remote)
    adb.run("shell", "rm", "-f", "/data/local/tmp/rikka-pressure.stop")
    samples = []
    target = threading.Event()
    with (out / "pressure.txt").open("w", encoding="utf-8") as log:
        helper = subprocess.Popen([*adb.command, "shell", remote, "1536", "4096", "150"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

        def read():
            for line in helper.stdout:
                log.write(line)
                log.flush()
                try:
                    sample = json.loads(line)
                    samples.append(sample)
                    if sample["available_kb"] <= 1536 * 1024:
                        target.set()
                except (ValueError, KeyError):
                    pass

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        try:
            for _ in range(45):
                if target.wait(1) or helper.poll() is not None:
                    break
            if helper.poll() is not None:
                raise RuntimeError(f"Pressure helper ended before the flow: {helper.returncode}")
            result = subprocess.run([sys.executable, "-X", "utf8", "-B", str(ROOT / "scripts/qa_runtime_performance.py"),
                "--adb", args.adb, "--serial", args.serial, "--warm", "--overlays", "--cycles", "6", "--output", str(out / "flow")], timeout=180)
            if result.returncode:
                raise RuntimeError(f"Pressure navigation failed: {result.returncode}")
        finally:
            adb.run("shell", "touch", "/data/local/tmp/rikka-pressure.stop")
            try:
                helper.wait(timeout=8)
            except subprocess.TimeoutExpired:
                # The helper also has a hard 150-second deadline if the USB link disappears.
                helper.terminate()
                helper.wait(timeout=5)
            reader.join(timeout=3)
            summary = {"target_available_mb": 1536, "target_reached": target.is_set(),
                "minimum_available_kb": min((s["available_kb"] for s in samples), default=None),
                "maximum_allocated_mb": max((s["allocated_mb"] for s in samples), default=None),
                "helper_exit": helper.returncode, "samples": len(samples)}
            (out / "pressure-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
            print(json.dumps(summary), flush=True)
            adb.run("shell", "rm", "-f", remote, "/data/local/tmp/rikka-pressure.stop")


if __name__ == "__main__":
    main()
