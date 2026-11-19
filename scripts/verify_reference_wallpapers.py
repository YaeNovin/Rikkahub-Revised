"""Check provided reference pictures over an explicitly selected wireless ADB transport."""
import argparse
from pathlib import Path
import subprocess
import sys
import json
from android_qa import Adb, ROOT

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--serial',required=True)
    parser.add_argument('--references',type=Path,required=True,help='Directory containing pink.png, night.png, sunset.png, pastel.jpg')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if ':' not in args.serial and '_adb-tls-connect' not in args.serial:
        parser.error('Select a wireless ADB serial explicitly')
    args.output.mkdir(parents=True,exist_ok=True)
    adb=Adb(args.adb,args.serial)
    package='me.rerere.rikkahub.revised.appearanceqa'
    all_results=[]
    for name,extension in (('pink','png'),('night','png'),('sunset','png'),('pastel','jpg')):
        remote='/data/local/tmp/rikkahub-reference-'+name+'.'+extension
        adb.run('push',str(args.references/(name+'.'+extension)),remote)
        adb.run('shell','run-as',package,'cp',remote,'files/diagnostic-background')
        adb.run('shell','am','force-stop',package)
        out=args.output/name
        subprocess.run([sys.executable,'-B',str(ROOT/'scripts/verify_appearance_modes.py'),
            '--adb',args.adb,'--serial',args.serial,'--materials','LIQUID_GLASS','--backdrops','real',
            '--provided-wallpaper','--output',str(out)],check=True)
        rows=json.loads((out/'results.json').read_text(encoding='utf-8'))
        all_results.append(dict(reference=name,transitions=rows))
    (args.output/'results.json').write_text(json.dumps(all_results,indent=2),encoding='utf-8')
    print('PASS four reference originals: app crop and provided wallpaper sources')

if __name__=='__main__': main()
