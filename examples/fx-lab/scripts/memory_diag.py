#!/usr/bin/env python3
"""Summarise an Issue 17 memory collection (run-memory-diag.sh) into Markdown tables.

Usage: memory_diag.py <data-dir>
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
WATCHED_CLASSES = ("javafx.scene.control.TextField", "javafx.scene.control.Button", "javafx.scene.Scene",
                   "javafx.scene.layout.VBox")
# Phase -> (checkpoint before, checkpoints after) whose differences are reported.
PHASES = {
    "structural": ("structuralBefore", ("structuralCheckpoint", "structuralAfterGc")),
    "install": ("installChurnBefore", ("installChurnCheckpoint", "installChurnAfterGc", "installChurnAfterDrain",
                                       "installChurnAfterTrim")),
}
# Every checkpoint BenchApp can snapshot, in run order; the absolute tables list each one a cell recorded.
CHECKPOINT_ORDER = ("afterWarmupGc", "afterIdleGc", "structuralBefore", "structuralCheckpoint", "structuralAfterGc",
                    "installChurnBefore", "installChurnCheckpoint", "installChurnAfterGc", "installChurnAfterDrain",
                    "installChurnAfterTrim")
SMAPS = ("Rss", "Anonymous", "PssFile")
# Checkpoints where BenchApp takes a class histogram (MemoryDiagnostics.snapshot(..., histogram=true)).
HISTOGRAM_CHECKPOINTS = frozenset({"afterWarmupGc", "afterIdleGc", "structuralBefore", "structuralAfterGc",
                                   "installChurnBefore", "installChurnAfterGc"})
MAX_REPORTED_PROBLEMS = 20
# With compressed oops HotSpot reserves the Java heap and class space below this address, while glibc arenas, thread
# stacks and other native mappings live near the top of the address space. A heuristic: memory_diag.py reports it as
# "JVM low range" and the verification record cross-checks it against NMT's Java Heap category.
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


def parse_pmap(text):
    """RSS kB of anonymous versus file-backed mappings, the anonymous RSS below LOW_RANGE_LIMIT (Java heap and class
    space with compressed oops) and the anonymous mapping count from `pmap -x`."""
    anon = file_backed = anon_count = low = 0
    for line in text.splitlines():
        match = re.match(r"^[0-9a-f]{8,16}\s+\d+\s+(\d+)\s+\d+\s+\S+\s+(.*)$", line)
        if not match:
            continue
        rss, mapping = int(match.group(1)), match.group(2).strip()
        if mapping.startswith("[") and mapping.endswith("]"):
            anon += rss
            anon_count += 1
            if int(line.split()[0], 16) < LOW_RANGE_LIMIT:
                low += rss
        else:
            file_backed += rss
    return {"anonRssKb": anon, "fileRssKb": file_backed, "anonMappings": anon_count, "lowAnonRssKb": low,
            "highAnonRssKb": anon - low}


def cell_key(name):
    match = CELL.match(name)
    if not match:
        return None, None
    return (match["kind"], int(match["nodes"]), match["bridge"], match["variant"]), int(match["rep"])


# --- summary -------------------------------------------------------------------------------------------------------

def fmt(values, scale=1.0, digits=1):
    values = [v / scale for v in values if v is not None]
    if not values:
        return "–"
    if len(values) == 1:
        return f"{values[0]:.{digits}f}"
    return f"{st.median(values):.{digits}f} ({min(values):.{digits}f}–{max(values):.{digits}f})"


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


def artifact_problem(kind, text):
    """Why an artifact cannot be used (empty, truncated or not the expected output), or None. An unusable artifact
    would otherwise parse to zeros or empty sets and appear in the summary as a measured value."""
    if kind == "smaps_rollup":
        missing = {"Rss", "Anonymous", "Pss_Anon", "Pss_File"} - set(parse_smaps_rollup(text))
        return f"lacks {', '.join(sorted(missing))}" if missing else None
    if kind == "malloc":
        if not text.rstrip().endswith("</malloc>"):
            return "is not a complete malloc_info document"
        parsed = parse_malloc_info(text)
        return None if parsed["arenas"] > 0 and parsed["system"] > 0 else "reports no arena or no system memory"
    if kind == "nmt":
        parsed = parse_nmt(text)
        return None if {"Total", "malloc", "Java Heap"} <= set(parsed) else "is not a complete NMT summary"
    if kind == "histo":
        if not re.search(r"^Total\s+\d+\s+\d+\s*$", text, re.M):
            return "has no Total line (truncated histogram)"
        return None if parse_histogram(text) else "lists no classes"
    if kind == "pmap":
        if not re.search(r"^total kB\s+\d+\s+\d+\s+\d+\s*$", text, re.M):
            return "has no total line (truncated pmap output)"
        parsed = parse_pmap(text)
        return None if parsed["anonMappings"] > 0 and parsed["fileRssKb"] > 0 else "lists no anonymous or file mappings"
    if kind == "trim":
        return None if "Trim native heap" in text else "is not System.trim_native_heap output"
    return f"has an unknown kind '{kind}'"


def validate(data, cells):
    """True when the raw artifacts are present and complete for every run, False for a JSON-only directory (no run has
    an artifact directory, e.g. the committed evidence). A partial collection exits: statistics computed from fewer
    runs than the cell reports would silently understate the variance."""
    names = [name for runs in cells.values() for name, _ in runs]
    if not any((data / name).is_dir() for name in names):
        return False
    problems = []
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
            for checkpoint, kind in sorted(expected_artifacts(run, key[3])):
                path = data / name / f"{checkpoint}.{kind}.txt"
                if not path.is_file():
                    problems.append(f"{name}/{checkpoint}.{kind}.txt is missing")
                    continue
                problem = artifact_problem(kind, path.read_text(errors="replace"))
                if problem:
                    problems.append(f"{name}/{checkpoint}.{kind}.txt {problem}")
    if problems:
        shown = problems[:MAX_REPORTED_PROBLEMS]
        more = len(problems) - len(shown)
        sys.exit("incomplete diagnostic collection in " + str(data) + ":\n  " + "\n  ".join(shown)
                 + (f"\n  ... and {more} more" if more else ""))
    return True


def read_artifact(data, cell, checkpoint, kind):
    """Only called for artifacts validate() has required, so a missing file is a bug, not a gap to skip."""
    return (data / cell / f"{checkpoint}.{kind}.txt").read_text()


def phase_points(key, runs):
    """The phase's checkpoints this cell snapshotted, in phase order (for the delta tables)."""
    before, afters = PHASES.get(key[0], (None, ()))
    points = checkpoints(runs[0][1])
    return [c for c in (before, *afters) if c in points]


def all_points(runs, histogram=False):
    """Every checkpoint this cell snapshotted, in run order (for the absolute tables)."""
    points = checkpoints(runs[0][1])
    return [c for c in CHECKPOINT_ORDER if c in points and (not histogram or c in HISTOGRAM_CHECKPOINTS)]


def load(data):
    cells = collections.defaultdict(list)
    for path in sorted(data.glob("*.json")):
        key, _ = cell_key(path.stem)
        if key:
            cells[key].append((path.stem, json.loads(path.read_text())))
    if not cells:
        sys.exit(f"no <kind>-n<nodes>-<bridge>-<variant>-r<rep>.json runs in {data}")
    return cells


def label(key):
    kind, nodes, bridge, variant = key
    return f"{kind} n={nodes} bridge {bridge} {variant}"


def summary(data):
    data = pathlib.Path(data)
    cells = load(data)
    raw = validate(data, cells)
    out = [f"# Memory diagnostics summary\n\nSource: `{data}`. Values: median (min–max) over repetitions; MB = 1024 KB.\n"]

    out.append("## Phase deltas (MB, after − before)\n")
    out.append("| Cell | Runs | Phase | Point | Heap | RSS | Anonymous | Pss_File |")
    out.append("| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        phase = key[0]
        if phase not in PHASES:
            continue
        before, afters = PHASES[phase]
        for after in afters:
            if f"{after}SmapsRssKb" not in runs[0][1]:
                continue
            heap_key = {"structuralAfterGc": "structuralHeapDeltaBytesAfterGc", "installChurnAfterGc": "installChurnHeapDeltaBytesAfterGc"}.get(after)
            heap = fmt([r.get(heap_key) for _, r in runs], 1024 * 1024) if heap_key else "–"
            row = [fmt([r[f"{after}Smaps{m}Kb"] - r[f"{before}Smaps{m}Kb"] for _, r in runs], 1024) for m in SMAPS]
            out.append(f"| {label(key)} | {len(runs)} | {phase} | {after} | {heap} | " + " | ".join(row) + " |")

    out.append("\n## Install churn timing\n")
    out.append("| Cell | Close p50 ms | Close max ms | Ready p50 ms | Ready max ms | Provider threads at checkpoint |")
    out.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        if key[0] == "install":
            values = [fmt([r.get(f) for _, r in runs]) for f in ("installChurnCloseMsP50", "installChurnCloseMsMax", "installChurnReadyMsP50", "installChurnReadyMsMax", "installChurnProviderThreadsCheckpoint")]
            out.append(f"| {label(key)} | " + " | ".join(values) + " |")

    if not raw:
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
            parsed = [parse_malloc_info(read_artifact(data, n, checkpoint, "malloc")) for n, _ in runs]
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
            pairs = [(parse_nmt(read_artifact(data, name, before, "nmt")), parse_nmt(read_artifact(data, name, after, "nmt")))
                     for name, _ in runs]
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
                parsed = [parse_nmt(read_artifact(data, name, checkpoint, "nmt")) for name, _ in runs]
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
            parsed = [parse_histogram(read_artifact(data, n, checkpoint, "histo")) for n, _ in runs]
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
        pairs = [(parse_histogram(read_artifact(data, name, before, "histo")),
                  parse_histogram(read_artifact(data, name, after, "histo"))) for name, _ in runs]
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
    out.append("JVM low range: anonymous mappings below 0x100000000000 (Java heap and class space with compressed oops); "
               "native range: everything above (malloc arenas, thread stacks, other native mappings).\n")
    out.append("| Cell | Checkpoint | Anonymous RSS | JVM low range | Native range | File RSS | Anonymous mappings |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        if key[3] == "nmt" or key[0] not in PHASES:
            continue
        for checkpoint in all_points(runs):
            parsed = [parse_pmap(read_artifact(data, n, checkpoint, "pmap")) for n, _ in runs]
            out.append(f"| {label(key)} | {checkpoint} | "
                       + " | ".join(fmt([p[f] for p in parsed], 1024) for f in ("anonRssKb", "lowAnonRssKb", "highAnonRssKb", "fileRssKb"))
                       + f" | {fmt([p['anonMappings'] for p in parsed], digits=0)} |")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: memory_diag.py <data-dir>")
    sys.stdout.write(summary(sys.argv[1]))
