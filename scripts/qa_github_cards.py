"""Run isolated Android GitHub card/Keystore fixtures; never accepts a real Token."""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
import subprocess
import sys
from android_qa import Adb, ROOT

PACKAGE = "me.rerere.rikkahub.revised.githubqa"

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--network-probe", action="store_true", help="Also read public GitHub repository metadata and languages once each")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = ROOT / "build/reports/github-cards-20261120"
    output.mkdir(parents=True, exist_ok=True)
    original = adb.run("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    try:
        adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", "3")
        if args.install:
            metadata = json.loads((ROOT / "app/build/outputs/apk/debug/output-metadata.json").read_text())
            if metadata["applicationId"] != PACKAGE:
                raise ValueError("Build with scripts/github-cards-qa.init.gradle first")
            for path in ["app/build/outputs/apk/debug/app-arm64-v8a-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
                result = adb.run("install", "-r", "--user", "0", str(ROOT / path), timeout=90)
                if "Success" not in result:
                    raise RuntimeError("Installation did not succeed; check the device's installation confirmation")
        classes = ["me.rerere.rikkahub.GitHubTokenStoreTest", "me.rerere.rikkahub.GitHubCardsUiTest"]
        extras = []
        if args.network_probe:
            classes += ["me.rerere.rikkahub.GitHubCardNetworkProbeTest"]
            extras = ["-e", "githubNetworkProbe", "true"]
        command = [*adb.command, "shell", "am", "instrument", "--user", "0", "-w", "-r", "-e", "class", ",".join(classes), *extras,
                   PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
        log = result.stdout + result.stderr
        (output / "instrumentation-direct-host.txt").write_text(log, encoding="utf-8")
        print(log)
        passed = result.returncode == 0 and re.search(r"OK \(\d+ tests?\)", log) is not None and "FAILURES!!!" not in log
        if passed:
            remote = f"/sdcard/Android/data/{PACKAGE}/files/github-card-qa"
            screenshots = output / "screenshots"
            screenshots.mkdir(parents=True, exist_ok=True)
            for name in adb.run("shell", "ls", remote).splitlines():
                if re.fullmatch(r"[A-Za-z0-9_.-]+\.png", name):
                    adb.run("pull", f"{remote}/{name}", str(screenshots / name))
        (output / "device-result.json").write_text(json.dumps({"package": PACKAGE, "passed": passed, "tests": classes,
            "realPersonalTokenUsed": False, "publicNetworkProbe": args.network_probe}, indent=2), encoding="utf-8")
        return 0 if passed else 1
    finally:
        if original.isdigit():
            adb.run("shell", "settings", "put", "global", "stay_on_while_plugged_in", original)

if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())
