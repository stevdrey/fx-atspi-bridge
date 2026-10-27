# Verification — Issue 21

Spec: [Issue 21](../specs/issue-21.md); historical measurements: [Issue 17](issue-17.md). The implementation is tooling and documentation only. Historical collection commit `87d8c94796c8b761ce33673819c4bd37664589ae`; library revision and environment are recorded in Issue 17. Current checks do not remeasure it.

## Evidence status and retrieval

The historical checkout contains 21 run JSON files and `environment.txt`. An inventory generated from that checkout enumerated 513 expected raw artifacts; all 513 were absent from that location. Their original hashes and sizes are unknown. This is a location-specific finding, not proof that no private copy exists. No accessible private archive, owner, expiry or retrieval method for the original captures has been established. Thus original artifact-derived tables cannot currently be independently regenerated or validated; the Issue 17 values remain historical claims, not newly verified measurements. The retained JSON can reproduce only the analyzer's JSON-only tables. If an authorized owner locates the originals, independently establish their provenance before treating a new inventory as trustworthy.

## Private retention and review workflow

The investigator owns a protected local collection and manifest, grants access only to named authorized reviewers through a verified private access mechanism, and records the owner, reason, audience, access controls, expiry and cleanup responsibility outside the repository. Use the shortest practical period, remove the collection and inventory when the investigation ends, and confirm removal. No private archive or access mechanism is assumed to exist. Public CI artifacts and Git are not private storage. Never share credentials or signed URLs. Follow the [repository retention policy](README.md#retention-and-exceptions); no generated inventory, run JSON, logs or snapshots may be added to Git.

On a trusted, access-controlled collection, create the manifest as soon as possible after collection, retain it separately under the same access controls, and establish its authenticity through an independent trusted channel. Record collection commit, JAR hashes, flags, tool versions and any unavailable provenance in the protected environment record; the inventory hashes that record and uses null for missing files. A retrospective inventory only attests to bytes present when it was generated. `pmap` may contain command lines and paths; logs and environment may contain secrets, and histograms may contain application class names. Other snapshots may also reveal machine details. No raw type or generic summary is automatically safe to publish: review each proposed derivative manually, and retain it privately if analysis or confidentiality cannot be preserved. The tool does not certify redaction.

From the repository root, with a private directory and a new private manifest path:

```sh
python3 examples/fx-lab/scripts/memory_evidence.py create <collection> <private-manifest>
python3 examples/fx-lab/scripts/memory_evidence.py verify <collection> <private-manifest> --mode full
python3 examples/fx-lab/scripts/memory_diag.py <collection> > <private-summary>
```

Stop if verification fails. Full mode checks all planned bytes and the existing parser validations; it is not proof of origin. For a collection without raw captures, `--mode json-only` checks only the environment and JSON and explicitly reports that raw evidence was not verified. Protect the files against modification between verification and analysis. Analyzer diagnostics and output can themselves include sensitive material; keep them private and inspect before sharing.

If originals are unavailable, a new collection can exercise the same methodology but cannot recreate the original samples. Use the prerequisites and `diag` command in [Issue 17](issue-17.md), on the intended revision and a clean tree, with an output directory outside Git. Differences in harness revisions, environment, instrumentation and backend must be recorded; do not label new results as the original collection. See [Issue 15](issue-15.md) for the distinct baseline contract.

## Verification performed

On 2026-10-27, Linux x86_64 with Zulu OpenJDK 27 (`27+35`) and Gradle wrapper 9.8.1:

- `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_memory*.py'`: 33 passed.
- `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'`: 299 ran, passed with one skipped. Socket-dependent tests required host access; the initial sandboxed attempt had 18 socket permission errors.
- `GRADLE_USER_HOME=<temporary-cache> ./gradlew check --offline --no-daemon`: passed with host socket access; 10 tasks were up to date. The initial sandboxed run could not access the usual wrapper lock, and the temporary-cache retry could not initialize Gradle's network service inside the sandbox.
- Historical inventory: JSON-only verification passed for the current retained bytes; full verification failed as expected with 513 missing raw entries. Inventory created locally is not original provenance and is not committed.

No original raw-table reproduction, desktop qualification, fresh benchmark or archive access test was performed.
