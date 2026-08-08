# Phase 1 MVP specification

## Purpose

GraphCRUD is an evidence-first, static-analysis system for answering how Java code reaches database CRUD operations. The Phase 1 outcome is a customer-deployable MVP that can explain its results from a local source location through code, configuration, SQL, and database objects.

## Scope

- Inputs: a local source root; Git commit when available, otherwise a content snapshot hash. Optional offline SQL exports and migration directories are Schema Sources.
- Java: Spring annotations and common XML Beans; Eclipse JDT is authoritative for Java syntax, types, and call resolution.
- Persistence: native MyBatis XML and CRUD annotations; direct JDBC; `JdbcTemplate` and `NamedParameterJdbcTemplate` static SQL.
- Database: PostgreSQL is fully implemented first, including static routine bodies and trigger chains. Oracle is the next dialect adapter; the core model already contains Packages and Routines.
- Output: CLI, read-only versioned JSON API, structured progress events, local HTML/JSON reports, and a GraphStore-backed active snapshot.

Out of scope for Phase 1: implementation of Python/JS/Shell scanners, full frontend scanning, runtime instrumentation, ORM/query-builder support, external-service traversal, multi-user hosting, and automatic source upload.

## Evidence and graph model

All adapters emit immutable intermediate facts. A resolver normalizes them into the graph; GraphStore only persists and queries the normalized graph.

Key nodes: Analysis Project, Module, Source File, Code Symbol, Configuration Document, Configuration Entry, Invocation Source, Invocation Binding, HTTP Endpoint Binding, UI Action, Database Source, Schema, Table, View, Trigger, Database Package, Database Routine, SQL Statement, Integration Channel, Message/File Contract, External System, and Review Annotation.

Key relationships include `CALLS`, `READS`, `INSERTS`, `UPDATES`, `DELETES`, `EXECUTES`, `DECLARES`, `ROUTES_TO`, `TRIGGERS`, `CONTAINS`, and binding relationships. Every fact has source location, adapter, snapshot, and evidence level:

- `confirmed`: unique and statically traceable;
- `possible`: bounded alternatives or profile-specific path;
- `unresolved`: runtime-dependent or unsupported structure, retained with its reason.

Default queries use only `confirmed` evidence. Query results always return their snapshot ID and evidence path.

Logical node identity is stable across snapshots and excludes source location and snapshot ID. A Java method key includes project, module, language, fully qualified declaring type, method name, and JVM parameter signature. Source location and commit belong to snapshot evidence; anonymous and local code constructs may have only snapshot-stable source anchors.

A canonical Relationship Assertion is distinct from its Evidence Occurrences. The relationship key includes its source, type, target, and required semantic qualifiers, while every supporting occurrence retains its own snapshot, adapter, source anchor, evidence level, and explanation. SQL Statements remain first-class nodes so separate statements are not prematurely collapsed into unexplained method-to-table edges.

## Entrypoints and configuration

An entry is modeled generically as `Invocation Source → Invocation Binding → Java Method`.

- Code Entrypoint is a broad role for any method invoked by a framework or runtime through an evidenced binding; it is not limited to externally initiated requests. Initial supported sources are Spring HTTP mappings, old URL-to-class/method configuration, servlet filters, `@Scheduled`, `main`, `ApplicationRunner`/`CommandLineRunner`, Spring event listeners, explicitly configured message consumers, and manually supplied UI Actions.
- Other framework callbacks become supported only when their Invocation Source adapter and fixtures exist. Ordinary public methods remain valid query origins but are not entrypoints merely because callers can invoke them.
- HTTP endpoints preserve raw route plus route shape. `ANY` represents a legacy binding with no method restriction.
- Configuration is first-class: only semantically meaningful XML/YAML/properties entries become nodes. Class-only references resolve to types, not guessed methods.
- JSP and JS/TS adapters are future sources of UI Actions. Shell scripts and other languages can join the same model later.

## SQL, schema, and database behavior

The rule is static recoverability, not whether SQL is called dynamic. Constant Java strings, text blocks, constants, and deterministic concatenation produce confirmed facts. MyBatis conditional branches produce possible facts. Runtime-dependent identifiers or fragments remain unresolved.

MyBatis XML binding is confirmed only when `namespace` resolves to a Mapper type and statement `id` identifies exactly one method. Overloaded candidates, configuration conflicts, and runtime-dependent `databaseId` selection are retained as possible or unresolved rather than guessed. CRUD annotations bind directly to their annotated method. Missing types, methods, or statements retain their raw identifiers, locations, candidates, and failure reasons.

Complex SQL is decomposed into reads and writes: `INSERT … SELECT`, `UPDATE … FROM`, `DELETE … USING`, `MERGE`, CTEs, and Views. Static PostgreSQL functions/procedures and triggers extend the chain to their table CRUD; dynamic `EXECUTE` is parsed when reducible to a static statement and otherwise unresolved.

Tables are identified by Database Source, schema, and dialect-normalized name; original identifier spelling is preserved. Unquoted PostgreSQL and Oracle names normalize according to their dialect, while quoted names remain case-sensitive. DataSources are recovered from configuration and injection; dynamic routing yields possible candidates.

Schema Sources are statically replayed with explicit target environment and priority. Flyway-style migrations are preferred. Conflicting sources never overwrite silently.

## Snapshot, query, and runtime rules

- New analysis writes a staging snapshot. A complete snapshot that passes validation atomically changes `activeSnapshotId`; old snapshots remain available to in-flight queries and are later cleaned up.
- A partial snapshot is allowed for per-file, dependency, or dynamic-analysis failures, is sealed and queryable by ID, and visibly reports coverage. It does not become active unless the user explicitly promotes it or opted into accepting partial results before analysis. Input/backend/manifest failures are fatal and do not produce a queryable or active snapshot.
- Queries against partial snapshots return ordinary results together with snapshot status, structured coverage, warnings, truncation, and the affected regions relevant to that query.
- `table-impact` returns direct CRUD plus reverse static paths. Default maximum depth is 12 and maximum returned paths is 100; truncation is explicit.
- One scan runs per project; jobs for different projects can run under a global concurrency limit.
- Parsing may be parallel but normalized facts are stably ordered for deterministic results.

## Security and customer deployment

The MVP runs entirely inside the customer environment: native Linux x86_64 or WSL2. Docker Compose is the preferred offline delivery; a JVM distribution that connects to customer-provided Neo4j is the alternative. Source roots are read-only; source, graph, report, and telemetry never leave the customer by default.

The scanner does not execute Maven/Gradle scripts, wrappers, plugins, or annotation processors; does not connect to production databases; and does not copy full source/configuration into Neo4j. Classpath inputs are, in priority order, an explicit manifest, a prepared offline dependency directory, an approved existing local cache, and best-effort static build metadata. Analysis never downloads dependencies by default. Any future dependency download is a separate explicit preparation operation with locked coordinates, origins, and checksums. Missing or conflicting dependencies lower affected evidence and are reported with coverage rather than halting unrelated analysis. It follows only in-root files by default and has built-in exclusions plus `.graphcrudignore`.

Reports, logs, and support bundles are local and redacted. A support bundle is customer-created and contains only approved metadata, hashes, errors, and statistics. Project data lives in a configurable directory and `purge-project` removes graph and run metadata without changing the source root.

## Graph storage and licensing

GraphStore is a capability-based interface. It requires batch writes, stable lookup, bounded path traversal, snapshot switching, and transactional behavior. Neo4j is the first adapter, but any backend must pass the same golden graph and query contract. Canonical facts are exportable as JSONL.

Neo4j Community is GPLv3 and must not be assumed as a bundled commercial customer component without legal approval. A customer may use an approved existing graph backend or a separately licensed product; unsupported backends are rejected by capability checks instead of silently returning incomplete answers.

## Evaluation and customer validation

The repository contains a suite of minimal hand-authored scenario fixtures plus one small end-to-end fixture. Each scenario owns structured expected facts and expected queries, including key negative assertions that prevent possible or unresolved evidence from being promoted to confirmed. They cover framework entrypoints, Spring and legacy bindings, UI Actions, JDBC/MyBatis, static and non-static SQL, complex SQL, schema migration, triggers/routines, missing dependencies, partial coverage, and polymorphism/cycles. Confirmed facts, source evidence, explanations, and failure reasons must match exactly with deterministic ordering.

Open-source projects are layered regression corpus, not truth: official MyBatis projects validate compatibility, RuoYi-Vue-Plus is a PostgreSQL-scale smoke corpus, and MyBatis-Plus is a Gradle sentinel. Every corpus run is pinned to immutable commit SHA and records JDK, commands, analyzer version, and result hash.

Customer validation uses reproducible stratified random sampling across adapter, CRUD operation, evidence level, module, and entrypoint. Reviewers create Review Annotations of correct, incorrect, or unknown; annotations do not overwrite derived facts.

Performance establishes fixed-environment baselines for discovery, parsing, resolution, graph write, and query. A regression greater than 20% in a stage fails after a baseline has been accepted.

## Completion gates

Technical MVP requires exact confirmed-fixture results with no known evidence upgrades, identical GraphStore contract behavior for in-memory and Neo4j, semantically equivalent CLI and HTTP queries, deterministic JSONL and result hashes, end-to-end snapshot-state coverage, and successful non-crashing runs on the pinned corpus.

Pilot-ready additionally requires verified offline Linux x86_64 or WSL2 installation, local analysis/query/purge workflows, redaction tests, SBOM and license manifests, checksums, operations documentation, and an approved commercial delivery position for the selected graph storage. Accepted performance baselines are release gates when any stage regresses by more than 20 percent.

## Delivery order

1. Golden fixture, canonical facts, and in-memory query assertions.
2. JDT, entrypoint model, Spring bindings, and JDBC.
3. MyBatis, JSqlParser, PostgreSQL schema/routine/trigger analysis.
4. GraphStore/Neo4j snapshot adapter and API/CLI/progress.
5. Evaluation, reports, offline packaging, and customer review flow.
6. Legacy XML/JSP depth, Oracle, additional GraphStore adapters, JS/TS, and integration adapters for HTTP, electronic messages, and HULFT.
