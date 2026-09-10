# 真实数据库 CI 回归

`.github/workflows/ci.yml` 在 PR、推送到 `main` 和手动触发时跑两个实库作业：一个起 MySQL 8.4、PostgreSQL 16、SQL Server 2022、Oracle Free 23，另一个单独起 MariaDB 11.4。

**为什么 MariaDB 要单独一个作业**：`mariadb-client` 与 `mysql-client` 在 apt 层面互斥（两者都提供 `virtual-mysql-client`），装不到同一个 runner 上。而拿 `mysqldump` 8.4 去打 MariaDB 11 会因为版本探测与 `information_schema` 差异失败 —— 用一个跑不通的组合证明不了兼容性。两个作业并行，墙上时间几乎不变。
测试连接独立的 `mydatadev_test` 数据库（Oracle 用 `FREEPDB1` 里的 `mydatadev` 用户），不读取应用里保存的连接。

## 覆盖范围

当前共 33 个参数化用例实例：基础兼容性 24 个、备份恢复 9 个。

| 测试路径 | MySQL 8.4 | MariaDB 11.4 | PostgreSQL 16 | SQL Server 2022 | Oracle Free 23 |
| --- | --- | --- | --- | --- | --- |
| 元数据、游标分页、最后一页、编辑冲突、重新提交、CSV 内容 | 是 | 是 | 是 | 是 | 是 |
| NULL → 空字符串 → 中文/emoji/引号/反斜杠/换行 → NULL，逐步回库核对 | 是 | 是 | 是 | 是 | 不适用 |
| 超出 JavaScript 安全整数范围的 BIGINT、高精度 DECIMAL、微秒时间、二进制和文本的 SQL 导出回放 | 是 | 是 | 是 | 是 | 是 |
| 网格批量提交后续行冲突时，前面成功的修改回滚 | 是 | 是 | 是 | 是 | 是 |
| 手动事务提交前不可见、提交后可见、失败后回滚 | 是 | 是 | 是 | 是 | 是 |
| 通过表管理服务建表、改列长度、加列、加索引、改名和删表 | 是 | 是 | 是 | 不适用 | 是 |
| SQL 逻辑备份 → 删除本例测试表 → 恢复 → 核对数据、主键、索引 | 是 | 是 | 是 | 是 | 是 |
| 原生备份恢复往返 | mysqldump/mysql | mariadb-dump/mariadb | pg_dump/pg_restore | 不适用 | 不适用 |
| 备份与恢复会话使用不同反斜杠转义模式 | 是 | 不适用 | 不适用 | 不适用 | 不适用 |

### 表里的「不适用」都是有意排除，不是漏了

**Oracle 不跑「NULL 与空字符串必须分开」**：Oracle 的 `VARCHAR2` 把空串直接存成 NULL，这条在 Oracle 上不成立，也不是产品能修的。与其把断言放宽成两者都接受（那等于不测），不如明确排除。备份往返那条用例里的空串行改为按方言断言：能区分的库读回空串，Oracle 读回 NULL。

**SQL Server 不跑表设计**：`SqlServerDialect.capabilities()` 把 `tableDesign` 声明为 false，服务端会直接拒绝，跑它等于断言一个产品不提供的功能。它同样不支持列注释（`supportsColumnComments()` 为 false）。

**SQL Server 与 Oracle 没有原生备份往返**：产品支持的原生方法只有 `MYSQLDUMP`、`PG_DUMP`、`ORACLE_EXP`；SQL Server 方言的 `nativeBackupMethods` 是空的，而 Oracle 的 `exp`/`imp` 客户端不在 runner 上，只跑 SQL 逻辑备份那条路。

**MariaDB 用自己的客户端**：`mysqldump` 8.4 打到 MariaDB 11 上会因为版本探测与 `information_schema` 差异失败，`--set-gtid-purged` 和 `--no-tablespaces` 这两个开关 `mariadb-dump` 也不认。所以工具路径按 `TEST_<TYPE>_<METHOD>_PATH` 优先解析，dump 参数按类型给。用一个跑不通的组合证明不了兼容性。

兼容性测试调用实际业务服务和 JDBC。备份恢复测试调用 `BackupService.run`、`RestoreService.preflight/start` 和真实客户端工具；应用元数据仓库及调度边界使用 mock，任务同步执行以便稳定断言。它不替代持久化仓库、异步队列或浏览器测试。

原生测试只备份本例随机命名的表，恢复前删除该表，验证恢复能重建对象。它不是整库灾难恢复演练，也不覆盖所有备份策略、跨版本恢复或跨数据库迁移。

## 防止静默跳过

每个作业用 `TEST_REQUIRED_DATABASES` 点名它负责哪几家（主作业是 `mysql,postgresql,sqlserver,oracle`，MariaDB 作业是 `mariadb`），再加 `TEST_NATIVE_TOOLS=true`。被点名那几家的 URL 缺失或关闭原生测试会失败；数据库不可连接、客户端缺失或用例失败同样会失败。报告始终尝试上传到 `database-compatibility-reports` artifact。任务最长运行 35 分钟 —— Oracle 容器要建 PDB 与用户，单它就可能占掉五六分钟。

普通本地 `mvn test` 未配置测试数据库时跳过这些实库测试。配置数据库但未设置 `TEST_NATIVE_TOOLS=true` 时，只跳过原生往返。没被 `TEST_REQUIRED_DATABASES` 点名、又没配 URL 的那几家照旧跳过。

## 本地复现

准备五个专用测试数据库，并安装 MySQL、MariaDB 与 PostgreSQL 16 客户端。缺哪一个的 URL，对应那家就跳过（CI 上则失败）。不要把 URL 指向业务库；测试会创建、修改并清理 `compat_` 开头的随机表。

```bash
# 点名清单而不是布尔开关：CI 分两个作业跑不同子集，一个「所有库都必须配」的布尔量
# 在那种情况下只能被整体关掉，防静默跳过也就跟着失效了。
export TEST_REQUIRED_DATABASES=mysql,mariadb,postgresql,sqlserver,oracle
export TEST_NATIVE_TOOLS=true
export TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/mydatadev_test?useSSL=false&allowPublicKeyRetrieval=true'
export TEST_MYSQL_USER=mydatadev
export TEST_MYSQL_PASSWORD='<测试密码>'
export TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:5432/mydatadev_test'
export TEST_POSTGRES_USER=mydatadev
export TEST_POSTGRES_PASSWORD='<测试密码>'
export TEST_MARIADB_URL='jdbc:mariadb://127.0.0.1:3307/mydatadev_test'
export TEST_MARIADB_USER=mydatadev
export TEST_MARIADB_PASSWORD='<测试密码>'
export TEST_MARIADB_MYSQLDUMP_PATH=/usr/bin/mariadb-dump
export TEST_MARIADB_MYSQL_PATH=/usr/bin/mariadb
export TEST_SQLSERVER_URL='jdbc:sqlserver://127.0.0.1:1433;databaseName=mydatadev_test;encrypt=true;trustServerCertificate=true'
export TEST_SQLSERVER_USER=sa
export TEST_SQLSERVER_PASSWORD='<测试密码>'
export TEST_ORACLE_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
export TEST_ORACLE_USER=mydatadev
export TEST_ORACLE_PASSWORD='<测试密码>'
export TEST_MYSQLDUMP_PATH=/usr/bin/mysqldump
export TEST_MYSQL_PATH=/usr/bin/mysql
export TEST_PG_DUMP_PATH=/usr/lib/postgresql/16/bin/pg_dump
export TEST_PG_RESTORE_PATH=/usr/lib/postgresql/16/bin/pg_restore
cd backend
mvn test --batch-mode -Dtest=DatabaseCompatibilityTest,DatabaseBackupCompatibilityTest
```

工具路径可以按本机安装位置修改；省略路径时使用应用的工具探测。CI 显式指定 PostgreSQL 16 工具，避免默认客户端版本变化影响恢复语句兼容性。

## 尚未覆盖

`DialectRegistry` 里共 14 个方言实现，这组测试覆盖 5 个。剩下的 SQLite、ClickHouse、达梦、OceanBase（MySQL 与 Oracle 两个变体）没有实库任务 —— 其中 OceanBase 两个变体在注册表里排在 Oracle/MySQL 之前，这种顺序敏感的匹配恰恰是单元测试最容易测过而实库出问题的地方。

存储过程与函数的参数化调用、外键依赖的复杂结构、权限完整性、全库备份恢复、压力测试及 Windows/macOS 桌面验收尚未由这组测试覆盖。CI 通过只表示上述用例通过，不代表所有数据库和版本均兼容。
