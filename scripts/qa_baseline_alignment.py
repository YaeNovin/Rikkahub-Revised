"""Validate P0 renderer behavior by driving the isolated Debug fixture over ADB."""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import secrets
import time

from android_qa import Adb

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'me.rerere.rikkahub.revised.baselineqa'
ACTIVITY = 'me.rerere.rikkahub.BaselineAlignmentActivity'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = args.output or ROOT / 'build/reports/p0-device' / time.strftime('%Y%m%d-%H%M%S')
    output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    token = secrets.token_hex(4)
    plain = f'P0 {token} plain first\nP0 {token} plain second'
    html = f'P0 {token} html first\nP0 {token} html second'
    source = f'#include <iostream>\nstd::vector<int> values;\n// keep source comment {token}'
    literal = f'alpha  &amp;  <Tag> [citation:literal_{token}]'
    samples = [plain, html + f'\n\n<div>fragment {token}</div>', source, chr(96) + literal + chr(96)]
    (output / 'samples.json').write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding='utf-8')
    # Only Base64 crosses the remote shell; sample code cannot become shell syntax.
    encoded = base64.b64encode(json.dumps(samples).encode()).decode()
    checks: list[dict] = []
    scenarios: list[str] = []
    screenshots: list[str] = []
    passed = False
    error = None

    def visible(text: str, name: str):
        adb.wait_node(lambda n: n.get('text') == text, output / (name + '.xml'), PACKAGE, timeout=20)
        checks.append({'name': name, 'exactText': True})
        adb.screenshot(output / (name + '.png'))
        screenshots.append(name + '.png')
        print('PASS ' + name, flush=True)

    def tap(label: str):
        node = adb.wait_node(lambda n: n.get('text') == label, output / 'controls.xml', PACKAGE)
        adb.tap(node)

    def role(expected: str, user: bool, name: str):
        tap('角色切换')
        marker = 'USER_MESSAGE' if user else 'STANDARD'
        adb.wait_node(lambda n: marker in n.get('text', '') and '样本' in n.get('text', ''),
                      output / 'role.xml', PACKAGE)
        visible(expected, name)

    try:
        adb.run('shell', 'am', 'force-stop', PACKAGE)
        launch = adb.run('shell', 'am', 'start', '-W', '-n', PACKAGE + '/' + ACTIVITY,
                         '--es', 'samples_base64', encoded)
        (output / 'launch.txt').write_text(launch, encoding='utf-8')
        for index, prose in enumerate([plain, html]):
            label = 'native' if index == 0 else 'html'
            if index:
                tap('下一样本')
            visible(prose, label + '-user')
            # The native AST retains EOL leaves in both modes. HTML text nodes
            # collapse soft breaks only in STANDARD mode; assert each contract.
            assistant_prose = prose if index == 0 else prose.replace('\n', ' ')
            for round_number in range(2):
                role(assistant_prose, False, f'{label}-assistant-{round_number}')
                role(prose, True, f'{label}-user-return-{round_number}')
            scenarios.append(label + '-soft-breaks-and-role-cache')
        tap('下一样本')
        visible(source, 'source-light')
        tap('主题切换')
        adb.wait_node(lambda n: '深色' in n.get('text', '') and '样本' in n.get('text', ''),
                      output / 'theme.xml', PACKAGE)
        visible(source, 'source-dark')
        tap('主题切换')
        visible(source, 'source-light-return')
        scenarios.append('pasted-source-and-theme-switch')
        tap('下一样本')
        # Native inline code adds one presentation-space on each side.
        inline_display = ' ' + literal + ' '
        visible(inline_display, 'inline-code-user')
        role(inline_display, False, 'inline-code-assistant')
        role(inline_display, True, 'inline-code-user-return')
        scenarios.append('literal-inline-code-and-citations')
        passed = True
    except Exception as exception:
        error = type(exception).__name__ + ': ' + str(exception)
        print(error, flush=True)
        try:
            adb.screenshot(output / 'failure.png')
        except Exception:
            pass
    finally:
        try:
            adb.run('shell', 'am', 'force-stop', PACKAGE)
        except Exception:
            pass
        summary = {'package': PACKAGE, 'validation': 'ADB UI hierarchy and screenshots', 'sampleToken': token,
                   'passed': passed, 'scenarios': scenarios, 'exactTextChecks': len(checks), 'checks': checks,
                   'screenshots': screenshots, 'error': error, 'userQaDataAccessed': False}
        (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        print(json.dumps(summary, indent=2), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
