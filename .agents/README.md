# Repository guidance

Use issue → Spec Context → implementation → evidence → PR. Reuse verified context until relevant state changes. Inspect current source rather than inferring behavior from roadmap proposals.

Architecture and security documents describe a target, not completed functionality. Keep accepted decisions separate from proposed contracts. Implement in small compatible steps. Record durable deviations in an ADR.

Load skills from `skills/README.md`. Run narrow meaningful checks during development and the applicable full build once at completion. Report unavailable tests precisely. Never claim desktop QA based only on unit tests. Use a Fedora/GNOME/Wayland session for the supported desktop qualification; Xvfb is only an additional harness.

Publish reviewed changes on a topic branch and PR. PR titles use `[Issue-N]`, with real issue numbers. Avoid redundant remote reads or repeated passing checks. Do not weaken repository protections or request extra host privileges just to simplify a task.
