# SQL Server 表设计与估算执行计划

本轮开放 SQL Server 可视化表设计和执行计划。基于 SQL Server 2022 真实实例验证；其他版本尚未回归。无需修改应用元数据 schema 或新增配置。

## 表设计

- 支持建表、表重命名、删除；字段增删、重命名、类型和可空性修改；普通索引与主键调整。
- 默认值通过 SQL Server 默认约束管理。修改或删除字段时，按系统目录查询真实约束名，不依赖命名惯例。
- 字段注释读写 `MS_Description` 扩展属性，支持中文和清空。
- 新增整数列可以勾选「自增」，生成 `IDENTITY(1,1)`，同时设为非空、清空默认值。已有 IDENTITY 列不能在设计器中增删自增属性或修改类型、可空性、默认值。
- 保留 `decimal(p,s)`、时间类型精度、`nvarchar(max)` 等完整类型。计算列、系统生成列和 rowversion 的类型、可空性、默认值禁止编辑；现有定义由数据库保留。
- 包含 INCLUDE、过滤条件、降序键、聚集属性或唯一约束的索引，不允许通过通用索引编辑器删除或重建。需要修改时使用原生 DDL。

设计器沿用 DDL 预览、结构版本检查、完整表名确认、权限和审计。DDL 仍可能因数据、外键、计算列依赖等被数据库拒绝；多条语句部分成功时会明确提示，应刷新后核对实际结构。

本轮不开放 SQL Server 结构对比自动生成迁移脚本：通用对比模型尚未包含计算表达式、自增种子/步长和完整索引属性。差异展示及显式要求的删表脚本保留。

## 估算执行计划

SQL 工作台的执行计划按钮使用 `SET SHOWPLAN_XML ON`，获取估算计划，不执行原查询。结果展示语句编号、节点与父节点、物理/逻辑算子、预估输出行数及子树成本。`EstimateRows` 是输出行数估算，成本也是优化器估算，不能当成实际耗时或扫描行数。

面板识别扫描算子和较大的预估输出行数，提供中文提示。XML 禁止 DTD/外部实体，限制大小和结果行数，超出行数时显示截断状态。

成功或失败都会单独执行 `SET SHOWPLAN_XML OFF`。若恢复失败，终止该 JDBC 连接，避免后续操作沿用估算模式。数据库账号需要查询权限及涉及数据库的 `SHOWPLAN` 权限。

## 验证

- `mvn test`：方言 DDL、XML 解析、会话清理和其他后端回归。
- `npm test`、`npm run build`：前端计划解读、字段属性传递及构建。
- `DatabaseSqlServerCompatibilityTest`：独立 SQL Server 实例上的专项回归。
- `DatabaseCompatibilityTest`：SQL Server 参与通用表生命周期和设计测试。

真实库测试沿用 `TEST_SQLSERVER_URL`、`TEST_SQLSERVER_USER`、`TEST_SQLSERVER_PASSWORD`；设置 `TEST_REQUIRED_DATABASES=sqlserver` 防止漏配静默跳过。现有 CI 的 `Database*CompatibilityTest` 通配符自动包含新增用例。

参考：[SHOWPLAN_XML](https://learn.microsoft.com/en-us/sql/t-sql/statements/set-showplan-xml-transact-sql)、[ALTER TABLE](https://learn.microsoft.com/en-us/sql/t-sql/statements/alter-table-transact-sql)、[扩展属性](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-addextendedproperty-transact-sql)。

## 后续顺序

1. 本轮：SQL Server 表设计与估算执行计划。
2. 已完成：[达梦执行计划及真实数据库回归](dameng-enhancements.md)。
3. SQLite：受版本能力约束的表设计及重建迁移。
4. ClickHouse：引擎、parts、mutations 管理。
5. OceanBase：两种兼容模式的租户、版本识别及回归。
