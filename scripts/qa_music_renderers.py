"""Run isolated, synthetic music Android regression tests and collect artifacts."""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import queue
import re
import subprocess
import tarfile
import threading
import time
from android_qa import Adb

PACKAGE = 'me.rerere.rikkahub.revised.musicqa'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    awake = adb.run('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')
    try:
        adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3')
        command = [*adb.command, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'me.rerere.rikkahub.MusicRenderingDeviceTest',
                   f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner']
        lines, events = [], queue.Queue()
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding='utf-8', errors='replace') as process:
            def read_lines():
                for line in process.stdout:
                    events.put(line.strip())
                events.put(None)
            reader = threading.Thread(target=read_lines, daemon=True)
            reader.start()
            deadline = time.monotonic() + 300
            try:
                while time.monotonic() < deadline:
                    try:
                        line = events.get(timeout=1)
                    except queue.Empty:
                        continue
                    if line is None:
                        break
                    lines.append(line)
                    if line == 'INSTRUMENTATION_STATUS_CODE: 1':
                        adb.run('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/androidx.activity.ComponentActivity')
                    if line.startswith('INSTRUMENTATION_STATUS: music_capture='):
                        name = line.split('=', 1)[1]
                        if not re.fullmatch(r'[a-z_-]+', name):
                            raise ValueError('Invalid fixture capture name')
                        adb.screenshot(output / f'{name}.png')
                        if name in ('abc-light', 'mei-mensural-light'):
                            (output / f'meminfo-{name}.txt').write_text(adb.run('shell', 'dumpsys', 'meminfo', PACKAGE), encoding='utf-8')
                        adb.run('shell', 'run-as', PACKAGE, 'touch', f'files/music-qa/{name}-captured')
                else:
                    raise TimeoutError('Instrumented music tests exceeded 300 seconds')
                process.wait(timeout=10)
            except BaseException:
                adb.run('shell', 'am', 'force-stop', PACKAGE)
                process.terminate()
                raise
            finally:
                (output / 'instrumentation.txt').write_text('\n'.join(lines), encoding='utf-8')
        result = '\n'.join(lines)
        print(result, flush=True)
        if 'OK (2 tests)' not in result:
            raise RuntimeError('Device regressions failed; inspect instrumentation.txt')
        archive = adb.run('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', '-C', 'files',
                          'music-qa', binary=True)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(output, filter='data')
        records = json.loads((output / 'music-qa/results.json').read_text(encoding='utf-8'))
        if len(records) != 10 or not all(r['ready'] for r in records):
            raise RuntimeError('Device regressions failed; inspect instrumentation.txt')
    finally:
        adb.run('shell', 'settings', 'delete' if awake == 'null' else 'put', 'global',
                'stay_on_while_plugged_in', *(() if awake == 'null' else (awake,)))


if __name__ == '__main__':
    main()
