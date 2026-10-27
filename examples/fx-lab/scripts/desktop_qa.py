#!/usr/bin/env python3
"""QA-02: MUTATING libatspi checks of a fresh fx-lab, selected by OS PID.

Usage: python3 desktop_qa.py --pid JAVA_PID --out NEW_DIRECTORY
Only use a disposable laboratory instance. No desktop/session settings are changed.
Exit 0 means every automated scenario passed; Orca/backend QA is recorded separately.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import platform
import time

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi, GLib

APP = "JavaFX AT-SPI Lab"
TEXT = "A🧪e\u0301Z"  # 5 code points, 6 UTF-16 units; combining marks are separate offsets
MAX_APPS = 256
MAX_NODES = 5000
MAX_EVENTS = 1000
RUN_SECONDS = 180
STEP_SECONDS = 10
CALL_MS = 2000
STARTUP_MS = 5000
EVENT_TYPES = ("object:text-changed", "object:state-changed", "object:children-changed")
SCENARIOS = ("fresh_fixture", "unicode_enable", "append", "clear", "dynamic_add",
             "dynamic_remove", "window_open", "window_close", "scene_replace", "scene_restore")


class CheckFailed(Exception):
    """Messages must be fixed check identifiers, never remote values."""


def require(condition, check):
    if not condition:
        raise CheckFailed(check)


def select_application(desktop, pid, call):
    count = call(desktop.get_child_count)
    require(0 <= count <= MAX_APPS, "desktop_application_limit")
    matches = []
    for index in range(count):
        candidate = call(desktop.get_child_at_index, index)
        if candidate is None:
            continue
        try:
            candidate_pid = call(candidate.get_process_id)
        except GLib.Error:
            continue  # a foreign application may exit during discovery; the lab must still match
        # GTK can register a second provider for this JVM. Inspect names only for
        # the requested PID, and require one match for this particular bridge.
        if candidate_pid == pid and call(candidate.get_name) == APP:
            matches.append(candidate)
    require(len(matches) == 1, "pid_missing_or_ambiguous")
    return matches[0]


def walk(root, call):
    """Iterative traversal with a budget for child lookups, including nulls/cycles."""
    nodes, pending, lookups = [], [root], 0
    while pending:
        require(len(nodes) < MAX_NODES, "tree_node_limit")
        node = pending.pop()
        call(node.clear_cache)
        name = call(node.get_name) or ""
        require(len(name) <= 256, "node_name_limit")
        nodes.append((node, name, call(node.get_role)))
        count = call(node.get_child_count)
        require(0 <= count <= MAX_NODES - lookups, "tree_lookup_limit")
        for index in range(count):
            lookups += 1
            child = call(node.get_child_at_index, index)
            if child is not None:
                pending.append(child)
    return nodes


class Events:
    def __init__(self):
        self.items = []
        self.overflow = False

    def add(self, kind, source, detail1, detail2):
        if len(self.items) >= MAX_EVENTS:
            self.overflow = True
        else:
            self.items.append({"type": kind, "source": source,
                               "detail1": detail1, "detail2": detail2})

    def has(self, start, kind, source, detail1=None):
        return any(e["type"] == kind and e["source"] == source
                   and (detail1 is None or e["detail1"] == detail1) for e in self.items[start:])


def finish(results, errors):
    return not errors and tuple(r["scenario"] for r in results) == SCENARIOS and all(
        r["status"] == "Passed" for r in results)


class Client:
    def __init__(self, pid):
        self.pid = pid
        self.deadline = time.monotonic() + RUN_SECONDS
        self.step_deadline = self.deadline
        self.events = Events()
        self.results = []
        self.fixtures = []
        self.errors = []
        self.listener = None
        self.registered = []
        self.callback_failed = False
        self.context = GLib.MainContext.default()

    def check_time(self):
        require(time.monotonic() < min(self.deadline, self.step_deadline), "deadline_exceeded")
        require(not self.events.overflow, "event_limit")
        require(not self.callback_failed, "event_callback_failed")

    def call(self, function, *args):
        self.check_time()
        remaining = int((min(self.deadline, self.step_deadline) - time.monotonic()) * 1000)
        Atspi.set_timeout(max(1, min(CALL_MS, remaining)), max(1, min(STARTUP_MS, remaining)))
        result = function(*args)
        self.check_time()
        return result

    def pump(self):
        for _ in range(100):
            self.check_time()
            if not self.context.pending():
                break
            self.context.iteration(False)

    def wait(self, condition):
        while True:
            self.pump()
            value = condition()
            self.check_time()
            if value:
                return value
            time.sleep(0.02)

    def tree(self):
        return walk(self.app, self.call)

    def find(self, name, role=None, nodes=None):
        nodes = self.tree() if nodes is None else nodes
        matches = [n for n, text, r in nodes if text == name and (role is None or r == role)]
        require(len(matches) == 1, "node_missing_or_ambiguous")
        return matches[0]

    def shows(self, present=(), absent=()):
        """One traversal for every name a wait condition needs."""
        names = {text for _, text, _ in self.tree()}
        return names.issuperset(present) and names.isdisjoint(absent)

    def identity(self, node):
        path = node.path
        require(isinstance(path, str) and len(path) <= 256, "source_path_limit")
        return path

    def enabled(self, node):
        self.call(node.clear_cache)
        return self.call(node.get_state_set).contains(Atspi.StateType.ENABLED)

    def text(self, node):
        self.call(node.clear_cache)
        count = self.call(Atspi.Text.get_character_count, node)
        require(0 <= count <= 1024, "text_length_limit")
        return self.call(Atspi.Text.get_text, node, 0, count)

    def write(self, node, value):
        require(self.call(Atspi.EditableText.set_text_contents, node, value), "set_text_rejected")

    def click(self, name):
        node = self.find(name, Atspi.Role.PUSH_BUTTON)
        require(self.call(Atspi.Action.do_action, node, 0), "button_action_rejected")

    def receive(self, event, _data):
        try:
            # These are local libatspi fields, not synchronous D-Bus calls from
            # inside event dispatch. Never retain payloads or foreign sources.
            if event.source is not None and event.source.app.bus_name == self.app.app.bus_name:
                kind = event.type
                require(len(kind) <= 128, "event_type_limit")
                self.events.add(kind, self.identity(event.source), event.detail1, event.detail2)
        except Exception:
            self.callback_failed = True

    def subscribe(self):
        self.listener = Atspi.EventListener.new(self.receive, None)
        for kind in EVENT_TYPES:
            def register():
                accepted = self.listener.register_with_app(kind, None, self.app)
                if accepted:
                    # Own it before call() checks the elapsed deadline: a late
                    # successful registration still needs deregistration.
                    self.registered.append(kind)
                return accepted
            require(self.call(register), "event_registration_failed")

    def event(self, start, kind, node, detail1=None):
        return self.events.has(start, kind, self.identity(node), detail1)

    def children_event(self, start, operation, parent):
        return self.event(start, "object:children-changed:" + operation, parent)

    def scenario(self, name, body):
        started = time.monotonic()
        self.step_deadline = min(self.deadline, started + STEP_SECONDS)
        start = len(self.events.items)
        try:
            self.settle()  # separate delayed events from the previous action
            start = len(self.events.items)
            body(start)
            self.check_time()
        except Exception as error:
            check = str(error) if isinstance(error, CheckFailed) else type(error).__name__
            self.results.append({"scenario": name, "status": "Failed", "check": check,
                                 "eventStart": start, "eventEnd": len(self.events.items),
                                 "seconds": round(time.monotonic() - started, 3)})
            raise
        self.results.append({"scenario": name, "status": "Passed",
                             "eventStart": start, "eventEnd": len(self.events.items),
                             "seconds": round(time.monotonic() - started, 3)})
        print("Passed:", name, flush=True)

    def settle(self):
        previous = len(self.events.items)
        quiet_since = time.monotonic()
        while time.monotonic() - quiet_since < .2:
            self.pump()
            current = len(self.events.items)
            if current != previous:
                previous, quiet_since = current, time.monotonic()
            time.sleep(.01)

    def run(self):
        try:
            Atspi.set_timeout(CALL_MS, STARTUP_MS)
            self.app = select_application(Atspi.get_desktop(0), self.pid, self.call)
            self.subscribe()
            self.scenario("fresh_fixture", self.fresh)
            self.scenario("unicode_enable", self.unicode)
            self.scenario("append", self.append)
            self.scenario("clear", self.clear)
            self.scenario("dynamic_add", self.dynamic_add)
            self.scenario("dynamic_remove", self.dynamic_remove)
            self.scenario("window_open", self.window_open)
            self.scenario("window_close", self.window_close)
            self.scenario("scene_replace", self.scene_replace)
            self.scenario("scene_restore", self.scene_restore)
        except Exception as error:
            self.errors.append(str(error) if isinstance(error, CheckFailed) else type(error).__name__)
        finally:
            Atspi.set_timeout(CALL_MS, STARTUP_MS)
            for kind in self.registered:
                try:
                    require(self.listener.deregister(kind), "event_deregistration_failed")
                except Exception:
                    self.errors.append("event_deregistration_failed")
            Atspi.exit()
        completed = {r["scenario"] for r in self.results}
        for name in SCENARIOS:
            if name not in completed:
                self.results.append({"scenario": name, "status": "Not tested", "reason": "prior_failure"})
        return {"schema": 1, "pid": self.pid, "client": {"python": platform.python_version(),
                "pygobject": gi.__version__, "atspi_namespace": "2.0"},
                "limits": {"runSeconds": RUN_SECONDS, "stepSeconds": STEP_SECONDS,
                           "callMs": CALL_MS, "startupMs": STARTUP_MS, "nodes": MAX_NODES, "events": MAX_EVENTS},
                "scenarios": self.results, "fixtures": self.fixtures, "events": self.events.items,
                "eventCounts": dict(Counter(e["type"] for e in self.events.items)),
                "errors": self.errors, "status": "Passed" if finish(self.results, self.errors) else "Failed"}

    def fresh(self, _start):
        nodes = self.tree()
        self.input = self.find("Entrada", Atspi.Role.ENTRY, nodes)
        self.output = self.find("Contenido", Atspi.Role.TEXT, nodes)
        self.add = self.find("Agregar", Atspi.Role.PUSH_BUTTON, nodes)
        self.frame = self.find("Desktop Control — JavaFX AT-SPI Lab", Atspi.Role.FRAME, nodes)
        for name in ("Limpiar", "Añadir campo", "Quitar campo", "Abrir segunda ventana", "Cambiar escena"):
            self.find(name, Atspi.Role.PUSH_BUTTON, nodes)
        require(not {"Campo dinámico", "Entrada secundaria", "Texto alternativo"} & {text for _, text, _ in nodes},
                "fixture_not_fresh")
        require(self.text(self.input) == self.text(self.output) == "", "fixture_not_empty")
        require(not self.enabled(self.add), "append_initially_enabled")
        self.fixtures.append({key: self.identity(node) for key, node in
                              (("input", self.input), ("output", self.output),
                               ("append", self.add), ("frame", self.frame))})

    def unicode(self, start):
        self.write(self.input, TEXT)
        self.wait(lambda: self.text(self.input) == TEXT and self.enabled(self.add)
                  and self.event(start, "object:text-changed:insert", self.input, 0)
                  and self.event(start, "object:state-changed:enabled", self.add, 1))
        require(self.call(Atspi.Text.get_character_count, self.input) == len(TEXT), "unicode_count")
        for left, right in ((1, 2), (1, 4), (2, 4), (3, 4), (0, 5)):
            require(self.call(Atspi.Text.get_text, self.input, left, right) == TEXT[left:right], "unicode_subrange")
        require(any(e["type"] == "object:text-changed:insert" and e["source"] == self.identity(self.input)
                    and e["detail2"] == len(TEXT) for e in self.events.items[start:]), "unicode_event_count")

    def append(self, start):
        self.click("Agregar")
        self.wait(lambda: self.text(self.output) == TEXT + "\n"
                  and self.event(start, "object:text-changed:insert", self.output))

    def clear(self, start):
        self.click("Limpiar")
        self.wait(lambda: self.text(self.input) == self.text(self.output) == "" and not self.enabled(self.add)
                  and self.event(start, "object:text-changed:delete", self.input)
                  and self.event(start, "object:text-changed:delete", self.output)
                  and self.event(start, "object:state-changed:enabled", self.add, 0))

    def dynamic_add(self, start):
        self.click("Añadir campo")
        self.wait(lambda: self.shows(present=("Campo dinámico",)))
        self.dynamic_parent = self.call(self.find("Campo dinámico").get_parent)
        self.wait(lambda: self.children_event(start, "add", self.dynamic_parent))

    def dynamic_remove(self, start):
        self.click("Quitar campo")
        self.wait(lambda: self.shows(absent=("Campo dinámico",))
                  and self.children_event(start, "remove", self.dynamic_parent))

    def window_open(self, start):
        self.click("Abrir segunda ventana")
        self.wait(lambda: self.shows(present=("Ventana secundaria", "Entrada secundaria"))
                  and self.children_event(start, "add", self.app))
        self.write(self.find("Entrada secundaria", Atspi.Role.ENTRY), TEXT)
        self.wait(lambda: self.text(self.find("Entrada secundaria", Atspi.Role.ENTRY)) == TEXT)

    def window_close(self, start):
        self.click("Cerrar segunda ventana")
        self.wait(lambda: self.shows(absent=("Entrada secundaria", "Ventana secundaria"))
                  and self.children_event(start, "remove", self.app))

    def scene_replace(self, start):
        self.click("Cambiar escena")
        self.wait(lambda: self.shows(present=("Texto alternativo",), absent=("Contenido",))
                  and self.children_event(start, "remove", self.frame)
                  and self.children_event(start, "add", self.frame))
        self.write(self.find("Texto alternativo", Atspi.Role.TEXT), TEXT)
        self.wait(lambda: self.text(self.find("Texto alternativo", Atspi.Role.TEXT)) == TEXT)

    def scene_restore(self, start):
        self.click("Volver al laboratorio")
        self.wait(lambda: self.shows(present=("Contenido",), absent=("Texto alternativo",))
                  and self.children_event(start, "remove", self.frame)
                  and self.children_event(start, "add", self.frame))
        self.fresh(start)  # obtains new references; verifies usable restored controls
        self.write(self.input, TEXT)
        self.wait(lambda: self.text(self.input) == TEXT and self.enabled(self.add))
        self.click("Agregar")
        self.wait(lambda: self.text(self.output) == TEXT + "\n")
        self.click("Limpiar")
        self.wait(lambda: self.text(self.input) == self.text(self.output) == "" and not self.enabled(self.add))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.pid <= 0:
        parser.error("--pid must be positive")
    # Refuse overwriting evidence, including a partially failed previous collection.
    args.out.mkdir(parents=True, exist_ok=False)
    # Imported only after argparse validation: the probe parses sys.argv at import time.
    from atspi_probe import libatspi_version
    package_version = libatspi_version()
    result = Client(args.pid).run()
    result["client"]["libatspi_package"] = package_version
    (args.out / "results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(result["status"], ", ".join(result["errors"]), flush=True)
    return 0 if result["status"] == "Passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
