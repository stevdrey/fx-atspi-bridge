"""Diagnostic only: python3 examples/fx-lab/scripts/gradle_cancellation_probe.py NEW_OUTPUT.

Uses real Gradle and nested sessions; stores numeric results and its log outside Git.
"""
from pathlib import Path
import json,os,signal,subprocess,sys,tempfile,time
from unittest.mock import patch
root=Path(__file__).resolve().parents[3];sys.path.insert(0,str(root/'examples/fx-lab/scripts'))
out=Path(sys.argv[1]).resolve();out.mkdir(mode=0o700,parents=True,exist_ok=False)
import qa_common
captured=[]
original_capture=qa_common._capture_owned

def capture(process,handles,stack):
    original_capture(process,handles,stack)
    for fd,group in handles:
        fields=Path(f'/proc/self/fdinfo/{fd}').read_text().splitlines()
        pid=int(next(line.split(':',1)[1] for line in fields if line.startswith('Pid:')))
        if pid>0:
            stat=Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
            captured.append({'pid':pid,'group':group,'start':stat[19]})

with tempfile.TemporaryDirectory(prefix='sc10-gradle-cancel-') as tmp:
    project=Path(tmp);ready=project/'ready.json';leaf=project/'leaf.py';middle=project/'middle.py'
    leaf.write_text("import json,os,signal,time\nfrom pathlib import Path\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nPath("+repr(str(ready))+").write_text(json.dumps({'pid':os.getpid(),'group':os.getpgrp()}))\ntime.sleep(60)\n")
    middle.write_text('import sys\nsys.path.insert(0,'+repr(str(root/'examples/fx-lab/scripts'))+')\nfrom qa_common import run_owned,environment\nrun_owned([sys.executable,'+repr(str(leaf))+'],log=None,env=environment(),timeout=45)\n')
    (project/'settings.gradle').write_text("rootProject.name='owned-cancellation-probe'\n")
    (project/'build.gradle').write_text('tasks.register("ownedProbe", Exec) { commandLine '+json.dumps(sys.executable)+', '+json.dumps(str(middle))+' }\n')
    real_popen=subprocess.Popen
    class ReadyProcess(real_popen):
        first_wait=True
        def wait(self,timeout=None):
            if self.first_wait:
                self.first_wait=False;deadline=time.monotonic()+40
                while not ready.exists() and time.monotonic()<deadline:
                    if self.poll() is not None:raise AssertionError('gradle-exited-before-probe')
                    time.sleep(.02)
                assert ready.exists(),'gradle-probe-startup-timeout'
                raise subprocess.TimeoutExpired(self.args,timeout)
            return super().wait(timeout=timeout)
    try:
        with (out/'gradle-probe.log').open('w') as log:
            with patch.object(qa_common,'_capture_owned',capture),patch.object(qa_common.subprocess,'Popen',ReadyProcess):
                try:qa_common.run_owned([str(root/'gradlew'),'-p',tmp,'ownedProbe','--no-daemon','--console=plain'],log=log,env=qa_common.environment(),timeout=45)
                except subprocess.TimeoutExpired:pass
                else:raise AssertionError('expected-outer-timeout')
        live=[]
        for value in captured:
            try:
                fields=Path(f"/proc/{value['pid']}/stat").read_text().rsplit(')',1)[1].split()
                if fields[19]==value['start'] and fields[0]!='Z':live.append(value)
            except (FileNotFoundError,ProcessLookupError):pass
        summary={'status':'Passed' if not live else 'Failed','ownedProcessesCaptured':len(captured),'independentProcessGroups':len({v['group'] for v in captured}),'liveOwnedProcessesAfterCleanup':len(live),'nestedLeafIgnoresSigterm':True,'startupSynchronizedBeforeDeadline':True}
        (out/'gradle-cancellation.json').write_text(json.dumps(summary,indent=2)+'\n')
        print(json.dumps(summary));assert not live
    finally:
        for value in captured:
            try:
                fd=os.pidfd_open(value['pid'])
                try:
                    fields=Path(f"/proc/{value['pid']}/stat").read_text().rsplit(')',1)[1].split()
                    if fields[19]==value['start']:signal.pidfd_send_signal(fd,signal.SIGKILL)
                finally:os.close(fd)
            except (ProcessLookupError,FileNotFoundError):pass
