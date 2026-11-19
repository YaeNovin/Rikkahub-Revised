"""Exercise production chat cancellation/persistence and search settings in an isolated debug APK."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time
import uuid
import xml.etree.ElementTree as ET

from android_qa import Adb, ROOT, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"
ACTIVITY = f"{PACKAGE}/me.rerere.rikkahub.ChatTurnRegressionActivity"


def tap_text(adb: Adb, text: str, output: Path):
    node = adb.wait_node(lambda n: n.get("text") == text, output, PACKAGE)
    adb.tap(node)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/chat-turns/device")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex
    adb.run("shell", "am", "force-stop", PACKAGE)
    # Let normal application startup finish before replacing this isolated app's
    # settings with a local fixture. The launch counter writes a settings snapshot.
    adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/me.rerere.rikkahub.RouteActivity")
    warm_pid = adb.run("shell", "pidof", "-s", PACKAGE)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        startup = adb.run("logcat", "-d", "--pid", warm_pid, "RikkaHubApp:I", "*:S")
        if "incrementLaunchCount:" in startup:
            break
        time.sleep(.2)
    else:
        raise RuntimeError("Normal application startup did not finish")
    launch = adb.run("shell", "am", "start", "-W", "-n", ACTIVITY, "--es", "run", run_id)
    (output / "launch.txt").write_text(launch, encoding="utf-8")
    adb.wait_node(lambda n: n.get("text", "").startswith("READY "), output / "ready.xml", PACKAGE)
    pid = adb.run("shell", "pidof", "-s", PACKAGE)
    results = {}
    try:
        for number, mode in enumerate(["immediate"] * 8 + ["body"] * 3 + ["stream"] * 3, 1):
            if mode == "stream" and number == 12:
                tree = adb.ui(output / "switch-to-stream.xml")
                switch = next(n for n in tree.iter("node") if n.get("checkable") == "true")
                adb.tap(switch)
            action = "立即发送并停止" if mode == "immediate" else "发送测试消息"
            tap_text(adb, action, output / f"{number:02}-send.xml")
            if mode != "immediate":
                stage = "接口状态：流式响应已开始" if mode == "stream" else "接口状态：正文读取中"
                snapshot = output / f"{number:02}-response-started.xml"
                adb.wait_node(lambda n: n.get("text") == stage, snapshot, PACKAGE, timeout=30)
                stop = next(n for n in ET.parse(snapshot).iter("node") if n.get("text") == "停止一次")
                adb.tap(stop)
            adb.wait_node(lambda n: n.get("text", "").startswith(f"PASS {number} "), output / f"{number:02}-passed.xml", PACKAGE)
            raw = adb.run("shell", "run-as", PACKAGE, "cat", f"files/chat-turn-{run_id}.json")
            results = json.loads(raw)
            item = results["reports"][-1]
            assert item["persisted"] is True, item
            assert item["stop_ack_ms"] < 100, item
            assert item["stop_complete_ms"] < 2000, item
            if mode != "immediate":
                assert item["connections"] >= number - 8, item
            print(json.dumps({"case": mode, **item}, ensure_ascii=False), flush=True)
        adb.screenshot(output / "turns-passed.png")

        tap_text(adb, "Gemini搜索", output / "gemini-tab.xml")
        adb.wait_node(lambda n: n.get("text") == "Google 内置搜索与 URL 上下文", output / "gemini-settings.xml", PACKAGE)
        adb.screenshot(output / "gemini-dark.png")
        tap_text(adb, "切换浅色", output / "gemini-light-switch.xml")
        adb.screenshot(output / "gemini-light.png")
        tree = adb.ui(output / "gemini-scroll.xml")
        scrollable = next(n for n in tree.iter("node") if n.get("scrollable") == "true" and n.get("class") != "android.webkit.WebView")
        left, top, right, bottom = bounds(scrollable)
        adb.run("shell", "input", "swipe", (left + right) // 2, int(bottom * .82), (left + right) // 2, int(top + (bottom - top) * .2), 250)
        adb.ui(output / "gemini-lower.xml")
        adb.screenshot(output / "gemini-lower.png")

        tap_text(adb, "豆包搜索", output / "volc-tab.xml")
        adb.wait_node(lambda n: n.get("text") == "搜索服务 API Key", output / "volc-custom.xml", PACKAGE)
        adb.screenshot(output / "volc-custom.png")
        tap_text(adb, "Global", output / "volc-global-switch.xml")
        tap_text(adb, "visual", output / "volc-visual-switch.xml")
        adb.wait_node(lambda n: n.get("text", "").startswith("图搜图："), output / "volc-visual.xml", PACKAGE)
        adb.screenshot(output / "volc-visual.png")
    finally:
        logs = adb.run("logcat", "-d", "--pid", pid, "AndroidRuntime:E", "ChatService:W", "*:S")
        (output / "logcat.txt").write_text(logs, encoding="utf-8")
        (output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    assert "FATAL EXCEPTION" not in logs, logs
    assert len(results.get("reports", [])) == 14
    assert results["reports"][-1]["connections"] >= 6
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
