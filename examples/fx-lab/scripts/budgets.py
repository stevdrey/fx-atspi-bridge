#!/usr/bin/env python3
"""Summarise SC-01 run JSON into Markdown tables and derive provisional budgets.

Usage: budgets.py <data-dir>
Budget rule (single, stated once): ceil(worst observed bridge-on value across repetitions * 1.25).
Delta metrics that are expressed in MB and can be zero or negative after GC (install-churn and structural heap
deltas) use a floor of 1 MB: max(1, ceil(worst * 1.25)), so GC noise is not reported as a regression.
"""
import glob
import hashlib
import json
import math
import pathlib
import statistics as st
import sys

DATA = sys.argv[1] if len(sys.argv) > 1 else "docs/verification/data/issue-1"
DIRTY = (1, 10, 100)


def read_limits():
    """baseline.conf is the single source of the validity limits (BenchApp reads the same file via --limits=)."""
    limits = {}
    for line in open(pathlib.Path(__file__).with_name("baseline.conf")):
        line = line.strip()
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            limits[key.strip()] = float(value)
    return limits


LIMITS = read_limits()
MAX_VALID_PULSE_MS = LIMITS["MAX_VALID_PULSE_MS"]
MAX_CADENCE_MS = LIMITS["MAX_CADENCE_MS"]
MAX_STRUCTURAL_S_PER_CYCLE = LIMITS["MAX_STRUCTURAL_S_PER_CYCLE"]


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


WAIVER_MANIFEST = pathlib.Path(DATA) / "legacy-waiver.sha256"


def environment_value(key):
    for line in open(f"{DATA}/environment.txt"):
        if line.startswith(key + "="):
            return line.strip().split("=", 1)[1]
    return None


def read_waiver():
    """legacy-waiver.sha256: first line '# git=<sha>', then '<sha256>  <file name>' for every file it covers."""
    if not WAIVER_MANIFEST.exists():
        return None, {}
    lines = WAIVER_MANIFEST.read_text().splitlines()
    git = lines[0].split("git=", 1)[1].strip() if lines and lines[0].startswith("# git=") else None
    entries = {}
    for line in lines[1:]:
        if line.strip() and not line.startswith("#"):
            digest, name = line.split(None, 1)
            entries[name.strip()] = digest
    return git, entries


def validate_runs():
    """Reject evidence from throttled displays and runs without the current validity markers.

    latencyMaxMs is mandatory (with 200 samples p99 cannot see the two largest pulses). The only exception is the
    retained pre-marker collection, and only through the manifest legacy-waiver.sha256: a file lacking latencyMaxMs is
    accepted only if it is listed there with an identical SHA-256 and environment.txt records the manifest's commit.
    Deleting a field, copying or editing a file therefore cannot turn a current run into a "legacy" one.
    """
    bad, legacy, missing_cadence, missing_markers = [], [], [], []
    for f in sorted(glob.glob(f"{DATA}/n*-d*-*-r*.json")):
        r = json.load(open(f))
        if "latencyMaxMs" not in r:
            legacy.append(f)
        elif r["latencyMaxMs"] >= MAX_VALID_PULSE_MS:
            bad.append(f)
        if "pulseCadenceP50Ms" not in r:
            missing_cadence.append(f)
        elif r["pulseCadenceP50Ms"] > MAX_CADENCE_MS:
            bad.append(f)
        if r["latencyP99Ms"] >= MAX_VALID_PULSE_MS:
            bad.append(f)
        if r.get("idleEdgePulseMaxMs", 0) >= MAX_VALID_PULSE_MS:
            bad.append(f)
        is_legacy = "latencyMaxMs" not in r
        # Current harness markers: display unthrottled around the idle window, bridge ready and provider alive.
        if not is_legacy and r.get("allocationMethod") != "total":
            missing_markers.append(f"{f} (allocationMethod=total)")
        if not is_legacy and r.get("idleSeconds", 0) > 0 and "idleEdgePulseMaxMs" not in r:
            missing_markers.append(f"{f} (idleEdgePulseMaxMs)")
        if r.get("structuralCycles") and not is_legacy and "structuralElapsedSeconds" not in r:
            missing_markers.append(f"{f} (structuralElapsedSeconds)")
        if r.get("bridge") == "on":
            if r.get("bridgeReady") is not True:
                missing_markers.append(f"{f} (bridgeReady)")
            if not is_legacy and r.get("providerAliveAtEnd") is not True:
                missing_markers.append(f"{f} (providerAliveAtEnd)")
            if r.get("installChurnCycles") and (r.get("installChurnFinalGenerationReady") is not True
                                               or r.get("installChurnProviderErrors") != 0):
                missing_markers.append(f"{f} (installChurnFinalGenerationReady/installChurnProviderErrors)")
        elif any(k in r for k in ("bridgeReady", "providerAliveAtEnd", "installChurnCycles")):
            bad.append(f)  # a bridge-off run must not carry bridge-on markers
        # The structural phase directly follows the idle phase: a cycle is 3 pulse waits plus 0.75 s of holds, so a 1 Hz
        # display needs >= 3.75 s per cycle; the limit (baseline.conf) sits between that and the slowest legitimate cell.
        if r.get("structuralCycles") and r.get("structuralElapsedSeconds", 0) / r["structuralCycles"] >= MAX_STRUCTURAL_S_PER_CYCLE:
            bad.append(f)
    if missing_cadence:
        sys.exit("runs without pulseCadenceP50Ms (no startup display-cadence evidence): " + ", ".join(missing_cadence))
    if missing_markers:
        sys.exit("runs without the required validity markers: " + ", ".join(missing_markers))
    if bad:
        sys.exit("invalid runs (throttled display or stalls), refusing to summarise: " + ", ".join(sorted(set(bad))))
    if legacy:
        waiver_git, entries = read_waiver()
        if waiver_git is None or waiver_git != environment_value("git"):
            sys.exit(f"{len(legacy)} runs lack latencyMaxMs and {WAIVER_MANIFEST.name} is missing or was made for another commit; re-collect the baseline")
        for f in legacy:
            name = pathlib.Path(f).name
            if entries.get(name) != hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest():
                sys.exit(f"{name} lacks latencyMaxMs and is not covered, byte for byte, by {WAIVER_MANIFEST.name}")
        print(f"WARNING: legacy validation (p99/cadence only) for {len(legacy)} runs without latencyMaxMs; see {WAIVER_MANIFEST.name} and VALIDATION-NOTE.txt\n")
    retries = pathlib.Path(DATA) / "retries.txt"
    if retries.exists():
        print(f"NOTE: {len(retries.read_text().splitlines())} invalid attempt(s) were retried during this collection; see {retries.name}\n")


validate_runs()


# The retained collection (profile=quick, 031f25c) predates the workload= line; this is its only legacy fallback.
LEGACY_WORKLOADS = {"quick": {"warmup": 50, "samples": 200, "idle": 10, "installChurn": 100}}


def read_workload():
    """workload=warmup:W,samples:S,idle:I,installChurn:C written by run-baseline.sh."""
    value = environment_value("workload")
    if value is not None:
        try:
            parsed = {k: int(v) for k, v in (item.split(":", 1) for item in value.split(","))}
            if set(parsed) == {"warmup", "samples", "idle", "installChurn"}:
                return parsed
        except ValueError:
            pass
        sys.exit(f"malformed workload= line in environment.txt: {value!r}")
    profile = environment_value("profile")
    if profile in LEGACY_WORKLOADS and WAIVER_MANIFEST.exists():
        return LEGACY_WORKLOADS[profile]
    sys.exit("environment.txt has no workload= line (and no legacy waiver for its profile); refusing to summarise an unknown workload")


WORKLOAD = read_workload()


def expect_workload(f, r, n, d, bridge):
    """Every recorded workload field must match the selected profile and plan, not only the cell identity."""
    structural = PLAN[n][2]
    want = {"warmup": WORKLOAD["warmup"], "samples": WORKLOAD["samples"],
            "idleSeconds": WORKLOAD["idle"] if d == 10 else 0}
    if d == 10:
        want["structuralCycles"] = structural
    if d == 10 and bridge == "on":
        want["installChurnCycles"] = WORKLOAD["installChurn"]
    got = {k: r.get(k) for k in want}
    extra = [k for k in ("structuralCycles",) if k in r and k not in want]
    extra += [k for k in ("installChurnCycles",) if k in r and k not in want]
    if got != want or extra:
        sys.exit(f"{f}: workload {got} (unexpected fields: {extra}) differs from the recorded profile/plan {want}")


def dirties(n):
    return PLAN[n][1]


def load(n, d, bridge):
    files = sorted(glob.glob(f"{DATA}/n{n}-d{d}-{bridge}-r*.json"))
    rs = [json.load(open(f)) for f in files]
    for f, r in zip(files, rs):
        if (r.get("nodes"), r.get("dirty"), r.get("bridge")) != (n, d, bridge):
            sys.exit(f"{f}: embedded cell (nodes={r.get('nodes')}, dirty={r.get('dirty')}, bridge={r.get('bridge')}) "
                     f"does not match its name (nodes={n}, dirty={d}, bridge={bridge}); renamed or swapped file?")
        expect_workload(f, r, n, d, bridge)
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
    print(f"\n### Bridge install/close churn ({WORKLOAD['installChurn']} cycles, bridge on)\n")
    print("| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |\n| ---: | --- | --- | ---: | --- | --- | --- |")
    for n in SIZES:
        rs = load(n, 10, "on")
        print(f"| {n:,} | {rng(rs, 'installChurnCheckpointSeconds', 1, 2)} | {rng(rs, 'installChurnProviderThreadsCheckpoint', 1, 0)} | {max(r['installChurnProviderThreadsAfterDrain'] for r in rs)} | {rng(rs, 'installChurnDrainSeconds', 1, 3)} | {rng(rs, 'installChurnRssDeltaKbCheckpoint', 1024, 0)} | {rng(rs, 'installChurnHeapDeltaBytesAfterGc', 2**20, 2)} |")
    print("\n### Budgets: ceil(worst observed bridge-on value x 1.25)\n")
    print("| Metric | " + " | ".join(f"{n:,} nodes" for n in SIZES) + " |\n| --- | " + " | ".join("---" for _ in SIZES) + " |")
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
