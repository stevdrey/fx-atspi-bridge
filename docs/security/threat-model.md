# Initial threat model

Scope: in-process JavaFX bridge, FFM/GIO and same-session AT-SPI bus. Static design assessment of baseline c29e8f3, not an executed vulnerability scan.

Assets: user-entered secrets/data, UI integrity/responsiveness, heap/native resources and process availability. Trust boundaries: external bus client → provider; provider → FX action handler; JVM → native ABI; repository source → wiki publication.

| Threat | Current exposure / proposed mitigation | Evidence required |
|---|---|---|
| Session client reads/acts on sensitive UI | No bridge-specific sender policy; passwords excluded. Add exclusion/redaction and configurable capability policy | Adversarial client checks all interfaces/events/cache |
| Request flood or oversized text | Pending map lacks explicit capacity/expiry | Burst and size limits, stable rejection, no late action |
| Modal action stalls transport | mutate holds global lock during handler | Real nested modal loop, concurrent reads and close |
| Native leak/use-after-free | Arena lifetime and callback/invocation lifetimes intertwined | Resource accounting and churn/close/failure tests |
| Stale/hidden/modal-blocked action | Initial state validation is insufficient for queued actions | Remove/reparent/disable between admission and execution |
| Wiki credential/content corruption | Workflow writes to independent wiki git repository | Token-free URLs, controlled branch, manifest and no-op tests |

Residual risks: a same-user desktop compromise is outside strong isolation guarantees; malicious application handlers cannot be safely interrupted; native faults can terminate the JVM. Automatic reconnect, sandboxing and a separate native helper require additional design. Report security issues privately via the maintainer's GitHub contact; do not publish secrets or exploit data in public issues.
