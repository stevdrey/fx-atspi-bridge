# Spec Context — Issue 16 (QA-02)

## Context and source evidence

[Issue #16](https://github.com/stevdrey/fx-atspi-bridge/issues/16), baseline
`37fe492331561fecb44d164350bea45c900d68d3`. Recover the unmerged QA-only
client/tests from `b7d71701248ed09edcc6af50826c1fb8e5cd5924`, adapting them
without merging its old runtime base. SC-01 established discovery only;
SC-03 separately exercised lifecycle with a different fixture. Neither establishes
current fx-lab desktop interoperability or screen-reader compatibility. SC-10
already verifies related interactions with a different disposable fixture;
QA-02 exercises the actual interactive laboratory on the desktop accessibility bus.
The existing FxLab exposes all required interactions. AtSpiProvider implements
SetTextContents/GetText, code-point CharacterCount and text/state/children events;
SnapshotDiff coalesces changes. Tests of these internals are not desktop evidence.
See [ADR 0001](../adr/0001-library-and-event-driven-design.md),
[security invariants](../security/security-invariants.md) and the
[threat model](../security/threat-model.md).

## Goal and non-goals

Record Passed/Failed/Not tested for backend, independent-client interactions,
Unicode, events, windows, scenes and assisted Orca navigation on a real desktop.
No library changes, API changes, refactors, MCP, new platform builds or fixes to
discovered product defects. No compatibility claims from Xvfb or unit tests.

## Contracts and design

New `examples/fx-lab/scripts/desktop_qa.py --pid PID --out DIR` targets exactly
one freshly launched fx-lab provider with that OS PID and the expected application
name/tree. It must reject ambiguity and fixture mismatch before mutation. The
independent client uses PyGObject/libatspi, with the existing read-only probe
unchanged. Subscribe before actions; correlate source identity, event type and
details with each transition, and independently verify final state. Re-discover
after scene restoration; do not assume removed object references survive.

Invalidate libatspi caches before tree/text/state reads. Drain events until 200 ms
of quiet before each scenario, bounded by that scenario's deadline. Callbacks
filter by the application's local bus identity without synchronous D-Bus calls.
Ignore GTK's separate provider for the same PID unless its application name also
matches; two matching providers are ambiguous. Only inspect foreign application
PIDs during discovery, never their contents.

The client runs outside the JVM. Production FX/provider thread ownership and
resource lifecycle remain unchanged. The client owns and deregisters listeners;
the collector owns only the lab/Orca processes it starts. Capture no other
application's tree, text or event payload. Use synthetic Unicode text only.

## Limits and failure modes

Bound discovery to 256 desktop applications, traversals to 5,000 nodes/lookups,
individual names to 256 characters, total run to 180 seconds, each scenario to
10 seconds and individual AT-SPI calls to 2 seconds (startup timeout 5 seconds).
Retain at most 1,000 matching events; overflow, timeout, missing events, wrong
state, application disappearance and incomplete steps fail instead of passing.
Only fixed check names, bounded identities and synthetic-text comparison results
are retained. Never serialize arbitrary exception messages or event payloads.

## Dependencies and affected files

JDK/JavaFX 27 from Gradle, real Fedora/GNOME session, installed PyGObject/Atspi,
X11 window properties for backend attribution, installed Orca plus the user's
audible observations. Add desktop client and offline tests, this spec,
`docs/verification/issue-16.md` and an updated SC-01 compatibility matrix.
Captures/logs stay outside Git, under `/tmp` or ignored build output, following
[the publication policy](../verification/README.md#result-summaries-and-publication).
No library source or Gradle dependency changes. Other distributions are optional;
explicitly record unavailable environments.

## Acceptance and verification

- Backend attributed to the actual lab PID, not inferred from session variables.
  Record native-Wayland startup feasibility with the same artifact. Repeat the
  suite and existing quick benchmark in both modes only if both are available.
  Native startup has a 15-second bound with DISPLAY removed, GDK_BACKEND=wayland
  and no substitution of JavaFX artifacts. Record startup failure as unavailable,
  never as successful native-Wayland qualification. Reuse #15 only as attributed
  historical performance evidence when a two-backend comparison is unavailable.
- Set synthetic text `A🧪éZ`: five code points, six UTF-16 units. Verify full text,
  count and subranges spanning the emoji and combining sequence.
- Agregar disabled → enabled → append verified; Limpiar empties both fields and
  disables Agregar. Require relevant text and enabled-state events.
- Dynamic field add/remove, second window open/close and alternate scene/restore
  have the expected tree and ChildrenChanged events. Restore the fresh lab state.
- Assisted Orca smoke records what the user actually heard during keyboard
  navigation/editing/activation, including silence. Startup alone is not Passed.
  The user chose to repeat assisted listening; link the known failure to
  [#32](https://github.com/stevdrey/fx-atspi-bridge/issues/32) if it repeats.
- Offline tests reject missing events, wrong outcomes, ambiguous PID, disappearing
  application, traversal/deadline/event limits and incomplete result sets.
- Run Python tooling tests and wrapper check/POM/installDist on JDK 27; record
  actual commands, versions, code/artifact hashes and limitations under verification.

## Rollout and documentation

Prepare QA tooling and authored summaries on `codex/issue-16-desktop-qa`.
Preserve historical SC-01 measurements. Check duplicates and draft focused issues
for confirmed product failures without fixing them here. Push, PR/Issue creation
and completion comments require separate explicit authorization. Close #16 only when every criterion has a
recorded result or documented inability as permitted by the issue. Revert tooling
and docs to roll back; no runtime migration or release-readiness claim.
