# 真实数据库 CI 回归

`.github/workflows/ci.yml` 在 PR、推送到 `main` 和手动触发时启动 MySQL 8.4、PostgreSQL 16 容器。测试连接独立的 `mydatadev_test` 数据库，不读取应用里保存的连接。

## 覆盖范围

当前共 17 个参数化用例实例：基础兼容性 12 个、备份恢复 5 个。

| 测试路径 | MySQL 8.4 | PostgreSQL 16 |
| --- | --- | --- |
| 元数据、游标分页、最后一页、编辑冲突、重新提交、CSV 内容 | 是 | 是 |
| NULL → 空字符串 → 中文/emoji/引号/反斜杠/换行 → NULL，逐步回库核对 | 是 | 是 |
| 超出 JavaScript 安全整数范围的 BIGINT、高精度 DECIMAL、微秒时间、二进制和文本的 SQL 导出回放 | 是 | 是 |
| 网格批量提交后续行冲突时，前面成功的修改回滚 | 是 | 是 |
| 手动事务提交前不可见、提交后可见、失败后回滚 | 是 | 是 |
| 通过表管理服务建表、改列长度、加列、加索引、改名和删表 | 是 | 是 |
| SQL 逻辑备份 → 删除本例测试表 → 恢复 → 核对数据、主键、索引 | 是 | 是 |
| mysqldump/mysql 或 pg_dump/pg_restore 原生往返 | 是 | 是 |
| 备份与恢复会话使用不同反斜杠转义模式 | 是 | 不适用 |

兼容性测试调用实际业务服务和 JDBC。备份恢复测试调用 `BackupService.run`、`RestoreService.preflight/start` 和真实客户端工具；应用元数据仓库及调度边界使用 mock，任务同步执行以便稳定断言。它不替代持久化仓库、异步队列或浏览器测试。

原生测试只备份本例随机命名的表，恢复前删除该表，验证恢复能重建对象。它不是整库灾难恢复演练，也不覆盖所有备份策略、跨版本恢复或跨数据库迁移。

## 防止静默跳过

CI 设置 `TEST_DATABASES_REQUIRED=true` 和 `TEST_NATIVE_TOOLS=true`。数据库 URL 缺失或关闭原生测试会失败；数据库不可连接、客户端缺失或用例失败同样会失败。报告始终尝试上传到 `database-compatibility-reports` artifact。任务最长运行 20 分钟。

普通本地 `mvn test` 未配置测试数据库时跳过这些实库测试。配置数据库但未设置 `TEST_NATIVE_TOOLS=true` 时，只跳过原生往返。

## 本地复现

准备两个专用测试数据库，并安装 MySQL 客户端和 PostgreSQL 16 客户端。不要把 URL 指向业务库；测试会创建、修改并清理 `compat_` 开头的随机表。

```bash
export TEST_DATABASES_REQUIRED=true
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
mvn test --batch-mode -Dtest=DatabaseCompatibilityTest,DatabaseBackupCompatibilityTest
```

工具路径可以按本机安装位置修改；省略路径时使用应用的工具探测。CI 显式指定 PostgreSQL 16 工具，避免默认客户端版本变化影响恢复语句兼容性。

## 尚未覆盖

Oracle、MariaDB、SQL Server、SQLite、ClickHouse、达梦、OceanBase 的独立实库任务；存储过程、外键依赖复杂结构、权限完整性、全库备份恢复、压力测试及 Windows/macOS 桌面验收尚未由这组测试覆盖。CI 通过只表示上述用例通过，不代表所有数据库和版本均兼容。
