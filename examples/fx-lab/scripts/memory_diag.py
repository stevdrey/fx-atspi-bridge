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
    "install": ("installChurnBefore", ("installChurnCheckpoint", "installChurnAfterGc", "installChurnAfterTrim")),
}
SMAPS = ("Rss", "Anonymous", "PssFile")
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


def read_artifact(data, cell, checkpoint, kind):
    path = data / cell / f"{checkpoint}.{kind}.txt"
    return path.read_text() if path.exists() else None


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

    out.append("\n## glibc malloc (MB) and arenas\n")
    out.append("| Cell | Checkpoint | Arenas | Held from OS | Free in allocator | In use | Direct mmap |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for key, runs in sorted(cells.items()):
        if key[3] == "nmt":
            continue  # NMT adds its own malloc headers; read allocator state from the cells without it
        before, afters = PHASES.get(key[0], (None, ()))
        for checkpoint in (before, *afters):
            parsed = [parse_malloc_info(t) for t in (read_artifact(data, n, checkpoint, "malloc") for n, _ in runs) if t]
            if parsed:
                out.append(f"| {label(key)} | {checkpoint} | {fmt([p['arenas'] for p in parsed], digits=0)} | "
                           + " | ".join(fmt([p[f] for p in parsed], 1024 * 1024) for f in ("system", "free", "inUse", "mmap")) + " |")

    out.append("\n## NMT committed (MB): phase deltas per category\n")
    out.append("Only categories whose median delta is at least 1 MB in some cell, plus Total and malloc.\n")
    rows = []
    categories = set()
    for key, runs in sorted(cells.items()):
        if key[3] != "nmt" or key[0] not in PHASES:
            continue
        before, afters = PHASES[key[0]]
        for after in afters:
            deltas = collections.defaultdict(list)
            absolute = collections.defaultdict(list)
            for name, _ in runs:
                start, end = read_artifact(data, name, before, "nmt"), read_artifact(data, name, after, "nmt")
                if not (start and end):
                    continue
                start, end = parse_nmt(start), parse_nmt(end)
                for category in end:
                    deltas[category].append(end[category] - start.get(category, 0))
                    absolute[category].append(end[category])
            if deltas:
                rows.append((key, after, deltas, absolute))
                categories |= {c for c, v in deltas.items() if abs(st.median(v)) >= 1024}
    columns = ["Total", "malloc"] + sorted(categories - {"Total", "malloc"})
    if rows:
        out.append("| Cell | Point | " + " | ".join(columns) + " |")
        out.append("| --- | --- |" + " ---: |" * len(columns))
        for key, after, deltas, _ in rows:
            out.append(f"| {label(key)} | {after} | " + " | ".join(fmt(deltas.get(c, []), 1024) for c in columns) + " |")
        out.append("\nAbsolute committed at the last point (MB):\n")
        out.append("| Cell | Point | " + " | ".join(columns) + " |")
        out.append("| --- | --- |" + " ---: |" * len(columns))
        for key, after, _, absolute in rows:
            out.append(f"| {label(key)} | {after} | " + " | ".join(fmt(absolute.get(c, []), 1024) for c in columns) + " |")

    out.append("\n## Retained heap by group after GC (class histogram)\n")
    out.append("| Cell | Checkpoint | Bridge MB | Bridge objects | JavaFX MB | JavaFX objects | Other MB | "
               + " | ".join(c.rsplit(".", 1)[1] for c in WATCHED_CLASSES) + " |")
    out.append("| --- | --- |" + " ---: |" * (5 + len(WATCHED_CLASSES)))
    for key, runs in sorted(cells.items()):
        if key[0] not in PHASES:
            continue
        before, afters = PHASES[key[0]]
        for checkpoint in (before, *afters):
            parsed = [parse_histogram(t) for t in (read_artifact(data, n, checkpoint, "histo") for n, _ in runs) if t]
            if not parsed:
                continue
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
        after = next((a for a in afters if a.endswith("AfterGc")), None)
        growth = collections.defaultdict(list)
        for name, _ in runs:
            start, end = read_artifact(data, name, before, "histo"), read_artifact(data, name, after, "histo")
            if not (start and end):
                continue
            start, end = parse_histogram(start), parse_histogram(end)
            for cls in set(start) | set(end):
                s, e = start.get(cls, (0, 0, "")), end.get(cls, (0, 0, ""))
                growth[(cls, e[2] or s[2])].append((e[0] - s[0], e[1] - s[1]))
        if not growth:
            continue
        top = sorted(growth.items(), key=lambda item: -st.median(b for _, b in item[1]))[:15]
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
        before, afters = PHASES[key[0]]
        for checkpoint in (before, *afters):
            parsed = [parse_pmap(t) for t in (read_artifact(data, n, checkpoint, "pmap") for n, _ in runs) if t]
            if parsed:
                out.append(f"| {label(key)} | {checkpoint} | "
                           + " | ".join(fmt([p[f] for p in parsed], 1024) for f in ("anonRssKb", "lowAnonRssKb", "highAnonRssKb", "fileRssKb"))
                           + f" | {fmt([p['anonMappings'] for p in parsed], digits=0)} |")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: memory_diag.py <data-dir>")
    sys.stdout.write(summary(sys.argv[1]))
