# financial-trading-system CRUD 分析报告

分析对象：`LS-Amber/financial-trading-system@edadb0617ca6bb26e7f8bb542b02d0c5fb4bf958`

GraphCRUD 版本：`0.1.0-SNAPSHOT`（MyBatis-Plus/MySQL 能力升级构建）

Snapshot：`git-edadb061-mp5`

## 结论

GraphCRUD 已增加 MyBatis-Plus 3.5.6 `BaseMapper<T>` 与 MySQL 反引号 DDL 的静态分析能力。真实项目中的 51 个候选 BaseMapper 调用点全部解析为表级 CRUD，未留下未解析的 MyBatis-Plus CRUD 候选：

- INSERT：8 个调用点
- READ：28 个调用点
- UPDATE：15 个调用点
- DELETE：0 个调用点；目标版本源码没有调用 BaseMapper 删除方法
- 已使用 Mapper 对应表：9 张
- DDL 中已发现并核对：10 张表；`t_risk_rule` 有 Mapper 和表定义，但目标版本没有 CRUD 调用点

| 表 | 调用点 | 识别的方法 |
|---|---:|---|
| `t_account` | 7 | `insert`, `selectOne`, `updateById` |
| `t_account_flow` | 2 | `insert`, `selectPage` |
| `t_market_quote` | 3 | `selectOne`, `updateById` |
| `t_operation_log` | 1 | `insert` |
| `t_position` | 7 | `insert`, `selectList`, `selectOne`, `updateById` |
| `t_trade_match` | 4 | `insert`, `selectCount`, `selectPage` |
| `t_trade_order` | 11 | `insert`, `selectCount`, `selectOne`, `selectPage`, `updateById` |
| `t_trade_symbol` | 9 | `insert`, `selectById`, `selectCount`, `selectList`, `selectOne`, `updateById` |
| `t_user` | 7 | `insert`, `selectById`, `selectCount`, `selectOne`, `selectPage` |

每条关系分别保留调用点、官方 BaseMapper 泛型声明、实体 `@TableName` 和匹配 DDL 四处来源证据，并记录 Mapper FQN、实体 FQN、方法、Wrapper 覆盖等级。为避免过度声明，能识别部分 Wrapper 操作但未完整证明列映射和控制流的 20 个调用标为 `PARTIAL`，无 Wrapper 的 31 个标为 `NONE`；本次不声明任何 Wrapper 为 `FULL`。Wrapper 条件细节与表级 CRUD 分开计量：即使运行时谓词不能完整还原，只要 Mapper 泛型、实体表名和 DDL 三段可核对，表级 CRUD 仍是 CONFIRMED。

## 可重复性与边界

同一 sealed snapshot 导出两次的 SHA-256 均为 `604f70ea607ed8c09d684d283d790eb22cc25079b7f12331173a24b25b1c8fda`，共 2,818 条事实：359 个 Node、502 条 Relationship Assertion、1,957 条 Evidence Occurrence。新增 51 条 MyBatis-Plus CRUD 关系和 213 条来源证据（含 9 条乐观锁配置证据）；未解析的 MyBatis-Plus CRUD 候选为 0。

Snapshot 仍诚实标记为 `PARTIAL`，原因是分析容器没有项目完整 Maven classpath 和 Lombok 注解处理结果；这些剩余告警涉及 Spring/Jackson/JWT/Lombok 等 Java 调用解析，不影响上述 51/51 个 MyBatis-Plus 表级 CRUD 调用点的覆盖结论。该报告是静态分析结果，不等同于运行时 SQL、行级影响或独立人工精度评测。

实现依据和验收规则见 `docs/research/mybatis-plus-mysql-financial-trading-contract.md`。
