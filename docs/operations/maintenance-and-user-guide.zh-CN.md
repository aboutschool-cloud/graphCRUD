# GraphCRUD 使用、运维与后续保守指南

本文面向客户管理员、运维人员、支持人员和版本发布负责人，适用于 Stage 6 交付的 GraphCRUD 0.1.0 离线包。本文中的“保守”指交付后的维护、巡检、故障处理、升级、合规和客户支持工作。

## 1. 产品边界与交付形态

GraphCRUD 在客户本地对一个 Analysis Project 的只读源码目录进行静态分析，把带来源证据的代码、调用关系和数据库 CRUD fact 写入 GraphStore。Phase 1 支持 Java、Spring、MyBatis 和 PostgreSQL SQL；Python、跨服务自动关联、消息总线和任意图数据库兼容性不在当前承诺范围内。

离线包提供三种使用形态：

1. **推荐：GraphCRUD 容器 + 包内 Neo4j Community。** 使用 `compose/compose.yaml`，可直接离线启动。
2. **GraphCRUD 容器 + 客户自有图存储。** 使用 `compose/compose-external.yaml`，不启动包内 Neo4j。客户提供的后端必须具备经批准的 GraphStore adapter，并通过 capability check 和共享 contract suite；仅仅支持 Bolt 不代表自动兼容。
3. **GraphCRUD 原生 JVM ZIP + 客户提供的 Bolt 端点。** 适用于不希望运行 GraphCRUD 容器的环境。此形态不会安装或启动图数据库。

GraphCRUD 与 Neo4j 是不同镜像、不同进程，只通过公开 Bolt 网络协议通信。客户可以完全移除或替换包内 Neo4j。GraphCRUD 不复制、链接或修改 Neo4j GPL 源码，商业合同不得限制客户对 GPL 组件依法享有的权利。

## 2. 角色与职责

| 角色 | 主要职责 |
|---|---|
| 客户系统管理员 | 保存离线包、配置凭据、控制源码目录权限、启动和停止服务 |
| GraphCRUD 操作员 | 发起分析、查看状态、审查 PARTIAL 结果、执行查询、导出和清理 |
| 图存储管理员 | 管理 Neo4j/客户自有 GraphStore 的容量、凭据、备份、恢复和升级 |
| 安全与合规负责人 | 验证校验和、SBOM、漏洞例外、GPL 对应源码和许可证材料 |
| 支持人员 | 收集经过脱敏的诊断资料，不接收密码、客户源码或未审查的完整图数据 |
| 发布负责人 | 生成发行包、执行门禁、保存证据、处理安全例外到期和版本回滚 |

生产环境中的权限分离、审批人和保留期限以客户制度及合同为准。

## 3. 环境要求

- Linux x86_64，或 Windows 上运行 Linux 容器的 Docker Desktop/WSL2。
- Docker Engine、Docker Compose，以及足够的本地磁盘空间。
- 默认资源上限：GraphCRUD 2 CPU/2 GiB，Neo4j 4 CPU/4 GiB；大型项目还需要为镜像、图数据、日志、备份和临时文件预留空间。
- 原生 JVM 形态需要 Java 21。
- 客户源码必须位于本机绝对路径，且由 Compose 以只读方式挂载。
- 离线运行不需要访问公网；制作发行包和刷新漏洞数据库属于受控的联网发布工作。

首次生产使用前，应在隔离环境完成安装、分析、查询、备份、恢复和卸载演练。

## 4. 离线包验收

以下命令均从解压后的离线包根目录执行。

### 4.1 检查目录

至少应包含：

```text
application/
compose/
compliance/
evidence/
images/
sources/
SHA256SUMS
verify-offline-bundle.ps1
```

`sources/` 中应有 Neo4j 5.26.29 对应源码 ZIP 和 GPL 许可证；`compliance/` 中应有许可证清单、GPL 合规说明和运维资料。

### 4.2 验证完整性和可运行性

在已审查脚本内容的 Windows/PowerShell 环境执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\verify-offline-bundle.ps1 `
  -BundleDirectory (Resolve-Path .)
```

验证器会检查：

- `SHA256SUMS` 中的每个文件；
- 两张镜像的精确 image ID 与 amd64 架构；
- Neo4j 保存归档的每一个 OCI layer 均不含另行授权的 fleet-management plugin；
- Compose 健康检查和 Bolt 连通性；
- analyze、status、promote、table-impact、export、report、support-bundle、purge；
- 验证项目的容器和卷被完整卸载。

任何一步失败都不得把该离线包投入生产。不要通过关闭校验、改标签或复用本机同名旧镜像来绕过失败。

## 5. 使用包内 Neo4j 安装

### 5.1 加载镜像

```bash
docker load -i images/graphcrud-0.1.0.tar
docker load -i images/neo4j-5.26.29-community-sanitized.tar
```

加载后确认镜像 ID 与 `compose/compose.yaml` 中的 digest 一致。

### 5.2 设置环境变量

Linux/WSL 示例：

```bash
export GRAPHCRUD_SOURCE_ROOT=/absolute/path/to/customer/source
export GRAPHCRUD_BOLT_PASSWORD='replace-with-a-strong-secret'
export NEO4J_AUTH="neo4j/${GRAPHCRUD_BOLT_PASSWORD}"
```

PowerShell 示例：

```powershell
$env:GRAPHCRUD_SOURCE_ROOT = 'C:\absolute\path\to\customer\source'
$env:GRAPHCRUD_BOLT_PASSWORD = 'replace-with-a-strong-secret'
$env:NEO4J_AUTH = "neo4j/$($env:GRAPHCRUD_BOLT_PASSWORD)"
```

不要把真实密码写入仓库、操作记录、支持包或截图。生产环境应通过客户批准的 secret 管理方式注入。

### 5.3 启动并确认健康

```bash
docker compose -f compose/compose.yaml up -d --pull never --wait neo4j
docker compose -f compose/compose.yaml ps
```

预期 Neo4j 为 `healthy`。包内配置仅将 HTTP/Bolt 映射到 `127.0.0.1`，默认关闭 Neo4j usage reporting。不要在未完成安全评审时改成 `0.0.0.0` 或开放防火墙端口。

## 6. 使用客户自有图存储

不要加载或启动包内 Neo4j 镜像。配置客户批准的端点：

```bash
export GRAPHCRUD_SOURCE_ROOT=/absolute/path/to/customer/source
export GRAPHCRUD_BOLT_URI=bolt://approved-graph-host:7687
export GRAPHCRUD_BOLT_USER=graphcrud
export GRAPHCRUD_BOLT_PASSWORD='replace-with-a-strong-secret'
```

命令前缀改为：

```bash
docker compose -f compose/compose-external.yaml run --rm --pull never graphcrud
```

切换前必须确认：

- 后端对应的 GraphStore adapter 已被批准并通过共享 contract suite；
- capability check 满足当前版本要求；
- TLS、认证、网络白名单、备份和审计符合客户制度；
- 已在非生产环境验证 analyze、status、query、export、purge 和恢复流程。

如果仅有 Bolt 兼容端点、但没有通过 GraphStore 合同验证，不得宣称受支持。

## 7. 使用原生 JVM ZIP

解压：

```bash
unzip application/graphcrud-0.1.0-SNAPSHOT.zip
```

设置 `GRAPHCRUD_BOLT_URI`、`GRAPHCRUD_BOLT_USER`、`GRAPHCRUD_BOLT_PASSWORD` 和 `GRAPHCRUD_SOURCE_ROOT`，然后执行：

```bash
launcher-0.1.0-SNAPSHOT/bin/launcher <command> ...
```

Windows 使用：

```powershell
.\launcher-0.1.0-SNAPSHOT\bin\launcher.bat <command> ...
```

原生 JVM 形态直接读取 `GRAPHCRUD_SOURCE_ROOT`，不会替用户安装、启动或升级图数据库。Java 必须为 21，Bolt 端点必须先通过连接和 GraphStore 能力验证。

## 8. 日常操作命令

以下示例使用包内 Compose。为避免遗漏 `-f`，可在 shell 中定义：

```bash
GC="docker compose -f compose/compose.yaml --profile cli run --rm --pull never graphcrud"
```

PowerShell 可直接写完整命令。`project` 与 `snapshot` 应使用客户内部可追踪、稳定且不含秘密的标识。

### 8.1 发起分析

```bash
$GC analyze <project> <snapshot> /workspace
```

分析只读取 `/workspace`。每次分析形成独立 snapshot，不要用同一个 snapshot 标识覆盖不同源码状态。

### 8.2 查看状态

```bash
$GC status <project> <snapshot>
$GC status <project>
```

带 snapshot 的命令查询指定结果；不带 snapshot 时查询 Active Snapshot。记录 COMPLETE/PARTIAL、coverage 和错误信息。

### 8.3 审查和晋升 PARTIAL snapshot

PARTIAL 表示存在明确的覆盖缺口，例如构建 classpath 不完整；它不是“分析失败”，也不能自动当作完整结果。先审查 coverage、unresolved facts 和支持范围，再由授权人员执行：

```bash
$GC promote-partial <project> <snapshot>
```

未经审批不得自动晋升 PARTIAL snapshot。

### 8.4 查询表影响

```bash
$GC table-impact <project> <database-source> <schema> <table> <depth> <paths> <snapshot>
```

示例：

```bash
$GC table-impact billing default public invoice 3 100 release-2026-08
```

`depth` 和 `paths` 是查询边界；生产自动化必须设置合理上限，避免无界查询。结果是静态、证据驱动的代码知识，不是运行时访问日志。

### 8.5 导出

```bash
$GC export <project> <snapshot> > approved-export.jsonl
```

导出可能包含文件路径、标识符、SQL 和数据库对象名，应按客户机密数据处理。传输前需要审批和加密。

### 8.6 生成报告

状态报告：

```bash
$GC report <project> > status-report.json
$GC report <project> html > status-report.html
```

表影响报告：

```bash
$GC report <project> table-impact <database-source> <schema> <table> > impact-report.json
$GC report <project> table-impact <database-source> <schema> <table> html > impact-report.html
```

### 8.7 生成支持包

```bash
$GC support-bundle <project> '<approved-redacted-diagnostic>' > support-bundle.zip
```

第二个参数是已脱敏的诊断文本，不是输出路径。生成后必须人工检查，不得包含密码、token、客户源码、原始数据库卷或未经审查的完整导出。

### 8.8 清理项目

```bash
$GC purge-project <project>
```

这是数据删除操作。执行前确认项目标识、保留策略、备份要求和审批记录。purge 不等同于销毁已有备份或外部归档。

## 9. 启停、卸载与密码管理

停止但保留数据卷：

```bash
docker compose -f compose/compose.yaml down
```

重新启动：

```bash
docker compose -f compose/compose.yaml up -d --pull never --wait neo4j
```

经备份和销毁审批后，卸载并删除 Compose 数据卷：

```bash
docker compose -f compose/compose.yaml down --volumes --remove-orphans
docker compose -f compose/compose.yaml ps --all
```

第二条命令应无残留服务。删除卷不可逆，且不会自动删除另行保存的备份、导出或支持包。

Neo4j 只在空数据卷首次初始化时读取 `NEO4J_AUTH`。修改环境变量不会旋转已有数据库密码；已有实例必须按照该 Neo4j 版本的密码管理程序变更。不要为了修改密码而删除生产卷。

## 10. 备份与恢复

### 10.1 备份前提

备份至少应关联：

- GraphCRUD 和图存储镜像 digest；
- GraphCRUD 版本、Compose 配置和非秘密参数；
- 图数据库数据备份；
- Active Snapshot 标识和一次 GraphCRUD JSONL 导出；
- `SHA256SUMS`、SBOM、许可证清单和对应源码；
- 备份时间、负责人、加密方式和恢复测试记录。

不要在数据库运行时直接复制其数据目录并假设所得副本一致。应使用客户批准、适用于当前 Neo4j Community 版本和存储方式的备份流程；若采用停机文件系统快照，先正常停止 Compose，并由图存储管理员确认一致性。

### 10.2 恢复验证

先在隔离环境恢复，随后验证：

1. 镜像 digest 与备份记录一致；
2. Neo4j 健康检查和 Bolt 连接成功；
3. `status <project> <snapshot>` 返回预期 snapshot；
4. Active Snapshot 正确；
5. 代表性 table-impact 查询与归档结果一致；
6. 新建一次测试分析不会破坏既有 snapshot；
7. 恢复演练日志不包含秘密或客户源码。

未通过恢复演练的备份不能作为灾难恢复承诺。

## 11. 升级与回滚

### 11.1 升级前

- 阅读目标版本说明和已知限制；
- 验证新离线包的校验和、SBOM、漏洞策略、GPL 材料和镜像架构；
- 备份现有图数据并导出 Active Snapshot；
- 在副本上验证数据库升级和 GraphStore contract；
- 记录当前镜像 digest、配置、密码轮换计划和回滚点；
- 安排维护窗口，停止新的分析任务。

### 11.2 升级执行

1. 停止旧 Compose，但不要删除卷。
2. 加载新镜像并确认 digest。
3. 使用新包自带的 Compose 文件，不要把旧配置盲目复制到新版本。
4. 按图数据库厂商支持的路径升级存储格式。
5. 启动后执行 health、status、代表性 query、export 和一次隔离分析。
6. 验证完成前不要销毁旧备份和旧离线包。

### 11.3 回滚

数据库存储一旦被新版本原地升级，旧版本未必能重新打开。回滚应恢复“旧镜像 + 与旧版本兼容的备份 + 对应配置”，不能只切换镜像标签。回滚后重新检查 Active Snapshot 和代表性查询。

## 12. 例行保守计划

### 每日或每次使用前

- `docker compose ... ps` 确认健康状态；
- 检查磁盘、内存和容器重启次数；
- 确认源码挂载仍为只读；
- 检查最近 Analysis Job 的状态、耗时和 coverage；
- 对 PARTIAL、FAILED、CANCELLED 结果建立处理记录。

### 每周

- 审查 Neo4j 日志和 GraphCRUD CLI stderr 中的新错误模式；
- 检查数据卷、日志和导出文件增长；
- 抽查 Active Snapshot 与最新获批 snapshot 是否一致；
- 确认支持包、导出和临时文件已按保留策略清理；
- 测试一个有边界的代表性 table-impact 查询。

### 每月或客户规定周期

- 执行备份并进行隔离恢复抽测；
- 复核账号、Bolt 密码、网络白名单和最小权限；
- 检查当前版本的安全公告和可用修复版本；
- 核对离线包、源码归档、GPL 文本、SBOM 和许可证清单仍能读取；
- 复核客户自有 GraphStore adapter 的批准状态和 contract 测试记录。

### 每次发布前

- 从 clean checkout 执行 `ciFast` 和 Neo4j integration test；
- 执行可复现 distribution、语料、性能、SBOM、许可证和安全门禁；
- 使用新生成的离线包执行完整 `verify-offline-bundle.ps1`；
- 确认 HIGH/CRITICAL 例外有负责人、理由和未过期的到期日；
- 完成 Standards 与 Spec 双轨审查；
- 推送精确提交并确认远端 CI 后再关闭发布 milestone。

## 13. 安全与漏洞保守

- 发行准备使用固定版本并校验归档的 Syft/Trivy；不得用未知缓存工具替代。
- 漏洞数据库超过策略允许的 72 小时、出现未匹配的 HIGH/CRITICAL、或例外过期时，发行门禁失败。
- 例外必须包含漏洞 ID、负责人、具体原因和到期日；到期前应升级镜像、确认厂商修复或重新完成风险审批。
- 当前包的端口默认只绑定 loopback。远程访问应通过客户批准的安全网络和加密方案，不要直接暴露默认端口。
- 不把客户源码上传到远端，不启用默认遥测或远程指标出口。
- GraphCRUD 镜像以非 root、只读文件系统运行，源码挂载为只读；修改这些控制需要重新安全评审。

## 14. GPLv3 与第三方组件保守

每次包含 Neo4j Community 的离线发行必须一同保存和交付：

- 精确 Neo4j 版本、架构、镜像 digest 和上游源码 commit；
- 完整对应源码 ZIP；
- GPLv3 文本、版权、许可证和无担保声明；
- 运行/重建所需脚本与配置；
- SBOM、许可证清单、checksum 和发行记录。

不得通过合同、密钥、访问控制或技术措施限制客户研究、修改、重建或替换 GPL 组件的法定权利。若以后修改 Neo4j 或其 GPL 覆盖内容，必须保留修改源码、标明变更和日期、提供相应构建安装信息，并重新进行法律与发布审查。

当前交付镜像通过 `FROM scratch` 从清理后的规范化 rootfs 重建，以确保另行授权的 fleet-management plugin 字节不残留在继承层；以后改变 Neo4j 基础版本时必须重新执行逐 OCI layer 审计。

详细清单见 `docs/operations/gplv3-aggregate-compliance.md`。本文是工程控制说明，不替代法律意见。

## 15. 故障排查

| 现象 | 优先检查 | 禁止的快捷处理 |
|---|---|---|
| Neo4j 一直不健康 | `docker compose ... logs neo4j`、密码、磁盘、内存、卷权限 | 直接删除生产卷 |
| Bolt 认证失败 | URI、用户名、已有卷实际密码、secret 注入 | 在日志/工单粘贴密码 |
| 镜像无法启动 | 架构、digest、`docker load` 结果、Docker Linux 容器模式 | 改用未校验的 `latest` |
| 分析为 PARTIAL | coverage、classpath、unsupported 机制、解析诊断 | 自动标记 COMPLETE 或自动晋升 |
| 查询结果为空 | project/snapshot、Active Snapshot、database-source/schema/table | 把空结果解释成“没有影响”而不看 coverage |
| 分析或查询变慢 | 当前环境指纹、项目规模、depth/paths、CPU/内存/磁盘 | 与不同环境基线直接比较 |
| 磁盘不足 | data/log/export/backup 增长、Docker 磁盘占用 | 未审批删除卷或备份 |
| checksum/digest 不符 | 重新取得原始包、介质损坏、文件被改写 | 跳过校验继续安装 |
| 安全例外过期 | 修复版本、风险负责人、到期记录 | 修改系统时间或删除扫描结果 |

故障没有定位前，应保留准确时间、版本、image ID、snapshot 状态、退出码和经过脱敏的日志。

## 16. 支持升级与交接资料

提交支持请求时提供：

- GraphCRUD 版本、提交或离线包标识；
- GraphCRUD/Neo4j image ID；
- 操作系统、Docker/Compose/Java 版本；
- 不含秘密的 Compose 配置差异；
- project/snapshot、状态、退出码和复现步骤；
- 脱敏后的错误日志与支持包；
- 最近一次成功时间及最近变更。

不得提供密码、token、客户源码、原始数据卷、未经批准的 SQL/JSONL 导出或包含个人信息的日志。支持响应时间和升级路径按合同约定，本文不定义 SLA。

## 17. 维护记录模板

每次保守作业至少记录：

```text
作业编号：
环境/客户：
日期与时区：
负责人/审批人：
GraphCRUD 版本与 image ID：
GraphStore 类型、版本与 image ID：
操作前状态和 Active Snapshot：
操作目的：
执行命令（已移除秘密）：
验证结果：
备份/恢复点：
异常与处置：
回滚是否执行：
后续动作、负责人和期限：
```

该记录应和客户的变更、事故、安全及数据保留制度一起保存。
