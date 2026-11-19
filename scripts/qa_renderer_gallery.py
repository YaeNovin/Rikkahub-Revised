"""Smoke-check every registered renderer with production components on a real device."""
from pathlib import Path
import argparse
import json
import time
from android_qa import Adb


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default='adb'); p.add_argument('--serial',required=True)
    p.add_argument('--manifest',required=True,type=Path); p.add_argument('--output',required=True,type=Path)
    args=p.parse_args(); a=Adb(args.adb,args.serial)
    package='me.rerere.rikkahub.revised.renderperf'
    out=args.output.resolve(); out.mkdir(parents=True,exist_ok=True)
    awake=a.run('shell','settings','get','global','stay_on_while_plugged_in')
    results=[]
    try:
        a.run('shell','settings','put','global','stay_on_while_plugged_in','3')
        for renderer in json.loads(args.manifest.read_text())['renderer_ids']:
            a.run('shell','am','start','-S','-W','-n',f'{package}/me.rerere.rikkahub.RenderStressActivity','--es','renderer',renderer)
            time.sleep(8 if renderer=='graphviz' else 3)
            tree=a.ui(out/f'{renderer}.xml')
            a.screenshot(out/f'{renderer}.png')
            texts=[n.get('text','') for n in tree.iter('node')]
            failures=[s for s in texts if any(token in s.lower() for token in ('渲染失败','加载失败','无法渲染','render failed','rendering failed','could not be loaded','syntax error'))]
            pid=a.run('shell','pidof','-s',package)
            (out/f'{renderer}-logcat.txt').write_text(a.run('logcat','-d','--pid',pid),encoding='utf-8')
            record={'renderer':renderer,'process_alive':bool(pid),'failure_text':failures,
                    'manual_preview':renderer=='html'}
            results.append(record); print(json.dumps(record,ensure_ascii=False),flush=True)
        (out/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    finally:
        a.run('shell','settings','delete' if awake=='null' else 'put','global','stay_on_while_plugged_in',*(() if awake=='null' else (awake,)))


if __name__=='__main__': main()
