# Spec Context — Issue 6 (SC-06)

## Context and source evidence

[#6](https://github.com/stevdrey/fx-atspi-bridge/issues/6), baseline `dc1451e`.
Read ADR 0001, system design, security invariants and the contracts implemented by
#3, #4, #5 and #7. `serve()` dispatches up to 64 nonblocking GLib iterations then
sleeps 5 ms. That polling also drives action expiry. `drainEmbed()` polls during
its bounded cancellation drain. Publications and completions currently do not wake GLib.

## Goal and non-goals

Block when idle; wake for published work, FX action starts/results, bus closure or
stop. Preserve action deadlines, bounded admission, snapshot coalescing, privacy,
owner-thread replies and the public API. No generic scheduler, per-action native
sources, automatic reconnect or new executor.

## Contracts and design

An internal wakeup gate holds one native wake callback and a monotonic notification
sequence. Work is published before notification. The provider captures the sequence
before draining completions, expiring actions, reconciling snapshots and dispatching
at most 64 ready GLib iterations. It arms the nearest deadline and rechecks stop,
mailboxes and sequence before one blocking iteration. Notifications after this last
check interrupt that iteration, including notifications before it enters GLib.
Never suppress a wake based on a racy "waiting" flag.

The gate serializes each nonblocking native wake with detachment. Detachment happens
before native teardown; a notifier already in flight finishes before detachment
returns. Late signals update only Java state. No shared lock crosses iteration,
application code, replies or resource destruction.

The coordinator scans at most 256 admitted commands for the nearest unanswered
queue/running deadline (O(n), no new retained index). FX publishes RUNNING and wakes
before invoking the handler; it also wakes after offering a completion. Deadlines
captured at admission remain unchanged by configuration updates. One provider-owned
one-shot GLib timeout source is armed for the earliest deadline, rounded up to
milliseconds; none is retained without a deadline. A timed-out running command keeps
its completion reservation until the real result arrives. Completion capacity stays
256 and snapshot publication retains the existing latest-generation/touched-id merge.

| Resource | Owner / lifetime | Release |
| --- | --- | --- |
| FFM arena, typed handles, callback stubs | provider; native lifetime | arena closes after sources, signal handlers, exports, connection and context |
| Main context | provider; one reference | detach wake gate, pop thread default, unref exactly once |
| Wake callback capture | gate; between attachment and teardown | clear under gate before context/arena release |
| Action deadline source | provider; at most one | destroy and unref when replaced, fired or stopped |
| Embed cancellation deadline source | provider; teardown only | destroy/unref after callback or one-second drain deadline |
| Connection closed handler | provider; connection lifetime | disconnect before close/unref; callback only records closure and notifies |
| GDBus registrations/node info and invocation refs | existing provider ownership | unchanged exactly-once unexport/reply/unref paths |
| GVariants and temporary native strings | existing call-scoped ownership | existing finally blocks/confined arenas |

## Limits and failure modes

The 64-iteration dispatch budget prevents sustained bus traffic from starving Java
work. Stop is idempotent, nonblocking and wakes idle dispatch; queued work never
starts after stop. Bus loss causes sanitized failure and teardown without reconnect.
Timeout callbacks only return control to the provider, never invoke application
callbacks. Embed cancellation retains its one-second bound using blocking dispatch.
Existing one-second flush cancellation and startup synchronous-call limitations stay.
Partial startup failures detach the gate and release acquired resources. Native
callback exceptions are contained and no peer text enters diagnostics.

## Dependencies and affected files

`AtSpiProvider`, `CommandCoordinator`, `Ffm`, a new internal wakeup gate and tests.
Prerequisites #3 safe native lifetime and #5 push publication are present; #4/#7
action deadlines and update acknowledgements must keep working without polling.
Benchmark infrastructure is test-only; no MCP component is added.

## Acceptance and verification

Use barriers to exercise notifications before/around/during wait and detach races;
native context tests prove a pre-wake interrupts the next blocking iteration.
Test burst publication/completion, idle stop, repeated start/close, bus loss,
pending Embed, queue expiry, running expiry shorter than queue timeout, late
completion, limit changes and absence of idle deadline sources. Assert invocation
and export counters balance. Run REQUIRE_NATIVE_BUS=1 focused tests and full check
with JDK 27 / JavaFX 27 / GLib/GIO/GObject.

Collect five paired before/after bridge off/on repetitions at 100/1,000/10,000 nodes,
10 dirty nodes, 50 warmups, 200 samples and 10 seconds idle. Retain CPU, voluntary
context-switch proxy, FX latency and independent libatspi request-to-event latency.
Use identical harnesses, clean commits, jar/environment hashes and existing display
validity guards. Compare the immediate baseline; SC-01 remains provisional and the
full matrix remains #15. Record actual commands/results in ../verification/issue-6.md;
raw data is a separate artifact, never invented or silently waived.

## Rollout and documentation

Update README/system design with actual dispatch/lifetime/deadline behavior.
No public API, privacy, Unicode or reply contract change. Rollback is reverting the
implementation commit. Do not claim desktop qualification from private-bus tests.
