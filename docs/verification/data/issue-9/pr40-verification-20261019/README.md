# PR #40 verification evidence (2026-10-19)

- `native-c395e72`: original 13-case native QA on clean `c395e7274612bf80cc0c436f2e1d50b42aae578e`.
- `native-c241124-1` / `native-c241124-2`: two independent 16-case native runs on clean `c2411243fe504a776113307600fb0f2d5e09cdc1`.
- `before` / `after`: dispatch collections on clean `8f78da798d0a115e018e0f301ba6cc44e3867958` and `c2411243fe504a776113307600fb0f2d5e09cdc1`, respectively.
- `artifacts-before.json` / `artifacts-after.json`: JAR and benchmark-class hashes. The benchmark class hashes match; the after bridge JAR also matches `c395e72`. The lab JAR changes because the isolated geometry fixture was extended.

Native runs used the corresponding revision's installed launcher and client under `gnome-session-inhibit --inhibit idle:suspend`:

```sh
./gradlew :examples:fx-lab:installDist --console=plain
python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab <fresh-output-directory>
```

Each performance collection used the unmodified command below, sequentially, without concurrent builds, native QA or profiling:

```sh
examples/fx-lab/scripts/run-baseline.sh <fresh-output-directory> dispatch
python3 examples/fx-lab/scripts/budgets.py <output-directory>
```

Each revision has five paired bridge-off/on runs at 100/1,000/10,000 nodes, dirty-10, warmup 50, 200 samples and 10 seconds idle. Environment/JAR hashes, run JSON, sanitized logs and any retry records are retained. The existing collector only retries runs rejected by validity guards; a valid budget failure is never retried or removed. Collection-validity output from `budgets.py` is **not** acceptance of a new budget: any proposed limits it prints do not replace the agreed limits.

The after collection was interrupted by the user with 26 JSON files present. On continuation the collector was no longer active; `dispatch --resume` skipped those files and collected the four missing cells. A log audit then found that `n10000-d10-on-r3` had metrics but no persisted collector log/exit confirmation. That original metrics record (p99 33.952650 ms) is preserved in `after/interrupted`, marked completion-unconfirmed, and included separately in `summary.json`. Only that cell was then collected again with `--resume` to confirm completion. The qualifying matrix therefore contains 60 completed runs, plus one retained interruption-affected metrics record. No result with confirmed successful completion was repeated or removed, and no retry was selected by its budget result. `after/interruption.json`, `after/interrupted/completion-unconfirmed.json` and the resume timestamps preserve this limitation. There were no validity-guard retries in either collection.

Run `python3 compare.py` from any directory to reproduce `summary.json`, `tables.md` and `MANIFEST.sha256`. The comparison applies the existing limits to every bridge-on run: p99 41/29/307 ms, allocation 116/299/8,844 KiB/sample, idle CPU 8/14/137 percent of one core, and RSS 327/434/1,590 MiB. Bridge-off results are controls. `summary.json` retains all individual checks and both revisions' verdicts.

The historical 33.476954-ms / 29-ms failure remains in the parent verification document. These fresh collections do not erase it. Desktop qualification is limited to the actual backend, monitor count and scales recorded in native results; no native-Wayland or mixed-monitor claim is made. JUnit XML and original build artifacts remain local under `/tmp/fx-atspi-issue9-verification-artifacts`.
