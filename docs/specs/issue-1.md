# Spec Context — Issue 1 (SC-01)

## Context and source evidence

- Issue: https://github.com/stevdrey/fx-atspi-bridge/issues/1
- Baseline: `main` at 525afca (PR #11).
- Before this issue, only a CI template (`ci/github-actions-build.yml.example`) existed. No workflow built the project, and no repeatable measurement existed.
- Source under measurement (not modified here): `FxAtSpiBridge` (100 ms `AnimationTimer` scan), `AtSpiProvider` (5 ms GLib poll on `fx-atspi-provider`, instance-wide monitor). ADR 0001 lists these as costs to *measure*, not fix, in SC-01.
- Constraints: ADR 0001, `docs/security/security-invariants.md` (invariant 10: minimal CI permissions, pinned actions), `docs/verification/README.md` (paired runs, no invented thresholds, Xvfb is not desktop QA).

## Goal and non-goals

Goal:
1. Active Linux/JDK 27 CI with read-only permissions and SHA-pinned actions.
2. A repeatable measurement harness and documented methodology for heap, process memory, FX latency and idle CPU/wakeups, as paired no-bridge/bridge runs.
3. Recorded results on a real Fedora/GNOME/Wayland session, with an independent AT-SPI client, or an explicit statement that evidence is missing.
4. Initial budgets derived from those measurements and an exact compatibility matrix.

Non-goals: production refactor, Maven Central release, desktop claims inferred from Xvfb, MCP.

## Contracts and design

- CI: `.github/workflows/build.yml`; triggers `pull_request` and `push` to `main`; `contents: read`; Zulu 27 (Azul publishes 27 GA); runs `./gradlew check generatePomFileForMavenJavaPublication --no-daemon`.
- Harness: `BenchApp` in `examples/fx-lab` (no library change). One JVM per cell (`--bridge=on|off`, `--nodes`), so runs are isolated and paired. Output is one JSON object per run; values contain no UI text.
  - Tree: N buttons (default 100, 1,000, 10,000) in a `FlowPane`.
  - Update latency: mutate K=10 nodes on the FX thread, then measure time to the end of the next pulse (`AnimationTimer` + `runLater`). Reports p50/p95/p99 over S samples after W warmup samples.
  - Idle: no UI change for T seconds; process CPU from `/proc/self/stat`, voluntary context switches summed over `/proc/self/task/*/status` as a wakeup proxy.
  - Memory: heap after `System.gc()` (observation only), `VmRSS`, thread count, allocated bytes over the sample phase (`com.sun.management.ThreadMXBean`).
  - Churn: C=100 install/close cycles (bridge on) on the FX thread; report retained `fx-atspi-provider` threads, heap, RSS and thread deltas.
- Independent client: `examples/fx-lab/scripts/atspi_probe.py` uses libatspi (via PyGObject) to walk the tree and read text.
- Orchestration: `examples/fx-lab/scripts/run-baseline.sh` runs R repetitions of each cell.

## Limits and failure modes

- Bench inputs are bounded (nodes ≤ 10,000, samples/churn bounded by CLI validation). The run ends with `Platform.exit()` and a hard timeout in the script.
- If the AT-SPI bus is unavailable the bridge reports status and the bench still completes; this is recorded, not hidden.
- RSS alone does not prove a leak; trends over repeated runs are reported with variance.
- Latency measures mutation-to-pulse-end on the FX thread, not end-to-end screen-reader latency.

## Dependencies and affected files

No prerequisites. New: `.github/workflows/build.yml`, `docs/verification/issue-1.md`, `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/BenchApp.java`, `examples/fx-lab/scripts/*`. Edited: `Launcher`, `module-info.java` (example), `README.md`, backlog reference. Removed: `ci/github-actions-build.yml.example` (superseded).

## Acceptance and verification

- [ ] CI workflow green on the PR branch.
- [ ] `./gradlew check` passes on JDK 27 locally.
- [ ] Harness runs for 100/1,000/10,000 nodes, bridge on/off, with repeated runs and variance.
- [ ] Independent AT-SPI client result recorded on Fedora/GNOME/Wayland, or marked missing.
- [ ] Budgets and compatibility matrix in `docs/verification/issue-1.md`, from measured data only.
- [ ] No Xvfb inference.

## Rollout and documentation

README CI section updated. Later issues (SC-02…SC-10) compare against `docs/verification/issue-1.md`. Rollback: delete the workflow. No release-readiness claim.
