"""Build/test QA with the local Gradle toolchain; no PowerShell dependency."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from local_build_resources import load_resources, android_tool_paths

ROOT = Path(__file__).resolve().parents[1]
NETWORK_FAILURE = re.compile(
    r"plugin .* was not found|could not resolve|could not get resource|could not download|"
    r"connection reset|connection timed out|unknownhost|temporary failure|failed to connect|received fatal alert",
    re.IGNORECASE,
)

def main(argv: list[str] | None = None) -> int:
    # Windows' redirected console can default to GBK. Vite/Gradle emit symbols
    # such as checkmarks which must not terminate the build's output consumer.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tasks", nargs="*", default=[":app:assembleQa"])
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--refresh-dependencies", action="store_true")
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--max-workers", type=int, default=1)
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--gradle-user-home", type=Path)
    parser.add_argument("--init-script", type=Path, action="append", default=[])
    parser.add_argument("--tests", action="append", default=[])
    parser.add_argument("--performance-probe", action="store_true", help="Use the isolated debug renderperf package")
    args = parser.parse_args(argv)
    if args.offline and args.refresh_dependencies:
        parser.error("--offline and --refresh-dependencies cannot be combined")
    if args.max_attempts < 1 or args.max_workers < 1:
        parser.error("attempt and worker counts must be positive")
    resources = load_resources(ROOT)
    gradle = args.gradle or (Path(resources['gradle']) if resources else ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    if not gradle.is_file():
        parser.error(f"Gradle executable not found: {gradle}")
    environment = os.environ.copy()
    java_home = args.java_home or (Path(resources['javaHome']) if resources else None)
    gradle_user_home = args.gradle_user_home or (Path(resources['gradleUserHome']) if resources else None)
    if java_home:
        environment["JAVA_HOME"] = str(java_home.resolve())
    if gradle_user_home:
        environment["GRADLE_USER_HOME"] = str(gradle_user_home.resolve())
    if resources:
        environment['RIKKAHUB_BUILD_RESOURCES'] = resources['root']
        environment['ANDROID_HOME'] = resources['androidSdk']
        environment['ANDROID_SDK_ROOT'] = resources['androidSdk']
        environment['PATH'] = os.pathsep.join([*android_tool_paths(resources['androidSdk']), environment.get('PATH', '')])
        print(f"Local build resources: {resources['root']}", flush=True)
    command = [str(gradle.resolve()), *(args.tasks or [":app:assembleQa"]),
               "--no-configuration-cache", "--no-daemon", "--console=plain",
               f"--max-workers={args.max_workers}"]
    if args.offline:
        command.append("--offline")
    if args.performance_probe:
        command.append("-Prikkahub.performanceProbe=true")
    if args.refresh_dependencies:
        command.append("--refresh-dependencies")
    if resources:
        command.extend(['-I', str(ROOT / 'scripts/local_build_resources.init.gradle')])
        agp = re.search(r'^agp\s*=\s*"([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8'), re.MULTILINE)
        if agp and agp[1] == resources.get('agpVersion') and Path(resources.get('aapt2', '')).is_file():
            command.append('-Pandroid.aapt2FromMavenOverride=' + resources['aapt2'])
    for init_script in args.init_script:
        command.extend(["-I", str(init_script.resolve())])
    for pattern in args.tests:
        command.extend(["--tests", pattern])
    reports = ROOT / "build/reports/qa-build" / time.strftime("%Y%m%d-%H%M%S")
    reports.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, args.max_attempts + 1):
        print(f"Gradle attempt {attempt}/{args.max_attempts}; logs: {reports}", flush=True)
        network_error = False
        with (reports / f"attempt-{attempt}.log").open("w", encoding="utf-8") as log:
            with subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as process:
                assert process.stdout is not None
                for line in process.stdout:
                    print(line, end="", flush=True)
                    log.write(line)
                    network_error |= bool(NETWORK_FAILURE.search(line))
                result = process.wait()
        if result == 0 or not network_error or args.offline or attempt == args.max_attempts:
            return result
        time.sleep(min(15 * attempt, 45))
    return 1

if __name__ == "__main__":
    sys.exit(main())
