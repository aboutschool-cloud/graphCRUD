# 开源语料的可行性：Java / Spring / MyBatis / PostgreSQL MVP

## 结论

MVP **不能把「仅支持 Maven」作为面向开源评估集的长期输入边界**。它可以是第一个可交付版本的实施顺序（先完成 Maven），但应在计划中承诺第二个输入适配器为 Gradle，且评估报告必须将「可解析的 Maven 项目」和「尚不支持的 Gradle 项目」分开报告。原因不是 Gradle 本身，而是公开 Java/MyBatis 生态同时存在两种构建方式；例如 MyBatis-Plus 的官方源码仓库本身使用 Gradle（[`settings.gradle`](https://github.com/baomidou/mybatis-plus/blob/3.5/settings.gradle)、[`gradle/wrapper`](https://github.com/baomidou/mybatis-plus/tree/3.5/gradle/wrapper)）。若只支持 Maven，评估结果只能声称覆盖 Maven 子集，不能概括为 Java/Spring/MyBatis 的通用能力。

更重要的是：没有一个现成开源项目同时提供“Spring + **原生** MyBatis XML/注解 + PostgreSQL DDL/migration + 人工标注的调用边与 CRUD 真值”。因此公开仓库应是**分层回归语料**，不能替代人工黄金真值。完整链路的 precision/recall 仍必须由本项目的可控 fixture 和人工标注产生。

## 候选仓库与实际边界

| 分层 | 仓库（第一方来源） | 实际构建与技术事实 | 适合验证什么 | 不能证明什么 |
|---|---|---|---|---|
| A：框架控制语料 | [mybatis/spring-boot-starter](https://github.com/mybatis/spring-boot-starter) | 根 [`pom.xml`](https://github.com/mybatis/spring-boot-starter/blob/master/pom.xml) 是 Maven 多模块工程，包含 `mybatis-spring-boot-samples`；仓库定位是 Spring Boot 的 MyBatis 集成，官方测试文档还列出 XML mapper 与 `SqlSessionTemplate` 变体（[文档](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-test-autoconfigure/)）。 | `@Mapper` / `@MapperScan`、注解 SQL、XML mapper、Mapper 方法到 statement 的绑定恢复。它应作为小而稳定的控制语料。 | 不是可用于 PostgreSQL schema 解析的真实应用语料；不能把其结果当作真实项目 CRUD 准确率。 |
| B：传统 MyBatis 应用控制语料 | [mybatis/jpetstore-6](https://github.com/mybatis/jpetstore-6) | 根 [`pom.xml`](https://github.com/mybatis/jpetstore-6/blob/master/pom.xml) 是 Maven `war` 工程，明确依赖 `org.mybatis:mybatis` 和 `org.mybatis:mybatis-spring`，且使用 HSQLDB。 | 真实应用形态下的 Java/Spring 到 MyBatis 关系、Web 层到持久层调用路径。 | 数据库是 HSQLDB 而非 PostgreSQL；其 DDL/SQL 不应计入 PostgreSQL 方言或 migration 评测。 |
| C：生产形态、PostgreSQL 语料 | [dromara/RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) 的 `5.X` 分支 | 根 [`pom.xml`](https://github.com/dromara/RuoYi-Vue-Plus/blob/5.X/pom.xml) 是 Maven 多模块；官方 README 声明 Spring Boot、MyBatis-Plus，并列出 PostgreSQL 支持。仓库提供 PostgreSQL 脚本 [`postgres_ry_vue_5.X.sql`](https://github.com/dromara/RuoYi-Vue-Plus/blob/5.X/script/sql/postgres/postgres_ry_vue_5.X.sql)。 | Maven 多模块 classpath、Spring 分层、PostgreSQL 表/DDL、真实项目规模下的表名与 CRUD 事实提取。建议先只选一个后端业务模块。 | MyBatis-Plus 的 `BaseMapper`/wrapper 可能隐式生成 SQL；它是「MyBatis 生态」而非原生 XML MyBatis 的等价代表。MVP 须将此类边标为 `possible`/`unresolved`，不能把它们计入 confirmed 准确率。 |
| D：构建系统覆盖语料 | [baomidou/mybatis-plus](https://github.com/baomidou/mybatis-plus) | 仓库含 [`settings.gradle`](https://github.com/baomidou/mybatis-plus/blob/3.5/settings.gradle) 与 [`gradle/wrapper`](https://github.com/baomidou/mybatis-plus/tree/3.5/gradle/wrapper)；其 README 明确同时给出 Maven 与 Gradle 依赖用法，且 MyBatis-Plus 提供基于 Mapper 的数据库操作 API。 | 证明 Gradle 不是可忽略的边缘情况；后续测试 Gradle classpath/源码集发现和 MyBatis-Plus 的显式限制。 | 它是框架本身，不是 Spring 业务应用，也不应承担端到端 CRUD 真值基准。 |

## 建议的实际评估组合

1. **黄金 fixture（唯一的发布门槛）**：本仓库内手写 Spring + 原生 MyBatis（XML 和注解）+ PostgreSQL migration 的最小项目；对 `CALLS`、`BINDS_MAPPER_METHOD`、`READS/INSERTS/UPDATES/DELETES`、表名及证据等级维护人工 JSON。所有 `confirmed` 事实必须精确匹配。
2. **A/B 控制语料（每次回归）**：固定 MyBatis 官方仓库的 commit SHA，提取可复核的 Mapper 绑定案例。它们测“框架识别是否回归”，不虚报为 PostgreSQL 准确率。
3. **C 真实项目冒烟（每个里程碑）**：固定 RuoYi-Vue-Plus 的 tag/commit，只分析选定 Maven 后端模块。输出发现数量、未解析原因和耗时；在人工抽样标注前，禁止从该项目计算全量 precision/recall。
4. **D 构建兼容性哨兵（Gradle 适配后）**：先验证源码集与依赖 classpath 的获取，不把 MyBatis-Plus 自动 SQL 伪装成已支持的原生 Mapper SQL。

每次下载或复跑时都必须记录：仓库 URL、分支/标签、**不可变 commit SHA**、子模块、JDK、构建命令、分析器版本与结果哈希。不要以可移动的 `master` / `5.X` 分支名作为结果身份。

## 对当前设计决策的调整

此前「MVP 只支持 Maven」应改为：

> **第一个实现迭代仅接受 Maven（含多模块）；评估结论明确限定为 Maven 子集。Gradle 支持是公开语料评估前的下一项构建适配工作，而不是拒绝 Gradle 仓库的产品结论。**

这样仍能保持一人 MVP 的范围：先完成 Maven + fixture + A/B/C，再为 Gradle 设计一个独立的 `BuildMetadataProvider`，只负责给 JDT 产出 source roots、generated sources、language level 与 resolved classpath。调用图、CRUD 分析和图存储不能依赖 Maven 类型或路径。

## 需在实施前显式写入的限制

- RuoYi-Vue-Plus 的 PostgreSQL 脚本是 schema 事实来源，不代表每一条 MyBatis-Plus 调用都有可静态恢复的确切 SQL。
- 公开仓库的测试并不自带调用图/CRUD 标注；工具输出与另一个静态分析器输出都不能充当真值。
- 解析失败（Maven profile、私有依赖、annotation processor、动态 SQL、wrapper/generic CRUD）必须在评估报告中分类展示，不能静默丢弃或算作 `confirmed`。

