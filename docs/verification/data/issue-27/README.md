# SC-05 final-implementation benchmark evidence (issue 27)

These are the raw files behind the "Final implementation re-measurement" section of [`docs/verification/issue-5.md`](../../issue-5.md). Four collections were taken on the same machine, in one session on 2026-10-15, in ABBA order: before, after, after, before.

| Directory | Tool and profile | Library commit | Runs | Retries |
| --- | --- | --- | ---: | ---: |
| `before/` | `run-baseline.sh quick` | `349df59` (main before SC-05) | 44 | 0 |
| `after/` | `run-baseline.sh quick` | `57d3237` (final head of PR #26) | 44 | 0 |
| `install10k-after/` | `run-memory-diag.sh install10k` | `57d3237` + `install10k-profile.patch` | 15 | 0 |
| `install10k-before/` | `run-memory-diag.sh install10k` | `349df59` + `install10k-profile.patch` | 15 | 0 |

- **Measured implementation:** `57d3237` is the head of PR #26. Its squash merge on `main`, `243a674`, has the identical tree (`3e62355a`). The bridge jar hashes are identical across the `quick` and `install10k` collections of each commit: `3fdb26c8…` at `349df59` and `fd9aacc8…` at `57d3237`.
- **`install10k` profile:** a local scratch commit added only this profile to `run-memory-diag.sh`; `install10k-profile.patch` is its exact diff. The profile runs 30 structural cycles and then 100 install/close cycles at 10,000 nodes, with the bridge on, 5 repetitions each of default malloc, NMT and `MALLOC_ARENA_MAX=2`. The `environment.txt` files record the scratch commits `c984c29` and `a9e26c7`, which were not pushed.
- **Contents:** each run is one JSON file. Each directory has `environment.txt`, with the plan, JDK and Gradle versions, and jar hashes. `install10k-*/summary.md` is the `memory_diag.py` output. In `environment.txt` and `summary.md`, local paths were redacted after collection: the home directory is `$HOME`, and the output directory is `<out>/<name>`. No collection retried a cell, so no `retries.txt` was written. Per-run logs and raw diagnostic snapshots (`pmap`, `smaps_rollup`, `malloc_info`, NMT, histograms) are not committed, because they contain the command line and local paths.
- **Integrity:** `MANIFEST.sha256` lists the SHA-256 of every other file here. Check it from the repository root with `cd docs/verification/data/issue-27 && grep -v '^#' MANIFEST.sha256 | sha256sum -c`.
- **Tables:** `python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-27/after` (and `before`) prints the tables. `python3 examples/fx-lab/scripts/memory_diag.py docs/verification/data/issue-27/install10k-after` (and `install10k-before`) regenerates the phase-delta and install-timing tables of `summary.md`. The allocator, NMT, class-histogram and `pmap` tables need the raw snapshots, so the committed `summary.md` is their only record.

Do not mix this data with the `cd818fc` collection in `data/issue-5/` (PR #28) or with the issue 1 data: each set is compared only with the "before" of its own session.
