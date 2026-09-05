# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

MyDataDev 是一个数据库工作台，由三个模块组成：`backend`（Spring Boot 3 / Java 17）、`frontend`（React + Vite + TypeScript）、`desktop`（Electron 打包器）。仓库文档、注释与提交信息以中文为主。

## 常用命令

```bash
# 界面冒烟（用系统 Chrome，不装 Playwright）
node scripts/ui-smoke.mjs --serve --shots ./ui-shots   # 自己拉起 release-assets 里的 Web 发行包
node scripts/ui-smoke.mjs --shots ./ui-shots           # 连到已经跑着的前后端

# 后端（无 mvnw wrapper，直接用 mvn）
# 首次启动自动生成 backend/secrets/mydatadev-master.key
cd backend && mvn spring-boot:run            # 启动 API，端口 8080
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
- **`service/SshTunnel` + `JdbcEndpoints` + `SshTunnelProfile`**：SSH 跳板机支持。`JdbcEndpoints` 按 URL scheme（不是方言）定位并改写 `host:port`，`SshTunnel` 用 Apache MINA sshd 起本地端口转发，`SshTunnelProfile` 负责隧道配置的规范化、掩码语义（`******` 沿用旧密钥）与加解密转换。SSH 凭据与数据库密码共用系统托管主密钥。
- **`service/DataDiffService` + `DataComparison`**：两张表的逐行数据对比（结构对比的另一半）。**按主键哈希比对，不做归并** —— 归并要求两侧按主键有序，而顺序取决于数据库的排序规则，两边排序规则不同会把大批相同的行报成差异。代价是两侧整批进内存，所以行数封顶；宁可限制规模也不要给出错误的结论。二进制与大对象列不参与对比（按文本读不可靠），跳过了哪些列写进 warnings。同步脚本按目标端方言生成，默认不生成 DELETE，一律交给用户在 SQL 工作台执行。表名一律用元数据解析出的规范名，不要拿用户输入去拼带引号的 SQL。
- **`service/SchemaDiffService` + `SchemaComparison`**：两个 Schema 的结构对比。`SchemaComparison` 是纯逻辑（差异判定 + 生成 `TableDesignRequest` 设计稿），DDL 一律交给**目标端方言**的 `createTableSql` / `alterTableSql`，复用表设计器那条路径。注意方言要求设计稿覆盖目标表的每个字段与可编辑索引，且必须排除主键背后的索引。对比只读，生成的脚本交给用户在 SQL 工作台执行 —— 这样生产确认、无 WHERE 写操作确认与审计都不会被绕过。
- **`repo/AuditRepository`**：审计写入只有两个入口 —— `onConnection(actor, action, connectionId, [subject,] detail)` 与 `global(actor, action, target, detail)`。**连接归属是 `audit_log.connection_id` 这个字段，不是拼在 `target` 字符串里的前缀**；`target` 只负责给人看。新增埋点时凡是有连接可归属的一律走 `onConnection`，否则那条记录在「按连接筛选」里会永远查不到。动作码要同步在 `frontend/src/auditLog.ts` 里加中文名，`AuditActionLabelCoverageTest` 会卡住。
- **`service/MetadataCacheService`**：Caffeine 多级元数据缓存（schema 目录、对象分页、对象详情）。任何改结构的操作都必须调用对应的 `evict*`，否则资源树会显示陈旧数据。
- **`service/RowLocatorCodec` / `TableCursorCodec`**：行定位令牌与游标分页令牌，用 `CryptoService` 签名后下发给前端，前端不接触真实主键值。解码失败即拒绝编辑请求。
- **`service/BackgroundTaskControl` + `BackupExecutionCoordinator` / `SqlFileExecutionCoordinator`**：备份和大 SQL 文件执行的后台队列、并发上限（含每连接上限）、进度上报与取消。`SqlExecutionRegistry` 负责前台 SQL 的按 executionId 取消。
- **`service/BackgroundTaskStream`**：后台任务进度的 SSE 推送（`GET /api/restores/operations/stream`）。轮询收到了服务端：有订阅者时按 `app.background-tasks.stream-interval-ms` 扫一遍，内容变了才推，没有订阅者时一条查询都不发。`/operations/active` 保留为降级用的轮询接口，前端在 SSE 建不起来时退回它。反向代理必须关闭响应缓冲。
- **前端 `src/resultChart.ts` + `components/ResultChart.tsx`**：查询结果图表。建模是纯逻辑（能画什么、色位怎么分、上限与提示），渲染是自绘 SVG（不引图表库，首屏预算是硬约束）。分类色板与深浅两套取值经 `dataviz` 规范的校验脚本在本应用的真实表面色上验证过，**色位顺序本身是色觉安全机制，不要随手调换**；序列色位跟着所选列走，筛掉一条不会让其余换色。绝不加第二个 Y 轴：量级悬殊时给提示让用户拆开看。
- **`service/XlsxStreamReader`**：Excel 导入的读取端，与 `XlsxWriter` 对着同一个 OOXML 子集，同样不引第三方库。难的只有日期 —— 在文件里它就是个数字，是不是日期只写在 `styles.xml` 里，不解析样式的话每个日期列都会变成一串五位数悄悄写进库。XML 解析一律关掉 DTD 与外部实体（上传文件在服务器上解析，XXE 是真实风险）。CSV 与 Excel 共用 `ImportRowSource` 之后的整条生成管线 —— 那里面有转义与注释两处安全约定，复制一份就等于多一处会漏掉的地方。
- **`service/XlsxWriter` + 前端 `src/xlsx.ts`**：Excel 导出的两个入口写的是同一个 OOXML 子集（单工作表、inline string、无 styles.xml），类型判定规则也必须保持一致 —— 后端负责「重新查询并导出」，前端负责「导出本批」。两边都不按数字写超过 15 位有效数字的整数：Excel 用双精度存数字，19 位的雪花 ID 会被静默改写。都没有引入第三方库（前端的 ZIP 用 STORED 不压缩）。
- **备份校验（`BackupService.verifyHistory` + `BackupVerification`）**：把备份文件完整读一遍，比对大小与 SHA-256。**这是文件级校验，不是恢复演练** —— 它回答「文件还在吗、内容有没有变、远端还取得回来吗」，恰好覆盖现实中最常见的备份失效（上传截断、远端损坏或被清理、备份进程被杀留下半成品）。真正的演练需要一个可写的临时库、建库权限和保证清理的路径，那是另一件事；别把这个功能说成演练。文本备份的尾部形状只作提示，不作判据。
- **`storage/BackupStorage` + `BackupStorageRegistry`**：SMB / NFS / FTP / SFTP 远端备份目标的统一抽象。远端备份先写本地再原子改名上传，失败保留暂存文件。

### 安全模型

`config/SecurityConfig` 有两条过滤链：`/mcp` 要求 `ROLE_MCP_AGENT`（由 `McpApiKeyAuthenticationFilter` 按 Agent API Key 认证，无状态）；其余请求按运行 profile 的认证模式处理。数据库密码使用系统托管主密钥加密后存 H2；默认文件为 `./secrets/mydatadev-master.key`，桌面正式版则由 Electron 经标准输入一次性交付。丢失或替换密钥会导致已有密文无法解密；旧环境变量安装通过 `crypto-key adopt` 一次性接管。

MCP 侧的授权在 `mcp/McpAccessService`：Agent 只能访问白名单内的连接，访问档位**按连接**授予（只读 / 数据读写 / 完全），生产连接需要额外授权。只读工具有 `listConnections`、`listNamespaces`、`searchObjects`、`describeObject`、`getObjectDdl`、`browseTable`、`query`、`explain`；写工具只有 `db_execute` 一个，它复用 `SqlService.execute` 那条路径，生产确认、未限定范围写确认与审计一个都不少。

应用自己调模型的那一侧在 `service/ai`（配置与连接共享策略）与 `service/ai/llm`（Provider 抽象，Claude 官方 SDK + OpenAI 兼容协议两个实现）。AI 能拿到哪条连接的什么内容由 `AiSettingsService.requireEnabled()` 与 `requireSharedConnection()` 两道闸门决定，默认档位是「不参与 AI」；方案与推进节奏见 `docs/ai-assistant.md`。前端对应的纯逻辑在 `src/aiSettings.ts`、`src/aiSuggestion.ts`、`src/aiChat.ts`、`src/sqlSuggestion.ts`、`src/aiResultPreview.ts`，界面是 `AiSettingsPanel`（管理抽屉，含连接共享策略、业务词典与 token 用量）、`AiAssistantPanel`（单轮回答抽屉：结果解读、数据字典、同步脚本审阅）与 `AiSqlChatPanel`（多轮 Agent 抽屉：生成、报错诊断、结果复盘、执行计划解读）。

自然语言 SQL 那条路是个服务端闭环的 Agent（`AiSqlAgentService`），有几处约定不要绕开：

- **会话在服务端**（`AiConversationStore`），浏览器只提交当前这句话加一个会话 ID。工具结果和「是否已经检查过结构」的标记都不经浏览器 —— 否则客户端可以伪造「模型已经看过表结构」的历史，把 grounding 变成摆设。会话按字符数而不是条数淘汰：里面存的是工具结果原文。
- **候选 SQL 的编译校验走 `DatabaseDialect.compileQuery`**，不要在 `service/ai` 里直接 `prepareStatement`。默认实现是 prepare + 读结果列元数据，PostgreSQL/Oracle/SQL Server 上确实不执行；但 Connector/J 的客户端预编译会把查询真跑一遍且不继承 `queryTimeout`，所以 MySQL 系覆盖成 `EXPLAIN`。新增方言时先确认驱动行为。校验入口只收 SELECT/WITH（`SqlStatementClassifier.isSelectQuery`）。
- **需求有歧义时反问走 `ask_user` 工具**（`AiClarify`），不要让模型在正文里提问 —— 正文里的问句用户点不了，也接不上下一轮。它由 `AiSqlAgentService` 拦下（不进只读的 `AiSchemaTools`），但**必须照常回一条工具结果**：少一条，下一轮的历史里就留下一个没有结果的工具调用，两家协议都会报错。审计里 `outcome=clarified`；评测集里有反问用例，打分反过来 —— 问了才算通过，否则打分只奖励「猜出一条 SQL」，模型永远不会选择问。
- **执行计划的解读也走 Agent**（`AiChatRequest.plan`）。只发计划文本的话，模型看不到这张表上真实存在哪些索引，只能泛泛地说「加个索引」。这一轮是唯一允许模型产出非 SELECT 的：一条 `CREATE INDEX`（`AiPlanAdvice.isIndexScript` 之外的 DDL 与写操作一律打回），且不做编译校验 —— `compileQuery` 只接 SELECT，prepare 一条 DDL 在某些驱动上等于执行它。脚本仍然只写进编辑器。
- **执行报错的诊断走 Agent，不要再开单次问答的路。** 最常见的报错是「字段/表不存在」，而报错里提到的名字本来就是错的 —— 只看那条 SQL 提到的表根本查不出正确名称，必须能搜结构、查词典。失败现场经 `AiChatRequest.failure` 传入，由 `AiChatPrompt` 加上不可信标注后才进模型：错误原文来自目标库，拼进用户消息就和用户的指令没区别了。
- **词典的另一半从「搜空的检索词」来**（`AiGlossaryGaps` + `ai_glossary_gap`）：`search_schema` 一个对象都没搜到的词，就是用户的说法和这个库的命名对不上的现场。`AiSqlAgentService` 在 `finally` 里汇总落库（失败和取消也记），整段吞异常 —— 这是旁路信息，不该让一次已经跑完的回答失败。筛选规则与下面那条同源：超过十二个汉字的是描述不是名字。这是唯一一处会落库的用户提问派生物，边界写在 `docs/ai-assistant.md` 的 7.8。
- **业务词典的候选从表注释推**（`AiGlossarySuggestions`），但不自动落库：注释里的词本来就能被 `search_schema` 搜到，自动生成的词条不算新信息 —— 词典不可替代的是用户嘴里的「会员」「买家」，那只能人补。目标是把「从零填写」变成「审阅和补别名」。
- **结果回流只发形状不发数据**（`frontend/src/aiResultShape.ts`）：行数、耗时、每列的空值数与不同取值数，全是计数。这样它才落在「只发结构」档；要发真实数据行是另一个入口（结果解读），那边要求连接开到样本档。
- **执行历史进入模型上下文前必须先过 `AiSqlShape.mask`**（`AiQueryHistoryService`）。历史里带着真实业务值 —— 手机号、金额、注释里的人名和工单号；抹掉字面量之后剩下的是查询骨架，这条工具才落得进「只发结构」这一档。绕开它就等于把这一档的承诺作废了。
- **每次 Agent 请求都要落一条 `AI_AGENT_CHAT` 审计，取消也不例外** —— 被取消时结构往往已经发给外部模型了。
- `AiAgentCoordinator` 管有界线程池、按用户并发上限与取消；`AiAgentMetrics` 出 Micrometer 指标，其中 `cache_read` 长期为 0 说明 prompt cache 的前缀被写脏了。
- **预算闸门挂在 `AiSettingsService.requireEnabled(actor)` 上**，每条 AI 路径的第一行都走它；用量记在 `ai_usage_daily`（不从审计 detail 解析），单轮问答和 Agent 两条路记的是同一样东西，取消与失败也照记。额度只在请求开始前检查，所以最后一次请求可能小幅超出 —— 这是有意的，细节见 `docs/ai-assistant.md` 的 M13。
- **系统提示、工具定义和历史消息是 prompt cache 的前缀，只许在尾部追加**：`AiPromptCachePrefixTest` 用剧本模型在 CI 里守着这条（系统提示逐字相等、消息只增不改、提示里不许出现当前年份）。往里塞时间戳这类每次都变的内容会让缓存命中率直接归零。审计 detail 里的 `seq=` 记着这次请求的工具调用序列 —— 汇总计数说不出它是搜了两次还是读了三次表。
- **改 Agent 的 prompt、工具或循环之前先看评测集**（`service/ai/eval/`）：`AiSqlAgentLoopTest` 用剧本化的假模型在 CI 里守住整条循环，`AiSqlAgentEvalTest` 用真模型跑固定用例集出报告（要 `AI_EVAL_API_KEY`，默认跳过）。用例只校验「命中了哪些表」，改已有用例等于让历史分数不可比。

### 结果分页与排序

SQL 结果是服务端分页的（`SqlService.executePage` + 方言的 `pageQuery`），**排序与列筛选都下推到服务端**：`SqlResultPushdown` 把原查询包成 `SELECT * FROM (原 SQL) mdd_view WHERE … ORDER BY …` 再交给分页，改排序或改筛选时 offset 归零。不要退回「只作用于当前这一页」—— 用户在第 1 页点一下列头或漏斗，看到的是这 500 行内部的结论，很容易当成整个结果集的结论，这种错觉比没有功能更糟。表浏览那条路（`DataEditService`）本来就是服务端筛选 + 排序 + keyset/offset 分页，两条路的语义要一致。

三条约定不要动：**筛选值一律绑定参数**（列名是标识符靠方言引用转义，值来自输入框，拼进去就是注入点，有测试盯着）；**列先转文本再比**（`DatabaseDialect.castToText`，各家写法不同，数字列、时间列、布尔列因此能共用一套「包含/等于/为空」语义）；**NULL 与空串同样落进「为空」**（前端原本就是这个行为，下推不该悄悄改掉）。

两个例外仍然走本地筛选与排序，界面上必须标出来：结果里有同名列时（按列标签排序或筛选在数据库那边是歧义的），以及结果压根不支持翻页时。

`SqlExecutionMetrics` 给查询、分页、脚本、执行计划四条路出 Micrometer 指标，**超时单独成一类**（顺着 cause 链找 `SQLTimeoutException`）：超时说明库慢了，一般报错通常是语句写错了，混在一个数里就指不出该看哪边。标签只有 `kind` 和 `outcome`，都是固定集合 —— 别往里加连接名或 SQL。

### 数据库迁移

元数据库 schema 由 Flyway 管理，迁移是 **Java 类**，位于 `backend/src/main/java/db/migration/`（`V1__BaselineSchema.java` 等），不是 SQL 文件。`src/main/resources/*.sql` 是被迁移引用的脚本，直接改它们不会触发迁移。

### 前端结构

`src/App.tsx` 是唯一的状态容器：连接选择、SQL 标签页、表格工作区、生产确认弹窗都在这里。

管理类面板（连接、备份与恢复、结构对比、MCP、活动会话、审计）**共用一个带左侧导航的抽屉**，分区定义在 `src/managementSections.ts`。新增管理面板 = 往那份清单里加一项 + 在抽屉里加一个分支，不要再开一个新的 `<Drawer>`。所有重型面板（`SqlWorkspace`、`ObjectDetailWorkspace`、`BackupPanel`、`McpSettingsPanel` 等）通过 `React.lazy` 懒加载 —— 这是构建体积预算能通过的前提。

样式走 `src/styles.css` 顶部的令牌：间距 `--space-*`、圆角 `--radius-*`、字号 `--text-*`。**`App.tsx` 里 `ConfigProvider` 的 `borderRadius` / `fontSize` 必须和 `--radius-md` / `--text-md` 保持同值**——两套尺度一旦分叉，就会退回到用 `!important` 互相覆盖。字号下限是 11px（`--text-xs`），中文在更小的字号下笔画会糊。抽屉宽度只有 `DRAWER_WIDTH` 的三档，不要再现拍新值。空状态与加载态一律用 `components/PanelState.tsx` 的 `PanelEmpty` / `PanelLoading`，不要各自再写一套骨架。

有状态但与界面无关的子系统抽成 `src/hooks/` 下的自定义 hook（`useBackgroundTasks`、`useSqlHistory`、`useVisiblePolling`、`useLayoutPreferences`、`useProductionConfirmation`），hook 内部依赖的纯逻辑再落到 `src/` 下的同名模块（`backgroundTaskStream.ts`、`sqlHistoryQuery.ts`、`productionConfirmation.ts`）。新增「一组状态 + 一条取数路径」时优先走这条路，而不是继续往 `App.tsx` 里加 `useState`。

`useProductionConfirmation` 是把已有状态搬出去的样板：状态与 resolver 在 hook 里，校验在纯逻辑模块里（有测试），错误文案怎么显示由调用方决定。**`await requestProductionConfirmation(...)` 拿不到值就直接 return** 这条约定是这道闸门好用的原因，搬动时不能改。`App.tsx` 里还剩两组同样该搬的：SQL 标签页、抽屉与弹层编排 —— 两组都比这一组大，应各自单独改一次并在中间跑一遍 `node scripts/ui-smoke.mjs --serve`。

编辑器里的「未知表名」波浪线（`src/sqlUnknownObjects.ts` + `SqlEditor` 的装饰层）用的就是补全那份对象清单，不打接口、不需要模型。**它只在清单确实完整时才提示** —— 资源树是分页的、还可能带着搜索关键字，拿一份筛过或只有第一页的清单去判断「不存在」会把正确的表名也划上线，那比没有提示更糟。CTE、表值函数、临时表、限定到别的 Schema 的引用一律跳过，比对折大小写。改这块时的默认方向永远是「宁可不提示」。

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
