#!/usr/bin/env python3
"""SC-06 independent libatspi request-to-insert-event latency on the existing fx-lab UI.

Run under gnome-session-inhibit on a clean built revision:
  dispatch_event_latency.py <install/fx-lab/bin/fx-lab> <empty-output-dir>
Five separate JVMs, 50 warmups + 200 serial synthetic text writes each. This includes
D-Bus request, FX dispatch/projection and client event dispatch; it is not pure transport latency.
No application/user text is printed or retained. The fixture is selected by its exact child PID.
"""
import hashlib
import json
import pathlib
import statistics
import subprocess
import sys
import time

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi, GLib

WARMUP = 50
SAMPLES = 200
REPETITIONS = 5


def percentile(values, quantile):
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * quantile)]


def find_input(pid, process):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("fixture exited before discovery")
        desktop = Atspi.get_desktop(0)
        if desktop.get_child_count() > 256:
            raise RuntimeError("desktop application bound exceeded")
        for i in range(desktop.get_child_count()):
            app = desktop.get_child_at_index(i)
            if app is None or app.get_process_id() != pid:
                continue
            pending = [app]
            visited = 0
            while pending:
                visited += 1
                if visited > 256 or time.monotonic() >= deadline:
                    raise RuntimeError("fixture discovery bound exceeded")
                node = pending.pop()
                if node.get_name() == "Entrada" and node.get_editable_text_iface() is not None:
                    return node
                count = node.get_child_count()
                if count > 256:
                    raise RuntimeError("fixture child bound exceeded")
                pending.extend(child for i in range(count) if (child := node.get_child_at_index(i)) is not None)
        while GLib.MainContext.default().pending():
            GLib.MainContext.default().iteration(False)
        time.sleep(0.02)
    raise RuntimeError("fixture not discovered")


def measure(binary, directory, repetition):
    latencies = []
    state = {"expected": None, "started": 0, "elapsed": None}
    loop = GLib.MainLoop()

    with (directory / f"r{repetition}.log").open("w") as log:
        process = subprocess.Popen([str(binary)], stdout=log, stderr=subprocess.STDOUT)
        listener = None
        try:
            target = find_input(process.pid, process)

            def event_received(event):
                if event.source == target and event.any_data == state["expected"]:
                    state["elapsed"] = (time.monotonic_ns() - state["started"]) / 1e6
                    loop.quit()

            listener = Atspi.EventListener.new(event_received)
            listener.register("object:text-changed:insert")
            for sample in range(WARMUP + SAMPLES):
                if process.poll() is not None:
                    raise RuntimeError("fixture exited during samples")
                state.update(expected=f"SC06_{sample:06d}", elapsed=None)
                timed_out = [False]

                def expired():
                    timed_out[0] = True
                    loop.quit()
                    return False

                timeout = GLib.timeout_add(2000, expired)
                try:
                    state["started"] = time.monotonic_ns()
                    if not target.get_editable_text_iface().set_text_contents(state["expected"]):
                        raise RuntimeError("synthetic write rejected")
                    if state["elapsed"] is None and not timed_out[0]:
                        loop.run()
                finally:
                    if not timed_out[0]:
                        GLib.source_remove(timeout)
                if timed_out[0] or state["elapsed"] is None:
                    raise RuntimeError("matching event not received within two seconds")
                if sample >= WARMUP:
                    latencies.append(state["elapsed"])
            if process.poll() is not None:
                raise RuntimeError("fixture exited after samples")
        finally:
            if listener is not None:
                listener.deregister("object:text-changed:insert")
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    data = {"warmup": WARMUP, "samples": SAMPLES, "repetition": repetition,
            "latencyP50Ms": statistics.median(latencies), "latencyP95Ms": percentile(latencies, .95),
            "latencyP99Ms": percentile(latencies, .99), "latencyMaxMs": max(latencies), "latenciesMs": latencies}
    (directory / f"r{repetition}.json").write_text(json.dumps(data, indent=2) + "\n")


def main():
    binary = pathlib.Path(sys.argv[1]).resolve()
    directory = pathlib.Path(sys.argv[2]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise RuntimeError("output directory must be empty")
    root = binary.parents[6]
    revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True).strip():
        raise RuntimeError("fixture checkout must be clean")
    libraries = binary.parent.parent / "lib"
    metadata = {"git": revision, "python": sys.version.split()[0], "pygobject": gi.__version__,
                "glib": f"{GLib.MAJOR_VERSION}.{GLib.MINOR_VERSION}.{GLib.MICRO_VERSION}",
                "clientSha256": hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                "jars": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(libraries.glob("*.jar"))}}
    metadata["libatspi"] = subprocess.check_output(["rpm", "-q", "at-spi2-core"], text=True).strip()
    (directory / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    Atspi.set_timeout(2000, 5000)
    for repetition in range(1, REPETITIONS + 1):
        measure(binary, directory, repetition)
        print(f"event latency repetition {repetition} complete", flush=True)


if __name__ == "__main__":
    main()
