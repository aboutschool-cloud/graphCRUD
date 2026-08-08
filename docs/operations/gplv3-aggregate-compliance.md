# GPLv3 compliance for the optional Neo4j Community component

The offline package is an aggregate distribution, not a combined program. GraphCRUD and Neo4j are separate images, separate processes, and communicate only through the public Bolt network protocol. GraphCRUD neither copies, links, modifies, nor incorporates Neo4j source code. Customers may remove Neo4j and configure any compatible `GraphStore` deployment.

For every offline package that contains the Neo4j Community object-code image, the release owner must:

1. Pin and record the exact Neo4j image digest, edition, version, architecture, and the corresponding upstream source commit.
2. Include the complete corresponding source in a durable offline form. This package uses a source-tree ZIP made directly from Neo4j tag `5.26.29` at commit `e1d373ea096b22961d1b944d9aaa85f999764c02`; it must be extractable without network access. The adjacent source manifest preserves the verified upstream identity.
3. Include the unmodified GPLv3 license text and preserve copyright, license, and warranty notices from the image and source.
4. Provide the scripts and configuration needed to run the supplied object code. Do not impose keys, contractual restrictions, access controls, or technical measures that prevent recipients from studying, modifying, rebuilding, or replacing the GPL component.
5. State clearly in product notices and contracts that GPLv3 rights apply without restriction to Neo4j and other GPL-covered components. Commercial GraphCRUD terms apply only to GraphCRUD and cannot narrow those GPL rights.
6. If Neo4j or its image is modified, retain the modifications as source, mark changed files and dates, provide build/install information required by GPLv3, and complete a new legal review before release.
7. Retain the released image, corresponding-source bundle, checksum manifest, SBOM, license inventory, and release record together for the support/compliance retention period.

The official Neo4j 5.26.29 Community image also contains an optional Aura fleet-management plugin whose embedded notice requires a separate commercial license. A normal `RUN rm` would leave its bytes in an inherited OCI layer, so the release process exports the merged filesystem after removing that one JAR, normalizes it reproducibly, and reconstructs the delivery image `FROM scratch`. The offline verifier audits every saved-image layer as well as the running filesystem for the plugin path. The plugin is not required by Community or by Bolt communication. No Neo4j GPL source or GPL binary is modified.

The customer-facing installation guide must also say that the bundled Neo4j is optional and replaceable. This checklist is an engineering distribution control, not legal advice; counsel approves the final notices and contract language.
