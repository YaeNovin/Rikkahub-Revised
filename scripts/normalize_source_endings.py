"""Normalize selected owned source files to LF without changing literal spaces.

Defaults to changed/untracked Git candidates. Never scans ignored user evidence,
vendored assets, symlinks, external paths or binary files. No index/Git writes.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".kt", ".kts", ".xml", ".toml", ".yml", ".yaml", ".ts", ".tsx", ".py", ".md", ".cjs", ".json"}
VENDOR = ("app/src/main/assets/", "third_party/", "material3/material-color-utilities/")


def normalize(data: bytes) -> bytes:
    if b"\0" in data:
        return data
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return data
    return data.replace(b"\r\n", b"\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    def paths(*arguments):
        return set(subprocess.check_output(["git", "-C", str(ROOT), *arguments]).decode().split("\0")) - {""}
    candidates = paths("diff", "--name-only", "HEAD", "-z") | paths("ls-files", "--others", "--exclude-standard", "-z")
    changed = []
    for name in sorted(candidates):
        file = ROOT / name
        if name.startswith(VENDOR) or file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(ROOT):
            continue
        if file.suffix not in SUFFIXES and file.name not in {".gitignore", ".gitattributes", "gradlew"}:
            continue
        before = file.read_bytes()
        after = normalize(before)
        if before != after:
            changed.append(name)
            if not args.check:
                file.write_bytes(after)
    print(json.dumps({"mode": "check" if args.check else "write", "changed": changed}, ensure_ascii=True, indent=2))
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    raise SystemExit(main())
