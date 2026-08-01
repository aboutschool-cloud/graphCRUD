# graphCRUD

An evidence-first code knowledge graph for tracing code paths to database CRUD facts. Its first delivery focuses on a bounded Java persistence stack.

## Analysis scope

**Phase-one target stack**:
Java services using Spring and MyBatis, with PostgreSQL SQL. Python, inter-service calls, and message-bus links are outside the first delivery.
_Avoid_: multi-language MVP, full enterprise scope

**CRUD fact**:
A source-backed assertion that a resolved code method reads from or writes to a specific database table through SQL.
_Avoid_: inferred database impact, guessed data access

**Analysis project**:
One local customer source root treated as the complete input boundary for one project graph; it is identified by a Git commit when available or by a content snapshot hash.
_Avoid_: repository federation, remote source upload

**Invocation Binding**:
An evidenced mapping from an invocation source, such as an HTTP route, UI action, scheduled task, or configuration entry, to a code symbol.
_Avoid_: assumed entrypoint, guessed framework dispatch

**Code Entrypoint**:
A code method invoked by a framework or runtime through an evidenced Invocation Binding. The concept is broad, while each delivery explicitly declares which invocation-source adapters it supports.
_Avoid_: public method, controller method, externally callable method

**Relationship Assertion**:
A canonical semantic relationship between two graph nodes that may be supported by one or more evidence occurrences.
_Avoid_: source occurrence, duplicated edge

**Evidence Occurrence**:
One snapshot-specific, source-located observation supporting a Relationship Assertion, with its adapter, evidence level, and explanation.
_Avoid_: relationship, confidence score

**Configuration Entry**:
A semantically meaningful, source-located declaration inside a configuration document that can reference code, a route, SQL, or a database object.
_Avoid_: arbitrary XML element, opaque config text

**Database Source**:
A configured logical database target that namespaces schemas and tables. Its sensitive connection values are never stored in the graph.
_Avoid_: connection string, database server

**Integration Channel**:
A customer-defined cross-system transport, such as HTTP, electronic message, or HULFT file exchange, that connects systems through an explicit contract.
_Avoid_: inferred cross-system match, same-name integration

**Evidence level**:
The confidence classification of a derived fact: confirmed, possible, or unresolved. It records what static analysis can prove rather than a probability.
_Avoid_: certainty score, runtime truth

**Active Snapshot**:
The analysis snapshot selected as the default query target. A partial snapshot is queryable but becomes active only after explicit acceptance.
_Avoid_: latest run, automatically promoted partial result
