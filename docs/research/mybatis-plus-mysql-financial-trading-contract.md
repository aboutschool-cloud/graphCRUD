# MyBatis-Plus / MySQL static-analysis contract for `financial-trading-system`

Status: implementation input, not an accuracy claim

Target repository revision: `LS-Amber/financial-trading-system@edadb0617ca6bb26e7f8bb542b02d0c5fb4bf958`

Target persistence dependency: MyBatis-Plus `3.5.6`
Research basis: MyBatis-Plus official documentation and tagged source, plus the MySQL 8.0 Reference Manual.

## Decision summary

GraphCRUD can statically recover table-level CRUD for this target without reconstructing generated SQL. The safe chain is:

1. resolve a mapper declaration `M extends BaseMapper<E>`;
2. resolve `E` to an entity declaration;
3. resolve the table from `E`'s constant `@TableName` value;
4. match the invoked `BaseMapper` method to the operation matrix below;
5. treat a Wrapper as optional condition evidence, not as the source of table ownership.

This follows the framework contract: MyBatis-Plus says extending `BaseMapper<T>` provides CRUD without a mapper XML file, and its tagged `3.5.6` source declares the operations on that generic entity type ([`BaseMapper.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/mapper/BaseMapper.java#L77-L91)). `@TableName.value` is the entity's corresponding table name ([`TableName.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-annotation/src/main/java/com/baomidou/mybatisplus/annotation/TableName.java#L27-L41)).

## Supported BaseMapper method-to-operation mapping

The following mapping is suitable for a first complete implementation. “Operation” is the application-visible CRUD classification; generated SQL details and plugin transformations are recorded separately.

| BaseMapper method family | Operation | Table source | Minimum confidence when mapper/entity/table resolve | Notes |
|---|---|---|---|---|
| `insert(T)` | CREATE | mapper `T` | CONFIRMED | One entity insert. |
| `selectById`, `selectBatchIds` | READ | mapper `T` | CONFIRMED | Primary-key selection; ID value need not be constant to confirm the table read. |
| `selectOne`, `selectCount`, `selectList`, `selectMaps`, `selectObjs`, `selectPage`, `selectMapsPage`, `exists`, `selectByMap` | READ | mapper `T` | CONFIRMED | Wrapper/map/page affects predicates or projection, not the mapper table. `exists` delegates to `selectCount`; `selectByMap` delegates to a wrapped `selectList` in 3.5.6. Record one call-site READ, not two synthetic reads. |
| `updateById(T)` | UPDATE | mapper `T` | CONFIRMED | Primary-key update. Optimistic locking may add a version predicate when the plugin is configured. |
| `update(T, Wrapper<T>)`, `update(Wrapper<T>)` | UPDATE | mapper `T` | CONFIRMED | Wrapper may contain SET and WHERE fragments; table classification remains valid if those fragments are not fully decoded. |
| `deleteById`, `deleteBatchIds`, `delete(Wrapper<T>)`, `deleteByMap` | DELETE (business semantic) | mapper `T` | CONFIRMED | With logical deletion configured, emitted SQL is an UPDATE; preserve `physicalEffect=LOGICAL_DELETE_UPDATE` as a qualifier rather than changing the application operation to UPDATE. |

The method families and delegation behavior above come directly from the tagged interface: insert/delete/update are declared at lines 86–150, selections at lines 153–337, `selectByMap` delegates to `selectList`, `exists` delegates to `selectCount`, and page helpers delegate to list methods ([`BaseMapper.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/mapper/BaseMapper.java#L86-L150), [`BaseMapper.java` selection methods](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/mapper/BaseMapper.java#L153-L337)).

Overload matching must use resolved declaring type and signature where available, not a bare method-name string. Calls such as Redis `delete` or `set`, collection updates, and unrelated application methods with the same name are not MyBatis-Plus evidence.

## Entity and table resolution

### Required resolution order

1. Resolve the receiver's mapper interface or implementation to a declaration assignable to `com.baomidou.mybatisplus.core.mapper.BaseMapper<E>`.
2. Substitute inherited generic parameters until `E` is a concrete type. Direct inheritance is sufficient for this target, but the contract should support intermediate generic mapper interfaces.
3. Inspect `E`, including inherited entity metadata where relevant.
4. If `E` has `@TableName` with a compile-time constant non-empty `value`, use that exact table identifier. A non-empty annotation `schema` qualifies it; the tagged annotation states that `schema` overrides the global schema ([`TableName.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-annotation/src/main/java/com/baomidou/mybatisplus/annotation/TableName.java#L29-L49)).
5. Match the resolved identifier against parsed DDL. Preserve the spelling from each source and store a normalized comparison key separately.

Do not guess the default table name from the Java class in this target: all ten persistent entities provide explicit constant `@TableName` values. If a future entity omits the annotation, MyBatis-Plus naming strategies and global `tablePrefix`/schema configuration become part of resolution; absent a fully resolved configuration, that inference must be POSSIBLE rather than CONFIRMED. `keepGlobalPrefix` only applies when both a global prefix and an annotation value are set, and defaults to false ([official annotation reference](https://baomidou.com/en/reference/annotation/#tablename)).

### Target mapping inventory

| Entity / `BaseMapper` generic | `@TableName` | MySQL DDL table |
|---|---|---|
| `User` | `t_user` | `t_user` |
| `Account` | `t_account` | `t_account` |
| `AccountFlow` | `t_account_flow` | `t_account_flow` |
| `TradeSymbol` | `t_trade_symbol` | `t_trade_symbol` |
| `MarketQuote` | `t_market_quote` | `t_market_quote` |
| `TradeOrder` | `t_trade_order` | `t_trade_order` |
| `TradeMatch` | `t_trade_match` | `t_trade_match` |
| `Position` | `t_position` | `t_position` |
| `RiskRule` | `t_risk_rule` | `t_risk_rule` |
| `OperationLog` | `t_operation_log` | `t_operation_log` |

MySQL permits `db_name.tbl_name` in `CREATE TABLE`, with database and table quoted separately when quoted ([MySQL 8.0 `CREATE TABLE`, “Table Name”](https://dev.mysql.com/doc/refman/8.0/en/create-table.html)). The DDL parser therefore needs to accept unqualified and qualified names plus backtick-quoted components. It should not globally lowercase and discard original spelling: MySQL database/table case sensitivity depends on the host filesystem and `lower_case_table_names`; Oracle recommends consistent lowercase for portability ([MySQL identifier case sensitivity](https://dev.mysql.com/doc/refman/8.0/en/identifier-case-sensitivity.html)). For this repository, exact lowercase annotation-to-DDL matches are available, so no case-folded fallback is needed.

## Wrapper interpretation contract

`LambdaQueryWrapper<E>` and `LambdaUpdateWrapper<E>` use property method references rather than database column strings; the official documentation describes this as a type-safe alternative to hard-coded fields ([official Wrapper guide](https://baomidou.com/en/guides/wrapper/)). In tagged source, `AbstractWrapper` implements comparisons and nested Boolean composition, and keeps the entity/entity class used for metadata ([`AbstractWrapper.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/conditions/AbstractWrapper.java#L41-L103)).

### In-scope target syntax

For this target revision, support these forms:

- constructor expressions such as `new LambdaQueryWrapper<TradeOrder>()`;
- wrapper local variables initialized by that constructor and subsequently extended;
- fluent `eq(Entity::getProperty, value)` and `gt(Entity::getProperty, value)`;
- `orderByAsc` and `orderByDesc` as non-CRUD query-shape metadata;
- nested `.and(w -> w.eq(...).or().eq(...))`;
- conditionally executed calls such as `if (status != null) wrapper.eq(...)`.

The tagged implementation maps `eq`, `gt`, `ge`, `lt`, `le`, and related methods to SQL comparison keywords, and implements nested `and`/`or` with child conditions ([`AbstractWrapper.java` comparisons](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/conditions/AbstractWrapper.java#L104-L218)). A lambda getter can be converted to an entity property only when the method reference and entity metadata resolve. Column resolution should then honor `@TableField`, inherited fields, and the configured underscore-to-camel mapping; an unresolved getter must not invalidate the enclosing table-level CRUD edge.

### Wrapper output levels

- **FULL**: wrapper construction, aliases, Boolean grouping, referenced entity properties, and relevant conditional guards all resolve.
- **PARTIAL**: table CRUD resolves, but some predicate, projection, SET clause, alias, or branch cannot be reconstructed.
- **NONE**: no wrapper was supplied (`null`) or wrapper internals are deliberately not analyzed. This is still a valid CONFIRMED table READ/UPDATE/DELETE when the mapper method resolves.

Do not treat `orderBy*` alone as another READ. It refines the same enclosing read. Do not require constant runtime values: a dynamic `userId` still gives confirmed column-reference evidence, while its value remains unknown.

### Hard limits and mandatory degradation

Degrade wrapper coverage instead of inventing SQL when encountering:

- an unresolved receiver or raw/erased `BaseMapper` generic;
- a wrapper passed through an unanalyzed method, field, collection, RPC boundary, reflection, or framework proxy;
- arbitrary string SQL APIs including `apply`, `last`, `first`, `setSql`, subquery fragments, or frontend-provided column names;
- loops/branches whose accumulated wrapper state cannot be soundly joined;
- custom mapper methods, XML statements, or annotations that are not declared by `BaseMapper`;
- interceptors that inject tenant, data-permission, dynamic-table, or other runtime predicates;
- runtime table-name handlers, sharding, or global naming configuration that cannot be resolved;
- lambda/property resolution failure caused by missing classpath or generated members.

This caution is framework-aligned: `last` directly appends supplied SQL in tagged source ([`AbstractWrapper.java` v3.5.6](https://github.com/baomidou/mybatis-plus/blob/v3.5.6/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/conditions/AbstractWrapper.java#L225-L246)), and official guidance warns that frontend-provided SQL fragments/field sections require validation because Wrapper coverage and injection checks are not absolute ([official Wrapper security guidance](https://baomidou.com/en/guides/wrapper/), [official data-security guide](https://baomidou.com/en/guides/security/)).

## Evidence and coverage rules

### Evidence object per call site

Record at least:

- source repository revision, file, line/range, enclosing method;
- resolved receiver type and mapper declaration;
- resolved `BaseMapper<E>` substitution;
- entity and `@TableName` annotation source location;
- matched DDL file and table declaration location;
- called method signature and application CRUD operation;
- wrapper coverage level and any resolved columns/operators;
- qualifiers such as logical deletion and optimistic locking;
- unresolved reasons, rather than only a global PARTIAL status.

### Confidence

- **CONFIRMED table CRUD** requires a resolved BaseMapper method, concrete mapper entity, constant table name, and matching parsed DDL table.
- **POSSIBLE table CRUD** applies when the call/method family is credible but entity, naming configuration, schema, or DDL match requires an assumption.
- **UNRESOLVED** applies when the receiver/method cannot be distinguished from unrelated APIs or no defensible table target exists.

Wrapper completeness is orthogonal to table CRUD confidence. For example, `orderMapper.selectOne(unknownWrapper)` can be a CONFIRMED READ of `t_trade_order` with PARTIAL wrapper coverage. This separation is essential to avoid reproducing the earlier false result of “no CRUD” simply because MyBatis-Plus generated SQL was absent.

### Coverage metrics

Report separate denominators:

1. recognized BaseMapper call sites / candidate BaseMapper call sites;
2. call sites with confirmed table / recognized BaseMapper call sites;
3. call sites with FULL wrapper coverage / call sites that supply a wrapper;
4. entities with exact annotation-to-DDL match / resolved BaseMapper entities;
5. custom mapper statements analyzed / custom mapper statements discovered.

Never label overall analysis COMPLETE solely because every BaseMapper call obtained a table edge. COMPLETE additionally requires that the configured completeness contract covers custom SQL, relevant DDL, runtime naming/interceptor effects, and call graph propagation. For this target, a truthful result may be table-CRUD complete while predicate coverage remains partial.

## Logical delete and optimistic-lock caveats

`BaseEntity.deleted` is annotated `@TableLogic`, and application configuration sets deleted/not-deleted values. Official MyBatis-Plus behavior is to filter logically deleted rows on SELECT, prevent updates to deleted rows, and translate DELETE to an UPDATE marking the row deleted ([official logical-delete guide](https://baomidou.com/en/guides/logic-delete/)). GraphCRUD should therefore expose both:

- `operation=DELETE` for application data-access intent; and
- `physicalEffect=UPDATE_LOGICAL_DELETE` when the annotation/configuration is resolved.

`Account.version` and `Position.version` use `@Version`. The official optimistic-lock plugin supports `updateById(entity)` and `update(entity, wrapper)` and adds version-based concurrency behavior when the interceptor is configured ([official optimistic-lock guide](https://baomidou.com/en/plugins/optimistic-locker/)). This remains one UPDATE edge to the entity table; version comparison/increment is a qualifier, not another CRUD operation. An annotation alone does not prove the interceptor is active, so mark the qualifier CONFIRMED only after resolving its configuration.

## Acceptance contract for the upgraded analyzer

For the pinned target revision, an acceptable run must:

1. discover all ten `BaseMapper<Entity>` interfaces and match all ten explicit `@TableName` values to the ten MySQL `CREATE TABLE` declarations;
2. emit CREATE edges for every resolved `insert`, READ edges for all resolved selection/count calls, and UPDATE edges for every resolved `updateById`;
3. distinguish Redis/cache `set`/`delete` and unrelated Java calls from MyBatis-Plus operations;
4. attach wrapper predicate evidence for the target's `eq`, `gt`, ordering, conditional `eq`, and nested `and/or` patterns where resolvable;
5. preserve table CRUD when wrapper detail is partial and state the exact unresolved reason;
6. flag logical-delete and optimistic-lock transformations without double-counting CRUD;
7. produce stable, reproducible export output on two runs over the same revision;
8. report method/table/wrapper coverage numerically and avoid claiming production accuracy without an independently reviewed oracle.

## Non-goals for this increment

- Executing the application or claiming the exact SQL emitted for every runtime configuration.
- Inferring arbitrary custom mapper SQL from method names.
- Evaluating runtime parameter values.
- Proving row-level impact when conditions depend on runtime data.
- Treating MySQL identifier matching as universally case-insensitive.
- Treating framework-generated CRUD recovery as a substitute for an accuracy oracle.

These boundaries permit GraphCRUD to recover the target's real table-level CRUD comprehensively while keeping every stronger claim auditable.
