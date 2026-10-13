# Spec Context — Issue 1 (SC-01)

## Context and source evidence

- Issue: https://github.com/stevdrey/fx-atspi-bridge/issues/1
- Baseline: `main` at 525afca (PR #11); the recorded data was produced after merging Issue 13 (PR #14), see the evidence file for the exact artifact.
- Before this issue, only a CI template (`ci/github-actions-build.yml.example`) existed. No workflow built the project, and no repeatable measurement existed.
- Source under measurement (not modified by this issue; the measured artifact also contains the Issue 13 fix merged from `main`, see `docs/verification/issue-1.md`): `FxAtSpiBridge` (100 ms `AnimationTimer` scan), `AtSpiProvider` (5 ms GLib poll on `fx-atspi-provider`, instance-wide monitor). ADR 0001 lists these as costs to *measure*, not fix, in SC-01.
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
  - Update latency: mutate K ∈ {1, 10, 100} nodes on the FX thread, then measure time to the end of the next pulse (`AnimationTimer` + `runLater`). Reports p50/p95/p99 over S samples after W warmup samples.
  - Idle: no UI change for T seconds; process CPU from `/proc/self/stat`, voluntary context switches summed over `/proc/self/task/*/status` as a wakeup proxy.
  - Memory: heap after `System.gc()` (observation only), `VmRSS`, thread count, allocated bytes over the sample phase (`com.sun.management.ThreadMXBean`).
  - Structural churn: 100 cycles of adding/removing 10 controls, then swapping the `Scene` to a fresh one, then restoring it, for bridge off and on; each state is held 250 ms (≥ 2 bridge scan intervals of 100 ms) so it can be observed — observation itself is not confirmed because the library exposes no hook; report heap (after GC), RSS and thread deltas.
  - Install churn: 100 bridge close/install cycles (bridge on) on the FX thread; thread/RSS checkpoint read 2 s after the loop and before GC, heap after GC, then a ≤30 s drain wait for stopped provider threads.
  - Retained listener/invocation counters are not observable without library changes and are **not** measured here.
- Independent client: `examples/fx-lab/scripts/atspi_probe.py` uses libatspi (via PyGObject) to walk the tree and read text.
- Orchestration: `examples/fx-lab/scripts/run-baseline.sh` builds the harness, refuses to run on a dirty tree, records commit/jar hashes in `environment.txt`, and runs a `quick` (default, ≈45 min) or `full` profile (see the evidence file for the exact plan), requires JDK 27, re-runs itself under an idle inhibitor and aborts invalid runs (including throttled displays); idle and both churn phases only for K=10. `budgets.py` produces the tables and budgets from the JSON. Bridge-on runs are invalid (exit 3, no JSON) unless the provider reports readiness and no error.

## Limits and failure modes

- Bench inputs are bounded (nodes ≤ 10,000, samples/churn bounded by CLI validation). The run ends with `Platform.exit()` and a hard timeout in the script.
- Readiness is tracked per installed provider (generation): the initial provider and the final provider after install churn must report `AT-SPI2 listo` within 15 s, and no generation may report an `Error…` status.
- If the AT-SPI bus is unavailable, the provider is not ready within 15 s, or it reports any `Error…` status, the bridge-on run is **invalid**: the process exits with code 3, writes no JSON and `run-baseline.sh` aborts. Unavailability is therefore not recorded as a result; it blocks the baseline instead.
- RSS alone does not prove a leak; trends over repeated runs are reported with variance.
- Latency measures mutation-to-pulse-end on the FX thread, not end-to-end screen-reader latency.

## Dependencies and affected files

No prerequisites. New: `.github/workflows/build.yml`, `docs/verification/issue-1.md`, `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/BenchApp.java`, `examples/fx-lab/scripts/*`. Edited: `Launcher`, `module-info.java` (example), `README.md`, backlog reference. Removed: `ci/github-actions-build.yml.example` (superseded).

## Acceptance and verification

- [ ] CI workflow green on the PR branch.
- [ ] `./gradlew check` passes on JDK 27 locally.
- [ ] Harness runs for 100/1,000/10,000 nodes, bridge on/off, with repeated runs and variance (`quick` profile by default: reduced coverage at 10,000 nodes is recorded as a limitation; `full` profile available).
- [ ] Independent AT-SPI client result recorded on Fedora/GNOME/Wayland, or marked missing.
- [ ] Budgets and compatibility matrix in `docs/verification/issue-1.md`, from measured data only.
- [ ] No Xvfb inference.

## Verification strategy for review iterations

Fast checks only: `./gradlew check`, `python3 -m unittest` for `budgets.py` (also in CI), and the `smoke` profile (~2 min) after harness/script changes. The long collections (`quick` ≈ 45 min, `full` ≈ 2 h) run once to produce the provisional baseline and again only in dedicated QA sessions; they are not repeated per review comment.

## Rollout and documentation

README CI section updated. Later issues (SC-02…SC-10) compare against `docs/verification/issue-1.md`. Rollback: delete the workflow. No release-readiness claim.
