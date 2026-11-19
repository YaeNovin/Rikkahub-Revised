"""Load the optional, machine-local resource store without changing system variables."""
from __future__ import annotations

import json
import os
from pathlib import Path


def load_resources(project: Path) -> dict | None:
    location = Path(os.environ.get('RIKKAHUB_BUILD_RESOURCES', project.parent / 'build-resources'))
    config_file = location / 'resources.json'
    if not config_file.is_file():
        return None
    config = json.loads(config_file.read_text(encoding='utf-8'))
    if config.get('schemaVersion') != 1:
        raise ValueError(f'Unsupported build resource configuration: {config_file}')
    for field in ('gradle', 'javaHome', 'gradleUserHome', 'androidSdk', 'mavenRepository'):
        path = Path(config[field])
        if not path.exists():
            raise FileNotFoundError(f'Build resource {field} missing: {path}; rerun resource staging')
    return {**config, 'root': str(location.resolve())}


def resolve_adb(requested: str | None, project: Path) -> str:
    """Use this checkout's SDK before a possibly stale inherited PATH."""
    if requested and requested.lower() not in ('adb', 'adb.exe'):
        return requested
    resources = load_resources(project)
    if resources:
        executable = Path(resources['androidSdk']) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
        if not executable.is_file():
            raise FileNotFoundError(f'ADB missing from the configured Android SDK: {executable}')
        return str(executable)
    return requested or 'adb'


def android_tool_paths(sdk: str) -> list[str]:
    root = Path(sdk)
    return [str(root / relative) for relative in ('platform-tools', 'cmdline-tools/latest/bin', 'emulator')
            if (root / relative).is_dir()]
