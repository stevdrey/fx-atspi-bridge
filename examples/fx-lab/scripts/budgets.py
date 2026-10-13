#!/usr/bin/env python3
"""Summarise SC-01 run JSON into Markdown tables and derive provisional budgets.

Usage: budgets.py <data-dir>
Budget rule (single, stated once): ceil(worst observed bridge-on value across repetitions * 1.25).
Delta metrics that are expressed in MB and can be zero or negative after GC (install-churn and structural heap
deltas) use a floor of 1 MB: max(1, ceil(worst * 1.25)), so GC noise is not reported as a regression.
"""
import glob
import json
import math
import pathlib
import statistics as st
import sys

DATA = sys.argv[1] if len(sys.argv) > 1 else "docs/verification/data/issue-1"
DIRTY = (1, 10, 100)
MAX_VALID_PULSE_MS = 900  # keep in sync with BenchApp
MAX_CADENCE_MS = 50


def read_plan():
    """plan=nodes:reps:dirty-list:structural-cycles lines written by run-baseline.sh."""
    plan = {}
    for line in open(f"{DATA}/environment.txt"):
        if line.startswith("plan="):
            nodes, reps, dirties, structural = line.strip().split("=", 1)[1].split(":")
            plan[int(nodes)] = (int(reps), tuple(int(d) for d in dirties.split(",")), int(structural))
    if not plan:
        sys.exit("environment.txt has no plan= lines; refusing to summarise an unknown collection")
    return plan


PLAN = read_plan()
SIZES = tuple(sorted(PLAN))
EXPECTED = sum(reps * len(dirties) * 2 for reps, dirties, _ in PLAN.values())
FOUND = len(glob.glob(f"{DATA}/n*-d*-*-r*.json"))
if FOUND != EXPECTED:
    sys.exit(f"expected {EXPECTED} run files from the recorded plan, found {FOUND}: mixed collection?")


WAIVER = pathlib.Path(DATA) / "VALIDATION-NOTE.txt"


def validate_runs():
    """Reject evidence from throttled displays: a ~1 Hz pulse shows up as ~1000 ms samples or a slow idle cadence.

    latencyMaxMs is mandatory (with 200 samples p99 cannot see the two largest pulses). A collection that predates the
    field is accepted only when a human-written VALIDATION-NOTE.txt waiver sits next to the data; a warning is printed.
    """
    bad, legacy = [], []
    for f in sorted(glob.glob(f"{DATA}/n*-d*-*-r*.json")):
        r = json.load(open(f))
        if "latencyMaxMs" not in r:
            legacy.append(f)
        elif r["latencyMaxMs"] >= MAX_VALID_PULSE_MS:
            bad.append(f)
        if r["latencyP99Ms"] >= MAX_VALID_PULSE_MS or r.get("pulseCadenceP50Ms", 0) > MAX_CADENCE_MS:
            bad.append(f)
    if bad:
        sys.exit("invalid runs (throttled display or stalls), refusing to summarise: " + ", ".join(sorted(set(bad))))
    if legacy:
        if not WAIVER.exists():
            sys.exit(f"{len(legacy)} runs lack latencyMaxMs and there is no {WAIVER.name} waiver; re-collect the baseline")
        print(f"WARNING: legacy validation (p99/cadence only) for {len(legacy)} runs without latencyMaxMs; see {WAIVER.name}\n")


validate_runs()


def dirties(n):
    return PLAN[n][1]


def load(n, d, bridge):
    rs = [json.load(open(f)) for f in sorted(glob.glob(f"{DATA}/n{n}-d{d}-{bridge}-r*.json"))]
    if len(rs) != PLAN[n][0]:
        sys.exit(f"cell n={n} d={d} {bridge}: {len(rs)} runs, expected {PLAN[n][0]}")
    return rs


def med(rs, k):
    return st.median(r[k] for r in rs)


def rng(rs, k, scale=1.0, digits=1):
    v = [r[k] / scale for r in rs]
    return f"{st.median(v):.{digits}f} ({min(v):.{digits}f}–{max(v):.{digits}f})"


def budget(rs, k, scale=1.0):
    return math.ceil(max(r[k] for r in rs) / scale * 1.25)


def main():
    print("### FX latency and allocation\n")
    print("| Nodes | Dirty | Bridge | Runs | p50 (ms) | p99 (ms) | Alloc (KB/sample) |\n| ---: | ---: | --- | ---: | ---: | --- | ---: |")
    for n in SIZES:
        for d in dirties(n):
            for b in ("off", "on"):
                rs = load(n, d, b)
                print(f"| {n:,} | {d} | {b} | {len(rs)} | {med(rs, 'latencyP50Ms'):.1f} | {rng(rs, 'latencyP99Ms')} | {med(rs, 'allocatedBytesPerSample') / 1024:,.0f} |")
    print("\n### Idle and memory (dirty=10)\n")
    print("| Nodes | Bridge | Runs | Idle CPU % | Switches/s | Heap MB | RSS MB |\n| ---: | --- | ---: | --- | ---: | ---: | ---: |")
    for n in SIZES:
        for b in ("off", "on"):
            rs = load(n, 10, b)
            print(f"| {n:,} | {b} | {len(rs)} | {rng(rs, 'idleCpuPercent')} | {med(rs, 'idleVoluntarySwitchesPerSecond'):.0f} | {med(rs, 'heapAfterIdleGcBytes') / 2**20:.1f} | {med(rs, 'rssAfterIdleKb') / 1024:.0f} |")
    print("\n### Structural churn (create/remove + scene-swap cycles, see cycles column; checkpoint 2 s after the loop, before GC)\n")
    print("| Nodes | Bridge | Runs | Cycles | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |\n| ---: | --- | ---: | ---: | --- | --- | --- | --- |")
    for n in SIZES:
        for b in ("off", "on"):
            rs = load(n, 10, b)
            print(f"| {n:,} | {b} | {len(rs)} | {rs[0]['structuralCycles']} | {rng(rs, 'structuralRssDeltaKbCheckpoint', 1024)} | {rng(rs, 'structuralRssDeltaKbAfterGc', 1024)} | {rng(rs, 'structuralHeapDeltaBytesAfterGc', 2**20, 2)} | {rng(rs, 'structuralThreadDeltaCheckpoint', 1, 0)} |")
    print("\n### Bridge install/close churn (100 cycles, bridge on)\n")
    print("| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |\n| ---: | --- | --- | ---: | --- | --- | --- |")
    for n in SIZES:
        rs = load(n, 10, "on")
        print(f"| {n:,} | {rng(rs, 'installChurnCheckpointSeconds', 1, 2)} | {rng(rs, 'installChurnProviderThreadsCheckpoint', 1, 0)} | {max(r['installChurnProviderThreadsAfterDrain'] for r in rs)} | {rng(rs, 'installChurnDrainSeconds', 1, 3)} | {rng(rs, 'installChurnRssDeltaKbCheckpoint', 1024, 0)} | {rng(rs, 'installChurnHeapDeltaBytesAfterGc', 2**20, 2)} |")
    print("\n### Budgets: ceil(worst observed bridge-on value x 1.25)\n")
    print("| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |\n| --- | --- | --- | --- |")
    rows = {
        "Latency p99, 1 / 10 / 100 dirty (ms)": lambda n: " / ".join(str(budget(load(n, d, "on"), "latencyP99Ms")) if d in dirties(n) else "n/a" for d in DIRTY),
        "Idle CPU (% of one core)": lambda n: str(budget(load(n, 10, "on"), "idleCpuPercent")),
        "Alloc, 1 / 10 / 100 dirty (KB/sample)": lambda n: " / ".join(f"{budget(load(n, d, 'on'), 'allocatedBytesPerSample', 1024):,}" if d in dirties(n) else "n/a" for d in DIRTY),
        "RSS after idle (MB)": lambda n: str(budget(load(n, 10, "on"), "rssAfterIdleKb", 1024)),
        "Install churn heap delta after GC (MB)": lambda n: str(max(1, budget(load(n, 10, "on"), "installChurnHeapDeltaBytesAfterGc", 2**20))),
        "Provider threads at install-churn checkpoint": lambda n: str(budget(load(n, 10, "on"), "installChurnProviderThreadsCheckpoint")),
        "Provider threads after drain": lambda n: str(budget(load(n, 10, "on"), "installChurnProviderThreadsAfterDrain")),
        "Structural heap delta after GC (MB)": lambda n: str(max(1, budget(load(n, 10, "on"), "structuralHeapDeltaBytesAfterGc", 2**20))),
    }
    for name, fn in rows.items():
        print(f"| {name} | " + " | ".join(fn(n) for n in SIZES) + " |")


if __name__ == "__main__":
    main()
