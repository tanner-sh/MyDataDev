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

# Web 发行包（内置前端的可执行 JAR + 前端 dist 包，输出到 release-assets/）
node scripts/build-web-bundle.mjs

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
- **`service/RemoteDataSourceRegistry`**：目标库 HikariCP 连接池的缓存与淘汰，连接配置变更后要 `evict`。连接启用 SSH 隧道时，池与 `SshTunnel` 的生命周期绑定在同一个 `PoolEntry` 上；建池（含 SSH 握手）发生在注册表锁外面，`install` 负责收拾并发建出来的多余池。
- **`service/SshTunnel` + `JdbcEndpoints` + `SshTunnelProfile`**：SSH 跳板机支持。`JdbcEndpoints` 按 URL scheme（不是方言）定位并改写 `host:port`，`SshTunnel` 用 Apache MINA sshd 起本地端口转发，`SshTunnelProfile` 负责隧道配置的规范化、掩码语义（`******` 沿用旧密钥）与加解密转换。密钥与数据库密码同用 `app.crypto-key`。
- **`service/SchemaDiffService` + `SchemaComparison`**：两个 Schema 的结构对比。`SchemaComparison` 是纯逻辑（差异判定 + 生成 `TableDesignRequest` 设计稿），DDL 一律交给**目标端方言**的 `createTableSql` / `alterTableSql`，复用表设计器那条路径。注意方言要求设计稿覆盖目标表的每个字段与可编辑索引，且必须排除主键背后的索引。对比只读，生成的脚本交给用户在 SQL 工作台执行 —— 这样生产确认、无 WHERE 写操作确认与审计都不会被绕过。
- **`repo/AuditRepository`**：审计写入只有两个入口 —— `onConnection(actor, action, connectionId, [subject,] detail)` 与 `global(actor, action, target, detail)`。**连接归属是 `audit_log.connection_id` 这个字段，不是拼在 `target` 字符串里的前缀**；`target` 只负责给人看。新增埋点时凡是有连接可归属的一律走 `onConnection`，否则那条记录在「按连接筛选」里会永远查不到。动作码要同步在 `frontend/src/auditLog.ts` 里加中文名，`AuditActionLabelCoverageTest` 会卡住。
- **`service/MetadataCacheService`**：Caffeine 多级元数据缓存（schema 目录、对象分页、对象详情）。任何改结构的操作都必须调用对应的 `evict*`，否则资源树会显示陈旧数据。
- **`service/RowLocatorCodec` / `TableCursorCodec`**：行定位令牌与游标分页令牌，用 `CryptoService` 签名后下发给前端，前端不接触真实主键值。解码失败即拒绝编辑请求。
- **`service/BackgroundTaskControl` + `BackupExecutionCoordinator` / `SqlFileExecutionCoordinator`**：备份和大 SQL 文件执行的后台队列、并发上限（含每连接上限）、进度上报与取消。`SqlExecutionRegistry` 负责前台 SQL 的按 executionId 取消。
- **`service/BackgroundTaskStream`**：后台任务进度的 SSE 推送（`GET /api/restores/operations/stream`）。轮询收到了服务端：有订阅者时按 `app.background-tasks.stream-interval-ms` 扫一遍，内容变了才推，没有订阅者时一条查询都不发。`/operations/active` 保留为降级用的轮询接口，前端在 SSE 建不起来时退回它。反向代理必须关闭响应缓冲。
- **前端 `src/resultChart.ts` + `components/ResultChart.tsx`**：查询结果图表。建模是纯逻辑（能画什么、色位怎么分、上限与提示），渲染是自绘 SVG（不引图表库，首屏预算是硬约束）。分类色板与深浅两套取值经 `dataviz` 规范的校验脚本在本应用的真实表面色上验证过，**色位顺序本身是色觉安全机制，不要随手调换**；序列色位跟着所选列走，筛掉一条不会让其余换色。绝不加第二个 Y 轴：量级悬殊时给提示让用户拆开看。
- **`service/XlsxWriter` + 前端 `src/xlsx.ts`**：Excel 导出的两个入口写的是同一个 OOXML 子集（单工作表、inline string、无 styles.xml），类型判定规则也必须保持一致 —— 后端负责「重新查询并导出」，前端负责「导出本批」。两边都不按数字写超过 15 位有效数字的整数：Excel 用双精度存数字，19 位的雪花 ID 会被静默改写。都没有引入第三方库（前端的 ZIP 用 STORED 不压缩）。
- **`storage/BackupStorage` + `BackupStorageRegistry`**：SMB / NFS / FTP / SFTP 远端备份目标的统一抽象。远端备份先写本地再原子改名上传，失败保留暂存文件。

### 安全模型

`config/SecurityConfig` 有两条过滤链：`/mcp` 要求 `ROLE_MCP_AGENT`（由 `McpApiKeyAuthenticationFilter` 按 Agent API Key 认证，无状态）；其余请求 `permitAll`。**`/api` 没有用户认证**，前端发送的 `X-User: admin` 只是审计用的操作者标签。因此 Web 模式必须部署在可信网络并由外层反向代理承担鉴权。数据库密码用 `app.crypto-key`（`DB_ADMIN_CRYPTO_KEY`）加密后存 H2；换密钥会导致已有密文无法解密。

MCP 侧的授权在 `mcp/McpAccessService`：Agent 只能访问白名单内的连接，生产连接需要额外授权，工具全部只读（`listConnections`、`listNamespaces`、`searchObjects`、`describeObject`、`getObjectDdl`、`browseTable`、`query`、`explain`）。

### 数据库迁移

元数据库 schema 由 Flyway 管理，迁移是 **Java 类**，位于 `backend/src/main/java/db/migration/`（`V1__BaselineSchema.java` 等），不是 SQL 文件。`src/main/resources/*.sql` 是被迁移引用的脚本，直接改它们不会触发迁移。

### 前端结构

`src/App.tsx`（约 2700 行）是唯一的状态容器：连接选择、SQL 标签页、表格工作区、后台任务轮询、生产确认弹窗都在这里。所有重型面板（`SqlWorkspace`、`ObjectDetailWorkspace`、`BackupPanel`、`McpSettingsPanel` 等）通过 `React.lazy` 懒加载 —— 这是构建体积预算能通过的前提。

有状态但与界面无关的子系统抽成 `src/hooks/` 下的自定义 hook（`useBackgroundTasks`、`useSqlHistory`、`useVisiblePolling`、`useLayoutPreferences`），hook 内部依赖的纯逻辑再落到 `src/` 下的同名模块（`backgroundTaskStream.ts`、`sqlHistoryQuery.ts`）。新增「一组状态 + 一条取数路径」时优先走这条路，而不是继续往 `App.tsx` 里加 `useState`。

**测试约定：纯逻辑从组件里抽出来，放在 `src/` 下的独立模块，每个模块配一个同名 `.test.ts`**（`sqlCompletion.ts`/`sqlCompletion.test.ts`、`resultGridData.ts`、`productionConfirmation.ts`、`tableLifecycle.ts` …）。仓库里没有组件渲染测试。新增行为时，先想清楚哪部分能抽成纯函数放进这类模块并补测试，而不是把逻辑埋进 `.tsx`。

`src/api.ts` 的 `ApiError` 会解析后端问题详情中的 `code`、`confirmationText`、`statements`，前端据此弹出生产确认或未限定范围写操作的确认框 —— 后端新增这类错误码时要同步这里。

### 桌面模式

Electron 主进程（`desktop/src/main.ts`、`backend.ts`、`paths.ts`、`secret-store.ts`）以 `--spring.profiles.active=desktop` 拉起同一个 Spring Boot JAR，并通过 `MYDATADEV_DESKTOP_HOME` / `_CONTROL_TOKEN` / `_PARENT_PID` 环境变量注入运行时上下文。desktop profile 下后端监听 `127.0.0.1:5173` 并直接提供打包进 JAR 的前端静态资源（Maven `desktop` profile 把 `frontend/dist` 复制到 `static/`），所以桌面版页面与 MCP 共用 5173。加密密钥存在操作系统安全存储中（Keychain / DPAPI / Secret Service）。

**桌面模式与 Web 模式使用完全独立的元数据库和文件目录，连接、密码、Agent、备份都不共享。** 开发时桌面数据写入 `desktop/.dev-data`。

### Web 发行模式

`mvn -Pweb package` 与 `desktop` profile 用同一套机制把 `frontend/dist` 拷进 JAR 的 `static/`，产出 `mydatadev-web.jar`：单进程在 8080 上同源提供 UI、`/api` 和 `/mcp`，前端默认的 `/api` 基址因此无需改动，也不涉及 CORS。发行 JAR 必须以 `--spring.profiles.active=web` 启动，`application-web.yml` 在这个 profile 下关闭 H2 控制台、启用优雅停机并落地日志文件；数据目录相对于进程工作目录。`.github/workflows/release.yml` 的 `web` 作业构建并烟测该 JAR，与桌面安装包发布到同一个 Release，部署细节见 `docs/web-deploy.md`。

## 约定

- 提交信息用中文，尽量详细。多模块改动用「中文标题 + 多条中文说明」，说明改了什么、为什么改、影响哪些功能。格式示例见 `AGENTS.md`。
- 后端 DTO/模型优先用 `record`；测试用 JUnit 5 + AssertJ + Mockito，类名沿用 `*Test`。
- 前端用严格 TypeScript 和函数组件，公共类型放 `src/types.ts`，UI 沿用已有 Ant Design 组件和 `src/styles.css` 的样式体系，不引入新的 UI 风格。
- 面向用户的报错文案是中文，且后端错误通过 `ApiProblemException(status, code, message, details)` 抛出，由 `ApiExceptionHandler` 统一序列化。

## 其他文档

- `AGENTS.md` — 仓库结构、编码与提交规范
- `docs/desktop.md` — 桌面开发、数据目录、打包与 GitHub Actions 发行
- `docs/web-deploy.md` — Web 发行包部署、反向代理、安全边界与升级
- `docs/mcp-server.md` — MCP 配置、客户端接入与安全边界
