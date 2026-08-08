# Stage 6 Pilot-ready evidence

This evidence package is separate from the Technical MVP report. It is valid only for the exact commits, image digests, source archive, scanner databases, exceptions, and checksums recorded by the release scripts.

| Gate | Executable evidence |
|---|---|
| Corpus compatibility and stable semantic hashes | `release/run-corpus.ps1`; `release/corpora.json`; `release/corpus-results.json` |
| Comparable performance policy | `release/verify-performance.ps1`; `PerformanceGate` contract tests |
| Reproducible JVM distribution | `release/verify-reproducible-distribution.ps1` |
| Immutable Compose and external graph option | `release/compose.yaml`; `release/compose-external.yaml`; Stage 6 package contract test |
| Offline Linux x86_64 / WSL2 install | `release/prepare-offline-bundle.ps1`; `release/verify-offline-bundle.ps1` |
| SBOM and license inventory | Syft SPDX artifacts; `release/verify-license-inventory.ps1`; reviewed overrides |
| Security scan and expiration policy | Trivy JSON artifacts; current database metadata; `SecurityPolicy` tests; `release/security-exceptions.json` |
| GPL aggregate and commercial boundary | ADR-0007; exact corresponding source ZIP; GPL text; sanitized Neo4j Dockerfile; compliance checklist |
| Operations/privacy/support | `docs/operations/stage6-offline-operations.md`; Stage 5 redaction and delivery tests |

Final release verification requires `ciFast`, Neo4j integration tests, Stage 6 package tests, distribution reproducibility, performance comparison, license verification, offline bundle verification, and a clean two-axis milestone review from commit `32665a6`. Remote CI must succeed for the pushed release commit before milestone closure.

Known limits remain explicit: corpus snapshots are `PARTIAL` without full customer build classpaths; corpus results are not accuracy truth; only the documented GraphStore/Neo4j delivery shapes are approved; the time-bounded security exceptions expire on 2026-08-16 and require image replacement or renewed review.
