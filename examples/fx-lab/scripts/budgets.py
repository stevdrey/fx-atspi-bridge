#!/usr/bin/env python3
"""Summarise SC-01 run JSON into Markdown tables and derive provisional budgets.

Usage: budgets.py <data-dir>
Budget rule (single, stated once): ceil(worst observed bridge-on value across repetitions * 1.25).
"""
import glob
import json
import math
import statistics as st
import sys

DATA = sys.argv[1] if len(sys.argv) > 1 else "docs/verification/data/issue-1"
SIZES = (100, 1000, 10000)
DIRTY = (1, 10, 100)


def expected_reps():
    for line in open(f"{DATA}/environment.txt"):
        if line.startswith("reps="):
            return int(line.split("=", 1)[1])
    sys.exit("environment.txt has no reps= line; refusing to summarise an unknown collection")


REPS = expected_reps()
TOTAL = len(SIZES) * len(DIRTY) * 2 * REPS
if len(glob.glob(f"{DATA}/n*-d*-*-r*.json")) != TOTAL:
    sys.exit(f"expected {TOTAL} run files for reps={REPS}, found {len(glob.glob(f'{DATA}/n*-d*-*-r*.json'))}: mixed collection?")


def load(n, d, bridge):
    rs = [json.load(open(f)) for f in sorted(glob.glob(f"{DATA}/n{n}-d{d}-{bridge}-r*.json"))]
    if len(rs) != REPS:
        sys.exit(f"cell n={n} d={d} {bridge}: {len(rs)} runs, expected {REPS}")
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
        for d in DIRTY:
            for b in ("off", "on"):
                rs = load(n, d, b)
                print(f"| {n:,} | {d} | {b} | {len(rs)} | {med(rs, 'latencyP50Ms'):.1f} | {rng(rs, 'latencyP99Ms')} | {med(rs, 'allocatedBytesPerSample') / 1024:,.0f} |")
    print("\n### Idle and memory (dirty=10)\n")
    print("| Nodes | Bridge | Idle CPU % | Switches/s | Heap MB | RSS MB |\n| ---: | --- | --- | ---: | ---: | ---: |")
    for n in SIZES:
        for b in ("off", "on"):
            rs = load(n, 10, b)
            print(f"| {n:,} | {b} | {rng(rs, 'idleCpuPercent')} | {med(rs, 'idleVoluntarySwitchesPerSecond'):.0f} | {med(rs, 'heapAfterIdleGcBytes') / 2**20:.1f} | {med(rs, 'rssAfterIdleKb') / 1024:.0f} |")
    print("\n### Structural churn (100 create/remove + scene-swap cycles; checkpoint 2 s after the loop, before GC)\n")
    print("| Nodes | Bridge | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |\n| ---: | --- | --- | --- | --- | --- |")
    for n in SIZES:
        for b in ("off", "on"):
            rs = load(n, 10, b)
            print(f"| {n:,} | {b} | {rng(rs, 'structuralRssDeltaKbCheckpoint', 1024)} | {rng(rs, 'structuralRssDeltaKbAfterGc', 1024)} | {rng(rs, 'structuralHeapDeltaBytesAfterGc', 2**20, 2)} | {rng(rs, 'structuralThreadDeltaCheckpoint', 1, 0)} |")
    print("\n### Bridge install/close churn (100 cycles, bridge on)\n")
    print("| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |\n| ---: | --- | --- | ---: | --- | --- | --- |")
    for n in SIZES:
        rs = load(n, 10, "on")
        print(f"| {n:,} | {rng(rs, 'installChurnCheckpointSeconds', 1, 2)} | {rng(rs, 'installChurnProviderThreadsCheckpoint', 1, 0)} | {max(r['installChurnProviderThreadsAfterDrain'] for r in rs)} | {rng(rs, 'installChurnDrainSeconds', 1, 3)} | {rng(rs, 'installChurnRssDeltaKbCheckpoint', 1024, 0)} | {rng(rs, 'installChurnHeapDeltaBytesAfterGc', 2**20, 2)} |")
    print("\n### Budgets: ceil(worst observed bridge-on value x 1.25)\n")
    print("| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |\n| --- | --- | --- | --- |")
    rows = {
        "Latency p99, 1 / 10 / 100 dirty (ms)": lambda n: " / ".join(str(budget(load(n, d, "on"), "latencyP99Ms")) for d in DIRTY),
        "Idle CPU (% of one core)": lambda n: str(budget(load(n, 10, "on"), "idleCpuPercent")),
        "Alloc, 1 / 10 / 100 dirty (KB/sample)": lambda n: " / ".join(f"{budget(load(n, d, 'on'), 'allocatedBytesPerSample', 1024):,}" for d in DIRTY),
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
