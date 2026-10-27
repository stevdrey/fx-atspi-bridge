# Spec Context — Issue 21

Issue [#21](https://github.com/stevdrey/fx-atspi-bridge/issues/21), following [#17](issue-17.md) and coordinated with [#15](../verification/issue-15.md). Baseline: `240d02b`. The Issue 17 analyzer checks the plan and parses complete raw collections but previously had no integrity inventory. The later [retention policy](../verification/README.md#retention-and-exceptions) supersedes the issue's proposal to publish per-run JSON or generated outputs. [Security invariants](../security/security-invariants.md) prohibit disclosing local data. [ADR 0001](../adr/0001-library-and-event-driven-design.md) preserves library boundaries.

## Goal and boundaries

Provide a private, reproducible inventory and integrity check for planned runs and their diagnostic files, with explicit missing entries and JSON-only versus full modes. Preserve existing analysis and collection APIs; no runtime library, JavaFX, provider thread, or production dependency changes. No new performance claim or raw publication. The manifest is a private generated index, not a committed artifact. Existing historical files remain untouched.

## Contract and limits

`memory_evidence.py create` enumerates the plan and run JSON using `memory_diag`, hashes the environment, each JSON, and each expected checkpoint artifact, and records unavailable files as null. It copies only a restricted commit and JAR hash allowlist into metadata; flags and versions remain in the hashed private environment. It creates a new owner-only manifest exclusively. Verify compares the full planned set and recorded size/hash, rejecting missing, changed and symlinked files. Full mode additionally invokes the existing artifact parsers; JSON-only mode verifies only environment and run JSON and says raw evidence is unverified. Run verification immediately before analysis against a protected, unchanged collection. These separate operations cannot prevent concurrent modifications; archive access must prevent them. A manifest is not a signature and cannot establish original provenance when generated retrospectively. A missing original cannot acquire a historical hash by recollection.

Diagnostics, environment, manifest and analyzer output can all contain machine or application details. No automatic transformation is certified safe for release. Errors from the new CLI are fixed and do not reproduce input. Sanitization tests exercise options, paths, environment and logs. Access, retention and reproduction procedure are recorded in the [verification summary](../verification/issue-21.md). The script has no network or storage service and requires Python standard library only.

## Acceptance and verification

Verify complete synthetic evidence, changed, omitted, absent and symlinked artifacts, JSON-only distinction and non-disclosure of sensitive values. Inventory historical retained JSON and explicitly report gaps; do not assert unavailable original hashes or access. Run Python tests and applicable Gradle check, recording outcomes separately. Existing collected data is not an immutable trusted source merely because it hashes successfully today.
