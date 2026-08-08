# Distribute Neo4j Community as a separate GPLv3 aggregate

GraphCRUD's pilot-ready offline bundle includes a digest-pinned Neo4j Community
image as the default, ready-to-run GraphStore option. This is an aggregate
distribution of independent programs, not a combined work:

- GraphCRUD does not copy, link, or modify Neo4j GPL source code.
- GraphCRUD and Neo4j run as separate processes in separate container images.
- They communicate only through the public Bolt protocol.
- Customers may remove or replace Neo4j completely.
- The GraphStore interface remains independent of Neo4j-specific implementation
  and every replacement must pass capability checks and the shared contract suite.
- Customer contracts preserve GPLv3 rights without additional restrictions for
  the Neo4j component while GraphCRUD retains its separate licensing terms.

The offline bundle conveys the exact Corresponding Source for the shipped Neo4j
image and its Docker build inputs alongside the object code. It also includes the
GPLv3 text, copyright and modification notices, installation/replacement
information, SBOMs, license inventory, and SHA-256 checksums. GraphCRUD must not
ship a modified Neo4j image unless the corresponding modifications and build
materials are included under GPLv3.

This decision approves the Community Edition aggregate profile only. It does not
authorize Neo4j Enterprise distribution or imply that an arbitrary graph database
works without a GraphStore adapter and contract verification.
