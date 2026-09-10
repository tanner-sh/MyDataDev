# 真实数据库 CI 回归

`.github/workflows/ci.yml` 在 PR、推送到 `main` 和手动触发时跑四个实库作业，**一家数据库一个作业**（MySQL 与 PostgreSQL 合在一个）：

| 作业 | 数据库 | 阻塞合并 |
| --- | --- | --- |
| `database-compatibility` | MySQL 8.4 + PostgreSQL 16 | 是 |
| `mariadb-compatibility` | MariaDB 11.4 | 是 |
| `sqlserver-compatibility` | SQL Server 2022 | 是 |
| `oracle-compatibility` | Oracle Free 23 | 是 |

**为什么一家一个作业**：一家的问题不该把另一家的结论一起遮掉。第一次把五家塞进一个作业时，Oracle 有个用例挂住，整个作业等到 35 分钟上限被取消 —— SQL Server 那一半的结论也就没了。另外 `mariadb-client` 与 `mysql-client` 在 apt 层面互斥（两者都提供 `virtual-mysql-client`），本来就装不到同一个 runner 上，而拿 `mysqldump` 8.4 去打 MariaDB 11 会因为版本探测差异失败 —— 用一个跑不通的组合证明不了兼容性。作业并行，墙上时间不受影响。

每个用例有类级超时（兼容性 3 分钟、备份恢复 5 分钟），**必须是 `threadMode = SEPARATE_THREAD`**。实库测试挂住的方式是等锁，而等锁不会自己超时；而 `@Timeout` 默认在原线程上跑、只在方法返回之后判定耗时 —— 真挂住时它一句话也说不出，用例会一直等到作业上限被外部取消，surefire 报告里连它跑到哪一步都没有（SQL Server 上就这么白烧过 20 分钟）。换成独立线程后 JUnit 才会真的打断它。

### SQL Server 与 Oracle 这一轮修掉的产品缺陷

四个实库作业现在全部阻塞合并。SQL Server 与 Oracle 刚接入时各有几处失败，逐条定性之后是五个真实产品缺陷（已全部修复）加若干条用例自己的 MySQL 中心假设。

SQL Server 上「备份完能不能恢复」此前压根不成立，两处叠加：

- **备份写 DDL 用的是 JDBC 元数据的引用字符，不是方言的。** `getIdentifierQuoteString()` 在 SQL Server 上返回双引号，而 `SqlServerDialect.quoteIdentifier` 用的是方括号：写成 `"dbo"."t"` 之后，恢复端的 SQL Server 解析器把它当字符串而不是标识符。改成走 `dialect.qualifiedName` / `quoteIdentifier` —— CLAUDE.md 那条「标识符引用收敛到方言接口」说的就是这里。
- **备份脚本只有分号，没有 `GO`。** `SqlFileStatementReader` 在 SQL Server 上刻意不按分号切分（T-SQL 的 `BEGIN … END;` 里有内部分号，按分号切会把存储过程切碎），只认独占一行的 `GO`。于是整份备份文件被当成一条语句，恢复预检报 `multi-statement be found`。方言新增 `scriptStatementSeparator()`：默认仍是分号，SQL Server 返回 `";\nGO"`。GO 同时是 sqlcmd 与 SSMS 的批分隔符，写出来的脚本因此也能直接喂给它们。两端的约定由 `SqlServerBackupScriptShapeTest` 钉住，包括反向那条（没有 GO 时 SQL Server 就是不切分）。

Oracle 上是三处：

- **读表结构与备份读索引会在目标库上收一次全表统计。** `getIndexInfo` 的 `approximate=false` 让 Oracle 驱动先跑 `DBMS_STATS.GATHER_TABLE_STATS`，而我们只取索引名与列，精确统计用不上。生产库上那是一次实打实的负载事件，缺 ANALYZE 权限时结构连展都展不开、备份直接失败。三处调用点全改成 `approximate=true`，`MetadataServiceTest` 用动态代理钉住这个实参。
- **导出的 SQL 与备份文件里的时间列在 Oracle 上回放不回去。** 写的是带引号的裸 ISO 串，而 Oracle 按会话的 `NLS_DATE_FORMAT` 解析（默认 `DD-MON-RR`），必然 ORA-01843。方言新增 `scriptTemporalLiteral()`：默认仍是带引号的 ISO 文本，Oracle 覆盖成 `TO_DATE` / `TO_TIMESTAMP` / `TO_TIMESTAMP_TZ`。
- **导出侧还差一层**：`getObject` 交回的是驱动私有类型 `oracle.sql.TIMESTAMP`，既不是 `java.util.Date` 也不是 `Temporal`，方言认不出。改在有列类型信息的读取处用 `getTimestamp` / `getDate` / `getTime`。

**还有一件相关的事没做****还有一件相关的事没做**：用户自己提供的 SQL Server 脚本如果用分号结尾、不带 `GO`（从 SSMS 导出的脚本通常就是这样），`SqlFileStatementReader` 仍会把整份文件当成一条语句。备份这一侧已经绕开了，但 SQL 文件执行那条路上这个限制还在 —— 要修就得在 SQL Server 上按分号切分同时跟踪 `BEGIN`/`END` 深度，那是单独一件事。

Oracle 已经跑绿，`continue-on-error` 已摘。它这一轮暴露了四个问题，两个是产品缺陷（读表结构与备份读索引会触发 `DBMS_STATS.GATHER_TABLE_STATS`；导出与备份的时间列写成裸 ISO 串，Oracle 按 `NLS_DATE_FORMAT` 解析必然 ORA-01843），两个是用例自己的假设太 MySQL 中心（标识符折大写、整数的包装类型）。

## 覆盖范围

当前共 32 个参数化用例实例：基础兼容性 23 个、备份恢复 9 个。

| 测试路径 | MySQL 8.4 | MariaDB 11.4 | PostgreSQL 16 | SQL Server 2022 | Oracle Free 23 |
| --- | --- | --- | --- | --- | --- |
| 元数据、游标分页、最后一页、编辑冲突、重新提交、CSV 内容 | 是 | 是 | 是 | 是 | 是 |
| NULL → 空字符串 → 中文/emoji/引号/反斜杠/换行 → NULL，逐步回库核对 | 是 | 是 | 是 | 是 | 不适用 |
| 超出 JavaScript 安全整数范围的 BIGINT、高精度 DECIMAL、微秒时间、二进制和文本的 SQL 导出回放 | 是 | 是 | 是 | 是 | 是 |
| 网格批量提交后续行冲突时，前面成功的修改回滚 | 是 | 是 | 是 | 是 | 是 |
| 手动事务提交前不可见、提交后可见、失败后回滚 | 是 | 是 | 是 | 不适用 | 是 |
| 通过表管理服务建表、改列长度、加列、加索引、改名和删表 | 是 | 是 | 是 | 不适用 | 是 |
| SQL 逻辑备份 → 删除本例测试表 → 恢复 → 核对数据、主键、索引 | 是 | 是 | 是 | 是 | 是 |
| 原生备份恢复往返 | mysqldump/mysql | mariadb-dump/mariadb | pg_dump/pg_restore | 不适用 | 不适用 |
| 备份与恢复会话使用不同反斜杠转义模式 | 是 | 不适用 | 不适用 | 不适用 | 不适用 |

### 表里的「不适用」都是有意排除，不是漏了

**SQL Server 的字符列用 `NVARCHAR`，字面量带 `N` 前缀**：`VARCHAR` 在 SQL Server 上是非 Unicode 类型，中文写进去会按排序规则的代码页替换成问号；而 T-SQL 里不带前缀的 `'中文'` 也是 VARCHAR 字面量，字符会先按代码页转换再赋给 NVARCHAR 列，同样变问号。两件都是 SQL Server 的真实行为、不是产品问题 —— 产品自己的写入端本来就带 `N` 前缀（备份脚本里就是 `N'…'`），漏掉的是用例的裸 SQL。`Fixture.text()` 负责这一层。

**Oracle 不跑「NULL 与空字符串必须分开」**：Oracle 的 `VARCHAR2` 把空串直接存成 NULL，这条在 Oracle 上不成立，也不是产品能修的。与其把断言放宽成两者都接受（那等于不测），不如明确排除。备份往返那条用例里的空串行改为按方言断言：能区分的库读回空串，Oracle 读回 NULL。

**SQL Server 不跑手动事务**：这条用例验证「提交前别的连接看不到」的办法是从第二条连接读同一行，而那需要 MVCC。SQL Server 默认的 READ COMMITTED 用锁实现，那个 SELECT 会一直阻塞到事务结束，用例就此挂住。阻塞在 SQL Server 上是正确行为，不是产品问题；要在那边验证同一件事得改成断言「读被阻塞」，那是另一条用例。

**SQL Server 不跑表设计**：`SqlServerDialect.capabilities()` 把 `tableDesign` 声明为 false，服务端会直接拒绝，跑它等于断言一个产品不提供的功能。它同样不支持列注释（`supportsColumnComments()` 为 false）。

**SQL Server 与 Oracle 没有原生备份往返**：产品支持的原生方法只有 `MYSQLDUMP`、`PG_DUMP`、`ORACLE_EXP`；SQL Server 方言的 `nativeBackupMethods` 是空的，而 Oracle 的 `exp`/`imp` 客户端不在 runner 上，只跑 SQL 逻辑备份那条路。

**MariaDB 用自己的客户端**：`mysqldump` 8.4 打到 MariaDB 11 上会因为版本探测与 `information_schema` 差异失败，`--set-gtid-purged` 和 `--no-tablespaces` 这两个开关 `mariadb-dump` 也不认。所以工具路径按 `TEST_<TYPE>_<METHOD>_PATH` 优先解析，dump 参数按类型给。用一个跑不通的组合证明不了兼容性。

兼容性测试调用实际业务服务和 JDBC。备份恢复测试调用 `BackupService.run`、`RestoreService.preflight/start` 和真实客户端工具；应用元数据仓库及调度边界使用 mock，任务同步执行以便稳定断言。它不替代持久化仓库、异步队列或浏览器测试。

原生测试只备份本例随机命名的表，恢复前删除该表，验证恢复能重建对象。它不是整库灾难恢复演练，也不覆盖所有备份策略、跨版本恢复或跨数据库迁移。

## 防止静默跳过

每个作业用 `TEST_REQUIRED_DATABASES` 点名它负责哪几家（`mysql,postgresql` / `mariadb` / `sqlserver` / `oracle`），原生工具那两个作业再加 `TEST_NATIVE_TOOLS=true`。被点名那几家的 URL 缺失或关闭原生测试会失败；数据库不可连接、客户端缺失或用例失败同样会失败。报告始终尝试上传到 `database-compatibility-reports` artifact。作业上限 20~30 分钟；Oracle 容器要建 PDB 与用户，单它就可能占掉五六分钟，所以它那个作业给到 30 分钟。

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
