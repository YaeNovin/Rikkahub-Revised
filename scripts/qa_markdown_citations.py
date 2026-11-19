"""Exercise production native/HTML Markdown with synthetic citations on USB QA."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import time

from android_qa import Adb, bounds, center

PACKAGE = 'me.rerere.rikkahub.revised.renderperf'
ACTIVITY = 'me.rerere.rikkahub.CitationRegressionActivity'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    results = []
    metadata = json.loads((Path(__file__).resolve().parents[1] / 'app/build/outputs/apk/debug/output-metadata.json').read_text(encoding='utf-8'))
    assert metadata['applicationId'] == PACKAGE, 'Build the isolated --performance-probe package first'
    expected_version = metadata['elements'][0]['versionName']
    package_info = adb.run('shell', 'dumpsys', 'package', PACKAGE)
    installed_version = re.search(r'versionName=(\S+)', package_info)
    assert installed_version and installed_version[1] == expected_version, 'Installed Debug version differs from the built APK'

    def state():
        for attempt in range(3):
            try:
                return json.loads(adb.run('exec-out', 'run-as', PACKAGE, 'cat', 'files/citation-regression/state.json'))
            except json.JSONDecodeError:
                if attempt == 2:
                    raise
                time.sleep(.05)

    def wait_state(predicate, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = state()
            if predicate(current):
                return current
            time.sleep(.25)
        raise AssertionError(f'Fixture state not reached: {current}')

    def node(description, name):
        tree = adb.ui(out / f'{name}.xml')
        found = next((n for n in tree.iter('node') if n.get('content-desc') == description and n.get('package') == PACKAGE), None)
        if found is None:
            scroller = next((n for n in tree.iter('node') if n.get('scrollable') == 'true' and n.get('package') == PACKAGE), None)
            if scroller is not None:
                left, top, right, bottom = bounds(scroller)
                adb.run('shell', 'input', 'swipe', (left + right)//2, int(top+(bottom-top)*.25),
                        (left + right)//2, int(top+(bottom-top)*.8), '240')
                tree = adb.ui(out / f'{name}-after-scroll.xml')
                found = next((n for n in tree.iter('node') if n.get('content-desc') == description and n.get('package') == PACKAGE), None)
        if found is None:
            raise AssertionError(f'Expected UI element not present: {description}')
        parents = {child: parent for parent in tree.iter() for child in parent}
        while found.get('clickable') != 'true' and found in parents:
            found = parents[found]
        assert found.get('clickable') == 'true', f'UI element has no clickable target: {description}'
        return found

    def click(description, name):
        adb.tap(node(description, name))
        time.sleep(.35)

    original = adb.run('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')
    try:
        adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3')
        adb.run('shell', 'am', 'start', '-S', '-W', '-n', f'{PACKAGE}/{ACTIVITY}')
        time.sleep(3)
        pid = adb.run('shell', 'pidof', '-s', PACKAGE)
        assert pid, 'Test application has no live process'
        for renderer, dark in [('native', True), ('native', False), ('html', False), ('html', True)]:
            label = f'{renderer}-{"dark" if dark else "light"}'
            current = state()
            if current['html'] != (renderer == 'html'):
                click('citation_renderer', label+'-renderer')
            if current['dark'] != dark:
                click('citation_theme', label+'-theme')
            wait_state(lambda s: s['html'] == (renderer == 'html') and s['dark'] == dark)
            time.sleep(1.5)
            tree = adb.ui(out / f'{label}-before.xml')
            texts = [n.get('text', '') for n in tree.iter('node') if n.get('package') == PACKAGE]
            assert not any('[citation:' in text and 'LITERAL_CHECK' not in text for text in texts), texts
            for number, source_id in [(1, '9d7feb'), (2, '54d9dd'), (3, 'fae5f3'), (4, 'table01')]:
                click(f'引用 {number}', f'{label}-source-{number}')
                saved = wait_state(lambda s: s['clicked_id'] == source_id)
                assert saved['clicked_url'].startswith('https://example.org/source-'), saved
            target = node('引用 2', label+'-long-press')
            x, y = center(target)
            adb.run('shell', 'input', 'swipe', x, y, x, y, '900')
            wait_state(lambda s: s['held_id'] == '54d9dd')
            adb.screenshot(out / f'{label}.png')
            click('citation_stream', label+'-stream')
            wait_state(lambda s: s['streaming'], timeout=5)
            wait_state(lambda s: not s['streaming'] and s['chars'] == s['complete_chars'])
            time.sleep(1.5)
            node('引用 1', label+'-after-stream')
            adb.screenshot(out / f'{label}-stream-complete.png')
            tree = adb.ui(out / f'{label}-scroll-before.xml')
            scroller = next(n for n in tree.iter('node') if n.get('scrollable') == 'true' and n.get('package') == PACKAGE)
            left, top, right, bottom = bounds(scroller)
            x = (left+right)//2
            high, low = int(top+(bottom-top)*.2), int(top+(bottom-top)*.8)
            code_found = False
            for index in range(4):
                tree = adb.ui(out / f'{label}-scroll-{index}.xml')
                code_found |= any('LITERAL_CHECK [citation:fae5f3,9d7feb]' in n.get('text', '') for n in tree.iter('node'))
                if code_found:
                    adb.screenshot(out / f'{label}-code.png')
                    break
                adb.run('shell', 'input', 'swipe', x, low, x, high, '220')
                time.sleep(.25)
            assert code_found, 'Code fence no longer preserves literal citation source'
            for index in range(5):
                adb.run('shell', 'input', 'swipe', x, high if index % 2 else low, x, low if index % 2 else high, '150')
            for _ in range(4):
                adb.run('shell', 'input', 'swipe', x, high, x, low, '220')
            assert adb.run('shell', 'pidof', '-s', PACKAGE) == pid, 'Fixture process changed during scrolling'
            record = dict(renderer=renderer, dark=dark, fourSourcesClickable=True,
                longPressSource='54d9dd', streamingCompleted=True, codeLiteralPreserved=code_found,
                processStable=True, state=state())
            results.append(record)
            (out/'device-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
            print(json.dumps(record,ensure_ascii=False),flush=True)
        log = adb.run('logcat', '-d', '--pid', pid)
        (out/'device-logcat.txt').write_text(log,encoding='utf-8')
        assert 'FATAL EXCEPTION' not in log and 'AndroidRuntime: FATAL' not in log
    finally:
        adb.run('shell', 'settings', 'delete' if original == 'null' else 'put', 'global',
                'stay_on_while_plugged_in', *(() if original == 'null' else (original,)))


if __name__ == '__main__':
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')
    main()
