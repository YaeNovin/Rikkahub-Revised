"""Check scroll and composition identity through production theme changes on an adb target."""
import argparse
import json
from pathlib import Path
import time
from android_qa import ROOT, Adb, bounds

PACKAGE = 'me.rerere.rikkahub.revised.appearanceqa'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/qa-text-follow/state')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb, args.serial)
    awake = adb.run('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')
    results = []

    def state(name):
        node = adb.wait_node(lambda n: n.get('content-desc', '').startswith('state:'),
            args.output / f'{name}.xml', PACKAGE)
        return node.get('content-desc')

    try:
        adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3')
        for material in ('LIQUID_GLASS', 'MICA', 'OPAQUE'):
            adb.run('shell', 'am', 'start', '-W', '-S', '-n',
                f'{PACKAGE}/me.rerere.rikkahub.AppearanceModeRegressionActivity', '--es', 'scenario', 'state',
                '--es', 'material', material)
            for swipe in range(3):
                node = adb.wait_node(lambda n: n.get('content-desc') == 'palette-scroll',
                    args.output / f'{material}-scroll-{swipe}.xml', PACKAGE)
                l, t, r, b = bounds(node)
                adb.run('shell', 'input', 'swipe', (l+r)//2, t+(b-t)*3//4, (l+r)//2, t+(b-t)//4, '350')
            time.sleep(.5)
            baseline = state(f'{material}-baseline')
            assert int(baseline.split(':')[2]) > 0, 'List was not scrolled'
            for control in ('APP_BACKGROUND', 'SYSTEM_WALLPAPER', 'AUTO_CLEAR', 'THEME',
                'PAGE_ACTIVE', 'SYSTEM_WALLPAPER', 'THEME', 'PAGE_ACTIVE', 'DARK_MODE', 'DARK_MODE'):
                name = f'{material}-{len(results)}-{control}'
                node = adb.wait_node(lambda n: n.get('text') == control, args.output / f'{name}-before.xml', PACKAGE)
                adb.tap(node)
                time.sleep(.3)
                actual = state(name)
                if actual != baseline:
                    adb.screenshot(args.output / f'{name}-failure.png')
                    raise AssertionError(f'{name}: expected {baseline}, got {actual}')
                results.append(dict(material=material, control=control, state=actual))
            adb.screenshot(args.output / f'{material}-final.png')
        (args.output / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
        print(f'PASS {len(results)} theme/mode/background changes retain composition identity and scroll anchor')
    finally:
        if awake == 'null':
            adb.run('shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in')
        else:
            adb.run('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', awake)


if __name__ == '__main__':
    main()
