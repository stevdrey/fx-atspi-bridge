#!/usr/bin/env python3
"""Summarise an Issue 17 memory collection (run-memory-diag.sh) into Markdown tables.

Usage: memory_diag.py <data-dir>
The directory must hold exactly the runs planned by the plan= lines of its environment.txt.
Every value is reported as median (min-max) over repetitions. Cells are grouped by <kind>-n<nodes>-<bridge>-<variant>.
Raw artifacts (<cell>/<checkpoint>.<kind>.txt) are read when present; the run JSON alone is enough for the RSS split.
Histogram classes are grouped by module: the bridge module, JavaFX modules, everything else.
"""
import collections
import json
import pathlib
import re
import statistics as st
import sys

CELL = re.compile(r"^(?P<kind>[a-z]+)-n(?P<nodes>\d+)-(?P<bridge>on|off)-(?P<variant>[a-z0-9]+)-r(?P<rep>\d+)$")
BRIDGE_MODULE = "dev.desktopcontrol.fxatspi"
JAVAFX_MODULE = re.compile(r"^javafx\.")
# The summary may be committed (docs/verification/README.md: captured values are sanitized): the data directory is shown
# relative to the repository, or as <out>/<name> when it lies outside it.
REPOSITORY = pathlib.Path(__file__).resolve().parents[3]
# Control characters and backticks in a directory name would break the `Source:` code span of the summary.
UNSAFE_IN_CODE_SPAN = re.compile(r"[\x00-\x1f\x7f`]")
WATCHED_CLASSES = ("javafx.scene.control.TextField", "javafx.scene.control.Button", "javafx.scene.Scene",
                   "javafx.scene.layout.VBox")
# Phase -> (checkpoint before, checkpoints after) whose differences are reported.
PHASES = {
    "structural": ("structuralBefore", ("structuralCheckpoint", "structuralAfterGc")),
    "install": ("installChurnBefore", ("installChurnCheckpoint", "installChurnAfterGc", "installChurnAfterDrain",
                                       "installChurnAfterTrim")),
}
LONG_STRUCTURAL = tuple(f"structuralCycle{i}" for i in (0, 30, 60, 100, 150))
LONG_INSTALL = tuple(f"installCycle{i}" for i in range(0, 1001, 100))
# Every checkpoint BenchApp can snapshot, in run order; the absolute tables list each one a cell recorded.
CHECKPOINT_ORDER = ("afterWarmupGc", "afterIdleGc", "structuralBefore", *LONG_STRUCTURAL,
                    "structuralCheckpoint", "structuralAfterGc", "installChurnBefore", *LONG_INSTALL,
                    "installChurnCheckpoint", "installChurnAfterGc", "installChurnAfterDrain", "installChurnAfterTrim")
SMAPS = ("Rss", "Anonymous", "PssFile")
# Checkpoints where BenchApp takes a class histogram (MemoryDiagnostics.snapshot(..., histogram=true)).
HISTOGRAM_CHECKPOINTS = frozenset({"afterWarmupGc", "afterIdleGc", "structuralBefore", "structuralAfterGc",
                                   "installChurnBefore", "installChurnAfterGc"}) | frozenset(LONG_STRUCTURAL + LONG_INSTALL)
MAX_REPORTED_PROBLEMS = 20
# Legacy fallback for collections that predate the recorded heap range (javaHeapStart/javaHeapEnd in the run JSON): with
# compressed oops HotSpot reserves the Java heap and class space below this address, while glibc arenas and thread
# stacks live near the top of the address space. Only valid for that heap placement; reported as "legacy heuristic".
LOW_RANGE_LIMIT = 0x1000_0000_0000


# --- parsers (pure; covered by test_memory_diag.py) ---------------------------------------------------------------

def parse_smaps_rollup(text):
    """kB values of /proc/<pid>/smaps_rollup."""
    values = {}
    for line in text.splitlines():
        match = re.match(r"^([A-Za-z_]+):\s+(\d+) kB$", line.strip())
        if match:
            values[match.group(1)] = int(match.group(2))
    return values


def parse_nmt(text):
    """Committed KB per NMT summary category, plus 'Total' and 'malloc' (all malloc'd memory NMT sees)."""
    values = {}
    total = re.search(r"Total: reserved=(\d+)KB, committed=(\d+)KB", text)
    if total:
        values["Total"] = int(total.group(2))
    malloc = re.search(r"^\s+malloc: (\d+)KB", text, re.M)
    if malloc:
        values["malloc"] = int(malloc.group(1))
    for match in re.finditer(r"^-\s+(.+?) \(reserved=\d+KB, committed=(\d+)KB", text, re.M):
        values[match.group(1).strip()] = int(match.group(2))
    return values


def parse_malloc_info(text):
    """Process-wide glibc malloc_info totals in bytes and the number of arenas (heaps).

    'system' is what the allocator holds from the OS (excluding direct mmap chunks), 'free' is free chunks it keeps
    (fast + rest), 'mmap' is directly mapped chunks (in use), 'inUse' = system - free.
    """
    heaps = len(re.findall(r"<heap nr=", text))
    tail = text[text.rfind("</heap>"):] if "</heap>" in text else text

    def total(kind):
        match = re.search(r'<total type="%s" count="\d+" size="(\d+)"/>' % kind, tail)
        return int(match.group(1)) if match else 0

    system = re.search(r'<system type="current" size="(\d+)"/>', tail)
    system = int(system.group(1)) if system else 0
    free = total("fast") + total("rest")
    return {"arenas": heaps, "system": system, "free": free, "mmap": total("mmap"), "inUse": system - free}


def parse_histogram(text):
    """{class name: (instances, bytes, module)} from GC.class_histogram output."""
    classes = {}
    for line in text.splitlines():
        match = re.match(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)(?: \(([^)@]+)(?:@[^)]*)?\))?\s*$", line)
        if match:
            classes[match.group(3)] = (int(match.group(1)), int(match.group(2)), match.group(4) or "")
    return classes


def histogram_groups(classes):
    """Bytes and instances per group: bridge module, JavaFX modules, other."""
    groups = {"bridge": [0, 0], "javafx": [0, 0], "other": [0, 0]}
    for instances, size, module in classes.values():
        group = "bridge" if module == BRIDGE_MODULE else "javafx" if JAVAFX_MODULE.match(module) else "other"
        groups[group][0] += instances
        groups[group][1] += size
    return {name: tuple(value) for name, value in groups.items()}


def heap_range(run):
    """(start, end, method) of the Java heap for splitting anonymous RSS: the range the bench recorded from
    GC.heap_info, the legacy address heuristic for collections without it, or None when the collector printed none."""
    if "javaHeapStart" in run:
        return run["javaHeapStart"], run["javaHeapEnd"], "recorded heap range"
    if run.get("javaHeapRange") == "unknown":
        return None
    return 0, LOW_RANGE_LIMIT, "legacy heuristic"


def parse_pmap(text, heap=(0, LOW_RANGE_LIMIT, "legacy heuristic")):
    """RSS kB of anonymous versus file-backed mappings, the anonymous RSS inside the Java heap range `heap` (start,
    end, method; None = unknown) and the anonymous mapping count from `pmap -x`."""
    anon = file_backed = anon_count = low = 0
    for line in text.splitlines():
        match = re.match(r"^[0-9a-f]{8,16}\s+\d+\s+(\d+)\s+\d+\s+\S+\s+(.*)$", line)
        if not match:
            continue
        rss, mapping = int(match.group(1)), match.group(2).strip()
        if mapping.startswith("[") and mapping.endswith("]"):
            anon += rss
            anon_count += 1
            if heap and heap[0] <= int(line.split()[0], 16) < heap[1]:
                low += rss
        else:
            file_backed += rss
    return {"anonRssKb": anon, "fileRssKb": file_backed, "anonMappings": anon_count,
            "heapAnonRssKb": low if heap else None, "otherAnonRssKb": anon - low if heap else None}


def cell_key(name):
    match = CELL.match(name)
    if not match:
        return None, None
    return (match["kind"], int(match["nodes"]), match["bridge"], match["variant"]), int(match["rep"])


# --- summary -------------------------------------------------------------------------------------------------------

def fmt(values, scale=1.0, digits=1):
    """Median (min–max). Every value must be present: a missing one would shrink the sample behind the Runs count, so
    callers take JSON values through field_values(), which refuses incomplete runs. All-None (not applicable) is –."""
    if all(v is None for v in values):
        return "–"
    if any(v is None for v in values):
        raise ValueError("fmt() got a partial sample; use field_values() to refuse incomplete runs")
    values = [v / scale for v in values]
    if len(values) == 1:
        return f"{values[0]:.{digits}f}"
    return f"{st.median(values):.{digits}f} ({min(values):.{digits}f}–{max(values):.{digits}f})"


def exit_with_problems(title, problems):
    shown = problems[:MAX_REPORTED_PROBLEMS]
    more = len(problems) - len(shown)
    sys.exit(f"{title}:\n  " + "\n  ".join(shown) + (f"\n  ... and {more} more" if more else ""))


def field_values(runs, key):
    """The JSON field `key` from every run of a cell; exits naming the runs that lack it."""
    missing = [name for name, run in runs if key not in run]
    if missing:
        exit_with_problems(f"runs lack the field {key}", [f"{name}.json" for name in missing])
    return [run[key] for _, run in runs]


def checkpoints(run):
    """Checkpoints a run snapshotted: BenchApp writes <checkpoint>SmapsRssKb into the JSON for every snapshot."""
    return {key[:-len("SmapsRssKb")] for key in run if key.endswith("SmapsRssKb")}


def expected_artifacts(run, variant):
    """(checkpoint, artifact) pairs MemoryDiagnostics writes for this run: smaps_rollup, malloc and pmap everywhere,
    nmt in NMT runs, histo at histogram checkpoints, trim where the native heap was trimmed."""
    expected = set()
    for checkpoint in checkpoints(run):
        expected |= {(checkpoint, kind) for kind in ("smaps_rollup", "malloc", "pmap")}
        if variant == "nmt":
            expected.add((checkpoint, "nmt"))
        if checkpoint in HISTOGRAM_CHECKPOINTS:
            expected.add((checkpoint, "histo"))
        if checkpoint == "installChurnAfterTrim":
            expected.add((checkpoint, "trim"))
    return expected


PARSERS = {"smaps_rollup": parse_smaps_rollup, "malloc": parse_malloc_info, "nmt": parse_nmt,
           "histo": parse_histogram, "pmap": parse_pmap, "trim": str}


def artifact_problem(kind, text, parsed=None):
    """Why an artifact cannot be used (empty, truncated or not the expected output), or None. An unusable artifact
    would otherwise parse to zeros or empty sets and appear in the summary as a measured value."""
    if kind not in PARSERS:
        return f"has an unknown kind '{kind}'"
    if parsed is None:
        parsed = PARSERS[kind](text)
    if kind == "smaps_rollup":
        missing = {"Rss", "Anonymous", "Pss_Anon", "Pss_File"} - set(parsed)
        return f"lacks {', '.join(sorted(missing))}" if missing else None
    if kind == "malloc":
        if not text.rstrip().endswith("</malloc>"):
            return "is not a complete malloc_info document"
        return None if parsed["arenas"] > 0 and parsed["system"] > 0 else "reports no arena or no system memory"
    if kind == "nmt":
        return None if {"Total", "malloc", "Java Heap"} <= set(parsed) else "is not a complete NMT summary"
    if kind == "histo":
        if not re.search(r"^Total\s+\d+\s+\d+\s*$", text, re.M):
            return "has no Total line (truncated histogram)"
        return None if parsed else "lists no classes"
    if kind == "pmap":
        if not re.search(r"^total kB\s+\d+\s+\d+\s+\d+\s*$", text, re.M):
            return "has no total line (truncated pmap output)"
        return None if parsed["anonMappings"] > 0 and parsed["fileRssKb"] > 0 else "lists no anonymous or file mappings"
    return None if "Trim native heap" in text else "is not System.trim_native_heap output"


def load_artifacts(data, cells):
    """{(run, checkpoint, kind): parsed artifact}, each file read and parsed once; None for a JSON-only directory (no
    run has an artifact directory, e.g. the committed evidence). A partial or unusable collection exits: statistics
    computed from fewer runs than the cell reports would silently understate the variance."""
    names = [name for runs in cells.values() for name, _ in runs]
    if not any((data / name).is_dir() for name in names):
        return None
    problems = []
    artifacts = {}
    for key, runs in sorted(cells.items()):
        points = checkpoints(runs[0][1])
        if key[0] in PHASES:
            before, afters = PHASES[key[0]]
            missing = {before, next(a for a in afters if a.endswith("AfterGc"))} - points
            if missing:
                problems.append(f"{runs[0][0]}.json: no snapshot of {', '.join(sorted(missing))}")
        for name, run in runs:
            if checkpoints(run) != points:
                problems.append(f"{name}.json: checkpoints differ from {runs[0][0]}.json")
            if run.get("longMemory") is True:
                cycles = run.get("structuralCycles", 0) if key[0] == "structural" else run.get("installChurnCycles", 0)
                expected_points = (LONG_STRUCTURAL if key[0] == "structural" else LONG_INSTALL)
                required = {p for p in expected_points if int(re.search(r"\d+$", p).group()) <= cycles}
                actual = {p for p in checkpoints(run) if p in set(LONG_STRUCTURAL + LONG_INSTALL)}
                if actual != required or any(run.get(p + "ProviderThreads") != (1 if key[2] == "on" else 0)
                                             or not isinstance(run.get(p + "HeapUsedBytes"), int)
                                             or not isinstance(run.get(p + "HeapCommittedBytes"), int) for p in required):
                    problems.append(f"{name}.json: invalid long-memory checkpoints")
            for checkpoint, kind in sorted(expected_artifacts(run, key[3])):
                path = data / name / f"{checkpoint}.{kind}.txt"
                if not path.is_file():
                    problems.append(f"{name}/{checkpoint}.{kind}.txt is missing")
                    continue
                text = path.read_text(errors="replace")
                parsed = parse_pmap(text, heap_range(run)) if kind == "pmap" else PARSERS[kind](text)
                problem = artifact_problem(kind, text, parsed)
                if problem:
                    problems.append(f"{name}/{checkpoint}.{kind}.txt {problem}")
                artifacts[(name, checkpoint, kind)] = parsed
    if problems:
        exit_with_problems(f"incomplete diagnostic collection in {data}", problems)
    return artifacts


def phase_points(key, runs):
    """The phase's checkpoints this cell snapshotted, in phase order (for the delta tables)."""
    before, afters = PHASES.get(key[0], (None, ()))
    points = checkpoints(runs[0][1])
    return [c for c in (before, *afters) if c in points]


def all_points(runs, histogram=False):
    """Every checkpoint this cell snapshotted, in run order (for the absolute tables)."""
    points = checkpoints(runs[0][1])
    return [c for c in CHECKPOINT_ORDER if c in points and (not histogram or c in HISTOGRAM_CHECKPOINTS)]


def planned_runs(environment):
    """Run names the collection planned: plan=kind:nodes:reps:bridges:variants:structural:install lines written by
    run-memory-diag.sh into environment.txt."""
    names = set()
    for line in environment.split("\n"):  # records end only at \n: splitlines() would also split at \r, \x85, U+2028 ...
        if not line.startswith("plan="):
            continue
        fields = line.split("=", 1)[1].strip().split(":")
        if len(fields) != 7 or not all(f.isdigit() for f in (fields[1], fields[2], fields[5], fields[6])):
            sys.exit(f"'{line.strip()}' is not a run-memory-diag.sh plan (kind:nodes:reps:bridges:variants:structural:"
                     "install); is this an SC-01 baseline directory?")
        kind, nodes, reps, bridges, variants, _, _ = fields
        for rep in range(1, int(reps) + 1):
            for variant in variants.split(","):
                for bridge in bridges.split(","):
                    names.add(f"{kind}-n{nodes}-{bridge}-{variant}-r{rep}")
    return names


def load(data):
    environment = data / "environment.txt"
    if not environment.is_file():
        sys.exit(f"{environment} is missing; refusing to summarise a collection whose plan is unknown")
    with open(environment, newline="\n") as file:  # no universal-newline translation of \r
        planned = planned_runs(file.read())
    if not planned:
        sys.exit(f"{environment} has no plan= lines; refusing to summarise a collection whose plan is unknown")
    found = {path.stem for path in data.glob("*.json")}
    missing, unexpected = sorted(planned - found), sorted(found - planned)
    if missing or unexpected:
        problems = [f"{name}.json is missing" for name in missing] + [f"{name}.json is not in the plan" for name in unexpected]
        exit_with_problems("collection does not match the plan in environment.txt", problems)
    cells = collections.defaultdict(list)
    for name in sorted(planned):
        key, _ = cell_key(name)
        cells[key].append((name, json.loads((data / f"{name}.json").read_text())))
    return cells


def display_path(data, root=REPOSITORY):
    """data relative to root (an absolute, resolved path), or <out>/<name> outside it; control characters and backticks
    become "?"."""
    data = pathlib.Path(data).resolve()
    try:
        shown = data.relative_to(root).as_posix()
    except ValueError:
        shown = f"<out>/{data.name}"
    return UNSAFE_IN_CODE_SPAN.sub("?", shown)


def label(key):
    kind, nodes, bridge, variant = key
    return f"{kind} n={nodes} bridge {bridge} {variant}"


def summary(data):
    data = pathlib.Path(data)
    cells = load(data)
    artifacts = load_artifacts(data, cells)
    out = [f"# Memory diagnostics summary\n\nSource: `{display_path(data)}`. Values: median (min–max) over repetitions; MB = 1024 KB.\n"]

    out.append("## Phase deltas (MB, after − before)\n")
    out.append("Snapshot cost: RSS added by the diagnostic snapshots taken inside the interval (the phase's own \"before\" "
               "snapshot and any mid-phase snapshot), recorded as `<checkpoint>SnapshotRssKb`; the RSS and Anonymous "
               "deltas include it. – = not recorded by this collection.\n")
    out.append("| Cell | Runs | Phase | Point | Heap | RSS | Anonymous | Pss_File | Snapshot cost |")
    out.append("| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        phase = key[0]
        if phase not in PHASES:
            continue
        before, afters = PHASES[phase]
        for index, after in enumerate(afters):
            if not any(f"{after}SmapsRssKb" in run for _, run in runs):
                continue
            heap_key = {"structuralAfterGc": "structuralHeapDeltaBytesAfterGc", "installChurnAfterGc": "installChurnHeapDeltaBytesAfterGc"}.get(after)
            heap = fmt(field_values(runs, heap_key), 1024 * 1024) if heap_key else "–"
            row = [fmt([a - b for a, b in zip(field_values(runs, f"{after}Smaps{m}Kb"), field_values(runs, f"{before}Smaps{m}Kb"))], 1024)
                   for m in SMAPS]
            long_points = LONG_STRUCTURAL if phase == "structural" else LONG_INSTALL
            inside = [c for c in (before, *long_points, *afters[:index]) if f"{c}SmapsRssKb" in runs[0][1]]
            if all(f"{c}SnapshotRssKb" in runs[0][1] for c in inside):
                cost = fmt([sum(v) for v in zip(*(field_values(runs, f"{c}SnapshotRssKb") for c in inside))], 1024)
            else:
                cost = "–"
            out.append(f"| {label(key)} | {len(runs)} | {phase} | {after} | {heap} | " + " | ".join(row) + f" | {cost} |")

    out.append("\n## Install churn timing\n")
    out.append("| Cell | Close p50 ms | Close max ms | Ready p50 ms | Ready max ms | Provider threads at checkpoint |")
    out.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        if key[0] == "install":
            values = [fmt(field_values(runs, f)) for f in ("installChurnCloseMsP50", "installChurnCloseMsMax", "installChurnReadyMsP50", "installChurnReadyMsMax", "installChurnProviderThreadsCheckpoint")]
            out.append(f"| {label(key)} | " + " | ".join(values) + " |")

    if artifacts is None:
        out.append("\nNo raw artifacts in this directory (JSON only): allocator, NMT, histogram and pmap tables need the "
                   "<cell>/ snapshot directories of the collection.")
        return "\n".join(out) + "\n"

    out.append("\n## glibc malloc (MB) and arenas\n")
    out.append("| Cell | Checkpoint | Arenas | Held from OS | Free in allocator | In use | Direct mmap |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        if key[3] == "nmt":
            continue  # NMT adds its own malloc headers; read allocator state from the cells without it
        for checkpoint in all_points(runs):
            parsed = [artifacts[(n, checkpoint, "malloc")] for n, _ in runs]
            out.append(f"| {label(key)} | {checkpoint} | {fmt([p['arenas'] for p in parsed], digits=0)} | "
                       + " | ".join(fmt([p[f] for p in parsed], 1024 * 1024) for f in ("system", "free", "inUse", "mmap")) + " |")

    out.append("\n## NMT committed (MB): phase deltas per category\n")
    out.append("Only categories whose median delta is at least 1 MB in some cell, plus Total and malloc.\n")
    rows = []
    categories = set()
    for key, runs in sorted(cells.items()):
        if key[3] != "nmt" or key[0] not in PHASES:
            continue
        before, *afters = phase_points(key, runs)
        for after in afters:
            pairs = [(artifacts[(name, before, "nmt")], artifacts[(name, after, "nmt")]) for name, _ in runs]
            # One delta per run for every category any run reports (NMT omits categories under 1 KB).
            seen = {category for start, end in pairs for category in (*start, *end)}
            deltas = {c: [end.get(c, 0) - start.get(c, 0) for start, end in pairs] for c in seen}
            rows.append((key, after, deltas))
            categories |= {c for c, v in deltas.items() if abs(st.median(v)) >= 1024}
    columns = ["Total", "malloc"] + sorted(categories - {"Total", "malloc"})
    if rows:
        out.append("| Cell | Point | " + " | ".join(columns) + " |")
        out.append("| --- | --- |" + " ---: |" * len(columns))
        for key, after, deltas in rows:
            out.append(f"| {label(key)} | {after} | " + " | ".join(fmt(deltas.get(c, []), 1024) for c in columns) + " |")
        out.append("\nAbsolute committed at every checkpoint (MB):\n")
        out.append("| Cell | Checkpoint | " + " | ".join(columns) + " |")
        out.append("| --- | --- |" + " ---: |" * len(columns))
        for key, runs in sorted(cells.items()):
            if key[3] != "nmt":
                continue
            for checkpoint in all_points(runs):
                parsed = [artifacts[(name, checkpoint, "nmt")] for name, _ in runs]
                out.append(f"| {label(key)} | {checkpoint} | "
                           + " | ".join(fmt([p.get(c, 0) for p in parsed], 1024) for c in columns) + " |")

    out.append("\n## Retained heap by group after GC (class histogram)\n")
    out.append("| Cell | Checkpoint | Bridge MB | Bridge objects | JavaFX MB | JavaFX objects | Other MB | "
               + " | ".join(c.rsplit(".", 1)[1] for c in WATCHED_CLASSES) + " |")
    out.append("| --- | --- |" + " ---: |" * (5 + len(WATCHED_CLASSES)))
    for key, runs in sorted(cells.items()):
        if key[0] not in PHASES:
            continue
        for checkpoint in all_points(runs, histogram=True):
            parsed = [artifacts[(n, checkpoint, "histo")] for n, _ in runs]
            groups = [histogram_groups(p) for p in parsed]
            row = [fmt([g["bridge"][1] for g in groups], 1024 * 1024, 2), fmt([g["bridge"][0] for g in groups], digits=0),
                   fmt([g["javafx"][1] for g in groups], 1024 * 1024, 2), fmt([g["javafx"][0] for g in groups], digits=0),
                   fmt([g["other"][1] for g in groups], 1024 * 1024, 2)]
            row += [fmt([p.get(c, (0, 0, ""))[0] for p in parsed], digits=0) for c in WATCHED_CLASSES]
            out.append(f"| {label(key)} | {checkpoint} | " + " | ".join(row) + " |")

    out.append("\n## Largest retained-heap growth per class (bridge on, plain; after GC − before)\n")
    for key, runs in sorted(cells.items()):
        if key[2] != "on" or key[3] != "plain" or key[0] not in PHASES:
            continue
        before, afters = PHASES[key[0]]
        after = next(a for a in afters if a.endswith("AfterGc"))
        pairs = [(artifacts[(name, before, "histo")], artifacts[(name, after, "histo")]) for name, _ in runs]
        # One delta per run for every class seen in any run (0 where a run lacks it), so medians cover all repetitions.
        modules = {}
        for start, end in pairs:
            for cls, (_, _, module) in (*start.items(), *end.items()):
                modules[cls] = modules.get(cls) or module
        growth = {}
        for cls, module in modules.items():
            deltas = []
            for start, end in pairs:
                s, e = start.get(cls, (0, 0, "")), end.get(cls, (0, 0, ""))
                deltas.append((e[0] - s[0], e[1] - s[1]))
            growth[(cls, module)] = deltas
        # Ties are ordered by class name so the summary is reproducible (set iteration order is not).
        top = sorted(growth.items(), key=lambda item: (-st.median(b for _, b in item[1]), item[0]))[:15]
        out.append(f"\n{label(key)}:\n")
        out.append("| Class | Module | Δ instances | Δ KB |")
        out.append("| --- | --- | ---: | ---: |")
        for (cls, module), values in top:
            out.append(f"| `{cls}` | {module} | {fmt([i for i, _ in values], digits=0)} | {fmt([b for _, b in values], 1024)} |")

    out.append("\n## pmap: anonymous versus file-backed RSS (MB)\n")
    out.append("Java heap: anonymous mappings inside the Java heap range. \"recorded heap range\" uses the reserved range "
               "the bench read from GC.heap_info; \"legacy heuristic\" (collections without it) counts every anonymous "
               "mapping below 0x100000000000, which with compressed oops is the heap plus class space. Outside Java heap: "
               "malloc arenas, thread stacks, class space and other native mappings.\n")
    out.append("| Cell | Checkpoint | Anonymous RSS | Java heap | Outside Java heap | File RSS | Anonymous mappings | Split |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |")
    for key, runs in sorted(cells.items()):
        if key[3] == "nmt" or key[0] not in PHASES:
            continue
        for checkpoint in all_points(runs):
            parsed = [artifacts[(n, checkpoint, "pmap")] for n, _ in runs]
            methods = {(heap_range(run) or (0, 0, "unknown heap range"))[2] for _, run in runs}
            out.append(f"| {label(key)} | {checkpoint} | "
                       + " | ".join(fmt([p[f] for p in parsed], 1024) for f in ("anonRssKb", "heapAnonRssKb", "otherAnonRssKb", "fileRssKb"))
                       + f" | {fmt([p['anonMappings'] for p in parsed], digits=0)} | {', '.join(sorted(methods))} |")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: memory_diag.py <data-dir>")
    sys.stdout.write(summary(sys.argv[1]))
