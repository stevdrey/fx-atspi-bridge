# Development Model

Canonical detail: [specs/TEMPLATE.md](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/specs/TEMPLATE.md).

Follow issue → Spec Context → implementation → verification → PR. Canonical instructions live in [AGENTS.md](https://github.com/stevdrey/fx-atspi-bridge/blob/main/AGENTS.md) and [.agents/](https://github.com/stevdrey/fx-atspi-bridge/tree/main/.agents). Use Java 27, meaningful modern features, imports and readable var locals. Select collections based on workload and ownership; prioritize security, memory and responsiveness. PR titles use [Issue-N]; descriptions use What, Why, Changes, How to test / verify, Risk & rollout, Notes for reviewers. Consult [release gates](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/verification/README.md).
