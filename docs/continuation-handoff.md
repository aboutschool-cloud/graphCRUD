# graphCRUD continuation handoff

## Goal

Continue the `grill-with-docs` design session for an evidence-first code knowledge graph that traces Java code paths to PostgreSQL CRUD facts.

## Confirmed decisions

- MVP scope: Java Spring + MyBatis + PostgreSQL SQL only. Python, cross-service calls, MQ, ontology, LLM, and UI are out of scope.
- One local Git repository is one analysis project and one project graph.
- Neo4j Community Edition runs through Docker Compose; no multi-database adapter in the MVP.
- Eclipse JDT is the authority for Java semantics and static call resolution. Tree-sitter is only for lightweight structural scanning; Graphify is not on the MVP path.
- Edges have `confirmed`, `possible`, or `unresolved` evidence. Default queries expose only `confirmed` edges.
- Supported MyBatis sources: XML mapper CRUD statements and `@Select`, `@Insert`, `@Update`, and `@Delete`. Dynamic branches produce possible table relationships; provider and generated SQL are unresolved.
- PostgreSQL migrations/DDL inside the repository are the schema source. Default schema is `public`; unknown tables remain external or unresolved.
- Golden fixture facts are the only strict accuracy oracle. All confirmed call and CRUD facts in it must match exactly. Possible/unresolved facts are measured separately.
- MVP has a CLI and read-only JSON HTTP API, not a graphical UI.
- Each analysis is tied to a Git commit, or a content hash for a dirty worktree. There is one atomically switched active snapshot; no historical snapshots or incremental updates in the MVP.
- Maven and Gradle metadata/classpath support are in scope because real open-source corpus is mixed. Inability to resolve dependencies must not halt analysis; dependent facts become unresolved.
- Spring DI support is limited to stereotype components and constructor, `@Autowired`, or `@Resource` injection. Multiple candidates are possible; conditional beans, AOP, and reflection are unresolved.
- Evaluation corpus is layered: hand-authored fixture (accuracy), official MyBatis projects (compatibility/integration), and RuoYi-Vue-Plus plus MyBatis-Plus Gradle (complexity/Gradle smoke). Pin all repositories to commit SHAs.
- Core implementation: Kotlin/JVM on JDK 21.
- SQL/DDL parser: JSqlParser. Failures retain the source SQL and reason, never regex-based inferred CRUD facts.
- `table-impact` returns direct CRUD facts plus reverse static call paths, default depth 12 and cycles deduplicated; possible edges require explicit opt-in.

## Continued discussion resolved

The follow-up design session resolved Code Entrypoint scope, stable node identity, relationship/evidence identity, conservative MyBatis binding, non-executing dependency resolution, explicit partial-snapshot promotion, fixture organization, modular project layout, Docker/CI boundaries, and Technical MVP versus Pilot-ready completion gates. The Phase 1 specification and glossary contain the normative results.

## Next action

Follow the dependency-ordered implementation roadmap, beginning with the golden model and in-memory vertical slice. Keep research questions and implementation tickets small enough to validate independently against the golden fixtures.

## Suggested skills

- `grill-with-docs` for the one-question-at-a-time design interview.
- `domain-modeling` for resolved project terminology and consequential ADRs.
- `research` for primary-source validation of corpus projects, tooling, and licenses.
- `tdd` for implementation and `code-review` before publication.
