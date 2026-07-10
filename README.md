# MyDataDev

MyDataDev 是一个面向私有网络使用的 Web 数据库管理工具原型，目标是提供接近 DataGrip 的常用数据库浏览、SQL 执行、数据编辑、导入导出和备份任务管理能力。应用采用前后端分离架构，目标数据库访问统一通过后端 JDBC 完成。

## 技术栈

- 后端：Spring Boot 3、Java 17、JDBC、H2 元数据库
- 前端：React、Vite、TypeScript、Ant Design、Monaco Editor
- 数据库驱动：H2、MySQL、PostgreSQL、SQL Server、SQLite、MariaDB、ClickHouse、Oracle
- 本地状态：连接配置、审计日志、SQL 历史和备份任务存储在后端 H2 元数据库中

## 项目结构

```text
backend/
  src/main/java/com/example/dbadmin/
    api/       REST 接口
    service/   业务逻辑
    repo/      JDBC 持久化
    core/      数据库方言适配
    model/     领域模型
    dto/       请求和响应 DTO
  src/main/resources/
    application.yml
    schema.sql
  src/test/java/       后端测试

frontend/
  src/
    components/        React 组件
    api.ts             API 请求封装
    types.ts           前端类型定义
    utils.ts           通用工具
    styles.css         全局样式
```

## 本地运行

启动后端：

```bash
cd backend
mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`。

启动前端：

```bash
cd frontend
npm install
npm run dev
```

前端默认访问 `http://localhost:5173`。前端 API 地址目前在 `frontend/src/constants.ts` 中配置为 `http://localhost:8080/api`。

## 构建与测试

后端测试：

```bash
cd backend
mvn test
```

前端类型检查与生产构建：

```bash
cd frontend
npm run build
```

提交前建议同时运行后端测试和前端构建，确保接口、类型和页面构建都可用。

## 已实现能力

- 数据库连接管理：连接新增、编辑、复制、删除、测试连接和只读标记。
- 密码保护：连接密码加密后存储，密钥由 `DB_ADMIN_CRYPTO_KEY` 控制。
- 元数据浏览：默认按当前 Schema 加载表和视图，切换其他 Schema 时按需加载并缓存；支持查看字段、索引、主键、行数和基础 DDL。
- SQL 工作台：支持多标签页、SQL 格式化、脚本拆分执行、执行计划、语句级结果展示和 SQL 历史。
- 智能补全：基于关键字和已加载元数据提供 SQL 补全建议。
- 查询结果导出：支持 CSV、JSON、SQL、XML 导出。
- 表数据编辑：支持表格浏览、新增行、编辑行、删除行、SQL 预览和提交；无主键或唯一键时限制危险编辑。
- 数据导入：支持 CSV、JSON、SQL 文件导入为待提交插入行。
- 备份任务管理：支持按连接展示备份任务，新建、编辑、启停、删除、手动执行、定时执行、下载最近备份文件。
- 多方式备份：支持内置 SQL 逻辑备份、MySQL/MariaDB `mysqldump`、Oracle `exp`，任务内可配置工具路径和有限额外参数。
- SQL 备份生成：支持全库或单表导出为 SQL `INSERT` 文件；CLOB 会完整写出，BLOB/VARBINARY 当前会明确失败并提示。
- 审计记录：连接、SQL 执行、数据提交和备份操作会写入审计或历史记录。

## 备份说明

备份文件默认写入后端配置的 `app.backup.directory`，默认值为 `./backups`。删除备份任务时可选择是否同时删除最近一次生成的备份文件；后端会校验文件路径必须位于备份目录内，避免误删其他路径文件。

备份方式包括：

- `SQL`：通过 JDBC 查询数据并生成 SQL `INSERT` 文件，适合轻量和跨库场景。
- `MYSQLDUMP`：调用后端服务器上的 `mysqldump`，支持 MySQL/MariaDB 全库或单表备份，密码通过环境变量传递。
- `ORACLE_EXP`：调用后端服务器上的 Oracle `exp`，支持全库或单表备份，复杂连接名可在任务中覆盖。

原生备份工具必须安装在运行后端服务的机器上。额外参数按“一行一个参数”填写，输出文件、账号、密码、数据库范围等关键参数由系统控制。

定时备份使用 Spring cron 表达式。启用定时任务时必须填写合法 cron；空 cron 表示手动任务。

## 配置说明

主要配置位于 `backend/src/main/resources/application.yml`：

- `server.port`：后端端口，默认 `8080`
- `spring.datasource.*`：H2 元数据库配置
- `app.crypto-key`：连接密码加密密钥，建议通过 `DB_ADMIN_CRYPTO_KEY` 环境变量提供
- `app.sql.max-rows`：SQL 查询默认最大行数
- `app.sql.timeout-seconds`：SQL 执行超时时间
- `app.backup.directory`：备份文件输出目录

不要将真实数据库凭据或生产密钥提交到 Git。

## Oracle 连接示例

```text
Service Name: jdbc:oracle:thin:@//localhost:1521/ORCLPDB1
SID:          jdbc:oracle:thin:@localhost:1521:ORCL
```

Oracle 表数据分页使用兼容 Oracle 11g 的 `ROWNUM` 查询；执行计划使用 `EXPLAIN PLAN FOR ...` 和 `DBMS_XPLAN.DISPLAY()`。

## 提交约定

本项目约定 Git 提交信息使用中文，并尽量写清楚改动内容。复杂改动建议使用中文标题加多条中文说明，例如：

```text
完善备份任务管理并优化结果区布局

- 新增备份任务编辑、启停和删除接口
- 补充前端任务管理入口和删除确认
- 增加服务层测试覆盖 cron 校验和文件删除策略
```
