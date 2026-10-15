# Verification — Issue 30 (sanitize local paths in benchmark environment capture)

Spec: [`docs/specs/issue-30.md`](../specs/issue-30.md). No new data directory: the smoke collections below validate the tooling and are not committed. Their redacted lines are quoted here as the evidence. This record and the spec quote example paths on purpose, so the path scan targets the committed evidence in `docs/verification/data/`.

## Status

| Acceptance criterion | Status | Evidence |
| --- | --- | --- |
| Home directory recorded as `$HOME` in `environment.txt` | **Met** | `RecordedPathTest`: boundaries, trailing slashes (also without `realpath`), logical aliases (`/home//u`, `/home/u/.`, `/home/x/../u`), a symlinked `HOME`, relative `HOME`, and `print_environment` with a fake JDK under `HOME`; smoke `environment.txt`: `java_bin=$HOME/...`, `java.home = $HOME/...` |
| Output/data directories relative to the repository or `<out>/<name>`, in `environment.txt` and the `memory_diag.py` summary | **Met** | `display_path`, `command_line` and `test_the_source_line_records_no_absolute_path`, including names that contain a newline, a tab or a backtick (encoded as `?`); smoke: `command=examples/fx-lab/scripts/run-baseline.sh <out>/smoke30 smoke`, ``Source: `<out>/mdsmoke30` `` |
| Parsed fields unchanged (`git=`, `profile=`, `plan=`, `variants=`, `limits_sha256=`, jar hashes); `--resume` works | **Met** | `budgets.py` and `memory_diag.py` read the smoke output; `--resume` on the smoke directory skipped both existing cells |
| Unit tests in `test_baseline_lib.py` and `test_memory_diag.py` | **Met** | 110 tests, 1 skipped (see Commands); mutation checks below |
| `docs/verification/data/issue-1/environment.txt` redacted | **Met** | `java_bin` and `java.home` show `$HOME`. `budgets.py docs/verification/data/issue-1` still passes. `git grep -nE '/home/\|/tmp/claude' -- docs/verification/data` returns nothing |

## Environment

| Item | Value |
| --- | --- |
| Revision | Smoke runs at `cda9e89` (clean tree; branch `30-sanitize-benchmark-environment-paths`, after merging `main` with #28 and #29). Unit tests and mutation checks at `412abf0`, which adds one test only |
| OS / desktop | Fedora 44, Linux 7.2.9-201.fc44.x86_64, GNOME Wayland (`WAYLAND_DISPLAY=wayland-0`) |
| JDK / build | OpenJDK 27 Zulu27.28+101-CA (build 27+35), Gradle 9.8.1 |
| Tooling | Python 3.14.8, GNU Awk 5.3.2, GNU coreutils `realpath` 9.10, glibc 2.43 |
| Smoke runs | 2026-10-15 22:52 UTC (`run-baseline.sh`) and 22:53 UTC (`run-memory-diag.sh`), under `gnome-session-inhibit` |

## Commands and results

```bash
python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'   # OK: 110 tests, 1 skipped
examples/fx-lab/scripts/run-baseline.sh <scratch>/smoke30 smoke          # exit 0
examples/fx-lab/scripts/run-memory-diag.sh <scratch>/mdsmoke30 smoke     # exit 0
python3 examples/fx-lab/scripts/budgets.py <scratch>/smoke30             # OK
python3 examples/fx-lab/scripts/memory_diag.py <scratch>/mdsmoke30       # OK; Source: `<out>/mdsmoke30`
examples/fx-lab/scripts/run-baseline.sh <scratch>/smoke30 smoke --resume # same commit/profile/plan: 2 cells skipped
git grep -nE '/home/|/tmp/claude' -- docs/verification/data              # no output (exit 1)
```

Redacted lines of the smoke `environment.txt` files at `cda9e89`. Neither file contains `HOME`, its physical path, or `/tmp/claude`:

```text
java_bin=$HOME/.sdkman/candidates/java/current/bin/java
    java.home = $HOME/.sdkman/candidates/java/27.0.0+35-zulu
command=examples/fx-lab/scripts/run-baseline.sh <out>/smoke30 smoke
```

**Mutation checks:** each protection was reverted on its own, and `test_baseline_lib` plus `test_memory_diag` failed every time:

| Reverted to | Result |
| --- | --- |
| `echo "command=$0 $*"` in `run-baseline.sh` | 1 failure |
| `} > "$out_abs/environment.txt"` instead of `write_environment` in `run-memory-diag.sh` | 1 failure |
| `sed "s#${HOME%/}#\$HOME#g"` as `redact_home` | 12 failures |
| `echo` instead of `printf` in `display_path` | 4 failures |
| `home="${home%/}"`, removing a single trailing slash | 1 failure (`test_trailing_slashes_are_stripped_even_without_realpath`; with `realpath` present the logical form already normalizes it) |
| no `realpath` forms (`home_forms` prints only the stripped value) | 4 failures (logical aliases, symlinked `HOME`) |
| no `[[:cntrl:]]` replacement in `display_path` | 3 failures (newline and tab names, `command_line`) |
| no control-character/backtick replacement in `memory_diag.display_path` | 1 failure |

`./gradlew check` was not run locally because no Java changed; CI runs it on the pull request.

## Review rounds

| Round | Findings | Resolution |
| --- | --- | --- |
| Internal `/code-review high` of `e4309e6` | 7 | Fixed in `002ada4`: <ul><li>no leading boundary, and a leak before `,` `;` quotes `)`;</li><li>redaction limited to `print_environment` (now `write_environment` for every line);</li><li>`command=` untested;</li><li>`issue-1.md` wording;</li><li>two simplifications.</li></ul> |
| Codex review of `002ada4` | 3 | <ul><li>Fixed in `3a2a737`: `display_path` printed nothing for directories named `-n` or `-e`, and `redact_home` removed only one trailing slash of `HOME`.</li><li>This Spec Context and verification record answer the AGENTS.md Spec Context rule.</li></ul> |
| Codex review of `12c4724` | 3 | <ul><li>The path scan over `docs` matched the example paths quoted in the spec and this record; it now targets `docs/verification/data` and its result is reproducible.</li><li>Fixed in `cda9e89`: `HOME` aliases (logical normalization and physical path through a symlink) are now redacted.</li><li>Fixed in `cda9e89`: control characters in a displayed directory name (and backticks in the summary) become `?`, so they cannot inject `environment.txt` lines such as `profile=full` or break the `Source:` code span.</li></ul> |

## Limitations

- **Only the home directory is redacted,** in its stripped, logical and physical forms. A JDK outside it, such as a system JDK under `/usr/lib/jvm`, is recorded as it is.
- **Without `realpath`,** only the stripped `HOME` value is redacted. GNU coreutils provides it on the supported Fedora session.
- **A relative `HOME`** is treated as unusable and nothing is redacted.
- **`<out>/<name>` keeps the last path component.** Control characters in it become `?`.
- **Console output is not redacted.** It is not evidence.
- **Not committed:** per-run logs and diagnostic snapshots, as before.
- **History:** earlier commits and pull requests keep the unredacted values. History is not rewritten.
- **Smoke runs validate the harness only.** They are not performance evidence.
