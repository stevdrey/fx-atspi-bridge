"""Reduce numerical pulse diagnostics and JFR stacks; reads no application fields."""
from pathlib import Path
import collections,datetime,hashlib,json,sys
root=Path(sys.argv[1]); result={}
for path in sorted(root.glob('latency-*-events.json')):
 tag=path.name.removesuffix('-events.json'); clocks={};pulses=[]
 for line in (root/(tag+'.log')).read_text().splitlines():
  s=line.split()
  if s and s[0]=='SC10_CLOCK':clocks[int(s[1])]=(int(s[2])/1000,int(s[3]))
  if s and s[0]=='SC10_PULSE':
   n,d,c=map(int,s[1:]);wall,tid=clocks[n];pulses.append((wall,d/1e9,c/1e6,tid))
 events=json.loads(path.read_text())['recording']['events'];sampled=pulses[80:];rows=[]
 for sample_index,(start,duration,cpu,tid) in sorted(enumerate(sampled),key=lambda p:p[1][1],reverse=True)[:10]:
  end=start+duration;parks=[];gcs=0;fx=collections.Counter();render=collections.Counter()
  for e in events:
   v=e['values'];t=datetime.datetime.fromisoformat(v['startTime']).timestamp();d=float(v.get('duration','PT0S')[2:-1]);overlap=max(0,min(end,t+d)-max(start,t))
   names=[f['method']['type']['name']+'.'+f['method']['name'] for f in (v.get('stackTrace') or {}).get('frames',[])]
   thread=v.get('eventThread') or v.get('sampledThread') or {}
   if e['type']=='jdk.ThreadPark' and overlap and thread.get('javaThreadId')==tid and any('PaintCollector.waitForRenderingToComplete' in n for n in names):parks.append(overlap*1000)
   if e['type']=='jdk.GarbageCollection' and overlap:gcs+=1
   if e['type']=='jdk.ExecutionSample' and start<=t<=end:
    if thread.get('javaThreadId')==tid:fx[tuple(names[:7])]+=1
    if thread.get('javaName','').startswith('QuantumRenderer'):render[tuple(names[:7])]+=1
  rows.append({'sampleRound':50+sample_index,'pulseMs':duration*1000,'fxCpuMs':cpu,'renderWaitMs':sum(parks),'overlappingGcCount':gcs,'fxSamples':[{'frames':list(k),'count':v} for k,v in fx.most_common(3)],'renderSamples':[{'frames':list(k),'count':v} for k,v in render.most_common(3)]})
 result[tag]={'diagnosticOnly':True,'samples':len(sampled),'clockQuantizationMs':1,'alignmentLimitation':'Sequential clock reads can be preempted; maximum alignment error is not certified.','topPulses':rows,'filesSha256':{n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in (tag+'.jfr',tag+'.log',tag+'-events.json')}}
print(json.dumps(result,indent=2))
