# Verification — Issue 24

Spec: [Issue 24](../specs/issue-24.md). This is a partial implementation and tooling check, not a completed memory campaign or a release qualification.

## Checks

- JDK: Zulu OpenJDK 27+35; Linux desktop session reported Wayland. Source baseline before local edits: `bddc215`.
- `GRADLE_USER_HOME=<private-cache> ./gradlew check :examples:fx-lab:installDist --no-daemon --offline`: passed after correcting a compilation error. Gradle reused unchanged tasks; this is not desktop qualification.
- Python script suite: 313 tests passed, one skipped, with local socket access. An initial sandboxed run failed 18 socket-creating tests due to denied socket creation.
- Direct reduced checks: 100 nodes with bridge on and 100 awaited close/install cycles recorded one provider at points 0 and 100; 100 nodes with bridge off and 30 structural cycles recorded zero providers at points 0 and 30. These are tooling smokes, not measurements of the target workload.

## Outstanding evidence

The full 1,000-install and 150-cycle structural campaign has not been collected. No growth or plateau conclusion, confidence estimate, root path, allocation stack, or comparison with #17 is claimed. `heaptrack` was not present in PATH; a heap-dump analysis tool was not identified. Internal lifecycle tests verify acquisition/release balances in their own workload; campaign-level counters are not exposed. A complete campaign needs a clean committed revision and an unlocked supported graphical session. Its inventory must verify before the private analysis is reviewed. Record failures, environment, instrumentation differences, retention ownership and sanitized aggregate results here after collection.

Suggested commands after preparing the clean revision and protected output locations:

```sh
examples/fx-lab/scripts/run-memory-diag.sh <private-collection> long
python3 examples/fx-lab/scripts/memory_evidence.py create <private-collection> <private-manifest>
python3 examples/fx-lab/scripts/memory_evidence.py verify <private-collection> <private-manifest> --mode full
python3 examples/fx-lab/scripts/long_memory_diag.py <private-collection> <private-manifest> > <private-summary>
```

Do not commit the collection, inventory or generated summary. No separate fix issue is justified by these smoke checks.
