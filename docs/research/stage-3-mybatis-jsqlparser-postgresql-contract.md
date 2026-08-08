# MyBatis, JSqlParser, and PostgreSQL contract for Stage 3

Status: accepted research input for Stage 3 Ticket 1
Scope: MyBatis statement binding, JSqlParser behavior, and PostgreSQL object semantics only; no production-code decision is implemented here.

This note separates framework/database-documented behavior from GraphCRUD inferences. Evidence classifications and bounds are project rules, not claims made by MyBatis, JSqlParser, or PostgreSQL.

## Conclusions

Stage 3 may confirm a CRUD fact only when a Mapper statement, parsed SQL relation, and PostgreSQL object resolve uniquely from static evidence. MyBatis runtime providers and dynamic branches, parser failures, ambiguous Mapper overloads, runtime `databaseId` selection, unresolved `search_path`, and irreducible PL/pgSQL `EXECUTE` must remain bounded `possible` or `unresolved` evidence. Original SQL and identifier spelling must be retained; regex guesses must never replace failed parsing.

## 1. MyBatis statement identity and SQL production

### Documented facts

- `@Select`, `@Insert`, `@Update`, and `@Delete` supply SQL on a Mapper method. Their provider variants designate a class and method that produces SQL at execution time; omitted provider method names may be resolved through `ProviderMethodResolver`, then by a `provideSql` fallback. [MyBatis Java API](https://mybatis.org/mybatis-3/java-api.html) and [`SelectProvider` API](https://mybatis.org/mybatis-3/apidocs/org/apache/ibatis/annotations/SelectProvider.html)
- MyBatis' own provider tests reject overloaded provider methods as ambiguous rather than selecting one arbitrarily. [MyBatis `SqlProviderTest`](https://mybatis.org/mybatis-3/xref-test/org/apache/ibatis/submitted/sqlprovider/SqlProviderTest.html)
- An XML statement `id` is unique within its Mapper namespace, and mapped statements are addressed by the qualified `namespace.id`. [Mapper XML reference](https://mybatis.org/mybatis-3/sqlmap-xml.html) and [Getting started](https://mybatis.org/mybatis-3/getting-started.html)
- Dynamic XML includes `if`, `choose/when/otherwise`, `trim/where/set`, and `foreach`; runtime parameters and OGNL determine the emitted SQL. When configured, `_databaseId` is available to dynamic expressions. [Dynamic SQL reference](https://mybatis.org/mybatis-3/dynamic-sql.html)
- With a `DatabaseIdProvider`, MyBatis loads statements whose `databaseId` matches the current database as well as statements without one. When the same statement exists in matching vendor-specific and generic forms, the generic form is discarded. CRUD annotations and providers also expose `databaseId`. [Configuration reference](https://mybatis.org/mybatis-3/configuration#databaseIdProvider), [Mapper XML reference](https://mybatis.org/mybatis-3/sqlmap-xml.html), and [Java API](https://mybatis.org/mybatis-3/java-api.html)

### GraphCRUD inference

An XML `namespace.id` does not encode a Java parameter signature. A namespace must resolve to one Mapper type and the statement name to exactly one method before GraphCRUD confirms an Invocation Binding. Mapper overloads, duplicate statements, XML/annotation conflicts, missing symbols, or runtime database selection retain raw identifiers, all bounded candidates, source anchors, and a stable failure reason.

Direct, statically recoverable CRUD annotation text may be confirmed on its annotated method. Provider methods execute Java to produce SQL and dynamic XML evaluates runtime values, so only independently proven static output may be confirmed. Finite branch alternatives are `possible`; unbounded or runtime-dependent fragments are `unresolved`.

## 2. JSqlParser PostgreSQL boundary

### Documented facts

- JSqlParser describes itself as RDBMS-agnostic with support focused on dialects including PostgreSQL and covers major families such as `SELECT`, `INSERT`, `UPDATE`, `MERGE`, `DELETE`, DDL, and `WITH`. It also explicitly lists unsupported syntax and notes that vendor-specific details, especially DDL, may be incomplete. [JSqlParser repository](https://github.com/JSQLParser/JSqlParser) and [unsupported grammar](https://jsqlparser.github.io/JSqlParser/unsupported.html)
- Successful parsing produces a traversable Java AST. With error recovery, parsing resumes at the next statement separator, the failed statement is represented as `null`, and parse errors remain available. Unsupported-statement mode can instead retain an `UnsupportedStatement`, subject to its documented first-statement constraint. [JSqlParser usage](https://jsqlparser.github.io/JSqlParser/usage.html)

### GraphCRUD inference

"PostgreSQL support" is not a completeness guarantee. Every required Stage 3 construct must have a pinned-version fixture. On a parse error or unsupported statement, retain the original SQL, its source anchor, parser diagnostics, and a stable unresolved reason. Do not infer table access with regex or token-name similarity after parsing fails.

## 3. PostgreSQL names, schemas, and views

### Documented facts

- PostgreSQL folds unquoted identifiers to lower case. Quoted identifiers preserve case and are always identifiers. [Lexical structure](https://www.postgresql.org/docs/current/sql-syntax-lexical.html)
- A qualified object name is `schema.object`. An unqualified name is resolved through `search_path`, taking the first matching object; absence is an error. The default path is `"$user", public`, nonexistent entries are ignored, and `pg_catalog` is searched implicitly unless explicitly positioned. The first existing path entry is also the default creation schema. [Schemas and `search_path`](https://www.postgresql.org/docs/current/ddl-schemas.html)
- A view stores a query that is run when the view is referenced. Simple views can be automatically updatable, while other view writes require suitable `INSTEAD OF` triggers or rules. [CREATE VIEW](https://www.postgresql.org/docs/current/sql-createview.html)

### GraphCRUD inference

Database object identity must retain Database Source, schema, dialect-normalized name, original spelling, and quote state. Fold only unquoted identifier components. Do not map every unqualified name to `public`: resolve it against an explicitly modeled environment and `search_path`; otherwise retain candidates or unresolved evidence. View expansion follows the static view query and any evidenced write mechanism, with bounded, cycle-aware traversal.

## 4. Routines, triggers, and dynamic execution

### Documented facts

- PostgreSQL functions may be overloaded by input argument types. Procedures are invoked with `CALL`, and routine bodies may use SQL or a procedural language. [CREATE FUNCTION](https://www.postgresql.org/docs/current/sql-createfunction.html), [CREATE PROCEDURE](https://www.postgresql.org/docs/current/sql-createprocedure.html), and [CALL](https://www.postgresql.org/docs/current/sql-call.html)
- A trigger invokes its trigger function for configured table or view events. Table triggers can cover `INSERT`, `UPDATE`, `DELETE`, and `TRUNCATE`, at row or statement level. PL/pgSQL trigger functions take no ordinary arguments and return `trigger`. [Trigger behavior](https://www.postgresql.org/docs/current/trigger-definition.html) and [PL/pgSQL trigger functions](https://www.postgresql.org/docs/current/plpgsql-trigger.html)
- Static PL/pgSQL SQL is submitted to the SQL engine and optimizable statements may reuse plans. `EXECUTE command-string` constructs dynamic SQL and replans it each time; data values can be parameters, but dynamic table or column names must be inserted into the command text. [PL/pgSQL statements](https://www.postgresql.org/docs/current/plpgsql-statements.html)

### GraphCRUD inference

Routine identity requires Database Source, schema, name, and input argument types. Static routine SQL and trigger chains may extend CRUD facts when every step is uniquely resolved. Traversal must be deterministic, bounded, and cycle-aware. Confirm a dynamic `EXECUTE` target only when its command and identifier substitutions are statically reducible to one statement; otherwise preserve its expression, bounded candidates if any, and unresolved evidence.

## 5. Stage 3 implementation constraints

- Keep MyBatis binding, SQL parsing, and PostgreSQL resolution as separate adapter seams that emit immutable intermediate facts.
- Suggested stable reasons include `MYBATIS_BINDING_AMBIGUOUS`, `MYBATIS_STATEMENT_CONFLICT`, `MYBATIS_DATABASE_ID_RUNTIME`, `MYBATIS_DYNAMIC_SQL_RUNTIME`, `SQL_PARSE_FAILED`, `SQL_UNSUPPORTED`, `POSTGRES_SEARCH_PATH_UNRESOLVED`, `POSTGRES_OBJECT_AMBIGUOUS`, and `POSTGRES_DYNAMIC_EXECUTE`.
- A finite, source-supported candidate set has maximum evidence `possible`; a missing or unbounded target is `unresolved`. Neither can be promoted to `confirmed` by naming convention.
- Preserve source anchors at the Mapper annotation/XML statement, original SQL, schema declaration, routine body, trigger declaration, and every expanded relationship occurrence.
- Test identifier quoting and `search_path`, XML overload/conflict and `databaseId`, dynamic tags/providers, parse failures, complex DML/CTEs, views, overloaded routines, triggers, recursive chains, and reducible versus irreducible `EXECUTE`.

## Ticket 2 readiness

Ticket 2 is unblocked by this research. Its first narrow seam can accept one source-located SQL statement and return parsed PostgreSQL table-access facts or a source-preserving unresolved result. Later tickets can add complex statements, MyBatis binding, schema replay, views, routines, and triggers without weakening that contract.
