# SC-05 benchmark evidence

These are the raw files behind the "Results" tables in [`docs/verification/issue-5.md`](../../issue-5.md). They come from two `run-baseline.sh quick` collections taken on the same machine and in the same session:

| Directory | Measured commit | Runs |
| --- | --- | ---: |
| `before/` | `349df59` (main before SC-05) | 44 |
| `after/` | `cd818fc` (first SC-05 implementation) | 44 |

- **Contents:** each run is one JSON file. Each directory also has `environment.txt` (plan, JDK/Gradle, jar hashes) and `retries.txt`. Local paths in `environment.txt` are replaced by `$HOME` and `<scratch>`.
- **Integrity:** `MANIFEST.sha256` lists the SHA-256 of every JSON; check it from the repository root with `cd docs/verification/data/issue-5 && grep -v '^#' MANIFEST.sha256 | sha256sum -c`.
- **Tables:** `python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-5/after` (and `before`) prints them.

The data describes `cd818fc`, not the final SC-05 implementation. Later commits changed measured paths: `NodeStore` snapshot sharing, memoized `showing`, listener arrays, and adopting the initial generation. Re-measuring the final implementation is tracked in [#27](https://github.com/stevdrey/fx-atspi-bridge/issues/27). The files were moved out of PR #26 to keep that PR reviewable.
