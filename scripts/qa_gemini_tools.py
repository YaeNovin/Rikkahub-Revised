"""Verify production Gemini assistant navigation and per-edition search keys using USB adb."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time

from android_qa import Adb, ROOT, bounds

PACKAGE = "me.rerere.rikkahub.revised.appearanceqa"


def proto_fields(data: bytes):
    """Read the wire types used by the AndroidX Preferences map, without saving its contents."""
    offset = 0

    def varint():
        nonlocal offset
        value = shift = 0
        while offset < len(data) and shift < 70:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7
        raise ValueError("Invalid Preferences varint")

    while offset < len(data):
        tag = varint()
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            value = varint()
        elif wire in (1, 2, 5):
            length = varint() if wire == 2 else {1: 8, 5: 4}[wire]
            if offset + length > len(data):
                raise ValueError("Truncated Preferences field")
            value = data[offset:offset + length]
            offset += length
        else:
            raise ValueError(f"Unsupported Preferences wire type: {wire}")
        yield field, wire, value


def launch_count(adb: Adb) -> int:
    # The isolated Debug package is debuggable. Read only this counter from the
    # persisted protobuf; ROMs may suppress logcat even after startup succeeds.
    raw = adb.run("exec-out", "run-as", PACKAGE, "cat", "files/datastore/settings.preferences_pb", binary=True)
    for field, wire, entry in proto_fields(raw):
        if field != 1 or wire != 2:
            continue
        fields = {(number, kind): value for number, kind, value in proto_fields(entry)}
        if fields.get((1, 2)) == b"launch_count":
            values = {(number, kind): value for number, kind, value in proto_fields(fields[(2, 2)])}
            return values[(3, 0)]
    return 0


def find_text(adb: Adb, text: str, output: Path, scroll: bool = False):
    for attempt in range(6 if scroll else 1):
        tree = adb.ui(output)
        node = next((n for n in tree.iter("node") if n.get("text") == text), None)
        if node is not None:
            return node
        scrollers = [n for n in tree.iter("node") if n.get("scrollable") == "true"]
        if not scroll or not scrollers:
            break
        viewport = max(scrollers, key=lambda n: bounds(n)[3] - bounds(n)[1])
        left, top, right, bottom = bounds(viewport)
        x = (left + right) // 2
        adb.run("shell", "input", "swipe", x, int(top + (bottom - top) * .85), x,
                int(top + (bottom - top) * .22), 250)
    raise AssertionError(f"Missing text: {text}")


def tap(adb: Adb, text: str, output: Path, scroll=False):
    adb.tap(find_text(adb, text, output, scroll))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/gemini-tools/device")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb.run("shell", "am", "force-stop", PACKAGE)
    previous_count = launch_count(adb)
    adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/me.rerere.rikkahub.RouteActivity")
    pid = adb.run("shell", "pidof", "-s", PACKAGE)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if launch_count(adb) > previous_count:
            break
        time.sleep(.2)
    else:
        raise AssertionError("Application initialization did not finish")
    adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/me.rerere.rikkahub.GeminiToolsRegressionActivity")
    adb.wait_node(lambda n: n.get("text") == "READY gemini-4-argon", output / "argon-ready.xml", PACKAGE)
    passed = []
    try:
        tap(adb, "Gemini 内置工具", output / "assistant-entry.xml", True)
        adb.wait_node(lambda n: n.get("text") == "Google 内置搜索与 URL 上下文", output / "argon-tools.xml", PACKAGE)
        adb.screenshot(output / "argon-tools-dark.png")
        tree = adb.ui(output / "argon-switches.xml")
        switches = [n for n in tree.iter("node") if n.get("checkable") == "true"]
        assert switches and switches[0].get("checked") == "true"
        adb.tap(switches[0])
        adb.wait_node(lambda n: n.get("text") == "本助手搜索=false", output / "search-off.xml", PACKAGE)
        passed.append("legacy defaults migrated and assistant search switch applies")

        tap(adb, "Carbon", output / "switch-carbon.xml")
        adb.wait_node(lambda n: n.get("text") == "READY gemini-4-carbon", output / "carbon-tools.xml", PACKAGE)
        find_text(adb, "本助手搜索=false", output / "carbon-state.xml")
        assert find_text(adb, "Google 内置搜索与 URL 上下文", output / "carbon-page.xml") is not None
        adb.screenshot(output / "carbon-tools.png")
        passed.append("Gemini 4 Carbon keeps tools page and assistant settings")
        tap(adb, "GPT", output / "switch-gpt.xml")
        adb.wait_node(lambda n: n.get("text") == "READY gpt-6.1-sol", output / "gpt-root.xml", PACKAGE)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            tree = adb.ui(output / "gpt-hidden.xml")
            texts = {n.get("text") for n in tree.iter("node")}
            if "Google 内置搜索与 URL 上下文" not in texts:
                break
        assert "Gemini 内置工具" not in texts and "Google 内置搜索与 URL 上下文" not in texts
        adb.screenshot(output / "gpt-tools-hidden.png")
        passed.append("switching to GPT dismisses and hides Gemini page")

        tap(adb, "Argon", output / "return-argon.xml")
        tap(adb, "Gemini 内置工具", output / "reopen-argon.xml", True)
        adb.wait_node(lambda n: n.get("text") == "Google 内置搜索与 URL 上下文", output / "argon-reopened.xml", PACKAGE)
        tap(adb, "Gemma", output / "switch-gemma.xml")
        adb.wait_node(lambda n: n.get("text") == "READY gemma-4-31b-it", output / "gemma-root.xml", PACKAGE)
        tree = adb.ui(output / "gemma-hidden.xml")
        assert all(n.get("text") != "Gemini 内置工具" for n in tree.iter("node"))
        passed.append("Google provider Gemma does not inherit Gemini tools")

        tap(adb, "Argon", output / "model-argon.xml")
        tap(adb, "模型", output / "model-form.xml")
        tap(adb, "内置工具", output / "model-tools-tab.xml")
        tree = adb.ui(output / "model-tools-migrated.xml")
        texts = {n.get("text") for n in tree.iter("node")}
        assert "Google 搜索" not in texts and "URL 上下文" not in texts
        assert any("已移至" in (t or "") for t in texts)
        adb.screenshot(output / "model-tools-migrated.png")
        passed.append("provider model form has no duplicate Gemini search switches")

        tap(adb, "豆包Key", output / "volc-tab.xml")
        tree = adb.ui(output / "custom-key.xml")
        edit = next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText")
        adb.tap(edit)
        adb.run("shell", "input", "text", "customfixture")
        adb.run("shell", "input", "keyevent", "4")
        tap(adb, "Global", output / "global-switch.xml")
        tree = adb.ui(output / "global-key.xml")
        edit = next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText")
        assert edit.get("text", "") == ""
        adb.tap(edit)
        adb.run("shell", "input", "text", "globalfixture")
        adb.run("shell", "input", "keyevent", "4")
        tap(adb, "Custom", output / "custom-return.xml")
        assert find_text(adb, "customfixture", output / "custom-retained.xml") is not None
        tap(adb, "Global", output / "global-return.xml")
        assert find_text(adb, "globalfixture", output / "global-retained.xml") is not None
        adb.screenshot(output / "edition-keys.png")
        passed.append("Custom and Global retain different keys across switches")
    finally:
        logs = adb.run("logcat", "-d", "--pid", pid, "AndroidRuntime:E", "*:S")
        (output / "logcat.txt").write_text(logs, encoding="utf-8")
        logcat_available = bool(adb.run("logcat", "-d", "-t", "1"))
        same_process = adb.run("shell", "pidof", "-s", PACKAGE) == pid
        (output / "results.json").write_text(json.dumps({"passed": passed, "count": len(passed),
            "startup_counter_increased": True, "same_process": same_process,
            "logcat_available": logcat_available}, ensure_ascii=False, indent=2), encoding="utf-8")
    assert "FATAL EXCEPTION" not in logs
    assert same_process, "Application process changed during UI verification"
    assert len(passed) == 6
    print(json.dumps({"passed": passed}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
