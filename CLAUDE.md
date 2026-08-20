# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

MyDataDev 是一个数据库工作台，由三个模块组成：`backend`（Spring Boot 3 / Java 17）、`frontend`（React + Vite + TypeScript）、`desktop`（Electron 打包器）。仓库文档、注释与提交信息以中文为主。

## 常用命令

```bash
# 后端（无 mvnw wrapper，直接用 mvn）
cd backend && mvn spring-boot:run           # 启动 API，端口 8080
cd backend && mvn test                      # 全部测试
cd backend && mvn test -Dtest=BackupServiceTest
cd backend && mvn test -Dtest=BackupServiceTest#shouldQueueJob

# 前端
cd frontend && npm run dev                  # Vite dev server，5173
cd frontend && npm test                     # vitest run
cd frontend && npm test -- src/utils.test.ts
cd frontend && npm run build                # tsc + vite build + 打包体积预算校验

# 桌面端
cd desktop && npm run dev                   # 编排 Spring Boot + Vite + Electron
cd desktop && npm test
cd desktop && npm run build:main            # 主进程 TS 构建（兼作类型检查）
cd desktop && npm run make                  # 生成当前平台安装包（需原生环境）
```

提交前的最低验证（见 AGENTS.md）：`cd backend && mvn test` 与 `cd frontend && npm run build`。

`npm run build` 末尾会执行 `frontend/scripts/check-build-budget.mjs`，对首屏资源数、首屏 gzip 体积以及 `SqlWorkspace` / `SqlEditor` 懒加载链的 gzip 体积设硬上限。新增依赖或改动 import 结构导致构建失败时，先确认是否把重型模块拉进了首屏，而不是直接调高预算常量。

`backend/pom.xml`、`frontend/package.json`、`desktop/package.json` 三个版本号必须一致，`desktop npm run make` 会通过 `check-versions.mjs` 强制校验。

## 架构要点

### 请求路径

浏览器/Electron 页面 → `REST /api` → Spring Boot → JDBC → 目标数据库。AI Agent → `Streamable HTTP /mcp` → 同一个后端。后端自身状态存在本地 H2 元数据库（连接配置、SQL 历史、审计、MCP Agent、备份/恢复任务），目标业务数据永远只留在远端库里。

Vite dev server 把 `/api` 和 `/mcp` 代理到 `http://localhost:8080`。前端 API 基址是 `frontend/src/constants.ts` 中的 `API`（`VITE_API_BASE_URL` 或 `/api`）。

### 后端分层

`api`（REST 控制器）→ `service`（业务规则）→ `repo`（JdbcTemplate 持久化），DTO 集中在 `dto/ApiDtos.java` 等文件，模型用 Java `record`。跨越这三层放代码会破坏现有约定。

几个必须先理解的中枢组件：

- **`core/DatabaseDialect` + `DialectRegistry`**：所有数据库差异（分页 SQL、EXPLAIN、DDL 生成、标识符引用、schema/catalog 语义、对象能力矩阵）都收敛到方言接口。`DialectRegistry` 按声明顺序做 `supports(dbType, jdbcUrl)` 首次匹配，顺序有意义（OceanBase 在 Oracle/MySQL 之前，`DefaultDialect` 兜底）。新增数据库支持 = 新增一个 dialect 并插入到正确位置，而不是在 service 里加 if。
- **`service/ExecutionGuard`**：只读连接与生产连接的统一闸门。生产连接上的自由 SQL 与所有写操作都要求调用方回传与连接名完全相同的确认串，否则抛 `409 PRODUCTION_CONFIRMATION_REQUIRED`。表浏览生成的查询不走这个闸门。
- **`service/RemoteDataSourceRegistry`**：目标库 HikariCP 连接池的缓存与淘汰，连接配置变更后要 `evict`。
- **`service/MetadataCacheService`**：Caffeine 多级元数据缓存（schema 目录、对象分页、对象详情）。任何改结构的操作都必须调用对应的 `evict*`，否则资源树会显示陈旧数据。
- **`service/RowLocatorCodec` / `TableCursorCodec`**：行定位令牌与游标分页令牌，用 `CryptoService` 签名后下发给前端，前端不接触真实主键值。解码失败即拒绝编辑请求。
- **`service/BackgroundTaskControl` + `BackupExecutionCoordinator` / `SqlFileExecutionCoordinator`**：备份和大 SQL 文件执行的后台队列、并发上限（含每连接上限）、进度上报与取消。`SqlExecutionRegistry` 负责前台 SQL 的按 executionId 取消。
- **`storage/BackupStorage` + `BackupStorageRegistry`**：SMB / NFS / FTP / SFTP 远端备份目标的统一抽象。远端备份先写本地再原子改名上传，失败保留暂存文件。

### 安全模型

`config/SecurityConfig` 有两条过滤链：`/mcp` 要求 `ROLE_MCP_AGENT`（由 `McpApiKeyAuthenticationFilter` 按 Agent API Key 认证，无状态）；其余请求 `permitAll`。**`/api` 没有用户认证**，前端发送的 `X-User: admin` 只是审计用的操作者标签。因此 Web 模式必须部署在可信网络并由外层反向代理承担鉴权。数据库密码用 `app.crypto-key`（`DB_ADMIN_CRYPTO_KEY`）加密后存 H2；换密钥会导致已有密文无法解密。

MCP 侧的授权在 `mcp/McpAccessService`：Agent 只能访问白名单内的连接，生产连接需要额外授权，工具全部只读（`listConnections`、`listNamespaces`、`searchObjects`、`describeObject`、`getObjectDdl`、`browseTable`、`query`、`explain`）。

### 数据库迁移

元数据库 schema 由 Flyway 管理，迁移是 **Java 类**，位于 `backend/src/main/java/db/migration/`（`V1__BaselineSchema.java` 等），不是 SQL 文件。`src/main/resources/*.sql` 是被迁移引用的脚本，直接改它们不会触发迁移。

### 前端结构

`src/App.tsx`（约 2700 行）是唯一的状态容器：连接选择、SQL 标签页、表格工作区、后台任务轮询、生产确认弹窗都在这里。所有重型面板（`SqlWorkspace`、`ObjectDetailWorkspace`、`BackupPanel`、`McpSettingsPanel` 等）通过 `React.lazy` 懒加载 —— 这是构建体积预算能通过的前提。

**测试约定：纯逻辑从组件里抽出来，放在 `src/` 下的独立模块，每个模块配一个同名 `.test.ts`**（`sqlCompletion.ts`/`sqlCompletion.test.ts`、`resultGridData.ts`、`productionConfirmation.ts`、`tableLifecycle.ts` …）。仓库里没有组件渲染测试。新增行为时，先想清楚哪部分能抽成纯函数放进这类模块并补测试，而不是把逻辑埋进 `.tsx`。

`src/api.ts` 的 `ApiError` 会解析后端问题详情中的 `code`、`confirmationText`、`statements`，前端据此弹出生产确认或未限定范围写操作的确认框 —— 后端新增这类错误码时要同步这里。

### 桌面模式

Electron 主进程（`desktop/src/main.ts`、`backend.ts`、`paths.ts`、`secret-store.ts`）以 `--spring.profiles.active=desktop` 拉起同一个 Spring Boot JAR，并通过 `MYDATADEV_DESKTOP_HOME` / `_CONTROL_TOKEN` / `_PARENT_PID` 环境变量注入运行时上下文。desktop profile 下后端监听 `127.0.0.1:5173` 并直接提供打包进 JAR 的前端静态资源（Maven `desktop` profile 把 `frontend/dist` 复制到 `static/`），所以桌面版页面与 MCP 共用 5173。加密密钥存在操作系统安全存储中（Keychain / DPAPI / Secret Service）。

**桌面模式与 Web 模式使用完全独立的元数据库和文件目录，连接、密码、Agent、备份都不共享。** 开发时桌面数据写入 `desktop/.dev-data`。

## 约定

- 提交信息用中文，尽量详细。多模块改动用「中文标题 + 多条中文说明」，说明改了什么、为什么改、影响哪些功能。格式示例见 `AGENTS.md`。
- 后端 DTO/模型优先用 `record`；测试用 JUnit 5 + AssertJ + Mockito，类名沿用 `*Test`。
- 前端用严格 TypeScript 和函数组件，公共类型放 `src/types.ts`，UI 沿用已有 Ant Design 组件和 `src/styles.css` 的样式体系，不引入新的 UI 风格。
- 面向用户的报错文案是中文，且后端错误通过 `ApiProblemException(status, code, message, details)` 抛出，由 `ApiExceptionHandler` 统一序列化。

## 其他文档

- `AGENTS.md` — 仓库结构、编码与提交规范
- `docs/desktop.md` — 桌面开发、数据目录、打包与 GitHub Actions 发行
- `docs/mcp-server.md` — MCP 配置、客户端接入与安全边界
