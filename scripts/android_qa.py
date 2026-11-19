"""Small standard-library helpers shared by adb QA scripts."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from local_build_resources import resolve_adb

ROOT = Path(__file__).resolve().parents[1]


def parse_ui(raw: str) -> ET.Element:
    start, end = raw.find("<hierarchy"), raw.rfind("</hierarchy>")
    if start < 0 or end < start:
        raise ValueError("ADB output contains no complete UI hierarchy")
    return ET.fromstring(raw[start:end + len("</hierarchy>")])


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise ValueError("Missing UI-tree bounds; refusing to guess coordinates")
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise ValueError("Empty UI-tree bounds")
    return left, top, right, bottom


def center(node: ET.Element) -> tuple[int, int]:
    left, top, right, bottom = bounds(node)
    return (left + right) // 2, (top + bottom) // 2


class Adb:
    def __init__(self, executable: str, serial: str):
        self.command = [resolve_adb(executable, ROOT), "-s", serial]

    def run(self, *args: str, binary: bool = False, timeout: float = 30):
        result = subprocess.run([*self.command, *map(str, args)], capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace").strip() or f"ADB failed: {args}")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

    def ui(self, output: Path | None = None) -> ET.Element:
        tree = parse_ui(self.run("exec-out", "uiautomator", "dump", "/dev/tty"))
        if output:
            output.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(tree).write(output, encoding="utf-8", xml_declaration=True)
        return tree

    def wait_node(self, predicate, output: Path, package: str, timeout: float = 15) -> ET.Element:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                tree = self.ui(output)
                node = next((n for n in tree.iter("node") if n.get("package") == package and predicate(n)), None)
                if node is not None:
                    return node
            except ValueError:
                pass
            time.sleep(.2)
        raise RuntimeError(f"Expected UI node is missing: {output.stem}")

    def tap(self, node: ET.Element):
        x, y = center(node)
        self.run("shell", "input", "tap", x, y)

    def screenshot(self, output: Path):
        output.parent.mkdir(parents=True, exist_ok=True)
        raw = self.run("exec-out", "screencap", "-p", binary=True)
        # Android 16 multi-display devices can write a warning before the PNG on stdout.
        start = raw.find(b"\x89PNG\r\n\x1a\n")
        if start < 0:
            raise RuntimeError("ADB screencap returned no PNG image")
        output.write_bytes(raw[start:])


def frame_summary(raw: str) -> dict:
    result = {}
    for key, pattern in {
        "frames": r"Total frames rendered:\s*(\d+)",
        "janky": r"Janky frames:\s*(\d+)",
        "p50_ms": r"50th percentile:\s*(\d+)ms",
        "p90_ms": r"90th percentile:\s*(\d+)ms",
        "p95_ms": r"95th percentile:\s*(\d+)ms",
        "p99_ms": r"99th percentile:\s*(\d+)ms",
    }.items():
        match = re.search(pattern, raw)
        if match:
            result[key] = int(match[1])
    if result.get("frames", 0) <= 0:
        raise ValueError("No rendered frames; this is not a valid performance capture")
    if "janky" in result:
        result["jank_percent"] = round(100 * result["janky"] / result["frames"], 2)
    return result
