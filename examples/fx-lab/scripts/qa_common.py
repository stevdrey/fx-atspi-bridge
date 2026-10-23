"""Bounded, value-free helpers for SC-10 external-client qualification."""
from contextlib import ExitStack
import hashlib
import json
import os
import select
from pathlib import Path
import signal
import subprocess
import time

from scaling_audit import stop_process

MAX_NODES = 256
MAX_EVENTS = 2048
MAX_OWNED_PROCESSES = 256


def check(condition, code):
    if not condition:
        raise AssertionError(code)


def read_json(path):
    path = Path(path)
    check(path.stat().st_size <= 1024 * 1024, 'report-size')
    return json.loads(path.read_text())


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def environment():
    result = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'FX_LAB_OPTS',
                'MODULAR_OPTS', 'CLASSPATH_OPTS', 'PYTHONOPTIMIZE'):
        result.pop(key, None)
    return result


def stop(process):
    if process is not None:
        stop_process(process)


def _stat(pid):
    # comm can contain spaces or parentheses: split only after the last ')'.
    return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()


def parent_pid(pid):
    return int(_stat(pid)[1])


def _capture_owned(process, handles, stack):
    """Pin live descendants before terminating parents, even across new process groups/sessions."""
    pending = [(process.pid, os.getpid())]
    seen = set()
    while pending:
        pid, parent = pending.pop()
        if pid in seen:
            continue
        check(len(seen) < MAX_OWNED_PROCESSES, 'owned-process-limit')
        seen.add(pid)
        try:
            fd = os.pidfd_open(pid)
        except ProcessLookupError:
            continue
        try:
            fields = _stat(pid)
            if int(fields[1]) != parent:
                continue  # A disappearing child/PID reuse must never admit an unrelated process.
            handles.append((fd, int(fields[2])))
            stack.callback(os.close, fd)
            fd = None
            # JVM subprocesses may be children of a worker thread, not the main thread.
            for task in Path(f'/proc/{pid}/task').iterdir():
                try:
                    children = (task/'children').read_text().split()
                except (FileNotFoundError, ProcessLookupError):
                    continue
                pending.extend((int(child), pid) for child in children)
        except (FileNotFoundError, ProcessLookupError):
            continue
        finally:
            if fd is not None:
                os.close(fd)


def _signal_handles(handles, group, sig):
    for fd, child_group in reversed(handles):
        # The outer group is signalled once separately; retain every fd for the final forced teardown.
        if sig == signal.SIGTERM and child_group == group:
            continue
        try:
            signal.pidfd_send_signal(fd, sig)
        except ProcessLookupError:
            pass


def _await_handles(handles, seconds):
    """Waits on pidfds without reaping; returns the handles still running when the period ends."""
    poller = select.poll()
    pending = {fd for fd, _ in handles}
    for fd in pending:
        poller.register(fd, select.POLLIN)
    deadline = time.monotonic() + seconds
    while pending and (remaining := deadline - time.monotonic()) > 0:
        for fd, events in poller.poll(max(1, int(remaining * 1000))):
            check(events & select.POLLIN, 'owned-process-wait-failed')
            pending.discard(fd)
            poller.unregister(fd)
    return [handle for handle in handles if handle[0] in pending]


def _wait_handles(handles):
    check(not _await_handles(handles, 5), 'owned-process-stop-timeout')


def _signal_group(process, sig):
    # Only an unreaped root keeps its process-group id reserved; after reaping, the id may name an unrelated group.
    if process.returncode is not None:
        return
    try:
        os.killpg(process.pid, sig)
    except ProcessLookupError:
        pass


def _terminate_owned(process):
    with ExitStack() as stack:
        handles = []
        try:
            _capture_owned(process, handles, stack)
        finally:
            # Capture errors still tear down the handles/groups already owned, then propagate.
            running = handles
            try:
                _signal_handles(handles, process.pid, signal.SIGTERM)
                _signal_group(process, signal.SIGTERM)
                # Every captured process, not only the root, gets the TERM grace period before SIGKILL.
                running = _await_handles(handles, 5)
            finally:
                try:
                    _signal_handles(running, process.pid, signal.SIGKILL)
                finally:
                    # The wrapper may have exited first; its remaining group is killed before the root is reaped.
                    _signal_group(process, signal.SIGKILL)
                    process.wait(timeout=5)
                    _wait_handles(handles)


def run_owned(command, *, log, env, timeout, cwd=None):
    """On timeout/interruption, stop the owned tree including nested Gradle sessions."""
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True, cwd=cwd)
    try:
        return process.wait(timeout=timeout)
    except BaseException:
        _terminate_owned(process)
        raise


def pump():
    from gi.repository import GLib
    context = GLib.MainContext.default()
    for _ in range(64):
        if not context.pending():
            break
        context.iteration(False)


def until(predicate, seconds=10):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        pump()
        value = predicate()
        if value:
            return value
        time.sleep(.03)
    raise TimeoutError('condition-deadline')


def discover(pid, name, seconds=20):
    from atspi_probe import find_applications, process_ids
    from gi.repository import Atspi
    deadline = time.monotonic() + seconds
    def find():
        matches = find_applications(Atspi.get_desktop(0), name, deadline)
        if matches:
            pids = process_ids(matches, deadline)
            if pids is not None:
                return next((app for app, candidate in zip(matches, pids) if candidate == pid), None)
        return None
    return until(find, seconds)


def tree(app):
    from gi.repository import Atspi
    deadline = time.monotonic() + 8
    pending, seen, found = [app], set(), {}
    while pending:
        check(time.monotonic() < deadline, 'tree-timeout')
        node = pending.pop()
        check(node is not None, 'null-child')
        if node in seen:
            continue
        seen.add(node)
        check(len(seen) <= MAX_NODES, 'tree-bound')
        node.clear_cache()
        identity = node.get_accessible_id()
        values = [node.get_name(), node.get_description(), identity, *node.get_attributes().values()]
        if node.get_text_iface() is not None:
            values.append(Atspi.Text.get_text(node, 0, -1))
        check(not any('SC10_SECRET' in str(value) for value in values), 'privacy-projection')
        if node.get_role() == Atspi.Role.PASSWORD_TEXT:
            check(node.get_text_iface() is None and node.get_editable_text_iface() is None, 'password-interfaces')
        if identity:
            check(identity not in found, 'duplicate-id')
            found[identity] = node
        count = node.get_child_count()
        check(0 <= count <= MAX_NODES and len(pending) + len(seen) + count <= MAX_NODES, 'child-bound')
        pending.extend(node.get_child_at_index(i) for i in range(count))
    return found
