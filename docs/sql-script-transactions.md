# SQL 脚本与文件事务

SQL 窗口、文件任务和 MCP 共用流式执行单元解析器。Oracle / OceanBase Oracle 的 PL/SQL 过程、函数、包、触发器和匿名块保持完整；独占行 `/` 只分隔、不发送给 JDBC，也不重复执行上一条语句。注释、UTF BOM、引号和过程体内分号不改变执行边界。MySQL `DELIMITER`、PostgreSQL dollar quote、SQL Server `GO` 保持支持，`GO n` 尚不支持。

## 文件执行

上传并完成分析后选择事务模式：

- **工具分批提交（BATCH）**：兼容旧接口及 CSV / Excel / 数据搬运。按现有批次提交，失败回滚未提交部分；发现顶层事务控制语句时保留 READY 任务，要求切换模式。创建过程时过程体中的 COMMIT / ROLLBACK 不作为顶层事务控制。
- **脚本控制事务（SCRIPT）**：目前开放给 Oracle、OceanBase Oracle。独占一个连接、关闭自动提交、按完整执行单元顺序执行。支持顶层 COMMIT、ROLLBACK、SAVEPOINT、ROLLBACK TO；不按批次额外提交，不改写过程体。暂不支持切换 AUTOCOMMIT、START TRANSACTION 等其他客户端/事务指令。

SCRIPT 必须明确文件结束策略，省略时默认 ROLLBACK：

- **ROLLBACK**：正常结束时回滚剩余未提交内容，只保留脚本中已经提交的内容。
- **COMMIT**：所有执行单元成功后提交剩余事务。出错或取消不执行这次提交。

后台任务结束后释放连接，不能在另一个 SQL 窗口补交它的事务。需要先核对数据再提交的普通 DML，应放到窗口的手动事务中执行。不要为了通过校验删除过程内部的提交或回滚语句。

开始执行请求：

```json
{
  "transactionMode": "SCRIPT",
  "endOfFileAction": "ROLLBACK",
  "productionConfirmation": "生产连接需要准确连接名"
}
```

未传新参数时继续使用 BATCH / COMMIT；不自动切换模式。事务模式在排队时与任务状态一起原子写入。元数据通过 V21 迁移添加字段，旧任务默认 BATCH。

## 失败、取消和结果

遇到错误立即停止，只回滚当前尚未提交的事务。此前显式提交、DDL 隐式提交、过程内部提交和自治事务不会因此撤销，不能承诺整份文件原子性。

“执行成功单元数”不等于“已提交单元数”。任务单独显示已确认的顶层提交次数和位置、回滚次数、收尾结果、失败行范围；过程内部提交不计入客户端统计。结束策略产生的最终提交/回滚也计入相应次数。

取消只是请求；执行线程完成回滚、退出和清理后才进入终态。提交时断连或回滚失败标记 UNKNOWN，回滚失败的连接尝试 abort。服务重启将未完成任务标记中断、结果未知，不自动续跑。最近提交位置只用于诊断，不能用作恢复检查点。

## SQL 窗口

普通执行沿用已有行为，顶层事务控制须切换为脚本控制事务。脚本模式使用同样的结束策略及事务收尾逻辑；开启手动事务时不能切换。手动事务仍只接受查询和普通 DML，由工具栏提交/回滚按钮管理，拒绝 DDL、过程和匿名块。

“执行”使用选中内容或整个编辑器内容；手动选中半个代码块会被解析或数据库校验拒绝。完整代码块算一个执行单元，不把过程内部语句计入窗口的 500 单元限额。

## MCP

- `db_execute`：仍是单个执行单元，Oracle 完整过程也算一个单元。
- `db_execute_script`：传入 SQL 文本、transactionMode 和 endOfFileAction，返回 taskId。复用文件后台队列、进度、取消和事务语义；不接受服务端文件路径。使用全限定对象名；该接口丢弃查询结果，读取数据使用 db_query。
- `db_script_status` / `db_cancel_script`：仅可操作当前 agent 创建且仍有连接权限的任务。

提交脚本至少需要 DATA_WRITE；逐单元检查 DDL / UNKNOWN 所需的 FULL 权限，匿名块和过程调用按 UNKNOWN 处理。SQL Server GO 批次可能含无分号写语句，批量 MCP 暂要求 FULL。保留生产确认、无 WHERE 写入确认、审计、SQL 长度限制；任务返回错误摘要受 MCP 文本上限约束。

## 验证

单元/集成测试使用合成脚本及 H2 独立连接，覆盖提交后失败、保存点、尾部策略、取消收尾、提交断连、权限和持久化，不提交客户脚本或凭据。

`DatabaseScriptTransactionCompatibilityTest` 使用现有 TEST_ORACLE_URL / USER / PASSWORD 及 TEST_REQUIRED_DATABASES 环境机制，包含 Oracle 完整过程安装、内部提交及失败回滚测试，并自动纳入 CI 的 Database*CompatibilityTest。未配置独立 Oracle 环境时跳过。OceanBase Oracle 还需要在对应隔离环境完成驱动及过程编译验收，不能由 H2 测试替代。
