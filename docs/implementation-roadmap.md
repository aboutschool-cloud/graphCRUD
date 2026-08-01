# GraphCRUD Phase 1 implementation roadmap

This roadmap turns the accepted Phase 1 design into dependency-ordered vertical slices. Each stage ends in an observable result and a testable gate; later stages should not compensate for an incomplete earlier contract.

## Working rhythm

Use `grill-with-docs` only when a new choice could change scope, domain language, evidence semantics, or architecture. Use `domain-modeling` as part of those discussions so resolved terms and consequential trade-offs are recorded immediately.

Use `tdd` for every implementation slice. Use `codebase-design` before introducing or substantially changing a module interface. Use `research` for external facts such as JDT, MyBatis, PostgreSQL, Neo4j, licenses, or corpus selection. Use `diagnosing-bugs` when behavior is failing or unexpectedly slow and the cause is not already known. Use `code-review` at each milestone boundary, reviewing from the milestone's starting commit against its accepted scope.

## Stage 0: Establish the executable skeleton

Create the Gradle Kotlin DSL multi-module build, JDK 21 toolchain, dependency locking and verification, shared test conventions, CI fast lane, and empty module boundaries. Define no parser behavior yet.

Deliverable: the repository builds and tests deterministically from a clean checkout without Docker.

Gate: dependency verification passes, module dependency rules prevent infrastructure from leaking into the model, and CI reports the same result locally and remotely.

Recommended conversation:

> Use `codebase-design` to design the minimal Gradle module interfaces for Stage 0 from `docs/phase-1-spec.md` and `docs/implementation-roadmap.md`. Do not implement parsing yet. Then use `tdd` to create the executable skeleton and verify a clean build.

## Stage 1: Golden model and in-memory vertical slice

Define canonical IDs, immutable nodes, Relationship Assertions, Evidence Occurrences, evidence levels, snapshot states, deterministic JSONL, and the GraphStore capability contract. Implement the in-memory reference store and the first minimal fixture from entrypoint through a hand-authored call and CRUD path.

Deliverable: a deterministic `table-impact` result produced entirely from canonical fixture facts.

Gate: positive and negative assertions pass, repeated runs have identical hashes, and partial snapshot promotion behavior is covered before any parser or Neo4j code exists.

Recommended conversation:

> Use `tdd` to implement Stage 1 of `docs/implementation-roadmap.md`. Start with failing contract tests for canonical identity, Evidence Occurrences, snapshot promotion, deterministic JSONL, and one end-to-end in-memory `table-impact` fixture. Stop when the Stage 1 gate passes.

## Stage 2: Java, Spring, and direct JDBC

Add source discovery, safe project boundaries, static build metadata inputs, Eclipse JDT parsing and binding, the initial Invocation Source allowlist, direct calls, Spring DI subset, and statically recoverable JDBC/Spring JDBC SQL. Missing dependencies must produce bounded unresolved evidence rather than aborting analysis.

Deliverable: real Java source reaches SQL Statements through confirmed or explicitly degraded evidence.

Gate: minimal fixtures cover HTTP, filter, scheduled, startup, event listener, message consumer configuration, direct JDBC, dependency loss, polymorphism, and cycles.

Recommended conversation:

> Use `tdd` to implement the next Stage 2 vertical slice: [name one fixture]. Treat Eclipse JDT as the semantic authority, preserve unresolved reasons, and run all prior fixtures before stopping.

When API behavior is uncertain, first ask:

> Use `research` to verify [specific JDT or Spring behavior] against primary documentation and record the findings under `docs/research/`. Do not change production code.

## Stage 3: MyBatis, SQL, and PostgreSQL semantics

Implement unique Mapper binding, CRUD annotations, XML statements, conservative dynamic branches, JSqlParser integration, schema replay, identifier normalization, complex read/write decomposition, routines, and trigger chains.

Deliverable: Spring/JDBC/MyBatis paths resolve to source-backed PostgreSQL CRUD facts.

Gate: fixtures cover overload ambiguity, XML/annotation conflicts, `databaseId`, dynamic SQL, parse failure retention, CTE/MERGE and read-write statements, views, triggers, routines, and dynamic execution boundaries.

Recommended conversation:

> Use `tdd` to implement Stage 3 fixture [scenario name]. Do not infer CRUD with regex when parsing fails; retain the SQL source and unresolved reason. Run the complete golden suite.

## Stage 4: Snapshot orchestration and Neo4j

Implement analysis jobs, staging/sealing/promotion, active snapshot switching, cancellation boundaries, progress events, cleanup, Neo4j storage, and capability checks. Keep parsing and normalization rules outside GraphStore.

Deliverable: the same canonical facts and bounded queries work through in-memory and Neo4j stores.

Gate: both stores pass identical contract tests; complete promotion is atomic; partial promotion is explicit; failed jobs preserve the previous active snapshot; in-flight reads remain consistent.

Recommended conversation:

> Use `codebase-design` to review the GraphStore and snapshot interfaces against ADR-0003 and ADR-0005, then use `tdd` to implement Stage 4 against shared contract tests. Do not add Neo4j-specific concepts to the core model.

## Stage 5: CLI, HTTP API, reports, and operations

Expose versioned read-only queries, structured partial coverage, progress streams, local reports, redacted diagnostics, purge, and support-bundle generation. CLI and HTTP must call the same application use cases.

Deliverable: a user can analyze, inspect status, query impact, explicitly promote a partial snapshot, export facts, and purge project data.

Gate: CLI/API semantic parity, stable response schemas, bounded traversal and explicit truncation, query-relevant coverage warnings, and redaction tests.

Recommended conversation:

> Use `tdd` to implement Stage 5 use case [analyze/status/query/promote/export/purge] once in the application layer, then expose it through CLI and HTTP with parity tests.

## Stage 6: Corpus evaluation and Pilot-ready packaging

Pin the official MyBatis control corpus, complexity corpus, and Gradle sentinel by commit. Add reproducible execution metadata, performance baselines, Compose with fixed image digests, offline installation, SBOM, license inventory, checksums, container scanning, and operations documentation.

Deliverable: a Technical MVP report followed by a separately evidenced Pilot-ready package.

Gate: all completion gates in the Phase 1 specification pass; open-source runs never serve as accuracy truth; graph-storage commercial delivery has an approved position.

Recommended conversation:

> Use `research` to verify and pin the Stage 6 corpus/tool/license facts from primary sources. Then use `tdd` for corpus harness and packaging acceptance tests. Do not treat corpus output as golden truth.

## Milestone review loop

At the end of every stage:

> Use `code-review` to review changes since [stage-start commit] against repository standards and Stage N of `docs/implementation-roadmap.md`. Report Standards and Spec findings separately; do not broaden the stage.

If the review or tests expose a non-obvious failure:

> Use `diagnosing-bugs` to determine why [specific observed failure] occurs. Reproduce it minimally and explain the cause; do not implement a fix until the diagnosis is supported by evidence.

If a new architectural or domain decision appears:

> Use `grill-with-docs` to resolve [decision] one question at a time against the existing glossary, ADRs, and code. Do not implement until we confirm shared understanding.

## Scope discipline

Do not start Oracle, JSP/JS scanning, general message-bus discovery, federation, additional GraphStore adapters, or graphical UI work during these stages. Capture such requests as later issues unless they reveal a defect in the Phase 1 contracts.
