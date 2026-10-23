# Agent entry point

Read `README.md`, `.agents/README.md`, relevant `docs/adr/`, `docs/security/` and affected source/tests before changes. Load the smallest relevant set from `.agents/skills/`.

## Mandatory rules

- Target Java 27 and the JavaFX version declared by Gradle. Use the wrapper; check the active JDK before running it.
- Prefer `var` for local variables when the initializer makes the type clear. Use explicit types for APIs, fields and ambiguous expressions.
- Use imports instead of fully qualified names; allow qualification only to resolve a real name collision. Prefer explicit imports over wildcards.
- Use Java 27 features only when they simplify a demonstrated need. Do not enable preview features by default. FFM is the native boundary; virtual threads are not a replacement for the JavaFX or GLib owner threads.
- Prioritize correctness, security, bounded resource use and responsiveness. Do not trade confidentiality or deterministic cleanup for speed. Support performance claims with measurements.
- Keep JavaFX objects on the FX thread; keep native registrations, invocation ownership and replies on the provider thread. Never hold shared locks while invoking application callbacks, actions, native blocking calls or modal dialogs.
- Bound queues, inputs and retention. Define expiry, saturation and disposal. Do not expose passwords or log field values.
- Follow Spec Context: establish observable acceptance criteria and affected boundaries before implementation; retain concise authored result summaries under `docs/verification/`, never generated test/QA/benchmark outputs.
- Keep canonical guidance in `.agents/`; provider-specific files may only point here.

Precedence: explicit task → accepted ADR → security invariants → this file and `.agents/README.md` → skills → local conventions. If a conflict affects correctness, document and resolve it; never silently weaken an invariant.
