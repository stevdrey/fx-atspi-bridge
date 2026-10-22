# Issue 43 — trusted workflow bootstrap verification

Baseline `36dbb5a`, Linux, Node.js 24.21.0. This is the separate prerequisite of
[PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45), not a claim that the
trusted companion is already installed or required on main.

| Check | Actual outcome | Reproduce / limitation |
|---|---|---|
| Canonical CI policy suite in the implementation worktree | Passed: 17 tests | `node --test .agents/scripts/pr-policy.test.mjs`; both embedded workflows, thresholds, transient evidence, complete pagination, API failures and inherited merge blobs |
| Bootstrap worktree suite | Passed: 17 tests, zero failures/skips | `node --test .agents/scripts/pr-policy.test.mjs` executed in this worktree |
| Required-check read-back | Existing PR file count is required, app 15368, no bypass | Ruleset 25669164 remains untouched during bootstrap preparation |
| Trusted base execution | Not run: workflow is not yet on main | Merge bootstrap after review; observe the target-event job on #45 before adding it as a second required check |

The trusted workflow reads only metadata and executes its own base-revision inline
policy; no checkout, PR source execution or artifact downloads occur; API patch fields are ignored. Stubbed tests
are not a live bypass/merge demonstration. Keep both required contexts once the
companion is activated. No raw output, snapshots or generated evidence are committed.
