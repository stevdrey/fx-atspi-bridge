# Spec Context — Issue 30 (sanitize local paths in benchmark environment capture)

## Context and source evidence

Issue: [#30](https://github.com/stevdrey/fx-atspi-bridge/issues/30). Baseline `243a674`, `main` after [#26](https://github.com/stevdrey/fx-atspi-bridge/pull/26). The rule: [`docs/verification/README.md`](../verification/README.md) says "Keep captured values sanitized". Related: [issue 1 harness](../verification/issue-1.md) and [issue 17 memory tooling](issue-17.md).

The Codex review of evidence PR [#28](https://github.com/stevdrey/fx-atspi-bridge/pull/28) found the collector's home directory and a private scratch path, including a session UUID, in committed `environment.txt` files. At `243a674` the benchmark tooling writes these values:

| Writer | Recorded value | Example at baseline |
| --- | --- | --- |
| `baseline-lib.sh` `print_environment` | `java_bin=`, `java.home = ` | `/home/<user>/.sdkman/...` |
| `run-baseline.sh` | `command=$0 $*` | the absolute output directory, e.g. `/tmp/claude-1000/<project>/<uuid>/scratchpad/...` |
| `memory_diag.py` `summary` | `Source:` line of `summary.md` | the absolute data directory |

#28 and [#29](https://github.com/stevdrey/fx-atspi-bridge/pull/29) redacted their own files after the fact. `docs/verification/data/issue-1/environment.txt` on `main` still contained the home path.

**Timing:** this Spec Context was written after the first implementation commit (`e4309e6`), in response to the Codex review of #31 that cites the AGENTS.md rule "establish observable acceptance criteria … before implementation". It records the contract as delivered and as shaped by two review rounds: an internal `/code-review` with 7 findings, and Codex with 3 findings.

## Goal and non-goals

**Goal:** new benchmark evidence carries no workstation-specific paths, without manual redaction and without changing any measured value or any field that the parsers read.

**Non-goals:**
- rewriting git history; earlier commits keep the old values;
- redacting other host data (the hostname is not recorded);
- changing what is measured;
- committing per-run logs or raw diagnostic snapshots, which remain uncommitted because they contain the command line and local paths.

## Contracts and design

| Function | Contract |
| --- | --- |
| `home_forms` | The distinct forms of `HOME` that a tool may print: the value without trailing slashes, its logical normalization (`realpath -ms`: `//`, `/.` and `..` collapsed, links kept) and its physical path (`realpath -m`: links resolved, e.g. `/home` → `/var/home`). A relative, empty or root `HOME` yields nothing. `realpath` is used only when it exists. |
| `redact_home` (stdin → stdout) | Replaces every `home_forms` value with the literal `$HOME`, only where it appears as a whole path. The character before it must be the line start or not a path character (`[A-Za-z0-9._/-]`). The character after it must be the line end or not a name character (`[A-Za-z0-9._-]`), so `/` may follow. A `HOME` that is unset, empty, relative or only slashes makes it a no-op. Implemented in `awk`, which reads the forms through `ENVIRON` so backslashes are not interpreted. |
| `display_path ROOT PATH` | Returns `.` for the root itself, the path relative to `ROOT` when inside it, and otherwise `<out>/<last component>`. Uses `printf`, so names such as `-n` or `-e` are printed literally. Control characters become `?`, so a name cannot inject lines (e.g. `profile=full`) into `environment.txt`. |
| `command_line ROOT SCRIPT OUT_ABS PROFILE` | Returns `command=<display_path script> <display_path out> <profile>`. |
| `write_environment FILE` | `redact_home > FILE`. It is the **only** writer of `environment.txt` in `run-baseline.sh` and `run-memory-diag.sh`, so every line is redacted, including lines a script appends after `print_environment`. The `resumed=` append (`>>`) carries no path. |
| `memory_diag.display_path(data, root=REPOSITORY)` | Same rule as `display_path`, for the `Source:` line; control characters and backticks become `?` so the code span stays intact. `REPOSITORY` is resolved once. |

- **Compatibility:**
  - `git=`, `tree=`, `profile=`, `plan=`, `variants=`, `limits_sha256=`, the jar hashes and the measured JSON fields are unchanged;
  - `resume_matches`, `budgets.py` and `memory_diag.py` read only those fields, so `--resume` works on directories collected before this change;
  - the per-line `awk` pass runs once per collection over about 35 lines, so its cost is negligible.

## Limits and failure modes

- **Only `HOME` is redacted,** in its stripped, logical and physical forms: a JDK outside `HOME`, such as `/usr/lib/jvm/...`, is recorded as it is. That is a system path, not a personal one.
- **Without `realpath`,** only the stripped value of `HOME` is redacted. GNU coreutils provides it on the supported Fedora session.
- **`<out>/<name>` keeps the last path component:** a directory name chosen by the collector is therefore visible, with control characters shown as `?`.
- **Console output still shows full paths:** for example, the `Summarize with:` hint. Console output is not evidence.
- **Earlier commits and pull requests:** they keep the unredacted values.

## Dependencies and affected files

- **Prerequisites:** none. #28 and #29 already redacted their own data directories, and both are merged.
- **Changed:**
  - `examples/fx-lab/scripts/baseline-lib.sh`, `run-baseline.sh`, `run-memory-diag.sh`, `memory_diag.py`;
  - tests in `test_baseline_lib.py` and `test_memory_diag.py`;
  - `docs/verification/data/issue-1/environment.txt`, with a note in `docs/verification/issue-1.md`. This file is not in `legacy-waiver.sha256`, so no manifest changes.

## Acceptance and verification

1. The home directory is recorded as `$HOME` in `environment.txt`.
2. Output and data directories are recorded relative to the repository, or as `<out>/<name>`, in `environment.txt` and in the `memory_diag.py` summary.
3. Parsed fields are unchanged, and `--resume` still matches.
4. Unit tests exist in `test_baseline_lib.py` and `test_memory_diag.py`, including:
   - boundary cases;
   - a `HOME` with several trailing slashes, logical aliases and a symlinked `HOME`;
   - option-like directory names and names that contain control characters;
   - a static check that both scripts write `environment.txt` only through `write_environment`.
5. `docs/verification/data/issue-1/environment.txt` is redacted.

**Commands:**
- `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'`;
- mutation checks of each protection;
- `run-baseline.sh <scratch> smoke` and `run-memory-diag.sh <scratch> smoke` on a Fedora/GNOME/Wayland session, followed by `budgets.py`, `memory_diag.py` and `--resume` on their output;
- `git grep -nE '/home/|/tmp/claude' -- docs/verification/data`. The scan targets the committed evidence; this spec and the verification record quote example paths on purpose.

Smoke runs validate the harness only and are never performance evidence. Results are in [`docs/verification/issue-30.md`](../verification/issue-30.md).

## Rollout and documentation

No library or runtime change. Rollback is a revert of the PR. Future evidence PRs need no manual redaction; reviewers should still check new `environment.txt` lines that print paths outside `HOME` or the output directory.
