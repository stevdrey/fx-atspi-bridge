#!/usr/bin/env python3
"""Compare a complete SC-07 quick collection with SC-01's fixed, provisional guardrails.

Usage: python3 compare_sc01.py <data-dir>
Run the repository's budgets.py validator first. Writes comparison-sc01.json and validation.json,
then prints counts and a latency table. Uses only the standard library. Completion logs are checked
by the collector before evidence is copied; ignored logs are not needed to reproduce these calculations.
"""
import json
import statistics
import sys
from pathlib import Path
root=Path(sys.argv[1])
files=sorted(root.glob('n*-d*-*-r*.json'))
assert len(files)==44, f'Expected 44 files, got {len(files)}'
runs=[(p.name,json.loads(p.read_text())) for p in files]
latency={100:{1:42,10:41,100:29},1000:{1:42,10:29,100:190},10000:{1:631,10:307}}
allocation={100:{1:34,10:116,100:403},1000:{1:205,10:299,100:2581},10000:{1:8506,10:8844}}
comparisons=[]
def compare(n,d,key,limit,unit,scale=1):
 selected=[(name,r[key]/scale) for name,r in runs if r['nodes']==n and r['dirty']==d and r['bridge']=='on']
 assert selected
 maximum=max(value for name,value in selected)
 comparisons.append(dict(nodes=n,dirty=d,metric=key,unit=unit,limit=limit,worstObserved=maximum,
                         withinProvisionalBudget=maximum<=limit,files=[name for name,value in selected]))
for n in latency:
 for d in latency[n]:
  compare(n,d,'latencyP99Ms',latency[n][d],'ms')
  compare(n,d,'allocatedBytesPerSample',allocation[n][d],'KiB/sample',1024)
 for key,limits,unit,scale in [
  ('idleCpuPercent',{100:8,1000:14,10000:137},'% of one core',1),
  ('rssAfterIdleKb',{100:327,1000:434,10000:1590},'MiB',1024),
  ('installChurnHeapDeltaBytesAfterGc',{100:1,1000:57,10000:1},'MiB',2**20),
  ('installChurnProviderThreadsCheckpoint',{100:2,1000:117,10000:2},'threads',1),
  ('installChurnProviderThreadsAfterDrain',{100:2,1000:2,10000:2},'threads',1),
  ('structuralHeapDeltaBytesAfterGc',{100:2,1000:6,10000:18},'MiB',2**20),
 ]:
  compare(n,10,key,limits[n],unit,scale)
reference=dict(commit='617d2e0',path='docs/verification/issue-1.md',section='Initial budgets (provisional until #15)',
               limitations='Historical prototype and earlier allocation method; loose indicative guardrails, not a same-session patch comparison or release qualification.')
output=dict(reference=reference,comparisons=comparisons,exceedances=[c for c in comparisons if not c['withinProvisionalBudget']])
(root/'comparison-sc01.json').write_text(json.dumps(output,indent=2)+'\n')
summary={'confirmedRuns':44,'bridgeOnRuns':22,'allBridgeProvidersAliveAtEnd':all(r.get('providerAliveAtEnd') is True for _,r in runs if r['bridge']=='on'),
         'installChurnProviderErrors':sum(r.get('installChurnProviderErrors',0) for _,r in runs),
         'maxProviderThreadsAfterDrain':max(r.get('installChurnProviderThreadsAfterDrain',0) for _,r in runs),
         'structuralCycles':sum(r.get('structuralCycles',0) for _,r in runs),
         'installCloseCycles':sum(r.get('installChurnCycles',0) for _,r in runs)}
(root/'validation.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
print('Provisional-budget exceedances:',len(output['exceedances']))
for c in output['exceedances']: print(c['nodes'],c['dirty'],c['metric'],c['worstObserved'],c['limit'])
print('| Nodes | Dirty | Runs off/on | p50 off/on (ms) | p99 off/on (ms) |')
print('| ---: | ---: | ---: | ---: | ---: |')
for n in latency:
 for d in latency[n]:
  a=[r for _,r in runs if r['nodes']==n and r['dirty']==d and r['bridge']=='off']
  b=[r for _,r in runs if r['nodes']==n and r['dirty']==d and r['bridge']=='on']
  pair=lambda key:f"{statistics.median(r[key] for r in a):.1f} / {statistics.median(r[key] for r in b):.1f}"
  print(f"| {n:,} | {d} | {len(a)} / {len(b)} | {pair('latencyP50Ms')} | {pair('latencyP99Ms')} |")
