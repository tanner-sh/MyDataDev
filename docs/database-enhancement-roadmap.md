# 数据库增强推进清单

用户确认顺序：E → F → H → J → K → L。A/B/C/D/G/I 暂缓。

| 顺序 | 范围 | 状态 |
| --- | --- | --- |
| E | OceanBase 租户、版本、兼容模式识别与回归 | MySQL 模式已实测；Oracle 模式已提供回归入口，等待独立实例 |
| F | MySQL/MariaDB 表属性、字符集、引擎、自增与容量 | 已完成，MySQL 8.4 / MariaDB 11.4 实测 |
| H | Oracle 分区、表空间、容量与执行计划隔离 | 已完成，Oracle Free 普通账号实测 |
| J | 达梦普通账号、兼容模式、备份恢复回归 | 已完成，DM8 普通账号模式 0 / 2 实测 |
| K | 统一能力识别与不可用原因 | 已完成基础识别；对象级数据库权限标为待验证 |
| L | 参数化查询分页、完整导出、参数输入与校验 | 已完成，H2 与六种实库组合验证 |

## E：OceanBase

连接列表的「更多 → 服务端信息」读取实际 JDBC 产品/版本/驱动和数据库目录中的当前租户、兼容模式，不从用户名推断租户。普通数据库也能查看服务端版本。

配置 OceanBase 类型的连接在借用后、切换命名空间前验证实际模式。模式不符或元数据不足时拒绝继续操作；服务端信息入口仍可读取并说明原因。当前识别依赖 OceanBase 4.x 的 DBA_OB_TENANTS；权限不足会标为未验证。

官方社区镜像只提供 MySQL 模式。MySQL 实测使用 4.3.5-lts，JDBC 使用现有 OceanBase Connector/J 2.4.17；CI 固定镜像摘要，并纳入 CI Gate。

```bash
bash scripts/start-oceanbase-test.sh
TEST_REQUIRED_DATABASES=oceanbase-mysql \
TEST_OB_MYSQL_URL='jdbc:oceanbase://127.0.0.1:12881/mydatadev_test' \
TEST_OB_MYSQL_USER=root@test TEST_OB_MYSQL_PASSWORD='MyDataDev_Test2026!' \
mvn -f backend/pom.xml test '-Dtest=Database*CompatibilityTest'
docker rm -fv mydatadev-ob-test
```

Oracle 模式配置 `TEST_OB_ORACLE_URL`、`TEST_OB_ORACLE_USER`、`TEST_OB_ORACLE_PASSWORD` 和 `TEST_REQUIRED_DATABASES=oceanbase-oracle`。未配置时跳过，不能算作实库验证通过。只使用独立测试租户。

## F：MySQL / MariaDB 表属性

物理表详情新增「表属性」，展示估算行数、数据/索引字节、引擎、默认字符集/排序规则和下一自增值。修改前预览一条 ALTER TABLE，执行时检查 DDL 权限、只读/生产保护、手动事务、完整表名确认和定义版本；自增只允许提高。仅列出 InnoDB / MyISAM / Aria 中服务端可用的持久化引擎。DDL 仍可能重建、锁表；默认字符集变更不转换已有列。

MariaDB 使用完整排序规则映射，兼容 11.4 的 utf8mb4_uca1400_ai_ci；旧版本列不存在时回退。实库验证默认字符集、自增起点、引擎切换和中文数据保留。

## H：Oracle 存储与执行计划

物理表详情新增只读「存储与分区」：表空间、优化器估算行数及统计时间、表段/普通索引/LOB 已分配字节、前 500 个分区。自己名下对象使用 USER_SEGMENTS；跨所有者容量目录无权限时显示未知，不以 0 代替。暂不修改分区或表空间，也不计算数据库级剩余容量。

每次执行计划生成独立 STATEMENT_ID，DBMS_XPLAN 按此标识读取，finally 只清理本次记录，不提交调用方事务。真实 Oracle Free 普通账号验证分区容量、只读查询作用域、失败后的清理和事务隔离。OceanBase Oracle 继承的执行计划仍需独立租户验证。

## J：达梦回归与恢复修复

普通账号仅授予 RESOURCE，CI 增加兼容模式 0 / 2 矩阵；按模式验证空串语义，不为读取配置额外授予管理权限。逻辑 SQL 备份恢复覆盖中文、大整数、小数、NULL/空串、主键、唯一约束、外键以及 501 条插入后的失败回滚与重试。

实测修复系统索引/约束保留名称无法恢复的问题：不重复创建主键与内部聚集索引；自动生成的唯一索引和系统外键名转换为稳定的 MDD_BAK_ / MDD_FK_ 名称，保留约束语义。范围不包括达梦原生物理备份和跨兼容模式迁移。

## K：能力报告

连接「更多 → 服务端信息」统一展示实际版本、驱动、OceanBase 模式和功能原因。综合方言实现、应用权限、只读配置、JDBC 事务能力、SQL Server 分页最低版本与 SHOWPLAN 权限；已确认不可用的表浏览/编辑/设计/执行计划入口禁用。切换连接或刷新配置后重新获取，丢弃过期响应。

对象级 SELECT / DML / DDL 权限可能随表不同，未逐表探测的能力标为「权限待验证」。执行接口继续使用原有服务端授权和数据库校验，能力报告不代替它们。

## L：参数查询

参数查询支持服务端分页及跨页排序筛选；重复参数先绑定，结果筛选值接续绑定。参数值仅在内存中的结果上下文保留，不保存到草稿和历史。参数结果不提供就地编辑。

工作台导出会重新填写参数并读取完整查询，不受已加载页数和原 10,000 行上限约束，支持现有格式与 JDBC 取消。仍受查询超时、256 MiB 文件预算、单元格与 Excel 格式限制；超限失败不返回部分文件。未引入可跨服务重启恢复的后台导出任务。

参数输入新增多行文本、日期控件、有效日历/时间校验、精确小数尺度校验。详见 [查询模板说明](sql-query-templates.md)。

本轮没有新增元数据 schema 迁移，也未改动暂缓的 A/B/C/D/G/I 范围。


SQL Server 参数查询的排序/筛选包装使用 OFFSET 0 使有序源查询合法嵌入，不改变绑定位置。CTE 参数查询暂走有界执行，导出仍可读取完整查询。语法依据：[Microsoft ORDER BY 文档](https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql)。

## 本轮收尾验证（2026-10-03）

- 后端全量：1,211 项，0 失败、0 错误；136 项未配置环境的实库用例跳过，另行运行下列实库回归。
- 前端：835 项测试通过，生产构建通过。首屏 gzip 238.9 KiB，可输入工作台 587.9 KiB；完整可选功能链 799.7 KiB，完整链预算调整为 804 KiB，首屏/编辑器预算未变，未新增依赖。
- 参数分页、重复绑定、结果筛选和导出：MySQL、MariaDB、Oracle、达梦、OceanBase MySQL、SQL Server 实库通过。H2 另验证超过 10,000 行的完整导出。
- 表属性、Oracle 存储与计划隔离、达梦模式 0 / 2 普通账号与逻辑恢复均通过对应实库回归。
- 系统 Chrome 界面冒烟通过，包含模板编辑、参数填写/绑定、历史不保存参数、表编辑、导出入口及浅色/暗色布局检查。
- CI Gate 依赖检查、脚本语法检查及 git diff --check 通过。测试容器已清理；未运行远程 CI，OceanBase Oracle 实库验证仍待独立租户。

## 提交前复审

修复能力报告只更新当前 SQL 工作台、未同步表文档的问题。列表和当前连接从同一原始连接快照派生能力；刷新连接后重新探测，旧配置的响应不会覆盖新配置，权限恢复后可重新启用入口。补充快照失效与能力恢复回归测试。

再次通过后端全量、前端 835 项测试和生产构建、桌面端 17 项测试与主进程构建、9 项 CI/发布检查脚本测试。实库验证范围与上述记录相同，OceanBase Oracle 仍待独立实例。

参数查询界面验收截图：[填写参数](images/database-enhancement-parameters.png)、[绑定结果](images/database-enhancement-results.png)。
