# Issue 10 / PR 42 — nested consumer-build cancellation

Historical cancellation verification; the later 8264fe4 follow-up adds TERM-grace
and unreaped-group safeguards, summarized in [review fixes](issue-10-review-fixes.md).
Generated results are kept outside Git.

The P2 fix is verified on clean QA commit `66657751955fef2797f15ee70a540d26f3f42877`. It changes only QA
process supervision/tests and documentation. Production, consumers, benchmark
workloads, budgets and retry rules are unchanged.

## Cause and correction

The collector owns an outer wrapper group, while Gradle's single-use daemon and
consumer run_owned calls can open independent sessions. Killing only the wrapper
group loses the nested monitor and leaves its build alive. The helper now captures
the live descendant tree before signalling parents, including subprocesses started
by JVM worker threads. Linux pidfds pin process identity; each process is admitted
only after verifying its parent. Cleanup sends TERM, then forces remaining owned
processes with KILL, retaining the original group fallback. It waits for pidfd exit
readiness before returning and closes all descriptors through ExitStack.

At most 256 process handles are retained. Capture/limit/termination failures remain
failures after cleanup of known handles/groups. No arbitrary desktop process is
signalled. The original five-second wrapper grace remains; the final descendant
exit wait is bounded to five seconds. Normal return codes and original timeout/
KeyboardInterrupt propagation remain intact. This requires Linux /proc and Python
pidfd APIs, exercised locally and by the Linux QA workflow.

[CI run 147](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39836792984) also Passed on the QA implementation commit.

## Regression and verification

The regression uses actual run_owned calls in separate Python processes/sessions.
An outer TimeoutExpired is injected after numeric readiness, avoiding startup-time
assumptions; the other case delivers a real SIGINT to an isolated supervisor and
observes KeyboardInterrupt propagation. A leaf ignores TERM and its parent exits
first. Tests assert both descendants stop, an unrelated sibling survives and the
caller's group remains intact. Additional checks cover ordinary exit codes,
non-child PID exclusion, capture failure, group cleanup and descriptor closure.

- **Before:** on the retained b62b469 helper, both cancellation regressions fail;
  three core tests run, two failures, zero errors. Each failure path explicitly
  kills its test-owned leftover processes.
- **After:** all five nested/ownership cases pass within the full **262-test Python
  suite**, with AT-SPI test dependencies mandatory.
- **Real Gradle diagnostic:** four captured processes, three independent groups,
  zero live owned processes after cancellation, including the TERM-ignoring leaf.
  Its helper bytes match 66657751955fef2797f15ee70a540d26f3f42877; the directed diagnostic predates the commit.
- **Full forced build:** wrapper check/installDist and independent consumers pass;
  **15 actionable tasks executed, 281 Java tests, zero failures/errors/skips**.
- **Equivalence:** all five installed JAR bytes and 59 canonical production/build/
  consumer input fingerprints match measured c784278. The three existing evidence
  manifests remain unchanged.

```sh
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -v -s examples/fx-lab/scripts -p 'test_*.py'
REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check :examples:fx-lab:installDist --rerun-tasks --no-build-cache --no-daemon --console=plain
python3 examples/fx-lab/scripts/gradle_cancellation_probe.py /tmp/new-sc10-cancellation-probe
```

The reusable Gradle probe above creates its own temporary project, starts a real
single-use daemon and Exec child with nested run_owned, and stores its numeric
summary/log in the new output directory. Capture results and raw logs remain local;
this authored summary preserves their verification outcome. No new desktop acceptance or
performance collection was necessary for this failure-path-only QA change: the
49-case ce8efda acceptance and 44-run/92-comparison c784278 matrix keep their original
attribution and the runtime JAR bytes are identical. Full profile #15, Orca #32 and
platform coverage remain separate stable-release gates. Evidence-only commits do
not relabel the tested QA source.
