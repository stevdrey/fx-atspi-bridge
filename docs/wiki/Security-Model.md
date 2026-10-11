# Security Model

Canonical detail: [security/security-invariants.md](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/security/security-invariants.md).

Treat session-bus clients as untrusted. Redact sensitive data, revalidate actions on FX, bound resources and preserve native ownership. See the [threat model](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/security/threat-model.md). A same-session bus is not a private channel to one agent.
