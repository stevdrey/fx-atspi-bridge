# Verification — Issue 30 (sanitize local paths in benchmark environment capture)

Spec: [`docs/specs/issue-30.md`](../specs/issue-30.md). No new data directory: the smoke collections below validate the tooling and are not committed. Their redacted lines are quoted here as the evidence. This record and the spec quote example paths on purpose, so the path scan targets the committed evidence in `docs/verification/data/`.

## Status

| Acceptance criterion | Status | Evidence |
| --- | --- | --- |
| Home directory recorded as `$HOME` in `environment.txt` | **Met** | JDK locations keep only the JDK directory name, inside or outside `HOME`: `test_a_jdk_outside_home_keeps_only_its_directory_name` (`/mnt/users/alice/jdks/27` → `<jdk>/27`, no `alice`) and `test_jdk_bin_display_cases`. A `HOME` that cannot be redacted (control character) refuses the collection: `test_a_home_that_cannot_be_redacted_refuses_the_collection_without_printing_it`, and both scripts with `HOME=$'/home/al\nice'` exit 1 before creating the output directory, without printing the value. `RecordedPathTest`: boundaries, trailing slashes (also without `realpath`), logical aliases (`/home//u`, `/home/u/.`, `/home/x/../u`), a symlinked `HOME`, relative `HOME`, and `print_environment` with a fake JDK under `HOME`; smoke `environment.txt`: `java_bin=$HOME/...`, `java.home = $HOME/...` |
| Output/data directories relative to the repository or `<out>/<name>`, in `environment.txt` and the `memory_diag.py` summary | **Met** | `display_path`, `command_line` and `test_the_source_line_records_no_absolute_path`, including names that contain a newline, a tab or a backtick (encoded as `?`); smoke: `command=examples/fx-lab/scripts/run-baseline.sh <out>/smoke30 smoke`, ``Source: `<out>/mdsmoke30` `` |
| Parsed fields unchanged (`git=`, `profile=`, `plan=`, `variants=`, `limits_sha256=`, jar hashes); `--resume` works | **Met** | `budgets.py` and `memory_diag.py` read the smoke output; `--resume` on the smoke directory skipped both existing cells. `test_a_newline_in_an_environment_value_cannot_add_records`: a JDK path containing `\nprofile=full\nplan=x:1` and newline-carrying session variables add no `profile=`/`plan=` records, and the file still resumes. `test_a_home_with_a_newline_is_not_split_into_fake_forms`, `test_line_breaks_from_a_child_process_cannot_add_records` (a `java.home` with `\r`, U+0085, U+2028 gives one `plan=` record, also for Python `splitlines()`), and `test_a_carriage_return_in_another_record_adds_no_plan` for `memory_diag.py` and `budgets.py` |
| Unit tests in `test_baseline_lib.py` and `test_memory_diag.py` | **Met** | 127 tests, 1 skipped (see Commands); mutation checks below |
| `docs/verification/data/issue-1/environment.txt` redacted | **Met** | `java_bin` and `java.home` show `$HOME`. `budgets.py docs/verification/data/issue-1` still passes. `git grep -nE '/home/\|/tmp/claude' -- docs/verification/data` returns nothing |

## Environment

| Item | Value |
| --- | --- |
| Revision | `c73e1ea`: smoke runs, unit tests and mutation checks (clean tree; branch `30-sanitize-benchmark-environment-paths`, after merging `main` with #28 and #29) |
| OS / desktop | Fedora 44, Linux 7.2.10-200.fc44.x86_64 (earlier rounds: 7.2.9-201), GNOME Wayland (`WAYLAND_DISPLAY=wayland-0`) |
| JDK / build | OpenJDK 27 Zulu27.28+101-CA (build 27+35), Gradle 9.8.1 |
| Tooling | Python 3.14.8, GNU Awk 5.3.2, GNU coreutils `realpath` 9.10, glibc 2.43 |
| Smoke runs | 2026-10-16 03:05 UTC (`run-baseline.sh`) and 03:06 UTC (`run-memory-diag.sh`), under `gnome-session-inhibit`; both `environment.txt` files passed the fail-closed path check, and so did all 8 `environment.txt` files committed under `docs/verification/data/`. In the previous round, at `776f789`, a first `run-memory-diag.sh` attempt failed in its first install cell with `pulse did not complete`, a stalled display pulse unrelated to the recorded fields, and was re-run from an empty directory |

## Commands and results

```bash
python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'   # OK: 127 tests, 1 skipped
examples/fx-lab/scripts/run-baseline.sh <scratch>/smoke30 smoke          # exit 0
examples/fx-lab/scripts/run-memory-diag.sh <scratch>/mdsmoke30 smoke     # exit 0
python3 examples/fx-lab/scripts/budgets.py <scratch>/smoke30             # OK
python3 examples/fx-lab/scripts/memory_diag.py <scratch>/mdsmoke30       # OK; Source: `<out>/mdsmoke30`
examples/fx-lab/scripts/run-baseline.sh <scratch>/smoke30 smoke --resume # same commit/profile/plan: 2 cells skipped
git grep -nE '/home/|/tmp/claude' -- docs/verification/data              # no output (exit 1)
```

Redacted lines of the smoke `environment.txt` files at `c73e1ea`. Neither file contains `HOME`, its physical path, the JDK's parent directories, `/tmp/claude` or `\r`:

```text
java_bin=<jdk>/current/bin/java
    java.home = <jdk>/27.0.0+35-zulu
command=examples/fx-lab/scripts/run-baseline.sh <out>/smoke30 smoke
```

**Mutation checks:** each protection was reverted on its own, and `test_baseline_lib`, `test_memory_diag` and `test_budgets` failed every time:

| Reverted to | Result |
| --- | --- |
| `echo "command=$0 $*"` in `run-baseline.sh` | 1 failure |
| `} > "$out_abs/environment.txt"` instead of `write_environment` in `run-memory-diag.sh` | 1 failure |
| `sed "s#${HOME%/}#\$HOME#g"` as `redact_home` | 13 failures |
| `echo` instead of `printf` in `one_line` (used by `display_path`) | 5 failures |
| `home="${home%/}"`, removing a single trailing slash | 1 failure (`test_trailing_slashes_are_stripped_even_without_realpath`; with `realpath` present the logical form already normalizes it) |
| no `realpath` forms (`home_forms` prints only the stripped value) | 4 failures (logical aliases, symlinked `HOME`) |
| no `[[:cntrl:]]` replacement in `one_line` | 5 failures (newline and tab names, `command_line`, `one_line`, injected environment records) |
| `echo "java_bin=$java_bin"` instead of `one_line` in `print_environment` | 1 failure (`test_a_newline_in_an_environment_value_cannot_add_records`) |
| no control-character/backtick replacement in `memory_diag.display_path` | 1 failure |
| no control-character check of `HOME` in `home_forms` | 1 failure (`test_a_home_with_a_newline_is_not_split_into_fake_forms`) |
| `redact_home > "$1"` without `one_record_per_line` in `write_environment` | 1 failure (`test_line_breaks_from_a_child_process_cannot_add_records`) |
| `socket_display` returning the raw value | 1 failure |
| `splitlines()` again in `memory_diag.planned_runs` | 1 error (the summary exits on the extra plan) |
| universal newlines again in `budgets.read_plan` | 1 failure |
| no `require_safe_home` call in `run-baseline.sh` | 1 failure (`test_collections_refuse_a_home_that_cannot_be_redacted`) |
| `require_safe_home` always succeeding | 3 failures |
| `one_line "java_bin=$java_bin"` instead of `jdk_bin_display` | 3 failures |
| no `jdk_home_display` on the `-XshowSettings` line | 1 failure |
| no `jdk_home_display` on the `java -version` lines | 1 failure |
| `jdk_bin_display` without `strip_trailing_slashes` | 1 failure (`JAVA_HOME` with a trailing slash) |
| `write_environment` without the fail-closed check | 4 failures |
| `retries.txt` written with a raw `printf` | 1 failure (`test_a_retry_reason_is_sanitized_like_the_environment`) |
| `socket_display` returning the raw value (rechecked with the behavior test) | 2 failures; the fail-closed check also refuses the raw socket path |
| `strip_trailing_slashes` removing a single slash | 2 failures |
| the previous rule (an ASCII name character required after the slash) | 6 failures: the 4 reported paths, a bare `/`, and the relative `café/x`, which that rule wrongly rejected |
| no `/` in the covering class | 1 failure (`a//b`) |
| no non-ASCII range in the covering class | 1 failure (`café/x`) |

`./gradlew check` was not run locally because no Java changed; CI runs it on the pull request.

## Review rounds

| Round | Findings | Resolution |
| --- | --- | --- |
| Internal `/code-review high` of `e4309e6` | 7 | Fixed in `002ada4`: <ul><li>no leading boundary, and a leak before `,` `;` quotes `)`;</li><li>redaction limited to `print_environment` (now `write_environment` for every line);</li><li>`command=` untested;</li><li>`issue-1.md` wording;</li><li>two simplifications.</li></ul> |
| Codex review of `002ada4` | 3 | <ul><li>Fixed in `3a2a737`: `display_path` printed nothing for directories named `-n` or `-e`, and `redact_home` removed only one trailing slash of `HOME`.</li><li>This Spec Context and verification record answer the AGENTS.md Spec Context rule.</li></ul> |
| Codex review of `12c4724` | 3 | <ul><li>The path scan over `docs` matched the example paths quoted in the spec and this record; it now targets `docs/verification/data` and its result is reproducible.</li><li>Fixed in `cda9e89`: `HOME` aliases (logical normalization and physical path through a symlink) are now redacted.</li><li>Fixed in `cda9e89`: control characters in a displayed directory name (and backticks in the summary) become `?`, so they cannot inject `environment.txt` lines such as `profile=full` or break the `Source:` code span.</li></ul> |
| Codex review of `48fc156` | 1 | Fixed in `c5fe096`: a newline in `JAVA_HOME` (outside `HOME`) reached `java_bin=` verbatim and could inject `profile=` or `plan=` records. The new `one_line` replaces control characters in every value that `print_environment` takes from the environment: `java_bin`, the `*_OPTS` values, `idle_inhibit` and the session line. `display_path` reuses it. |
| Codex review of `2c8d389` | 3 | Fixed in `db3131a`: <ul><li>a `HOME` containing a newline was split into fake forms (e.g. `profile`) that redacted ordinary records; `home_forms` now treats such a `HOME` as unusable;</li><li>a `java.home` printed by the JVM with `\r` (or U+0085/U+2028) bypassed `one_line` and became extra `plan=` records for Python readers; every line now goes through `one_record_per_line` in `write_environment`, and `memory_diag.py` and `budgets.py` split only at `\n`;</li><li>an absolute `WAYLAND_DISPLAY` socket path was recorded verbatim; `socket_display` records `<socket>/<name>`.</li></ul> |
| Codex review of `66939a7` | 1 | Fixed in `800a66c`: a `HOME` containing a control character silently disabled redaction, so a JDK beneath it was recorded with only `?` in place of the control character. The new `require_safe_home` makes both collections refuse to run in that case, before creating the output directory and without printing the value. |
| Codex review of `daa3221` | 1 | Fixed in `776f789`: a JDK in a user- or session-specific place outside `HOME` was recorded verbatim in `java_bin=` and `java.home`. Both now keep only the JDK directory name (`jdk_bin_display`, `jdk_home_display`), also on any `java -version` line. |
| Internal `/code-review high` of `cc3f241` | 5 | Fixed in `6a7e8a5`: <ul><li>`JAVA_HOME` with a trailing slash recorded `<jdk>/bin/java` without the JDK name; `strip_trailing_slashes` is now shared by the three normalizations;</li><li>no fail-closed check: `write_environment` now refuses a line that still holds an uncovered absolute path;</li><li>`retries.txt` bypassed sanitization; its lines now go through `one_line` and `sanitize_records`;</li><li>the socket-display test only matched source text; it now runs `print_environment`.</li></ul> |
| Follow-up review of `cbacd3c` (P2) | 1 | Fixed in `c73e1ea`: the fail-closed check required an ASCII name character after the slash, so `//mnt/...`, `///tmp/...`, `/équipe/...` and `/~private/...` passed. `uncovered_absolute_path_lines` now decides each `/` by the byte before it only. Tests cover the 4 reported paths, a bare `/`, `a b /x`, `path:/x`, `c=(/usr/bin)`, `x=a:/opt/b` and `/srv/home/u` (refused, file removed, only line numbers reported). They also cover `$HOME`, `<jdk>`, `<out>`, `<socket>`, repository-relative paths, `café/x` and `a//b` (accepted). |

## Limitations

- **The home directory is redacted** in its stripped, logical and physical forms. **JDK locations keep only the JDK directory name** (`<jdk>/27.0.0+35-zulu`), so a JDK directory name chosen by the user stays visible. Evidence collected before this change keeps `$HOME/...` JDK paths.
- **Without `realpath`,** only the stripped `HOME` value is redacted. GNU coreutils provides it on the supported Fedora session.
- **A relative `HOME`** is treated as unusable and nothing is redacted.
- **`<out>/<name>` keeps the last path component.** Control characters in it become `?`.
- **A `java.home` value with a newline** is cut at the newline. `grep` keeps only its first line, so the rest is dropped, not recorded as a new field. Other line-breaking characters in it become `?`.
- **A `HOME` containing a control character** refuses the collection; it cannot be redacted. Library code that calls `redact_home` directly treats such a `HOME` as unusable and redacts nothing.
- **Display socket paths** keep only their last component (`<socket>/wayland-0`).
- **Console output is not redacted.** It is not evidence.
- **The fail-closed path check covers `environment.txt` only,** at the start of a collection. `retries.txt` lines are sanitized but not checked, so a retry never aborts a long collection. The committed `environment.txt` files under `docs/verification/data/` also pass the check.
- **Not committed:** per-run logs and diagnostic snapshots, as before.
- **History:** earlier commits and pull requests keep the unredacted values. History is not rewritten.
- **Smoke runs validate the harness only.** They are not performance evidence.
