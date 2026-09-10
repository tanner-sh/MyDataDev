# 真实数据库 CI 与发布门槛

## 合并与发布规则

`main` 的分支保护配置保存在 `.github/main-protection.json`，通过 GitHub API 应用到仓库：

- 必须通过 PR 合入，分支需要保持最新，8 个 CI 检查必须全部成功。
- 管理员同样遵守；禁止强制推送和删除 `main`，PR 讨论必须解决。
- 不要求另一人批准（审批数为 0），个人维护者可以在检查通过后自行合并。
- 检查绑定 GitHub Actions 的 App，其他来源不能用同名状态代替。

配置文件本身不会自动更新 GitHub 设置。管理员变更清单后，应执行并回读确认：

```bash
gh api --method PUT repos/tanner-sh/MyDataDev/branches/main/protection --input .github/main-protection.json
gh api repos/tanner-sh/MyDataDev/branches/main/protection
```

tag 发布在构建前、上传 Release 前均执行 `scripts/verify-release-ci.mjs`：待发布 SHA 必须属于 `main`；同一 SHA 最新一轮 main CI 必须成功，且所有必需作业实际成功。缺失、失败、取消、跳过或 API 错误均拒绝发布。正在运行的 CI 最多等待 35 分钟。构建与发布 checkout 固定同一个 SHA。

普通分支上的手动发布工作流仍可生成测试安装包，但不会创建 Release。只有 tag 运行创建 Release。发布检查与分支保护共用上述必需检查清单；该脚本的行为测试由前端 CI 任务执行。

## CI 数据库环境

PR、推送到 `main` 和手动运行 CI 时启动独立服务容器，不读取应用保存的连接。

| 作业 | 数据库 | 原生备份客户端 |
| --- | --- | --- |
| `database-compatibility` | MySQL 8.4、PostgreSQL 16 | mysqldump/mysql、pg_dump/pg_restore |
| `mariadb-compatibility` | MariaDB 11.4 | mariadb-dump/mariadb |
| `sqlserver-compatibility` | SQL Server 2022 | 未覆盖原生备份 |
| `oracle-compatibility` | Oracle Free 23 | 未安装 exp/imp，仅 SQL 逻辑备份 |

所有实库作业的失败都使 CI 失败。前三个作业最长 20 分钟，Oracle 最长 30 分钟。用例另有 JUnit 超时，但驱动或子进程不响应线程中断时，仍需依靠作业超时兜底。

## 覆盖范围

当前共 74 个参数化用例实例：基础兼容性 27、原有备份恢复 9、复杂写入 20、复杂恢复 10、故障注入 8。各 CI 作业只实际运行其配置数据库对应的用例，其他数据库实例会跳过；不能把单个作业的跳过数当作缺失覆盖。

| 路径 | 覆盖数据库 |
| --- | --- |
| 元数据、游标分页、编辑冲突、重新提交、CSV 内容 | 五种 |
| NULL、空字符串、中文、emoji、引号、反斜杠、换行往返 | MySQL、MariaDB、PostgreSQL、SQL Server |
| BIGINT、DECIMAL、微秒时间、二进制、文本的 SQL 导出回放 | 五种 |
| 网格后续行冲突时，前面的修改回滚 | 五种 |
| 手动事务提交前不可见、提交后可见、失败回滚 | MySQL、MariaDB、PostgreSQL、Oracle |
| 表管理服务建表、改列、加列、索引、改名、删表 | MySQL、MariaDB、PostgreSQL、Oracle |
| SQL 逻辑备份恢复及数据、主键、索引核对 | 五种 |
| 原生备份恢复 | MySQL、MariaDB、PostgreSQL |
| MySQL 两种反斜杠转义模式间的恢复 | MySQL |
| 通过数据编辑服务批量新增、复合主键更新/删除、租户隔离 | 五种 |
| 无主键时的复合非空唯一索引定位；拒绝可空唯一索引更新 | 五种 |
| 省略自增/identity 列及默认值列，由数据库生成 | 五种 |
| 批量插入遇外键约束失败时整批回滚 | 五种 |
| 父子表备份恢复，核对连接查询、复合主键、唯一约束及外键的实际约束效果 | 五种 |
| 追加恢复 501 条成功语句后主键冲突，整体回滚；修正后重新预检和重试 | 五种 |
| 慢查询超时、执行中取消、服务端终止连接、连接池耗尽 | MySQL、PostgreSQL |

复杂写入使用 `DataEditService`；恢复测试调用 `BackupService`、`RestoreService.preflight/start`。数据操作、解析、事务和 JDBC 实际执行；应用元数据仓库与调度边界使用 mock。恢复测试将调度同步化，检查失败状态、数据回滚和后台任务占用释放，不代替持久化队列或并发调度测试。

故障注入使用真实 `SqlService`、`SqlExecutionRegistry`、`RemoteDataSourceRegistry` 和 Hikari 连接池。取消及断连前先从服务器观测带随机标记的本例查询，避免在执行前取消而产生误判。只终止本例会话，不重启数据库。断连模拟的是服务端主动关闭会话，不是静默丢包。用例验证失败记录、执行注册清理、连接归还以及下一条查询可执行。

### 数据库差异与边界

- Oracle 将空字符串存为 NULL，因此不运行必须区分二者的用例。
- SQL Server 默认读已锁定行会阻塞；现有手动事务用例以 MVCC 可见性为前提，尚未为 SQL Server 单独验证锁行为。
- SQL Server 当前产品不开放可视化表设计，因此不跑该功能用例。
- MariaDB 使用自身客户端，避免 MySQL 客户端探测差异造成无效测试。
- SQL Server 备份按 `GO` 分批；Oracle 时间用显式转换，读取索引不触发精确统计收集。

## 防止静默跳过与报告

每个作业通过 `TEST_REQUIRED_DATABASES` 点名其负责的数据库；漏配 URL 就失败。原生往返还需要 `TEST_NATIVE_TOOLS=true`，被点名数据库的原生测试不能静默跳过。客户端缺失或数据库连不上也失败。

实库报告始终尝试上传到对应的 `*-compatibility-reports` artifact。测试选择器与报告路径均覆盖 `Database*CompatibilityTest`，新增同类测试无需逐个修改工作流。

## 本地复现

仅使用专用测试数据库。测试会创建、修改并清理随机 `compat_` 表；故障用例还会终止自己创建的测试会话。

```bash
export TEST_REQUIRED_DATABASES=mysql,postgresql
export TEST_NATIVE_TOOLS=true
export TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/mydatadev_test?useSSL=false&allowPublicKeyRetrieval=true'
export TEST_MYSQL_USER=mydatadev
export TEST_MYSQL_PASSWORD='<测试密码>'
export TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:5432/mydatadev_test'
export TEST_POSTGRES_USER=mydatadev
export TEST_POSTGRES_PASSWORD='<测试密码>'
export TEST_MYSQLDUMP_PATH=/usr/bin/mysqldump
export TEST_MYSQL_PATH=/usr/bin/mysql
export TEST_PG_DUMP_PATH=/usr/lib/postgresql/16/bin/pg_dump
export TEST_PG_RESTORE_PATH=/usr/lib/postgresql/16/bin/pg_restore
cd backend
mvn test --batch-mode '-Dtest=Database*CompatibilityTest'
```

MariaDB、SQL Server、Oracle 分别配置 `TEST_MARIADB_*`、`TEST_SQLSERVER_*`、`TEST_ORACLE_*` 的 URL/USER/PASSWORD，并加入必需数据库清单。MariaDB 原生客户端路径为 `TEST_MARIADB_MYSQLDUMP_PATH` 和 `TEST_MARIADB_MYSQL_PATH`。

普通 `mvn test` 未配置独立数据库时会跳过这些实库实例。只跑新增用例可指定 `-Dtest=DatabaseWriteCompatibilityTest,DatabaseRecoveryCompatibilityTest,DatabaseFaultCompatibilityTest`，不需要原生客户端。

## 尚未覆盖

SQLite、ClickHouse、达梦、OceanBase 没有独立实库任务；现有五种数据库缺多版本矩阵。存储过程、复杂视图、全库灾难恢复、恢复后 identity/sequence 的完整状态、跨版本/跨库迁移、恢复任务中途取消、真实网络丢包与数据库重启、磁盘耗尽、大数据压力测试仍未覆盖。CI 通过不表示这些场景已经验证。
