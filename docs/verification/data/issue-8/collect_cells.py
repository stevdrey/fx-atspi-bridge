#!/usr/bin/env python3
"""Reduced SC-01 cells: 100/1000/10000, dirty=10, off/on, two repetitions, 10 structural/install cycles.
Usage: python3 docs/verification/data/issue-8/collect_cells.py /tmp/new-output [--retention]
--retention uses the original SC-01 quick structural/install counts, still only dirty=10 and two repetitions.
Use an otherwise idle graphical session. Does not change the display or suppress measurement guards.
"""
import argparse,hashlib,json,os,platform,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[4]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('output',type=Path)
parser.add_argument('--retention',action='store_true')
args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
launcher=root/'examples/fx-lab/build/install/fx-lab/bin/fx-lab'
env=dict(os.environ)
for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','FX_LAB_OPTS'):env.pop(key,None)
scope=('representative dirty=10 SC-01 quick retention counts, 2 repetitions each; not full quick qualification' if args.retention else 'representative SC-01 cells, 2 repetitions each; reduced churn (10), not full quick qualification')
meta={'scope':scope,'os':platform.freedesktop_os_release(),'session':env.get('XDG_SESSION_TYPE'),'desktop':env.get('XDG_CURRENT_DESKTOP'),'sourceHead':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),'sourceTree':'Exact Java source hashes below identify the measured build, including uncommitted additions','jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (launcher.parent.parent/'lib').glob('*.jar')}}
meta['sourceSha256']={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for base in ('src/main/java','examples/fx-lab/src/main/java') for p in (root/base).rglob('*.java')}
(out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
for n in (100,1000,10000):
 structural=(30 if n==10000 else 100) if args.retention else 10
 installs=100 if args.retention else 10
 for rep in (1,2):
  for mode in ('off','on'):
   name=f'n{n}-d10-{mode}-r{rep}'
   command=[str(launcher),'--bench',f'--bridge={mode}',f'--nodes={n}','--dirty=10','--warmup=50','--samples=200','--idle-seconds=10',f'--structural={structural}',f'--install-churn={installs}',f'--limits={root}/examples/fx-lab/scripts/baseline.conf',f'--out={out}/{name}.json']
   with (out/f'{name}.log').open('w') as log:
    result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=env,timeout=900 if args.retention else 300)
   print(name,result.returncode,flush=True)
   if result.returncode:raise SystemExit(result.returncode)
print('Measurements complete',flush=True)
