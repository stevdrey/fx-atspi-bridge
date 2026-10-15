# SC-05 benchmark evidence

These are the raw files behind the "Results" tables in [`docs/verification/issue-5.md`](../../issue-5.md). They come from two `run-baseline.sh quick` collections taken on the same machine and in the same session:

| Directory | Measured commit | Runs |
| --- | --- | ---: |
| `before/` | `349df59` (main before SC-05) | 44 |
| `after/` | `cd818fc` (first SC-05 implementation) | 44 |

- **Contents:** each run is one JSON file. Each directory also has `environment.txt` (plan, JDK/Gradle, jar hashes). `before/retries.txt` records the one invalid attempt that was retried; the `after/` collection had no retries, so it has no `retries.txt`. Local paths in `environment.txt` were redacted after collection: the home directory is `$HOME`, and the output directory is `<out>/<name>`.
- **Integrity:** `MANIFEST.sha256` lists the SHA-256 of every JSON, `environment.txt` and `retries.txt`; check it from the repository root with `cd docs/verification/data/issue-5 && grep -v '^#' MANIFEST.sha256 | sha256sum -c`.
- **Tables:** `python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-5/after` (and `before`) prints them.

The data describes `cd818fc`, not the final SC-05 implementation. Later commits changed measured paths: `NodeStore` snapshot sharing, memoized `showing`, listener arrays, and adopting the initial generation. The final implementation is re-measured in [#27](https://github.com/stevdrey/fx-atspi-bridge/issues/27); its data is in `docs/verification/data/issue-27/` ([PR #29](https://github.com/stevdrey/fx-atspi-bridge/pull/29)). The files were moved out of PR #26 to keep that PR reviewable.
