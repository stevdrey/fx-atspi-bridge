# Issue 10 / PR 42 — performance follow-up

Historical report: the original 44-run campaign and its seven failures are
summarized in [issue-10](issue-10.md); raw captures are kept outside Git.
The historical successful qualification measured clean runtime commit
`c7842786e0373fefa593b59686c15e461941c2d1` against the original budgets,
workload, validity guards and retry policy. Subsequent evidence-only commits
retained that measured revision; recorded source hashes and all five installed
JAR hashes were rechecked then. These results do not certify the later 8264fe4 runtime;
see [current-head qualification](issue-10-review-fixes.md).

## Corrections

The complete intermediate 183a5ab campaign had six exceedances (five p99 and
one new sparse allocation at 35.136 > 34 KiB/sample). It is retained alongside
the final campaign. NodeStore kept an entire small tree in its delta under the
preferred 256-entry compaction floor. The final policy caps the trigger at half
the live cardinality and transfers the unpublished delta into its immutable base,
merging unchanged entries and dropping tombstones. Earlier views remain immutable.
Directed one-dirty measurements became 23.601/23.365/24.219 KiB/sample.

An additional directed off-control was invalid at 1002.340 ms against the unchanged
900 ms validity guard. No result JSON was accepted. A subsequent session query was
unlocked; exact occlusion was not proven. The owned benchmark window now requests
above/foreground before cadence/installation. Removing that setup-only hint gives
the original benchmark source byte for byte: sampling, mutation, idle, churn, GC
and validity code remain unchanged. The hint changes no desktop setting. A canonical
smoke validates this setup but is never budget evidence.

The budgets are therefore best-case for window state: an unoccluded, focused benchmark window.
After review the hint is an explicit `--window=foreground` option (default: the original window
behaviour). `run-baseline.sh` passes it and records `window=foreground` in `environment.txt`;
`qualification.py` reports that condition and accepts no other value. The retained final campaign
predates the line and is reported as `unrecorded-historical`; its recorded source applied the
hint unconditionally. Occluded or background-window performance is not qualified.

- Reuse one sanitized immutable geometry per observed target for name-only changes.
  Bounds, inherited transforms/clips, ancestry, showing/visibility and policy
  updates invalidate it on FX. Parent-origin changes invalidate dependent PARENT
  coordinates. Reuse/invalidation, privacy, adapters and external geometry clients
  exercise positive and adverse paths.
- Share constant button action metadata and the private semantic-state array.
- Release the large backing tables identified in disposed observer, geometry,
  registry, model and provider export state. Native deregistration/reply order,
  target identity and public installation/closed contracts are unchanged.
- Parse and emit each native signal within one confined arena. Pre-encode exactly
  eleven fixed public interface/member names in the existing Ffm arena. Dynamic
  paths, text and parameters are not cached; unknown names use the call arena.

| Resource | Owner | Lifetime / disposal |
| --- | --- | --- |
| Geometry projection | FX observer | One sanitized immutable value per target; invalidate from properties/policy/ancestry; discard on detach/close |
| Eleven encoded protocol constants | Ffm / provider | Fixed immutable map; existing Ffm arena closes after native teardown |
| Signal path, text and error slots | Provider call | One confined arena; close on success and failure |
| Parsed GVariant | Provider call | Existing ownership; unref in finally after emission/preparation failure |
| Unknown protocol name | Provider call | Temporary encoding; never admitted into the map |

No new native symbol, public API, dependency, shared lock, timer or queue is added.
Password/redaction rules, action admission/revalidation and native callback ownership
remain enforced. Geometry invalidation and per-call native cleanup are the main
behavioral review boundaries.

## Diagnostics (not qualification)

The original library JAR recovered from the user checkout matches the initial
campaign's SHA-256. A private copy of the current unchanged fixture/JavaFX installer
replaces only that library; the original checkout and unrelated files are untouched.
Intermediate diagnostic builds are separate from clean qualification.

With 10,000 nodes, 30 structural cycles and 100 awaited reinstallations, the original
control measured +1,037,720 bytes (+0.990 MiB) after GC, and the candidate measured
−1,251,432 bytes (−1.193 MiB). Both ended with one provider and zero errors. This
original control was just below 1 MiB; the initial qualification's two failures
remain retained rather than retroactively reclassified.

The original class delta included 883,232 bytes of Object arrays and 327,680 bytes
of HashMap table arrays. Those growth entries disappear after releasing disposed
tables. Class counts do not identify GC roots or prove universal leak-freedom.
A caller may retain a closed bridge; releasing its large empty tables must not
require collecting that bridge. Four local class histograms supported the numeric/class observations below;
object contents and memory maps are not published.

An allocation JFR identified repeated geometry/metadata work and native encoding.
Arena consolidation alone still measured 406.136, 404.026 and 396.025 KiB/sample in
three targeted on-runs. Fixed-name reuse then measured 379.014, 378.830 and 378.683.
All targeted failures and off-controls are retained; none replaces a matrix cell.

### Latency

Temporary pulse/FX CPU instrumentation was removed before qualification;
the setup-only source difference is described above. Raw JFR/native logs,
temporary instrumentation and reduced outputs stay local. The reusable reducer is
`examples/fx-lab/scripts/analyze_latency.py`: it reads SC10_CLOCK/SC10_PULSE log
lines and JFR JSON events from a diagnostic directory; it is not a qualification tool. The 100-node diagnostic uses 50
warmup/200 samples; 1,000-node profiles use 50 warmup/1,000 samples to inspect rare
tails. Neither substitutes for fixed-budget evidence.

A 1,000-node off-pulse measured 144.037 ms, including 127.063 ms in the FX rendering
wait and 2.651 ms FX CPU. An on-pulse measured 136.054 ms, including 125.247 ms
rendering wait and 2.066 ms FX CPU. Neither overlaps recorded GC. Stacks include
`PaintCollector.waitForRenderingToComplete` and concurrent Prism/Marlin work.
Other stalls require the additional attribution in the final diagnostic summary;
these examples do not imply every tail has one cause.

Sequential wall/monotonic anchors have 1 ms wall-clock quantization; preemption
between reads prevents certifying maximum alignment error. FX thread IDs identify
events even when GTK changes their displayed names. Unprofiled original outliers
cannot be retrospectively attributed to an exact stack.

## Verification and fixture correction

JDK 27 check and consumer/artifact gates pass: 276 Java tests, zero failures/errors/
skips. Unchanged Python tooling passes 240 tests. All 49 independent acceptance,
semantic, geometry and published-consumer cases pass. Source and all five runtime
JAR hashes match across the functional and performance campaigns. [CI run 143](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39716825789) passes.

Real-desktop checks exposed late focus and placement during supposed idle setup.
A directed failure recorded `State(focused)` changes; observer setup recorded three
`BoundsChanged` changes. Fixtures now await native focus. Observer setup drains
invalidations until 600 ms quiet, with a five-second deadline; it asserts one initial
publication separately, then tests the same 300 ms idle window against its settled
baseline. The bridge's 500 ms idle window is unchanged. A periodic rescan cannot
satisfy the quiet interval. Controls are built on FX and the harness closes. Failure
logs remain local; value-free change-kind evidence is retained. No benchmark budget
or timing changes.

## Continuity

An earlier follow-up on b6619dc was interrupted. Its /tmp raw profiles/partial
matrix did not survive the session restart. Rounded conversation observations
include a third allocation failure at 411.914 KiB/sample; they motivated further
native corrections but are not qualification. The original 44-run outcome and failure history remain in the authored summary.
The successful c784278 campaign started fresh on a different runtime revision.
Raw outputs, including intermediate failures, are retained outside Git.

## Final quick matrix

**44/44 valid runs, zero retries; 92/92 comparisons passed; 0 exceeded unchanged limits.** Campaign: **Passed**; stable release: **Blocked**.

| Original failed run | Metric | Original | Final | Limit | Final cell |
| --- | --- | ---: | ---: | ---: | --- |
| `n100-d100-on-r1.json` | allocation (KiB/sample) | 469.711914 | 379.248047 | 403 | Passed |
| `n100-d100-on-r2.json` | p99 (ms) | 32.992678 | 17.828651 | 29 | Passed |
| `n100-d100-on-r2.json` | allocation (KiB/sample) | 472.705078 | 379.244141 | 403 | Passed |
| `n100-d100-on-r3.json` | allocation (KiB/sample) | 482.817383 | 379.587891 | 403 | Passed |
| `n1000-d1-on-r2.json` | p99 (ms) | 132.821104 | 20.580543 | 42 | Passed |
| `n10000-d10-on-r1.json` | install heap (MiB) | 1.256462 | 0.082146 | 1 | Passed |
| `n10000-d10-on-r2.json` | install heap (MiB) | 1.207573 | 0.056953 | 1 | Passed |

No final numerical exceedance is hidden by a median or replacement run. Passing these recorded cells does not prove universal elimination of rendering tails.


This is the complete 44-cell SC-01 quick profile. The larger full profile remains
#15. Every valid observation and validity-triggered retry is retained. No replacement
budget, median substitution or valid-outlier exclusion is used. The benchmark
includes application layout/rendering in mutation-to-pulse completion; it does not
measure AT-SPI round trips.

Orca (#32), native Wayland and other-platform coverage remain separate release gates.
The PR review state does not certify release readiness.

## Reproduction and raw-result retention

Keep new output directories outside Git, in owner-only temporary storage. From a
clean measured commit with JDK 27 and the supported graphical session:

```sh
qa_dir=$(mktemp -d /tmp/fx-atspi-sc10.XXXXXX)
chmod 700 "$qa_dir"
python3 examples/fx-lab/scripts/qualification.py collect . "$qa_dir/functional"
bash examples/fx-lab/scripts/run-baseline.sh "$qa_dir/quick" quick
python3 examples/fx-lab/scripts/qualification.py report . "$qa_dir/functional" --performance "$qa_dir/quick"
```

Report exit 1 means a recorded failure, not permission to replace its value.
Preserve failed/invalid attempts and retries locally. Publish only authored aggregate
results, exact measured commit/JAR hashes, environment, commands and limitations.
The original 5eb7f1d and intermediate 183a5ab failures remain attributed to those
revisions; c784278's 44/92 pass is historical. No raw JFR, heap dump, native memory
map, field value, manifest or per-run output belongs in this PR.

## QA review follow-up

The [Codex review follow-up](issue-10-review.md) hardens JDK, canonical-matrix and
source-bound rebuild gates without changing this measured runtime. A copy of the
44-run campaign passes the new validator with all raw digests and 92 numeric
comparisons unchanged. Fresh acceptance and exact runtime JAR equivalence are
recorded separately; this report remains attributed to c784278.
