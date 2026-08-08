# graphCRUD benchmark corpus：真实工程与 28 个场景硬指标

## 决策摘要

建议采用“五层语料、两类真值”的基准套件：

1. MyBatis 官方 Spring Boot samples：小而真实的 Spring/MyBatis 控制组；
2. MyBatis 官方 jPetStore：跨 service/mapper/XML 的真实调用链控制组；
3. MyBatis 3 官方回归测试：`databaseId`、动态 SQL、`include`、provider 等机制真值；
4. PostgreSQL 官方回归测试：复杂 SQL、view、routine、trigger 的数据库语义真值；
5. RuoYi-Vue-Plus：大型 Maven/Spring/PostgreSQL 工程的 coverage、稳定性和负向结论压力组。

前四层可以抽取小型、固定片段，经过人工复核后形成 **expected facts 真值包**。RuoYi-Vue-Plus 不能把 analyzer 当前输出当真值，也不能要求 Phase 1 推断 MyBatis-Plus 自动生成 SQL；它只适合做规模、覆盖度和“不把不可恢复 SQL 升级为 confirmed”的硬指标。这延续了 [Phase 1 spec](../phase-1-spec.md#evaluation-and-customer-validation) 的原则，也避免重复 [open-source-corpus-feasibility.md](./open-source-corpus-feasibility.md) 已做的“哪些项目可用”判断：本文进一步固定版本、定位源码、设计 28 个可执行场景和验收阈值。

## 真值分层

| 等级 | 可断言内容 | 证据来源 | 禁止事项 |
|---|---|---|---|
| G1：逐事实真值 | 节点、关系、CRUD operation、表、Evidence level、Source Anchor、失败码、负向事实 | 固定提交中的 Java/XML/SQL 与官方测试；再由 graphCRUD 仓库内 reviewer-owned expected JSON 人工复核 | 不从 graphCRUD 首次运行结果生成 golden |
| G2：机制真值 | MyBatis 或 PostgreSQL 对语句/配置的官方预期行为 | 上游官方测试输入、测试代码与 expected output | 官方测试“能运行”不自动证明 graphCRUD 的完整调用图 |
| G3：工程硬指标 | 不崩溃、确定性、coverage 守恒、已知 unsupported 不误报 confirmed、性能 | 固定真实工程及其构建结构 | 不计算 precision/recall，除非完成独立人工标注抽样 |

PostgreSQL 的 `src/test/regress/sql/*.sql` 与 `expected/*.out` 可证明该版本接受什么语义以及运行预期，但 expected output 并不列出静态 CRUD 边。因此复杂 SQL 的 G1 expected facts 仍需由两名 reviewer 依据 SQL 源码独立标注；有分歧时标为 `unknown`，不得用 analyzer 输出裁决。

## 候选工程清单

### C1. mybatis/spring-boot-starter samples

- **固定版本**：tag `mybatis-spring-boot-3.0.4`，commit [`b2f19484522d5559d78be677ee479ae2c73506cc`](https://github.com/mybatis/spring-boot-starter/tree/b2f19484522d5559d78be677ee479ae2c73506cc)。建议固定 commit，并把 tag 仅作人类可读标签。
- **许可**：Apache-2.0，[LICENSE](https://github.com/mybatis/spring-boot-starter/blob/b2f19484522d5559d78be677ee479ae2c73506cc/LICENSE)。
- **技术栈**：Maven 多模块、Spring Boot 3、MyBatis；官方 samples 同时给出 annotation mapper、XML mapper、web、schema/data SQL。[samples 目录](https://github.com/mybatis/spring-boot-starter/tree/b2f19484522d5559d78be677ee479ae2c73506cc/mybatis-spring-boot-samples)
- **真值来源**：annotation `CityMapper` 的 `@Select`、XML `CityMapper.xml` 的 namespace/id/SQL、对应 Java mapper 和官方应用测试。[annotation mapper](https://github.com/mybatis/spring-boot-starter/blob/b2f19484522d5559d78be677ee479ae2c73506cc/mybatis-spring-boot-samples/mybatis-spring-boot-sample-annotation/src/main/java/sample/mybatis/annotation/mapper/CityMapper.java) [XML mapper](https://github.com/mybatis/spring-boot-starter/blob/b2f19484522d5559d78be677ee479ae2c73506cc/mybatis-spring-boot-samples/mybatis-spring-boot-sample-xml/src/main/resources/sample/mybatis/xml/mapper/CityMapper.xml) [test](https://github.com/mybatis/spring-boot-starter/blob/b2f19484522d5559d78be677ee479ae2c73506cc/mybatis-spring-boot-samples/mybatis-spring-boot-sample-xml/src/test/java/sample/mybatis/xml/SampleMybatisApplicationTest.java)
- **适用**：`@Mapper`、annotation SQL、XML 唯一绑定、`@MapperScan`/auto-configuration、小 Maven 工程发现。
- **不适用**：样例数据库主要为嵌入式测试库，不是 PostgreSQL 语义真值；模板语言 samples、Kotlin/Groovy 不属于 Phase 1，应成为 structured coverage exclusion，而不是失败。
- **建议场景**：S01–S04。

### C2. mybatis/jpetstore-6

- **固定版本**：tag `jpetstore-6.3.0`，peeled commit [`ce8015be2c2658d0b657be20328e53045f3a9641`](https://github.com/mybatis/jpetstore-6/tree/ce8015be2c2658d0b657be20328e53045f3a9641)。
- **许可**：Apache-2.0，[LICENSE](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/LICENSE)。
- **技术栈**：Maven WAR、Spring、MyBatis XML、service/mapper 分层；数据库脚本为 HSQLDB。[pom](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/pom.xml)
- **真值来源**：`OrderService` 明确组合四个 mapper；七个 XML mapper 明确声明 statement 与 SQL；上游测试验证应用行为。[OrderService](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/src/main/java/org/mybatis/jpetstore/service/OrderService.java) [mapper XML 目录](https://github.com/mybatis/jpetstore-6/tree/ce8015be2c2658d0b657be20328e53045f3a9641/src/main/resources/org/mybatis/jpetstore/mapper) [tests](https://github.com/mybatis/jpetstore-6/tree/ce8015be2c2658d0b657be20328e53045f3a9641/src/test/java/org/mybatis/jpetstore)
- **适用**：跨类调用、构造器注入、循环中的 mapper 调用、一个业务操作触达多个表、同一表多种 CRUD。
- **不适用**：不能作为 PostgreSQL identifier/schema、routine/trigger 真值；Web 入口使用 Stripes 而非 Phase 1 Spring MVC allowlist，因此从 HTTP 到 service 的完整路径不应要求 confirmed。
- **建议场景**：S05–S08。

### C3. mybatis/mybatis-3 官方回归测试

- **固定版本**：tag `mybatis-3.5.19`，commit [`ee0d4f4831ffdd311b0183c202f4ad6492a3f404`](https://github.com/mybatis/mybatis-3/tree/ee0d4f4831ffdd311b0183c202f4ad6492a3f404)。
- **许可**：Apache-2.0，[LICENSE](https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/LICENSE)。
- **技术栈**：MyBatis 核心 Maven 工程；`src/test/.../submitted` 是针对具体行为的 Java/XML/config 小测试，主要使用内存数据库。[submitted tests](https://github.com/mybatis/mybatis-3/tree/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/java/org/apache/ibatis/submitted)
- **真值来源**：`multidb` 同时给出 `databaseIdProvider`、默认/供应商 statement 和 `_databaseId` 动态分支；`refid_resolution` 给出有效/无效 include；`foreach` 与 `sqlprovider` 给出官方支持模式及测试断言。[multidb XML](https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/resources/org/apache/ibatis/submitted/multidb/MultiDbMapper.xml) [multidb config](https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/resources/org/apache/ibatis/submitted/multidb/MultiDbConfig.xml) [refid XML](https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/resources/org/apache/ibatis/submitted/refid_resolution/Mapper.xml) [foreach XML](https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/resources/org/apache/ibatis/submitted/foreach/Mapper.xml) [provider tests](https://github.com/mybatis/mybatis-3/tree/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/test/java/org/apache/ibatis/submitted/sqlprovider)
- **适用**：框架机制和 evidence degradation 的精确负向测试。
- **不适用**：不是 Spring 工程或 PostgreSQL 工程；不能整体跑来衡量 graphCRUD 端到端 precision。应抽取原文件最小闭包，不执行上游构建。
- **建议场景**：S09–S15。

### C4. PostgreSQL 官方 regression suite

- **固定版本**：tag `REL_18_0`，commit [`3d6a828938a5fa0444275d3d2f67b64ec3199eb7`](https://github.com/postgres/postgres/tree/3d6a828938a5fa0444275d3d2f67b64ec3199eb7)。若产品目标改为其他 PostgreSQL major，另建 corpus revision，不浮动跟踪 `master`。
- **许可**：PostgreSQL License，[COPYRIGHT](https://github.com/postgres/postgres/blob/3d6a828938a5fa0444275d3d2f67b64ec3199eb7/COPYRIGHT)。
- **技术栈**：PostgreSQL 服务端官方源码与 `pg_regress` SQL/expected output。
- **真值来源**：`insert.sql`、`update.sql`、`delete.sql`、`with.sql`、`merge.sql`、`create_view.sql`、`create_procedure.sql`、`triggers.sql`、`plpgsql.sql` 及同名 expected outputs。[SQL 目录](https://github.com/postgres/postgres/tree/3d6a828938a5fa0444275d3d2f67b64ec3199eb7/src/test/regress/sql) [expected 目录](https://github.com/postgres/postgres/tree/3d6a828938a5fa0444275d3d2f67b64ec3199eb7/src/test/regress/expected)
- **适用**：SQL parser/normalizer、复杂 read/write 分解、view/routine/trigger 依赖、动态执行边界。
- **不适用**：完整文件包含大量错误用例、会话状态和前置依赖，不能整体作为 Schema Source。必须抽取自包含片段并记录原始行 anchor；官方 expected output 不是 graph expected facts。
- **建议场景**：S16–S25。

### C5. dromara/RuoYi-Vue-Plus

- **固定版本**：研究时 `5.X` commit [`c3a83c11d54b82453382b65c3975d11784f38e9b`](https://github.com/dromara/RuoYi-Vue-Plus/tree/c3a83c11d54b82453382b65c3975d11784f38e9b)。因维护分支持续移动，corpus manifest 必须写 commit，不能只写 `5.X`。
- **许可**：MIT，[LICENSE](https://github.com/dromara/RuoYi-Vue-Plus/blob/c3a83c11d54b82453382b65c3975d11784f38e9b/LICENSE)。
- **技术栈**：大型 Maven 多模块 Spring Boot 工程、MyBatis-Plus、PostgreSQL 初始化与升级 SQL；大量 mapper 继承项目的 `BaseMapperPlus`。[root pom](https://github.com/dromara/RuoYi-Vue-Plus/blob/c3a83c11d54b82453382b65c3975d11784f38e9b/pom.xml) [BaseMapperPlus](https://github.com/dromara/RuoYi-Vue-Plus/blob/c3a83c11d54b82453382b65c3975d11784f38e9b/ruoyi-common/ruoyi-common-mybatis/src/main/java/org/dromara/common/mybatis/core/mapper/BaseMapperPlus.java) [PostgreSQL schema](https://github.com/dromara/RuoYi-Vue-Plus/blob/c3a83c11d54b82453382b65c3975d11784f38e9b/script/sql/postgres/postgres_ry_vue_5.X.sql)
- **真值来源**：对 G3，仓库文件清单、Maven module/source roots、可读/跳过/unsupported 统计是客观真值；对少量 DDL，可人工标注表身份。MyBatis-Plus 在运行时注入 generic CRUD，源码 mapper 本身不含对应 SQL，因此 Phase 1 不得据 `BaseMapperPlus` 猜 confirmed CRUD。
- **适用**：规模、Maven 多模块、PostgreSQL DDL、真实 controller/service/mapper 路径、unsupported coverage、稳定哈希和性能。
- **不适用**：不能作为 Phase 1 精确 CRUD recall corpus；项目还含 workflow、Redis、对象存储等范围外技术，负向查询只有在 relevant coverage 完整时才能下结论。
- **建议场景**：S26–S28。

## 28 场景矩阵

`Expected` 中的 CRUD 集合均指 canonical operation；所有 G1 场景还必须校验 Source Anchor、adapter、Evidence level 和 explanation/failure code。

| ID | 层/来源 | 具体输入与场景 | Expected 硬指标 | 等级 |
|---|---|---|---|---|
| S01 | C1 annotation sample | `CityMapper.findByState` 的 `@Select` | 唯一 method→SQL→`city` READS，confirmed；不得产生 write | G1 |
| S02 | C1 XML sample | `CityMapper.xml#selectCityById` + 同名 Java method | namespace/id 唯一绑定；SQL READS `city`，confirmed | G1 |
| S03 | C1 XML sample | `HotelMapper` 与 `CityMapper` 同模块扫描 | 两个 mapper 不串绑；同名短名称必须以 FQCN/namespace 消歧 | G1 |
| S04 | C1 sample tree | 同时包含 Java samples 及 Kotlin/Groovy/template-language modules | Java in-scope 文件计数守恒；范围外文件 structured skipped/unsupported；完整 Java 子集允许否定结论，整个 repo 不允许声称全语言完整 | G3 |
| S05 | C2 jPetStore | `AccountService` update/insert 账户 | 分别解析 account/profile/signon 的 INSERT/UPDATE；调用路径不混合 | G1 |
| S06 | C2 jPetStore | `OrderService.insertOrder` | 识别 inventory UPDATE、orders/ordstatus/lineitem INSERT；循环调用仍只形成 canonical Assertion，多 occurrence 保留 | G1 |
| S07 | C2 jPetStore | `OrderService.getOrder` | orders/ordstatus/lineitem/item/inventory 的 READ 路径均可解释；不得产生 write | G1 |
| S08 | C2 jPetStore | `ProductMapper.searchProductList` 动态条件 | 静态表集合 confirmed；运行时谓词分支保留但不虚构其他表；HSQL 方言差异不得误标 PostgreSQL 语义 | G1/G2 |
| S09 | C3 refid | 有效本地 `<include refid="columnList">` | statement 引用 fragment，最终 READS `table1`；anchor 指向 include 与 fragment | G1/G2 |
| S10 | C3 refid | 不存在的 `noSuchMapper.columnList` | unresolved，含 raw refid/anchor/failure code；不得猜到本地同名 fragment | G1/G2 |
| S11 | C3 multidb | 默认 `select1` 与 `databaseId="hsql"` 竞争 | 已知 HSQL profile 选择 hsql；未知目标环境为 possible/unresolved，不能同时 confirmed | G1/G2 |
| S12 | C3 multidb | `_databaseId` 控制 FROM 表 | 候选 `hsql`/`common` 有界且 possible；无明确 Database Source 时无 confirmed table | G1/G2 |
| S13 | C3 multidb | `selectKey` + 分支 INSERT | READ max(id) 与 INSERT 都保留；databaseId 分支不升级 confirmed | G1/G2 |
| S14 | C3 foreach | `<foreach>` 生成 IN 列表，表名静态 | READ 的表 confirmed；值/列表长度不影响 table identity；动态 identifier 场景另为 unresolved | G1/G2 |
| S15 | C3 provider | `@SelectProvider/@InsertProvider` 的 provider method | 仅当 provider 返回值可静态规约才生成 SQL；一般运行时 provider 为 unresolved 且有覆盖原因，不执行 Java | G1/G2 |
| S16 | C4 insert/update | `INSERT INTO update_test SELECT ... FROM update_test` | INSERTS 与 READS 同一表同时存在，不能折叠成单一 operation | G1/G2 |
| S17 | C4 update | `UPDATE update_test ... FROM (...)`，再加真实 source table 变体 | target UPDATES；FROM 中真实 relation READS；`VALUES` 不是表 | G1/G2 |
| S18 | C4 with/delete | `DELETE FROM y USING t`，`t` 为 CTE | `y` DELETES；CTE 基表 READS；CTE 名不建 Table | G1/G2 |
| S19 | C4 with | data-modifying CTE：`wcte AS (INSERT INTO child...) UPDATE parent ...` | child INSERTS + parent UPDATES，RETURNING 不额外创建 read | G1/G2 |
| S20 | C4 with | read CTE 驱动 DELETE/UPDATE | CTE 基表 READS + target DELETE/UPDATE；alias/CTE 名不泄漏为数据库对象 | G1/G2 |
| S21 | C4 merge | `MERGE INTO target USING source` 含 matched UPDATE/DELETE 与 not matched INSERT | target 的 INSERTS/UPDATES/DELETES 为分支可能性；source READS；保留分支 evidence | G1/G2 |
| S22 | C4 view | `v4/v5/v6` 对多个 base table 的子查询/EXISTS | View 节点依赖所有基表；从 view 的 READ 沿链扩展到 base READS | G1/G2 |
| S23 | C4 view | `v10→v11→v12` 多层 view | 直接依赖与传递影响均正确；循环保护与深度/truncation 显式 | G1/G2 |
| S24 | C4 routine | 从 `create_procedure.sql` 抽取 static SQL procedure body | EXECUTES routine；body 内 CRUD 扩展到表，anchor 同时保留 CALL/body | G1/G2 |
| S25 | C4 trigger/plpgsql | trigger→function→INSERT/UPDATE 目标表，以及动态 `EXECUTE` 对照 | 静态 body 产生 TRIGGERS/EXECUTES/CRUD；不可规约 dynamic EXECUTE 为 unresolved，绝不猜表 | G1/G2 |
| S26 | C5 RuoYi | 全 repo Maven/source-root/ignore 扫描 | discovered = analyzed + skipped + failed；每个 module 有状态；0 silent drop；不执行 wrapper/plugin | G3 |
| S27 | C5 RuoYi | `BaseMapperPlus` controller→service→mapper 路径 | 代码调用可解析到 mapper；自动生成 CRUD 标为 unsupported/unresolved，confirmed CRUD 数必须为 0（除非源码另有静态 SQL） | G3 |
| S28 | C5 RuoYi | PostgreSQL schema + “不存在某表影响”的负向查询 | 仅当该 schema source、相关 Java modules 和 persistence adapters coverage 完整时允许 `no confirmed impact`；否则响应必须携带 query-relevant coverage warning | G3 |

## 验收指标

### 1. 准确性门禁（G1）

- 28 个场景中 S01–S03、S05–S07、S09–S14、S16–S25 建立 reviewer-owned expected facts；**confirmed precision = 100%，confirmed recall = 100%**。任何多报/漏报 confirmed canonical fact 均失败。
- `possible`/`unresolved` 的 expected subject、候选集、failure code 和 Source Anchor **逐项完全相等**；尤其 S10、S11–S13、S15、S25 不允许 evidence upgrade。
- 每条结果必须包含 Snapshot ID 与 evidence path；同一 canonical Assertion 的多个调用点不得丢失 Evidence Occurrence。
- 负向断言：每个场景至少一个“不得产生的 CRUD/表/confirmed 绑定”。

### 2. 数据库语义门禁（G2）

- 抽取的 PostgreSQL snippet 在固定 `REL_18_0` 容器/二进制上可重放，且对应官方 regression test 的原始来源与 expected output 均记录在 manifest。
- S16–S25 的 expected table-operation 集由两名 reviewer 独立标注；Cohen's kappa 不是替代裁决，分歧必须逐项解决或标 `unknown` 并排除出 accuracy denominator。
- identifier 断言覆盖 unquoted lowercase normalization、schema qualification 与至少一个 quoted mixed-case 表；不得把 CTE/alias 当 Table。

### 3. Coverage 与否定结论门禁

- 每次 corpus run 满足 `discovered = analyzed + skipped + failed`；每个失败/跳过文件都有 path、adapter、reason 和 query relevance。
- S04、S15、S27、S28 必须产生预先声明的 structured coverage；不得只在日志中出现。
- 对负向查询，只有 relevant adapters/files/schema sources 全覆盖且 Snapshot COMPLETE 时才能返回无警告的 `no confirmed impact`；否则结果必须带 structured warning、affected region 和 completion。
- RuoYi 的 MyBatis-Plus generic CRUD 作为已知 Phase 1 gap：**0 个由继承 `BaseMapperPlus` 单独推断出的 confirmed CRUD**。

### 4. 确定性、存储与查询门禁

- 同一输入、JDK、analyzer/adapter 版本连续运行 3 次：canonical JSONL SHA-256、coverage JSON SHA-256、每个场景查询结果 SHA-256 完全相同。
- in-memory 与 Neo4j GraphStore 对全部场景返回语义等价结果；排序、truncation 和 evidence IDs 一致。
- 查询 bounds 固定为 spec 默认 depth 12/path 100；S23 另造超界 view 链验证 `truncated=true`，不能静默截断。

### 5. 工程与性能门禁（G3）

- C1/C2/C3/C5 全部从 clean checkout 静态分析成功；不得执行 Maven/Gradle、不得联网下载、不得连接数据库。
- 每次记录 OS/CPU/RAM、JDK、commit SHA、source snapshot hash、analyzer/adapter 版本、命令、阶段耗时、峰值 RSS、事实数、coverage 数和结果 hash。
- 首个 accepted baseline 后，discovery、Java parse/bind、persistence/SQL、graph write、代表性 query 任一阶段回退超过 20% 即失败；先用至少 5 次运行的 median 建 baseline，不引用上游项目性能数字。
- RuoYi 硬超时应按固定测试机基线另定；在建立实测前不写拍脑袋秒数。超时或 OOM 必须形成 failed job 并保留旧 Active Snapshot。

## Corpus manifest 与落地结构

建议新增独立、机器可读的 corpus manifest，而不是 vendoring 整个上游仓库：

```text
evaluation/corpus/
  manifest.yaml                 # repo, commit, tag, license, allowed paths, hashes
  scenarios/S01...S28.yaml      # input closure, source URLs, expected level, query
  expected/S01...S25.jsonl      # reviewer-owned G1 facts（仅适用场景）
  expected/negative.json        # 禁止的事实/evidence upgrades
  patches/                      # 仅用于自包含化；每个 patch 有理由和 hash
  README.md                     # 获取、离线缓存、复核流程
```

每个 scenario manifest 至少包含：`sourceRepo`、`commit`、`license`、`sourcePaths`、每个文件 SHA-256、上游 blob URL、是否原样或裁剪、裁剪规则、需要的前置 DDL、expected truth level、适用 adapter、expected coverage、query 和 bounds。获取步骤是显式的准备操作；CI fast lane 使用已审核缓存，不在普通分析中联网。

## 建议执行顺序

1. 先落 S01、S02、S09、S10、S16、S18、S22、S25 八个最小场景，覆盖四条关键 seam 与正负证据。
2. 再落 jPetStore S05–S08，验证从真实 service 到多表 CRUD 的组合路径。
3. 完成 PostgreSQL S17–S24，建立 complex SQL 与间接数据库影响 hard gate。
4. 最后接 RuoYi S26–S28，只设 G3 指标，并在人工抽样完成前拒绝发布 precision/recall。

最终发布报告应分开呈现：`fixture exactness`、`official-mechanism conformance`、`real-project coverage/stability`、`human-reviewed sample precision`。把四者合并成一个“总体准确率”会掩盖 unsupported coverage，不符合 graphCRUD 的 evidence-first 定位。
