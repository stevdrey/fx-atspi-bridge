"""Real Linux process regressions: nested sessions must not survive cancellation."""
from contextlib import ExitStack
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, call, patch

import qa_common


class NestedOwnedProcesses(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='sc10-owned-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = str(Path(qa_common.__file__).parent)
        record = '''
import json,os
from pathlib import Path

def record(path):
    fields=Path('/proc/self/stat').read_text().rsplit(')',1)[1].split()
    Path(path).write_text(json.dumps({'pid':os.getpid(),'group':os.getpgrp(),'start':fields[19]}))
'''
        self.leaf = self.root/'leaf.py'
        self.leaf.write_text(record+'''
import signal,sys,time
signal.signal(signal.SIGTERM,signal.SIG_IGN)
record(sys.argv[1])
time.sleep(60)
''')
        self.middle = self.root/'middle.py'
        self.middle.write_text(record+f'''
import sys
sys.path.insert(0,{scripts!r})
from qa_common import run_owned,environment
record(sys.argv[1])
run_owned([sys.executable,{str(self.leaf)!r},sys.argv[2]],log=None,env=environment(),timeout=30)
''')
        self.middle_record = self.root/'middle.json'
        self.leaf_record = self.root/'leaf.json'
        self.command = [sys.executable,str(self.middle),str(self.middle_record),str(self.leaf_record)]
        self.addCleanup(self.kill_owned_records)

    def kill_owned_records(self):
        # Keep the test failure path safe even when exercising the old, leaking implementation.
        for path in (self.leaf_record,self.middle_record):
            if not path.exists():continue
            value=json.loads(path.read_text())
            try:
                fd=os.pidfd_open(value['pid'])
                try:
                    fields=Path(f"/proc/{value['pid']}/stat").read_text().rsplit(')',1)[1].split()
                    if fields[19]==value['start']:signal.pidfd_send_signal(fd,signal.SIGKILL)
                finally:os.close(fd)
            except (FileNotFoundError,ProcessLookupError):pass

    def wait_ready(self):
        deadline=time.monotonic()+10
        while not self.leaf_record.exists() and time.monotonic()<deadline:time.sleep(.01)
        self.assertTrue(self.leaf_record.exists(),'nested process never became ready')
        # The file is published before sleeping; wait until its short JSON write completes.
        while time.monotonic()<deadline:
            try:return json.loads(self.leaf_record.read_text())
            except json.JSONDecodeError:time.sleep(.01)
        self.fail('incomplete nested process record')

    def assert_stopped(self,path):
        value=json.loads(path.read_text())
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            try:
                fields=Path(f"/proc/{value['pid']}/stat").read_text().rsplit(')',1)[1].split()
                if fields[19]!=value['start'] or fields[0]=='Z':return
            except (FileNotFoundError,ProcessLookupError):return
            time.sleep(.01)
        self.fail('owned descendant still running after cancellation')

    def test_outer_timeout_kills_nested_session_even_when_leaf_ignores_term(self):
        # Trigger the real timeout path only after the nested process has started, avoiding startup timing assumptions.
        real_popen=subprocess.Popen
        test=self
        class ReadyProcess(real_popen):
            first_wait=True
            def wait(self,timeout=None):
                if self.first_wait:
                    self.first_wait=False
                    test.wait_ready()
                    raise subprocess.TimeoutExpired(self.args,timeout)
                return super().wait(timeout=timeout)
        unrelated=real_popen([sys.executable,'-c','import time;time.sleep(30)'],
                             stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            with patch.object(qa_common.subprocess,'Popen',ReadyProcess):
                with self.assertRaises(subprocess.TimeoutExpired):
                    qa_common.run_owned(self.command,log=subprocess.DEVNULL,env=qa_common.environment(),timeout=20)
            self.assertIsNone(unrelated.poll(),'cleanup signalled a sibling outside the owned tree')
        finally:
            unrelated.kill();unrelated.wait(timeout=5)
        self.assert_stopped(self.middle_record)
        self.assert_stopped(self.leaf_record)
        leaf=json.loads(self.leaf_record.read_text());middle=json.loads(self.middle_record.read_text())
        self.assertNotEqual(leaf['group'],middle['group'],'regression must exercise independent sessions')

    def test_keyboard_interrupt_kills_nested_sessions_without_signalling_the_caller(self):
        result=self.root/'cancelled'
        code=f'''
import sys
from pathlib import Path
sys.path.insert(0,{str(Path(qa_common.__file__).parent)!r})
from qa_common import run_owned,environment
try:run_owned({self.command!r},log=None,env=environment(),timeout=30)
except KeyboardInterrupt:Path({str(result)!r}).write_text('cancelled')
'''
        caller_group=os.getpgrp()
        process=subprocess.Popen([sys.executable,'-c',code],stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL,start_new_session=True,env=qa_common.environment())
        try:
            self.wait_ready()
            process.send_signal(signal.SIGINT)
            self.assertEqual(0,process.wait(timeout=15))
            self.assertEqual('cancelled',result.read_text())
            self.assert_stopped(self.middle_record)
            self.assert_stopped(self.leaf_record)
            self.assertEqual(caller_group,os.getpgrp())
        finally:
            if process.poll() is None:process.kill()
            process.wait(timeout=5)

    def test_descendant_in_another_session_gets_the_term_grace_period(self):
        marker=self.root/'graceful'
        self.leaf.write_text(self.leaf.read_text().replace('signal.signal(signal.SIGTERM,signal.SIG_IGN)',
            f"""def graceful(*_):
    time.sleep(.5)  # longer than the root takes to die from its own TERM
    Path({str(marker)!r}).write_text('done');sys.exit(0)
signal.signal(signal.SIGTERM,graceful)"""))
        real_popen=subprocess.Popen
        test=self
        class ReadyProcess(real_popen):
            first_wait=True
            def wait(self,timeout=None):
                if self.first_wait:
                    self.first_wait=False
                    test.wait_ready()
                    raise subprocess.TimeoutExpired(self.args,timeout)
                return super().wait(timeout=timeout)
        with patch.object(qa_common.subprocess,'Popen',ReadyProcess):
            with self.assertRaises(subprocess.TimeoutExpired):
                qa_common.run_owned(self.command,log=subprocess.DEVNULL,env=qa_common.environment(),timeout=20)
        self.assertEqual('done',marker.read_text(),'the descendant was killed before it could handle TERM')
        self.assert_stopped(self.leaf_record)

    def test_successful_command_preserves_its_exit_status(self):
        self.assertEqual(0,qa_common.run_owned([sys.executable,'-c','pass'],log=subprocess.DEVNULL,
                                             env=qa_common.environment(),timeout=5))
        self.assertEqual(7,qa_common.run_owned([sys.executable,'-c','raise SystemExit(7)'],log=subprocess.DEVNULL,
                                             env=qa_common.environment(),timeout=5))


class OwnershipFailures(unittest.TestCase):
    def test_non_child_pid_is_not_admitted(self):
        handles=[]
        with ExitStack() as stack:
            qa_common._capture_owned(Mock(pid=os.getpid()),handles,stack)
        self.assertEqual([],handles)

    def test_capture_failure_cleans_up_known_handles_and_group_and_closes_fds(self):
        def capture(process,handles,stack):
            handles.append((456,456));stack.callback(qa_common.os.close,456)
            raise AssertionError('owned-process-limit')
        with patch.object(qa_common.subprocess,'Popen') as spawn, \
             patch.object(qa_common,'_capture_owned',side_effect=capture), \
             patch.object(qa_common.signal,'pidfd_send_signal') as send, \
             patch.object(qa_common.os,'killpg') as kill,patch.object(qa_common.os,'close') as close, \
             patch.object(qa_common,'_wait_handles'), \
             patch.object(qa_common,'_await_handles',side_effect=lambda handles,seconds:handles):
            child=spawn.return_value;child.pid=123;child.returncode=None
            child.wait.side_effect=[subprocess.TimeoutExpired('outer',1),0]
            with self.assertRaisesRegex(AssertionError,'owned-process-limit'):
                qa_common.run_owned(['outer'],log=None,env={},timeout=1)
            self.assertEqual([call(456,signal.SIGTERM),call(456,signal.SIGKILL)],send.call_args_list)
            self.assertEqual([call(123,signal.SIGTERM),call(123,signal.SIGKILL)],kill.call_args_list)
            close.assert_called_once_with(456)


if __name__=='__main__':unittest.main()
