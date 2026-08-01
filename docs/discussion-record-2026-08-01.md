# Phase 1 design discussion record

This document preserves the decisions reached in the design interview before implementation begins. It is a structured record of the conversation, not a runtime specification replacement; the normative Phase 1 scope is in `phase-1-spec.md`.

## Product intent

GraphCRUD is an evidence-first system for tracing a customer-owned source root through Java code, configuration, SQL, database objects, and eventually cross-system contracts. The first customer-facing question set is:

1. Which entrypoints can write a table?
2. Which tables can a Java method read or write?
3. Which entrypoints, methods, configuration, triggers, and routines should be reviewed when a table changes?

The answers are static structural evidence, not claims about business correctness, runtime volume, performance, or every dynamic path.

## Scope and implementation sequence

- Phase 1 fully implements Java, Spring, native MyBatis, direct JDBC, Spring JDBC, PostgreSQL, and static database routine/trigger analysis.
- Kotlin/JVM on JDK 21 is the implementation runtime. Eclipse JDT is the authority for Java syntax, types, and call resolution. Tree-sitter is only a lightweight scanner; Graphify is not in the main path.
- PostgreSQL is the first complete dialect. Oracle is the next dialect adapter because it is common in customer environments; the core model already supports Packages and Routines.
- Development proceeds in vertical test-first slices: golden fixture and in-memory facts; JDT and JDBC; MyBatis/SQL/schema; GraphStore and API; then evaluation and customer packaging.

## Graph, evidence, and identity

- All adapters emit immutable intermediate facts. A resolver creates the canonical graph; storage never contains parsing rules.
- Every fact keeps source location, adapter, snapshot, and one of `confirmed`, `possible`, or `unresolved`. Default queries use confirmed facts only.
- Stable node identity derives from project, kind, and canonical key. A code symbol has language, module, signature, and source location; future JavaScript, Shell, Python, and other-language adapters share this model.
- Configuration is first-class. `Configuration Document` represents XML/YAML/properties; only semantic `Configuration Entry` nodes are created, not every XML element.
- A class-and-method declaration resolves to a method; a class-only declaration resolves only to a type. Unknown references retain their raw value and reason.

## Entrypoints and UI

- The generic shape is `Invocation Source → Invocation Binding → Java Method`.
- Initial sources include Spring request mappings, old URL-to-class/method mappings, `@Scheduled`, `main`, and manually provided UI Actions.
- An `HTTP Endpoint Binding` is a node with method, raw route, route shape, evidence, and source. `ANY` represents legacy bindings with no HTTP method restriction.
- Route shape ignores path-variable names but preserves URL case and treats trailing slash equivalence only when the adapter declares it. Legacy wildcard routes remain patterns.
- UI Action is a source node. The MVP reads it from `.graphcrud.yaml`; future JSP and JS/TS scanners will produce the same model.
- JSP Phase 1 extension scope is static forms, links, and known tag-library routes. Scriptlets and runtime URL construction remain possible or unresolved.
- Query results retain configuration nodes by default for auditability and may offer a simplified, folded view.

## Java resolution and persistence

- Direct calls resolve to their compiled declaration. Implementations discovered through type hierarchy or DI are possible until a unique binding proves them confirmed.
- Spring annotations and common Spring XML Beans/refs are parsed. FactoryBean, SpEL, conditional runtime factories, AOP, and unknown reflection remain conservative.
- Statically recoverable `Class.forName`/`getMethod`/`invoke` calls are resolved; runtime-determined reflection is unresolved.
- MyBatis XML CRUD tags and CRUD annotations are in scope. MyBatis conditional branches emit possible table facts; providers and runtime-generated SQL remain unresolved unless fully recoverable.
- JDBC, `JdbcTemplate`, and `NamedParameterJdbcTemplate` static SQL are in scope. JPA/Hibernate, MyBatis-Plus Wrapper, and other query builders are later adapters.
- SQL is analyzed whenever it can be statically reconstructed, including constant Java strings, text blocks, constants, and deterministic concatenation. Runtime-dependent fragments remain unresolved.

## Database semantics

- SQL records separate reads and writes: `INSERT … SELECT`, `UPDATE … FROM`, `DELETE … USING`, MERGE, CTEs, and Views are decomposed rather than reduced to one verb.
- PostgreSQL functions/procedures/triggers are parsed when static; the graph follows `Table → Trigger → Routine → Table CRUD`. Dynamic executable SQL is parsed only if reducible to a static statement.
- Java calls to stored routines are represented by `EXECUTES` and then continue to routine CRUD. Ambiguous routine overload/schema resolution is possible or unresolved.
- Table identity includes Database Source, schema, and dialect-normalized name; original spelling is preserved. Unquoted PostgreSQL normalizes lower-case; Oracle upper-case; quoted identifiers remain case-sensitive.
- DataSources are recovered from configuration documents, injection, qualifiers, and Bean references. Dynamic routing preserves candidate sources rather than merging same-named tables.
- Schema Sources may be migrations or customer-provided offline SQL exports. They are statically replayed with explicit environment and priority; conflicts never silently overwrite.

## Source roots, builds, and analysis jobs

- Inputs may be any local customer source root. Git commit is used if present; otherwise a content snapshot hash identifies the run.
- The scanner excludes VCS, output, dependency, binary, and generated paths by default, with `.graphcrudignore` for customer rules. Generated sources and tests may be optionally included and are marked accordingly.
- Scan roots, symlinks, mounts, source encoding, and Java language level are explicit and safe. Only in-root files are followed by default. Legacy encodings can be configured.
- Maven and Gradle metadata is read without executing project scripts. Missing dependencies do not stop source analysis; affected relations are unresolved.
- Profiles are explicit. Facts that exist only under some profile configurations are conditional/possible.
- One job runs per project. Jobs are cancel-safe at batch boundaries. New snapshots stage first and atomically become active only after success; partial snapshots are explicitly marked.
- Progress is structured by stage: discovery, build metadata, parsing, resolution, graph build, write, and evaluation. CLI renders progress bars; API exposes an event stream.

## Query and storage contract

- Every query returns its snapshot ID, evidence path, adapter, source location, and explanation.
- `table-impact` defaults to depth 12 and at most 100 de-duplicated paths. Truncation is explicit.
- GraphStore is a capability contract with batch write, lookup, bounded traversal, transactional/snapshot semantics, and capability checks. Canonical facts export as JSONL.
- Neo4j is the first adapter, but Neo4j Community's GPLv3 license requires legal review before commercial customer delivery. A customer may use an approved alternative only when its adapter passes the same graph/query contract.

## Customer deployment and security

- The MVP runs inside the customer environment: native Linux x86_64 or WSL2. Docker Compose is the preferred offline deployment; a JVM package may connect to customer-provided Neo4j.
- Customer source is read-only and stays inside the environment. There is no automatic upload, telemetry, update check, production-database connection, or source/config copy into Neo4j.
- Reports, graph data, and logs remain in customer-configured storage. `purge-project` removes project graph/run data without modifying source.
- Logs and support bundles are redacted: no source, full SQL, paths, table names, or secret values. A customer must explicitly export any support bundle.
- Offline release packages include checksums, SBOM, licenses, and image/dependency versions.

## Evaluation and validation

- A hand-authored golden fixture is the only strict correctness oracle, with `expected-graph.json` and `expected-queries.json`.
- It covers Spring, legacy XML bindings, UI Actions, JDBC/MyBatis, dynamic boundaries, complex SQL, migrations, triggers/routines, polymorphism, and cycles.
- Open-source repositories are regression corpus, not truth: official MyBatis examples are control corpus; RuoYi-Vue-Plus is a PostgreSQL smoke corpus; MyBatis-Plus is a Gradle sentinel. All runs pin commit SHA and execution metadata.
- Customer validation uses stratified random sampling across entrypoint, adapter, CRUD, module, and evidence level. Customer reviewers create independent Review Annotations.
- Performance is baseline-based on fixed environment and corpus; a stage regressing over 20% after baseline acceptance fails.
- Every new framework, database dialect, or integration adapter must ship a fixture, manual truth, and unresolved-boundary cases before it is advertised as supported.

## Future federation and adapter order

- Projects keep distinct identity spaces. A Federation layer joins projects only through explicit contracts, never name similarity.
- `Integration Channel` and `Message/File Contract` generalize HTTP, electronic messages, and HULFT file exchange. HULFT matching relies on transfer ID or a customer-confirmed contract ID, not file name/path. Electronic messages use channel, direction, type, and version.
- External systems use customer-configured stable IDs; addresses and URLs are evidence attributes only.
- Future priority: Oracle SQL/PLSQL/Package/Trigger; deeper legacy XML and JSP; JS/TS UI scanning; HTTP/message/HULFT federation; then Shell and other-language callers.
- Runtime traces/logs may later become separate Runtime Observations. They enrich or validate the graph but never overwrite static facts.

## Documentation note

`CONTEXT.md` contains the initial glossary. Its planned update could not be applied in the Windows restricted-token sandbox because updating existing files was rejected, although creating new files worked. The glossary additions are represented in the Phase 1 specification and should be synchronized after restarting in a workspace-write or trusted/full-access session.

The glossary was synchronized during the continuation session after write access became available.

## Continuation decisions

- Code Entrypoint is a broad role for a method invoked by a framework or runtime through an evidenced Invocation Binding. Phase 1 support is an explicit allowlist: Spring HTTP and legacy URL bindings, servlet filters, schedules, application startup, Spring event listeners, explicitly configured message consumers, and manual UI Actions. Ordinary public methods are query origins, not entrypoints by visibility alone.
- Logical node IDs remain stable across snapshots; locations and commits are snapshot evidence. Relationship Assertions are canonical semantic edges, while separate Evidence Occurrences retain each supporting source observation.
- MyBatis mapper bindings are confirmed only when namespace and statement ID resolve uniquely. Overloads, conflicts, and runtime database selection remain possible or unresolved.
- Customer build execution and dependency preparation are outside analysis. The analyzer uses explicit or offline classpaths and best-effort static metadata, never runs Maven/Gradle project code, and does not download by default.
- Partial snapshots are sealed and queryable but do not replace active results without explicit acceptance.
- Golden truth is organized as minimal scenario fixtures plus one integrated fixture, including negative assertions and evidence details.
- Implementation is a Gradle Kotlin DSL multi-module modular monolith. Core models and contracts remain independent from JDT, SQL, Neo4j, CLI, and HTTP adapters.
- CI keeps core tests Docker-free, runs fixed-digest Neo4j/Compose integration tests separately, and applies corpus, performance, security, SBOM, license, checksum, and offline-install gates to releases.
- Technical MVP and Pilot-ready are separate completion states; the latter additionally requires offline operations, security/package verification, and an approved graph-storage licensing position.
