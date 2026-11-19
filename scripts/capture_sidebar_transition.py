"""Record the QA sidebar-to-settings handoff without changing app preferences."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import time
from android_qa import Adb, bounds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ffmpeg', type=Path, help='Optional full FFmpeg build with MP4 decoding and the tile filter')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    package = 'me.rerere.rikkahub'
    awake = adb.run('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')

    def ui(name):
        for attempt in range(8):
            try:
                tree = adb.ui(args.output / f'{name}.xml')
                if any(n.get('package') == package for n in tree.iter('node')):
                    return tree
                raise RuntimeError('QA is not in the foreground')
            except ValueError:
                time.sleep(.3)
        raise RuntimeError('No complete UI hierarchy')

    try:
        adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3')
        adb.run('shell', 'am', 'start', '-W', '-n', f'{package}/.RouteActivity')
        for attempt in range(6):
            tree = ui('before')
            if any(n.get('content-desc') == '助手设置' for n in tree.iter('node')):
                break
            drawer = next((n for n in tree.iter('node') if n.get('content-desc') == '打开侧边栏'
                or n.get('resource-id', '').endswith('chat_open_drawer')), None)
            # Older builds expose only the button's bounds. Require the chat input first.
            if drawer is None and any('输入消息' in n.get('text', '') for n in tree.iter('node')):
                drawer = next(n for n in tree.iter('node') if n.get('clickable') == 'true'
                    and bounds(n)[0] < 100 and bounds(n)[1] < 400 and bounds(n)[2] < 300)
            if drawer is not None:
                adb.tap(drawer)
                time.sleep(.6)
                tree = ui('drawer')
                break
            adb.run('shell', 'input', 'keyevent', '4')
        assert any(n.get('content-desc') == '助手设置' for n in tree.iter('node')), 'Sidebar is missing'
        settings = next((n for n in tree.iter('node') if n.get('text') == '设置'), None)
        if settings is None:
            screen = next(n for n in tree.iter('node') if n.get('package') == package)
            _, _, width, height = bounds(screen)
            choices = [n for n in tree.iter('node') if n.get('clickable') == 'true'
                and bounds(n)[1] > height * .9 and bounds(n)[2] < width * .85]
            settings = max(choices, key=lambda n: bounds(n)[2])
        remote = '/data/local/tmp/rikkahub-sidebar-transition.mp4'
        command = [args.adb, '-s', args.serial, 'shell', 'screenrecord', '--size', '720x1600', '--time-limit', '4', remote]
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as recording:
            time.sleep(.5)
            adb.tap(settings)
            stdout, stderr = recording.communicate(timeout=10)
            if recording.returncode:
                raise RuntimeError(stderr.decode('utf-8', 'replace'))
        video = args.output / 'transition.mp4'
        adb.run('pull', remote, str(video))
        ui('destination')
        if args.ffmpeg:
            subprocess.run([str(args.ffmpeg), '-hide_banner', '-loglevel', 'error', '-y', '-ss', '0.2', '-i', str(video),
                '-vf', 'fps=30,scale=180:-1,tile=6x4', '-frames:v', '1', str(args.output / 'frames.png')], check=True)
        print(f'Captured sidebar handoff: {video}')
    finally:
        if awake == 'null':
            adb.run('shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in')
        else:
            adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', awake)


if __name__ == '__main__':
    main()
