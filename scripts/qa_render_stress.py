"""Profile a real-clock synthetic long response and repeated production ChatList messages."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import threading
import time
from android_qa import Adb, bounds, frame_summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    a = Adb(args.adb, args.serial)
    package = 'me.rerere.rikkahub.revised.renderperf'
    metadata = Path(__file__).resolve().parents[1] / 'app/build/outputs/apk/debug/output-metadata.json'
    expected_version = json.loads(metadata.read_text())['elements'][0]['versionName']
    package_info = a.run('shell','dumpsys','package',package)
    version = re.search(r'versionName=(\S+)', package_info)
    assert version and version[1] == expected_version, 'Installed test version differs from the built APK; no test started'
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    awake = a.run('shell','settings','get','global','stay_on_while_plugged_in')
    stopped = threading.Event()
    memory = []
    pid = ''

    def state():
        return json.loads(a.run('exec-out','run-as',package,'cat','files/render-stress/state.json'))

    def tap(tag):
        tree = a.ui(out/f'control-{tag}.xml')
        node = next(n for n in tree.iter('node') if n.get('content-desc')==tag)
        a.tap(node)

    def sample_memory():
        while not stopped.is_set():
            try:
                raw = a.run('shell','dumpsys','meminfo',package)
                index = len(memory)
                (out/f'meminfo-{index:03}.txt').write_text(raw,encoding='utf-8')
                pss = re.search(r'TOTAL PSS:\s*(\d+)',raw)
                memory.append({'seconds':round(time.monotonic()-start,2),'pss_kb':int(pss[1]) if pss else None})
            except Exception as e:
                memory.append({'error':str(e)})
            stopped.wait(3)

    def begin_trace(name, duration):
        remote=f'/data/misc/perfetto-traces/render-stress-{name}-{int(time.time())}.pftrace'
        a.run('shell','perfetto','--background-wait','-o',remote,'-t',f'{duration}s','--app',package,
              'sched','freq','idle','am','wm','gfx','view','binder_driver','dalvik')
        a.run('shell','dumpsys','gfxinfo',package,'reset')
        return remote,time.monotonic()+duration+1

    def end_trace(name, trace):
        remote,deadline=trace
        raw=a.run('shell','dumpsys','gfxinfo',package)
        (out/f'{name}-gfxinfo.txt').write_text(raw,encoding='utf-8')
        (out/f'{name}-framestats.txt').write_text(a.run('shell','dumpsys','gfxinfo',package,'framestats'),encoding='utf-8')
        frames=frame_summary(raw)
        print(json.dumps({'phase':name,**frames}),flush=True)
        while time.monotonic()<deadline: stopped.wait(min(1,deadline-time.monotonic()))
        a.run('pull',remote,str(out/f'{name}.pftrace'))
        a.screenshot(out/f'{name}.png')
        assert a.run('shell','pidof','-s',package)==pid,'Process exited during stress'
        return frames

    start=time.monotonic()
    sampler=None
    try:
        a.run('shell','settings','put','global','stay_on_while_plugged_in','3')
        a.run('shell','am','start','-S','-W','-n',f'{package}/me.rerere.rikkahub.RenderStressActivity')
        pid=a.run('shell','pidof','-s',package)
        (out/'exit-before.txt').write_text(a.run('shell','dumpsys','activity','exit-info',package),encoding='utf-8')
        manifest=json.loads(a.run('exec-out','run-as',package,'cat','files/render-stress/manifest.json'))
        assert manifest['whitespace_words']>16*1024
        (out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
        sampler=threading.Thread(target=sample_memory,daemon=True)
        sampler.start()
        stream_trace=begin_trace('streaming',45)
        tap('stress_start')
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            assert a.run('shell','pidof','-s',package)==pid,'Process exited while streaming'
            try:
                progress=state()
                if progress['phase']=='DONE': break
            except (ValueError,RuntimeError): pass
            time.sleep(1)
        else: raise RuntimeError('Simulated streaming did not complete')
        streaming=end_trace('streaming',stream_trace)
        (out/'stream-state.json').write_text(json.dumps(progress),encoding='utf-8')
        tap('stress_bottom')
        time.sleep(1)
        complete=a.ui(out/'completed-bottom.xml')
        assert any('END OF COMPLETE OUTPUT' in n.get('text','') for n in complete.iter('node')), 'Final output was not rendered completely'
        a.screenshot(out/'completed-bottom.png')
        tap('stress_copy')
        time.sleep(1)
        copied=state()
        assert copied['copies']==5
        (out/'copies-state.json').write_text(json.dumps(copied),encoding='utf-8')
        tap('stress_top')
        tree=a.ui(out/'copied-initial.xml')
        scrollable=[n for n in tree.iter('node') if n.get('scrollable')=='true' and n.get('package')==package]
        rect=bounds(max(scrollable,key=lambda n:(bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1])))
        l,t,r,b=rect
        x=(l+r)//2
        high=int(t+(b-t)*.22); low=int(t+(b-t)*.78)
        scroll_trace=begin_trace('copied-scroll',65)
        for _ in range(8):
            a.run('shell','input','swipe',x,low,x,high,120)
            a.run('shell','input','swipe',x,high,x,low,120)
        # Traverse all five identical long messages; physical swipes alone do
        # not cross a 100KB message within a bounded test.
        for index in range(5):
            tap('stress_next')
            for _ in range(3): a.run('shell','input','swipe',x,low,x,high,160)
            a.screenshot(out/f'copy-{index}.png')
        for _ in range(8): a.run('shell','input','swipe',x,high,x,low,180)
        scroll=end_trace('copied-scroll',scroll_trace)
        (out/'final-ui.xml').write_text(a.run('exec-out','uiautomator','dump','/dev/tty'),encoding='utf-8')
        (out/'exit-after.txt').write_text(a.run('shell','dumpsys','activity','exit-info',package),encoding='utf-8')
        (out/'logcat.txt').write_text(a.run('logcat','-d','--pid',pid),encoding='utf-8')
        (out/'crash-log.txt').write_text(a.run('logcat','-b','crash','-d','--pid',pid),encoding='utf-8')
        result={'package':package,'version':version[1],'pid':int(pid),'manifest':manifest,'stream':progress,'copied':copied,
                'streaming_frames':streaming,'copied_scroll_frames':scroll,'same_process':True}
        (out/'results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
    finally:
        stopped.set()
        if sampler: sampler.join(timeout=15)
        (out/'memory-samples.json').write_text(json.dumps(memory,indent=2),encoding='utf-8')
        a.run('shell','settings','delete' if awake=='null' else 'put','global','stay_on_while_plugged_in',*(() if awake=='null' else (awake,)))


if __name__=='__main__': main()
