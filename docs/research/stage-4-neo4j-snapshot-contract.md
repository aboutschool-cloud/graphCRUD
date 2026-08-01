# Neo4j snapshot and Java Driver contract for Stage 4

Status: accepted research input for Stage 4 Ticket 1
Scope: Neo4j Community Edition and the Java Driver transaction, schema, batching,
consistency, cancellation, cleanup, and integration-test behavior only. No
production-code decision is implemented here.

This note separates Neo4j-documented behavior from GraphCRUD inferences. `Active
Snapshot`, job states, cleanup policy, and the backend-neutral `GraphStore`
capability contract remain GraphCRUD concepts governed by ADR-0003 and ADR-0005.

## Conclusions

Neo4j Community can implement the Stage 4 contract on one standard database with
ACID transactions, Cypher, the Java Driver, and property uniqueness constraints.
It can make one batch atomic and can atomically change an Active Snapshot pointer,
but a sequence of independently committed batches is not one atomic operation.
Therefore facts must remain isolated under a staging snapshot ID until sealing;
failed or cancelled staging data is cleanup work, never a query target.

Neo4j's default isolation is read committed and permits non-repeatable reads.
GraphCRUD must obtain one snapshot ID at query start and restrict every read to
that immutable snapshot. A multi-query read also needs protection from concurrent
cleanup (for example, one read transaction or an application-owned read lease).
Neo4j Community does not provide clustering, failover, online backup, multiple
standard databases, property-existence constraints, property-type constraints, or
node/relationship key constraints. None may be required by the core contract.

## 1. Java Driver transactions and atomic boundaries

### Documented facts

- A Neo4j transaction is committed in its entirety or rolled back on failure;
  multiple queries run in one transaction form one atomic unit. An explicit
  transaction supports `commit()` and `rollback()`, and is automatically rolled
  back at the end of its lifetime when neither is called. [Java Driver: run your
  own transactions](https://neo4j.com/docs/java-manual/current/transactions/)
- `Session.executeRead()` and `Session.executeWrite()` use managed transactions.
  The driver automatically retries failures it classifies as transient until the
  configured retry time is exhausted. Because the callback may run more than once,
  it must have the same effect on every execution and must not rely on or mutate
  external state. [Java Driver: run your own
  transactions](https://neo4j.com/docs/java-manual/current/transactions/)
- A `Driver` is safe to share, but sessions are not thread-safe; concurrent work
  requires separate sessions. Sessions should be closed, normally with
  try-with-resources. [Java Driver: run your own
  transactions](https://neo4j.com/docs/java-manual/current/transactions/)
- The database provides ACID behavior and uses a write-ahead transaction log.
  Atomicity leaves database state unchanged when part of a transaction fails.
  [Database internals and transactional
  behavior](https://neo4j.com/docs/operations-manual/current/database-internals/)

### GraphCRUD inference

One `GraphStore` batch write should be one managed write transaction. Its Cypher
must be idempotent under callback retry, using stable canonical identities and
constraints rather than client-generated side effects. Progress and job-state
events must be emitted only after the transaction returns successfully, outside
the retryable callback.

Sealing and automatic promotion of a complete snapshot should be one transaction
that validates the expected staging state, makes the snapshot immutable, and
changes the Active Snapshot pointer. Explicit partial promotion uses the same
compare-and-change boundary. The whole analysis cannot be one transaction without
defeating bounded batches and cancellation, so earlier committed staging batches
may remain after failure and require idempotent cleanup.

## 2. Constraints and idempotent schema setup

### Documented facts

- Community Edition supports property uniqueness constraints. Property existence,
  property type, and node/relationship key constraints are Enterprise features.
  Uniqueness and key constraints have backing range indexes. [Cypher constraint
  overview](https://neo4j.com/docs/cypher-manual/current/constraints/)
- `CREATE CONSTRAINT ... IF NOT EXISTS` avoids an error and creates nothing when a
  constraint with the same name, or an equivalent constraint on the same schema,
  already exists. A same-name but different constraint can also cause the command
  to do nothing, with a notification. [Create
  constraints](https://neo4j.com/docs/cypher-manual/current/schema/constraints/create-constraints/)
- `MERGE` alone guarantees that a pattern exists, not uniqueness under concurrent
  load. Neo4j recommends constraints before merging; uniqueness constraints also
  protect against concurrent duplicate creation. [Cypher
  `MERGE`](https://neo4j.com/docs/cypher-manual/current/clauses/merge/)

### GraphCRUD inference

Startup may run named uniqueness constraints with `IF NOT EXISTS`, but must then
inspect the installed schema and verify that every expected name, entity kind,
label/type, and property set matches. `IF NOT EXISTS` by itself is not a capability
check because a conflicting same-name constraint can turn it into a no-op.

Use Community-compatible uniqueness constraints for canonical node identity,
Relationship Assertion identity, Evidence Occurrence identity, snapshot identity,
and the singleton Active Snapshot marker. Validate required properties and types in
the adapter before writes; do not require Enterprise-only schema guarantees. A
missing or mismatched constraint is a clear startup capability failure, not a
reason to run with weakened correctness.

## 3. Batched writes

### Documented facts

- Neo4j recommends batching bulk parameter values into a single query with `UNWIND`
  rather than sending one query per row. Fewer, larger transactions reduce overhead,
  while a failure in a grouped transaction rolls back the whole group. Separate
  auto-commit transactions increase throughput but are not retried and do not roll
  back together. [Java Driver performance
  recommendations](https://neo4j.com/docs/java-manual/current/performance/)
- `CALL { ... } IN TRANSACTIONS` intentionally evaluates subqueries in separate
  transactions. [Cypher clauses](https://neo4j.com/docs/cypher-manual/current/clauses/)

### GraphCRUD inference

Use bounded parameter lists with `UNWIND` inside managed write transactions. Treat
each returned transaction as the durable batch boundary and record progress only
after it commits. Do not use `CALL ... IN TRANSACTIONS` where the application must
observe, cancel, and report each boundary itself. Retry-safe `MERGE` plus verified
uniqueness constraints gives deterministic duplicate handling; batch ordering must
still be canonicalized by GraphCRUD rather than delegated to Neo4j.

## 4. Read consistency and Active Snapshot switching

### Documented facts

- Neo4j's default isolation is read committed. A transaction that reads an entity
  does not prevent another transaction from changing it, traversed data is not
  protected from modification, and non-repeatable reads may occur. Serializable-like
  behavior requires explicit locks. [Concurrent data
  access](https://neo4j.com/docs/operations-manual/current/database-internals/concurrent-data-access/)
- Sessions provide a causal chain of transactions. In clustered Enterprise
  deployments, bookmarks can ensure a later transaction does not execute before a
  represented earlier state is established. [Java Driver: run your own
  transactions](https://neo4j.com/docs/java-manual/current/transactions/) and
  [clustering architecture](https://neo4j.com/docs/operations-manual/current/clustering/introduction/)

### GraphCRUD inference

Do not model Active Snapshot switching by relabeling all fact nodes: that widens the
atomic boundary unnecessarily. Store an immutable snapshot ID on snapshot-scoped
records and update one Active Snapshot marker transactionally. At query start,
resolve the requested ID or Active Snapshot exactly once; all subsequent lookups and
traversal predicates use that ID, never re-read the active marker.

Immutability prevents promotion from mixing old and new facts, but it does not by
itself prevent cleanup from deleting the selected snapshot between multiple read
transactions. Execute a bounded query in one read transaction or maintain an
application-owned read lease which cleanup honors. Bookmarks and Enterprise causal
consistency are not needed for the Community single-instance guarantee and must not
appear in the core interface.

## 5. Cancellation, failure, and cleanup

### Documented facts

- Explicit transactions can be rolled back; leaving their resource scope without
  commit also rolls them back. Managed transactions own their boundaries and may
  retry transient failures. [Java Driver: run your own
  transactions](https://neo4j.com/docs/java-manual/current/transactions/)
- A per-transaction timeout can be supplied through `TransactionConfig`; the server
  terminates transactions that exceed it. A custom Driver timeout overrides the
  server default. [Java Driver transaction
  configuration](https://neo4j.com/docs/java-manual/current/transactions/#transaction-configuration)
  and [database transaction
  timeout](https://neo4j.com/docs/operations-manual/current/database-internals/transaction-management/)
- Neo4j can list and terminate transactions, and a user may terminate its own
  transactions. [Manage
  queries](https://neo4j.com/docs/operations-manual/current/monitoring/query-management/)

### GraphCRUD inference

Promise cooperative cancellation only at application-owned boundaries: before a
batch, after a committed batch, and before sealing. If cancellation arrives during
a managed transaction, its callback may already commit or retry; cancellation must
wait for that outcome and must never report a batch as rolled back without evidence.
Use finite transaction timeouts as a safety bound, not as the job cancellation
protocol. Avoid administrative transaction termination in the portable contract.

After failure or cancellation, the previous Active Snapshot remains unchanged.
Delete abandoned staging facts in idempotent bounded transactions. Cleanup may also
remove expired sealed snapshots, but must exclude the current Active Snapshot and
any snapshot protected by an in-flight read lease. A cleanup crash merely leaves
retryable garbage; it must not corrupt query visibility.

## 6. Community Edition capability boundary

### Documented facts

- Community Edition is intended for a single-instance deployment and supports ACID
  transactions, Cypher, and programming APIs. Clustering, failover, and online
  backup are Enterprise capabilities. [Neo4j editions](https://neo4j.com/docs/operations-manual/current/introduction/)
- A Community installation has exactly one standard database; Enterprise can have
  multiple standard databases. A transaction cannot span standard databases.
  [Database administration](https://neo4j.com/docs/operations-manual/current/database-administration/)

### GraphCRUD inference

Stage 4 can promise atomic writes within one Community database, deterministic
snapshot isolation by application data modeling, explicit partial promotion, and
previous-active preservation on job failure. It cannot promise high availability,
cluster fault tolerance, online backup, database-per-project isolation, cross-database
atomicity, or Enterprise-only constraint enforcement. Those operational properties
are outside `GraphStore`; availability and backup requirements belong to deployment
policy, not canonical model types.

## 7. Integration-test implications

- Run the shared `GraphStore` contract unchanged against `InMemoryGraphStore` and a
  real Community server through Bolt. Neo4j's official installation guide documents
  starting a local Docker instance, and the official Java Reference documents the
  lightweight Neo4j Harness plus Java Driver for integration tests. [Driver
  installation](https://neo4j.com/docs/java-manual/current/install/) and [Neo4j
  Harness project setup](https://neo4j.com/docs/java-reference/current/extending-neo4j/project-setup/)
- Pin compatible server and Driver versions; do not test against a floating
  `latest` image. Assert schema capability checks on empty startup, repeat startup,
  and a deliberately conflicting same-name constraint.
- Test atomic rollback within one batch, durable earlier batches after a later
  failure, managed-transaction idempotency, cancellation at every owned boundary,
  staging cleanup retry, and sealing/promotion compare-and-change races.
- Use two sessions to exercise concurrent promotion/read and cleanup/read races.
  Assert that each query returns only its selected snapshot and that a protected
  snapshot cannot be deleted mid-query. Sessions must never be shared between the
  test threads.
- Run the Community profile specifically so the adapter cannot accidentally depend
  on multiple databases, clustering, or Enterprise-only constraints. Keep Docker or
  Harness integration tests in a separate gate from the deterministic no-Docker fast
  lane, while running the same backend-neutral contract suite in both stores.

## Ticket 2 readiness

Ticket 2 is unblocked. Its interface design can express batch write, seal, explicit
partial promotion, one-time snapshot selection for bounded queries, and cleanup/read
coordination without exposing Driver sessions, Cypher, labels, constraints, locks,
bookmarks, databases, or Neo4j transaction types. The Neo4j adapter must fail its
capability check if Community-compatible uniqueness constraints and atomic marker
updates cannot be established and verified.
