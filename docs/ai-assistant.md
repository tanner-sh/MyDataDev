# AI 助手接入方案

本文是 MyDataDev 内置 AI 能力的推进依据。目标是让产品自己会调用大模型，而不只是把数据库能力暴露给外部 AI agent（后者已由 `docs/mcp-server.md` 的 MCP Server 完成）。

## 1. 目标与非目标

**目标**

- 在 SQL 工作台内提供自然语言生成 SQL、连续对话修正、执行报错诊断、执行计划解读等能力。
- 生成 SQL 时由模型按需搜索当前命名空间的表/字段注释，并继续读取候选对象的字段、索引和外键，避免依赖前端碰运气选表。
- 结构元数据的出网范围由用户显式控制，默认不发送任何业务行数据。
- AI 生成的一切 SQL 走与界面完全相同的执行路径，生产确认、未限定范围写确认、审计一个都不能少。
- Provider 可插拔：默认 Claude，允许接入兼容协议的自建服务或本地模型，离线环境可以完全不启用。

**非目标（本期不做）**

- 不做自动执行：AI 永远只把 SQL 写进编辑器，由人按下执行。
- 不做对话历史的长期存储与检索：多轮会话存在服务端进程内的短期缓存里，按用户隔离、按 TTL 过期，不落库。
- 不给内置 Agent 查询业务行或执行 SQL 的工具；Agent 只能读取受控元数据，最终 SQL 仍由用户确认。
- 不替换 `sqlCompletion.ts` 的确定性补全。

## 2. 现状盘点

| 已有的东西 | 位置 | 本方案怎么用 |
| --- | --- | --- |
| MCP Server（9 个工具，含写操作 `db_execute`） | `mcp/McpDatabaseTools.java` | 不改；它解决的是「别人的 AI 用我们」，与本方案正交 |
| Spring AI 1.1.8 BOM | `backend/pom.xml:28` | 目前只用于 `spring-ai-starter-mcp-server-webmvc`，本方案不扩用它做模型调用（见 6.2） |
| 元数据缓存 | `service/MetadataCacheService` | 构造结构上下文的唯一取数入口 |
| 方言 | `core/DatabaseDialect` + `DialectRegistry` | 提供 prompt 中的方言片段（库类型、分页写法、标识符引用） |
| 执行闸门 | `service/ExecutionGuard`、`service/SqlService` | AI 产出的 SQL 复用这条路径，不新开执行通道 |
| 审计 | `repo/AuditRepository` + `frontend/src/auditLog.ts` | 新增 AI 动作码 |
| 加密 | `service/CryptoService`（系统托管主密钥） | 加密存储 Provider API Key |
| 执行计划确定性信号 | `frontend/src/explainInsights.ts` | LLM 只补它做不了的解释层，不重做规则判断 |
| 管理抽屉分区 | `frontend/src/managementSections.ts` | 新增 `ai` 分区，不新开 `<Drawer>` |

> 注：`CLAUDE.md` 中「MCP 工具全部只读」的描述已过期（`db_execute` 是写工具），推进本方案时顺手订正。

## 3. 架构

### 3.1 分层

沿用既有约定，不跨层：

```
api/AiController            REST 入口，SSE 流式输出
  └── service/ai/AiAssistantService      编排：取上下文 → 组 prompt → 调 provider → 校验产出
        ├── service/ai/SchemaContextBuilder   结构摘要的选取与裁剪（纯逻辑，可单测）
        ├── service/ai/AiPromptBuilder        prompt 组装（纯逻辑，可单测）
        ├── service/ai/SqlSuggestionValidator 产出 SQL 的合法性与语句数校验（纯逻辑，可单测）
        └── service/ai/provider/LlmClient     provider 接口
              ├── AnthropicLlmClient          默认实现
              └── OpenAiCompatibleLlmClient    自建 / 本地模型
  └── service/ai/AiSqlAgentService       多轮编排：模型 → 元数据工具 → 模型 → 编译校验 → SQL
        ├── service/ai/AiSchemaTools          搜索注释、读取对象详情/DDL/外键邻接、检索历史写法；连接与 Schema 由服务端绑定
        ├── service/ai/AiQueryHistoryService  从跑过的 SQL 里找同类写法（AiSqlShape 抹掉字面量后才出网）
        ├── service/ai/AiSqlValidationService 候选 SQL 的目标库编译校验（怎么校验交给方言）
        ├── service/ai/AiConversationStore    服务端短期可信会话；工具结果不经浏览器
        ├── service/ai/AiAgentCoordinator     有界线程池、按用户并发上限、可中断的请求句柄
        ├── service/ai/AiAgentMetrics         轮次、工具、校验与 token 的 Micrometer 指标
        └── service/ai/AiGlossaryService      连接级业务词典（管理员维护，喂给 search_schema）
  └── repo/AiSettingsRepository        JdbcTemplate 持久化（Provider 配置、连接级开关）
```

DTO 进 `dto/ApiDtos.java`，模型用 `record`。

**硬约束**：`AiAssistantService` 里不允许出现 `if (dbType == MYSQL)` 这类分支，方言差异一律问 `DatabaseDialect` 要。

### 3.2 前端

- `components/AiSqlChatPanel.tsx` 提供自然语言生成、多轮修正与执行报错诊断；`AiAssistantPanel.tsx` 承载计划解读、结果解读和文档这几个单次问答，两个面板都按需懒加载。
- 设置面板 `components/AiSettingsPanel.tsx`，走管理抽屉的新分区 `ai`（`requiresAdmin: true`）。
- 纯逻辑抽 `src/aiSuggestion.ts`（流式片段拼接、SQL 代码块提取、插入编辑器的位置判断）+ 同名 `.test.ts`。
- 模型调用全在后端，前端只发 REST，首屏体积预算不受影响。

### 3.3 流式

`GET /api/ai/completions/stream`（SSE）。参照 `service/BackgroundTaskStream` 的结论：反向代理必须关闭响应缓冲，部署文档 `docs/web-deploy.md` 同步补一句。前端在 SSE 建不起来时退回一次性 POST。

## 4. 数据模型

新增 Flyway Java 迁移 `V14__AiAssistant.java` + `src/main/resources/ai-schema.sql`（与 `V2__McpWebConfiguration` 同样的写法）：

```sql
CREATE TABLE IF NOT EXISTS ai_settings (
    id             BIGINT PRIMARY KEY,
    enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    provider       VARCHAR(32)  NOT NULL,   -- ANTHROPIC / OPENAI_COMPATIBLE
    base_url       VARCHAR(512),            -- 自建 / 本地模型时使用
    model          VARCHAR(128) NOT NULL,
    api_key_cipher VARCHAR(2048),           -- CryptoService 加密
    effort         VARCHAR(16),
    updated_at     TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_connection_policy (
    connection_id   BIGINT PRIMARY KEY,
    schema_sharing  VARCHAR(16) NOT NULL DEFAULT 'NONE',  -- NONE / STRUCTURE / STRUCTURE_AND_SAMPLE
    sample_row_limit INT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_ai_policy_connection FOREIGN KEY (connection_id)
        REFERENCES db_connection(id) ON DELETE CASCADE
);
```

未配置策略的连接按 `NONE` 处理 —— 新连接默认不参与 AI，必须显式开启。生产连接的 `STRUCTURE_AND_SAMPLE` 在界面上禁用。

两条已定的约束：

- **`ai_settings` 全局单行**（`id` 恒为 1），不带 `user_id`：API Key 由管理员统一维护，多用户模式下所有人共用同一份 Provider 配置。
- **桌面模式与 Web 模式一致处理**，各自存自己的元数据库。两边数据本就不共享，桌面版需要单独配一次 Provider —— 与连接、Agent、备份的现状相同，不做特例。

## 5. 接口草图

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/ai/settings` | 读取 Provider 配置（Key 掩码为 `******`，语义沿用 SSH 隧道那套） |
| `PUT` | `/api/ai/settings` | 保存配置；Key 为 `******` 时沿用旧值 |
| `POST` | `/api/ai/settings/test` | 连通性测试，只发一条最小请求 |
| `GET`/`PUT` | `/api/ai/connections/{id}/policy` | 连接级共享策略 |
| `POST` | `/api/ai/sql/generate` | 自然语言 → SQL |
| `GET`/`PUT` | `/api/ai/connections/{id}/glossary` | 连接级业务词典（管理员） |
| `GET` | `/api/ai/connections/{id}/glossary/suggestions` | 从表注释推候选词条；只读，不落库 |
| `GET`/`DELETE` | `/api/ai/connections/{id}/glossary/gaps` | AI 搜过却一无所获的业务词；DELETE 是「忽略」 |
| `GET` | `/api/ai/usage` | token 用量与预算（管理员）；`days` 默认 14，上限 90 |
| `POST` | `/api/ai/sql/chat/stream` | 自然语言 → 元数据工具 → 编译校验 → SQL；只提交当前这句话，历史在服务端会话里。报错诊断、结果复盘、执行计划解读都走这一条（`failure` / `outcome` / `plan`） |
| `GET`/`DELETE` | `/api/ai/sql/conversations/{id}` | 刷新后恢复可见消息；删除等于新建对话 |
| `POST` | `/api/ai/sql/chat/{requestId}/cancel` | 取消正在跑的 Agent 请求 |
| `POST` | `/api/ai/sql/{action}/stream` | 上述三项的 SSE 流式变体 |
| `GET` | `/api/ai/status` | 可用性快照（所有登录用户可读，不含配置细节） |

两处与最初草图的偏差：

- **流式入口是 POST，不是 GET。** 请求要带上整条 SQL 与报错原文，塞进查询串既有长度上限，也会被访问日志和反向代理原样记下来。代价是前端不能用 `EventSource`，改用 `fetch` 读 `ReadableStream`。
- **多了 `GET /api/ai/status`。** 设置面板是管理员的，但「这条连接上要不要显示 AI 按钮」是每个用户都要知道的事，所以单开一个只说「功能开没开、哪些连接被授权」的只读接口，不含任何配置细节。

错误一律用 `ApiProblemException(status, code, message, details)` 抛出。新增错误码需要在 `frontend/src/api.ts` 的 `ApiError` 侧同步识别：

- `AI_DISABLED` — 未启用或未配置 Provider
- `AI_CONNECTION_NOT_SHARED` — 该连接的共享策略为 `NONE`
- `AI_PROVIDER_ERROR` — 上游返回错误（透传状态码与简要原因）
- `AI_AGENT_LIMIT` — 本轮已达到元数据工具/模型轮次上限，仍无法确定 SQL
- `AI_BUDGET_EXCEEDED` — 今日 token 额度已用完（全站或个人），HTTP 429

## 6. 模型与 Provider

### 6.1 选型

- 默认模型 **`claude-opus-5`**（1M 上下文，$5 / $25 每 MTok）。
- thinking 用 `{type: "adaptive"}`；`output_config.effort` 分场景：SQL Agent（生成与报错诊断）用 `high`，注释生成之类的批量轻任务用 `low`。
- 长输出（文档生成、结果解读）一律流式，避免 HTTP 超时。
- 换更便宜的模型是产品取舍，不作为默认。

### 6.2 SDK

模型调用用官方 **`com.anthropic:anthropic-java`**（`AnthropicOkHttpClient`），不扩用 Spring AI 的 model starter。理由：

1. pom 里的 Spring AI BOM 目前只为 MCP server 服务，两件事不必绑在一起；
2. adaptive thinking、`effort`、prompt caching 这些参数在官方 SDK 上是第一手支持；
3. 多 provider 由我们自己的 `LlmClient` 接口收敛，比套第三方抽象更可控。

### 6.3 成本

诊断、计划解读等固定上下文流程仍把结构摘要作为稳定前缀，并使用 prompt cache。SQL Agent 不再预先塞入整库结构，而是只把工具定义与按需命中的对象详情放入上下文；Schema 搜索目录按 `MetadataCacheService` 的目录版本缓存，元数据失效时同步换代。

Agent 的多轮循环有两个缓存断点：一个打在系统提示上（缓存前缀的顺序是 tools → system → messages，所以它覆盖工具定义），另一个是每轮打在最后一条消息上的滚动断点。后者才是关键 —— 每一轮都要把此前所有工具结果原样重发，不缓存的话输入 token 随轮次平方增长。命不命中看 `dbadmin.ai.agent.tokens{type="cache_read"}`：这个数长期为 0 就说明前缀里混进了每次都变的内容。

## 7. 安全与隐私边界

1. **默认不出网**：连接级策略默认 `NONE`，管理员逐条开启。
2. **默认只出结构**：`STRUCTURE` 档只发表名、列名、类型、可空、主外键、索引、注释，不发任何行数据。
3. **采样受限**：`STRUCTURE_AND_SAMPLE` 才发样本行，条数受 `sample_row_limit` 约束，生产连接禁用该档。
4. **不给 AI 执行权**：`/api/ai/*` 下没有任何执行入口，产出只能回到编辑器。
5. **Key 与数据库密码同级**：`CryptoService` 加密后入库，桌面模式自然落进操作系统安全存储。
6. **权限**：AI 设置与连接策略均为 `requiresAdmin`，API Key 只由管理员维护、普通用户看不到（读接口一律掩码）；调用侧要求连接的 `QUERY` 权限。
7. **审计**：每次调用写审计，走 `AuditRepository.onConnection(...)`，记录动作、连接、共享档位与模型，不记录用户输入的自然语言原文（避免二次泄露），只记长度与是否带样本。M12 起额外记一条工具序列（`seq=search_schema,describe_objects,…`），它只有工具名，不含任何参数。
8. **只有搜空的业务词会落库**：M12 的「词典待补清单」把 `search_schema` 一无所获的检索词写进 `ai_glossary_gap`。这触到了决策 3 的边界，所以边界画在这里 —— 落库的是模型提炼出的单个业务词（超过十二个汉字的整句、纯数字和纯符号都会被 `AiGlossaryGaps` 挡掉），不是用户那句话；而且只有「什么都没搜到」的词才留下，搜到了的一律不记。管理员可以在词典面板上逐个「忽略」，词条补上之后自动销账。

新增动作码（需同步 `frontend/src/auditLog.ts`，否则 `AuditActionLabelCoverageTest` 会失败），建议新增审计分类 `ai`：

| 动作码 | 中文名 |
| --- | --- |
| `AI_GENERATE_SQL` | AI 生成 SQL |
| `AI_DIAGNOSE_ERROR` | AI 诊断执行报错（M9 起不再写入，历史记录仍在） |
| `AI_EXPLAIN_INSIGHT` | AI 解读执行计划 |
| `AI_SETTINGS_UPDATE` | 修改 AI 设置 |
| `AI_POLICY_UPDATE` | 修改连接 AI 共享策略 |

## 8. 分期推进

### M1 — 骨架与配置（先打地基，不出功能） · 已完成

- `V14__AiAssistant.java` + `ai-schema.sql`
- `AiSettingsRepository`、`AiSettingsService`、`LlmClient` 接口 + `AnthropicLlmClient` + `OpenAiCompatibleLlmClient`
  兼容协议实现放在 M1 一起做：provider 抽象只有在第二个实现落地后才谈得上验证，等到 M5 再补必然要返工 `LlmClient` 的接口形状。
- `/api/ai/settings`、`/settings/test`、连接策略接口
- 管理抽屉新增 `ai` 分区与 `AiSettingsPanel`
- 审计动作码 `AI_SETTINGS_UPDATE` / `AI_SETTINGS_TEST` / `AI_POLICY_UPDATE`

落地文件（与本节计划一致，两处偏差记在下面）：

| 后端 | 前端 |
| --- | --- |
| `db/migration/V14__AiAssistant.java`、`resources/ai-schema.sql` | `src/aiSettings.ts` + `aiSettings.test.ts` |
| `service/ai/`：`AiProvider`、`AiEffort`、`AiSchemaSharing`、`AiSettings`、`AiConnectionPolicy`、`AiSettingsProfile`、`AiSharingRules`、`AiSettingsService` | `src/components/AiSettingsPanel.tsx` |
| `service/ai/llm/`：`LlmClient`、`LlmRequest`、`LlmResponse`、`LlmException`、`LlmClientFactory`、`AnthropicLlmClient`、`OpenAiCompatibleLlmClient` | `src/types.ts`、`src/managementSections.ts`、`src/auditLog.ts`、`App.tsx` |
| `repo/AiSettingsRepository`、`dto/AiDtos`、`api/AiSettingsController` | |
| `config/SecurityConfig`、`service/EncryptedSecretInventory`（新密文列纳入主密钥校验） | |

与计划的两处偏差：

1. 多了一个审计码 `AI_SETTINGS_TEST`。连通性测试会真的向外发一次请求，属于「有数据出网」的动作，不记等于留了个没痕迹的出网口。
2. `AiSharingRules.effective` 是新增的读时降级：一条连接可能先按测试库开了样本档、之后被改成生产环境，库里那行还留着旧档位。读的时候降级，而不是等发请求时才发现。

### M2 — 执行报错诊断（最小闭环） · 已完成

选它先落地，因为上下文最小、价值最直观、没有「生成的 SQL 可能被执行」的风险。

- `SqlTableReferences`（从 SQL 里认出引用到的表）、`SchemaContext` / `SchemaContextFormat`（结构上下文与渲染）、`SchemaContextBuilder`（取数与取样本）
- `AiPromptBuilder`：系统提示只由结构与方言决定，本次 SQL 与报错放在用户提示里 —— 前缀稳定，缓存才打得中
- `AiAssistantService` + `AiAssistantController`：两道闸门 → 取结构 → 组提示 → 调模型 → 写审计
- 前端 `aiSuggestion.ts`（SSE 分片解析、事件折叠、SQL 代码块提取）、`AiAssistantPanel` 抽屉、SQL 工作台两处报错区的「AI 诊断」按钮
- `useAiStatus` + `/api/ai/status`：功能关着或连接未授权时，按钮整个不出现
- 反代缓冲说明已写进 `docs/web-deploy.md`

两处顺带的改动：

1. `ReadOnlyQueryScope` 从包内可见改为 public。取样本行要走同一套只读保护（只读事务 + 回滚 + 恢复），与其在 `service/ai` 里再抄一份，不如共用这个已经验证过的实现。
2. 新增 `AiAuditActionsTest`。AI 这一侧几个入口共用一条写审计的路径，动作码是变量，`AuditActionLabelCoverageTest` 的正则扫不到；动作码集中在 `AiAssistantService.AUDIT_ACTIONS`，由新测试盯着中文名。

### M3 — 自然语言 → SQL · 已完成

- `TableSelector`（后端，纯逻辑）：从整库表清单里挑最多 8 张相关表。打分只用三件确定的事 —— 表名是否被问题原文提到、词块重合、同分时短名优先。选不出来就一张不给，让模型明说「看不到相关的表」，好过拿八张无关表编一条 SQL。
- `SchemaContextBuilder.forQuestion`：候选表来自元数据目录（走缓存），再交给 `TableSelector`。
- `sqlSuggestion.ts`（前端，纯逻辑）：语句拆分、类型判定（查询 / 写 / DDL / 认不出）、插入前的提示。
- 工具栏「AI 生成」按钮 + 提问弹窗；产出开在**新标签页**，不覆盖用户手里正在写的 SQL。

与计划的两处偏差：

1. **选表策略只做了关键词与词块匹配**，没做「按最近使用」和「按外键邻接扩展」。前者要读 SQL 历史再做一次归因，后者要对每张候选表查一次外键 —— 两者都会把一次生成变成十几次元数据查询，而收益是「可能多认出一张相关表」。留到有真实反馈说选表不准时再补。
2. **`SqlSuggestionValidator` 落在前端而不是后端**，名字也改成了 `sqlSuggestion.ts`。判定该发生在 SQL 要进编辑器的地方：流式回答是一段段到的，后端见到的是自己吐出去的片段，用户点「插入」时面对的是拼完的那一整段。放后端等于把同一条规则写两份。

### M4 — 执行计划解读 · 已完成（M14 起并入 Agent）

- `explainInsights.ts` 新增 `explainPlanText` 与 `explainFindingsText`：把计划渲染成制表符表格（JSON 会把列名在每行重复一遍，同样的信息多花一倍 token），把规则结论渲染成一行一条。
- `AiPromptBuilder.explain` 把规则结论单列一段，并明说「不必重复判断」—— 模型要做的是在这些事实之上解释原因、给出改法。
- `ExplainInsightsPanel` 增加可选的「AI 解读」按钮：规则结论在前、AI 解读在后，两者不混在一起显示。
- 计划没有明显问题时，提示词要求模型直接说「没有明显问题」，不为了凑建议而建议。

### M5 — 增强能力 · 已完成

- **结果集解读与图表推荐**：前端 `aiResultPreview.ts` 截前 20 行、把 `resultChart.ts` 算出的图表候选一并发过去，模型只能在真实候选里挑。这是**唯一会把真实数据发出去的入口**，因此后端额外要求连接开了样本档（`AI_SAMPLE_NOT_ALLOWED`），只授权结构的连接连按钮都不出现。
- **Schema 数据字典**：SQL 工作台「更多」菜单里选表生成，一次最多 20 张，走 `BackgroundTaskControl` 的并发闸门 —— 同一条连接上同时只允许一个文档任务。
- **结构同步脚本的风险说明**：结构对比面板里按目标连接授权判断，只发脚本本身、不取结构上下文，提示词明确要求模型不改写脚本。

三处与计划的偏差：

1. **数据字典没有落库的任务队列**，而是复用 M2 的 SSE 流：`BackgroundTaskControl` 提供的是并发闸门（tryAcquire / release），不是任务存储。攒一份可查询的任务记录要新建表、进度上报与轮询接口，而文档任务的产出是一段文本、一次会话内就看完了 —— 等到有人要求「昨天那份文档还能不能翻出来」再补。
2. **写的是数据字典而不是 `COMMENT` DDL**。注释语句的写法各方言不同（MySQL 改列、PostgreSQL 用 `COMMENT ON`），要落地得给每个方言加一个 `commentSql`，波及所有方言实现；而文档本身已经能解决「不熟悉这个库的人看不懂表」这个真问题。要把注释写回库里，用 M3 的「AI 生成」按方言要一条语句即可。
3. **`AI_INTERPRET_RESULT` 在审计里标了 `dangerous`**。它不破坏数据，但它是唯一一个把真实业务数据送出本机的动作 —— 审计的读者最该一眼看到的就是这个。

### M6 — 元数据工具调用与多轮 SQL 对话 · 已完成

- 新增 `AiSqlAgentService`，一轮请求最多进行 8 次模型推理、16 次元数据工具调用；达到上限后明确失败，不无限循环。
- 新增六个只读工具：`search_schema` 搜索表名/表注释/字段名/字段注释与业务词典，`describe_objects` 读取字段、主键、索引与外键，`find_related_objects` 沿真实外键发现邻接表，`search_query_history` 检索这个库跑过的同类查询，`get_object_ddl` 在必要时补充对象定义，`validate_sql` 在目标库上编译校验候选 SQL。
- 工具的连接和 Schema 由服务端请求上下文绑定，模型参数里没有 `connectionId`；对象详情也必须先命中当前 Schema 的目录，不能用限定名跳到其他 Schema。
- 工具不提供查行、写入或执行 SQL 的入口；Agent 只生成一条只读查询，并写入新标签页等待用户确认。
- 会话搬到了服务端（`AiConversationStore`）：浏览器只提交当前这句话和一个会话 ID，工具结果和 `requireInspection` 标记都留在后端，客户端无法伪造“模型已经检查过结构”的历史。会话按登录用户隔离、按 TTL 过期，刷新可恢复可见消息。
- 会话缓存按字符数而不是条数淘汰：里面存的是工具结果原文（结构 JSON、DDL），一条会话到几百 KB 很正常，按条数限制等于让堆占用不受控。
- Anthropic 与 OpenAI 兼容协议都实现原生 tool use/function calling，兼容自建网关和本地模型。

### M7 — 校验、会话与运行治理 · 已完成

- **候选 SQL 在目标库上编译校验，并把「怎么校验」交给方言。** `DatabaseDialect.compileQuery` 的默认实现只 prepare 再读结果列元数据 —— PostgreSQL、Oracle、SQL Server 的驱动会把它翻译成一次服务端 Describe，确实不执行。但 Connector/J 在默认的客户端预编译下，`getMetaData()` 会另建一个语句把查询真跑一遍，而且不继承外层的 `queryTimeout`：一条笛卡尔积会在目标库上不受限地跑完。所以 MySQL 系覆盖成 `EXPLAIN`（不带 ANALYZE，只解析和生成计划）。新增方言时要先确认驱动行为，别默认继承。
- **校验入口只收 SELECT（可带 WITH 前缀）。** SHOW、DESCRIBE、EXPLAIN 也是只读的，但它们能不能被「只解析不取数」地校验各方言差别很大，`EXPLAIN ANALYZE SELECT` 在 PostgreSQL 上更是真跑一遍。`SqlStatementClassifier.isSelectQuery` 把这道判断收在一处，模型的系统提示和重试提示同步收窄。
- **校验失败自动回到模型修正**，失败原文作为下一轮的用户消息；修正次数受同一个轮次上限约束。
- **`AiAgentCoordinator`**：有界线程池 + 队列 + 按用户并发上限，取消靠 `Thread.interrupt()`。局限要知道：轮次之间的 `checkCancelled()` 是有效的，但如果 provider 的 HTTP 客户端不响应中断（OkHttp 就不响应），正在进行的那次模型调用仍会跑完才停。
- **审计对取消也写一条。** 取消发生时，工具往往已经把库结构发给外部模型了 —— 只记成功和失败，等于这次外发在审计里查不到。审计动作码是独立的 `AI_AGENT_CHAT`，和单轮 `AI_GENERATE_SQL` 分开筛。
- **最终回答流式输出。** Agent 每轮开始时发 `answer-reset`，前端据此丢掉上一轮的开场白或没通过校验的候选 SQL，正文随 `delta` 增量显示。OpenAI 兼容协议下 `LlmClient.turn` 退化成非流式（写完一次性回调），行为与之前一致。
- **连接级业务词典**（`ai_business_glossary`，V15 迁移）：管理员维护「业务词 → 别名 → 真实对象名」，`search_schema` 命中词典的对象直接加权。词典是 AI 唯一能看到的、库里没有的知识来源。

### M8 — 把执行历史接进 grounding · 已完成

表注释和外键说明的是**可以**怎么关联，执行历史说明的是**实际**怎么关联。哪张是主表、统计成交算不算未支付的单、销售额取商品标价还是取明细金额 —— 这类口径在表结构里一个字都没有，只有这个库跑过的语句里有。所以新增 `search_query_history` 工具，让模型在确定候选表之后、动手写 SQL 之前，先看一眼既有写法。

**隐私上它落在「只发结构」这一档，靠的是三道处理**：

1. 只取执行成功的只读查询 —— 失败的语句本身就是错的写法，写操作也不是「怎么查」的参考；
2. `AiSqlShape.mask` 抹掉全部字符串、数字和注释。`WHERE mobile = '13800138000'` 是一条个人信息，`WHERE amount > 88888` 是一笔真实金额，而历史的价值只在查询骨架上，抹掉之后一点没少。标识符引号（双引号、反引号、方括号）里的内容原样保留 —— 那是表名和列名，正是要留下的；
3. 按形状去重并统计跑过多少次。同一条查询跑一百次只给模型看一次，而跑得多的写法更可能是这个库的惯例，排序时权重更高。

排序上命中表的权重远高于关键词：找相似写法时，「用到了同样这几张表」几乎总比「文本里出现过同一个词」更说明问题，后者很容易只是碰巧撞上一个列名。

给模型的措辞刻意说清楚：历史只作写法参考，**表名和字段名一律以 `describe_objects` 的结果为准** —— 历史里的表可能已经改过或删掉了。参考到的历史写法会作为 `QUERY_HISTORY` 出现在结构依据面板上，用户能看见 AI 借鉴了哪条既有查询。

评测集同步加了一维 `expectedTokens`：表选对了口径仍然可能错，所以对几条用例额外要求某个决定口径的字段必须出现（`category-revenue` 要 `AMT` 而不是 `LIST_PRICE`，`rep-monthly` 要 `ORDER_STATUS`）。这一维就是用来量历史检索有没有真的把口径带过去的。

### M9 — 执行报错回流，诊断并入 Agent · 已完成

**原来的诊断路看不见结构。** `AiAssistantService.prepareDiagnose` 走的是 `SchemaContextBuilder.forSql`：从出错的那条 SQL 里抽表名，再去查这几张表。可最常见的报错恰恰是 `column "x" does not exist` 和 `table "y" not found` —— **报错里提到的名字本来就是错的**，抽出来查不到，模型拿到一份空上下文，只能凭印象猜。

而隔壁 Agent 有 `search_schema`、业务词典、历史写法，正好能回答「那这个东西到底叫什么」。所以诊断并进 Agent，旧的 `/api/ai/sql/diagnose` 与 `/diagnose/stream` 一并删掉 —— 留着两条诊断路、其中一条还更差，是比多一个端点更糟的事。

实测（`deepseek-v4-flash`，H2 演示库）：`SELECT name, phone FROM customer WHERE status = 1` 全错，Agent 靠业务词典找到真实表 `T_CRM_0021`，把 `name`→`CUST_NM`、`phone`→`MOBILE`、`status = 1`→`ENABLED = TRUE` 全部映射正确并通过编译校验。

几处设计取舍：

- **失败现场是独立字段（`AiChatRequest.failure`），不是让前端拼进 `message`。** 跑挂的 SQL 和驱动返回的错误原文都是不可信数据 —— 错误原文来自目标库，里面可以是任何内容，包括一句像指令的话。拼进用户消息，模型看到的就和用户说的没有区别。组装逻辑在 `AiChatPrompt`，加了来源标注和用途限定，并且有测试盯着「材料必须出现在标注之后」。
- **带着失败现场进来时强制重新检查结构**（`requireInspection = true`），哪怕这段会话刚查过。执行失败本身就意味着之前对结构的理解是错的，沿用旧结论等于用错误的前提去修错误。
- **落进当前连接上的那段会话**，所以「刚才让它生成的这条」有上下文可接。会话按连接加命名空间存在 sessionStorage 里，不需要给每个 SQL 标签页单独记会话 id。
- **审计里用 `mode=generate|diagnose` 区分**，动作码仍是 `AI_AGENT_CHAT`。`AI_DIAGNOSE_ERROR` 不再写入，但 `auditLog.ts` 里的中文名要留着 —— 历史记录里还有这个码。
- 界面上入口没变，还是结果区那个「AI 诊断」按钮；点下去自动发一轮，用户不用再敲一遍「这条为什么跑不起来」。

### M10 — 结果回流与回答排版 · 已完成

**编译校验挡得住拼错的字段，挡不住语义写错。** 关联方向反了、过滤条件写反了，SQL 照样编译通过 —— 只是返回零行或者几百万行。执行结果是唯一能暴露这类错误的信号，所以结果区加了一个入口，把它送回同一段会话复盘。

**只发形状，不发数据。** `frontend/src/aiResultShape.ts` 把结果压成行数、耗时、每列有多少空值和多少个不同取值，全是计数，一个业务值都没有。这条路因此留在「只发结构」这一档 —— 和结果解读（`AI_INTERPRET_RESULT`）是两回事，那边发真实数据行，要求连接开到「结构 + 样本行」。查错真正需要的信号本来也是计数：

| 看到的形状 | 通常意味着 |
| --- | --- |
| 零行 | 过滤条件过严，或关联方向反了 |
| 某列全为空 | 外连接没匹配上 |
| 行数远超预期 / 被截断 | 缺了关联条件，成了笛卡尔积 |

统计只覆盖当前这一页 —— 不为了复盘再去数据库跑一次聚合，所以文案里写明基数是「本页 N 行」，否则模型会把它当成全量结论。

审计里用 `mode=generate|diagnose|review` 区分三种轮次。

**顺带修了回答的排版。** 此前整段按纯文本渲染，```sql 的反引号原样显示、SQL 挤在正文字体里 —— 这一屏最该看清的东西反而最难读。`aiChat.ts` 里加了代码围栏和两个行内标记（`**加粗**`、`` `代码` ``）的解析，没引 Markdown 依赖：仓库一贯不为渲染引依赖，而回答里实际会出现的也就这几种。未闭合的围栏也按代码渲染，否则流式输出时 SQL 会先以正文字体出现再整段跳成代码块。

### M11 — 业务词典自动初始化 · 已完成

**词典功能做完了，但没人会对着空表格从零填一百条 —— 功能存在等于不存在。** 库里其实已经有一份现成的语料：表注释。`GET /api/ai/connections/{id}/glossary/suggestions` 把它整理成「业务词 → 真实对象」的候选，管理员改成勾选、改名、补别名。

先说清楚这件事能做到哪一步，免得高估它：**注释里的词本来就能被 `search_schema` 搜到，所以自动生成的词条本身不是新信息。** 词典真正不可替代的是用户嘴里的「会员」「买家」—— 那些不会出现在任何注释里，只能由人补。这里做的是把剩下那部分工作变便宜，不是替人做决定，所以候选不自动落库。

推导规则（`AiGlossarySuggestions`，纯逻辑）：

- 注释在第一个分隔符处截断（`客户主档：每日全量` → `客户主档`），再反复剥通用后缀（主档、明细表、信息、字典…）得到业务词，完整注释留作别名和说明。
- **超过 12 个字的就不是名字，是描述**，放弃。业务实体名通常二到八个字；放宽到二十几个字，「这张表按天汇总每个渠道的成交金额」就会被当成词条塞进词典 —— 词典里混进一句话，比少一条词条更糟。
- **同一个业务词命中多张表就合成一条**：`订单主表` 和 `订单明细` 都推出「订单」，应该是一条词条指向两张表。
- 按执行历史里的使用次数排序：没人查过的表，先给它写词条没有意义。
- **没有注释的表单独列出来**。给不出候选词，但它们恰恰是 AI 最找不到的那批 —— 与其静悄悄丢掉，不如告诉人该去补什么。

前端合并逻辑在 `frontend/src/aiGlossary.ts`：业务词在后端有唯一约束，重名会让整批保存失败，而那要等到点保存时才报错 —— 在合并这一步就挡掉，用户根本碰不到那个错误。

### M12 — 把已经产生却被丢掉的信号接起来 · 已完成

这一期没加新功能，做的是三处「数据已经在手上、只差接根线」的地方。

**词典待补清单：搜空的检索词就是缺口的现场采样。** M11 从表注释推候选，能推出来的都是注释里已经有的词 —— 而它自己也写了，词典真正不可替代的是用户嘴里的「会员」「买家」，那些不在任何注释里。这半份此前完全没有来源，其实每天都在产生：`search_schema` 搜了一个词、一个对象都没搜到，就说明用户的说法和这个库的命名对不上。这个信号原本只用来决定要不要让模型重试（`searchFoundObjects`），用完就丢。

- `AiSchemaTools.ToolExecution` 多带一个 `unmatchedQueries`（逐词命中数本来就算给模型看了，顺手留给编排层）；`AiSqlAgentService` 在 `finally` 里汇总落库，**请求失败或被取消也照记** —— 搜不到东西本来就是它答不出来的原因之一。
- 记录整段吞异常：这是给管理员看的旁路信息，为它让一次已经跑完的 AI 回答失败不划算。
- **筛选沿用 M11 那条规则**（`AiGlossaryGaps`，纯逻辑）：超过十二个汉字的不是名字是描述，纯数字和纯符号谁也补不了，一次请求最多记十个，一条连接最多留两百条。词典里已有的词（含别名）先剔掉 —— 管理员已经为那个词给过答案，再列出来只会让人重复补同一个词。
- 界面上排在候选词条**前面**：候选是从库里推的，而这一条条都是有人真的问过、AI 真的没找到的。保存词典时自动销账，也可以逐个「忽略」——没有出口的清单会越积越长，最后没人看。

**工具序列进审计。** 优化基线那次，两处浪费是靠逐条用例的调用序列看出来的，而线上此前只有汇总计数：「6 次工具调用」说不出它是搜了两次还是读了三次表。审计 detail 里加 `seq=`，事后能筛出哪类问题让它反复摸索。只记工具名，不记参数。

**prompt cache 前缀的回归测试**（`AiPromptCachePrefixTest`）。「缓存读 token」此前只有跑真模型才拿得到，等到有人在系统提示里塞进一个时间戳，要到下一次手动评测才会发现，而那时账单已经按全价出过好几轮。现在用剧本模型把同一件事变成 CI 断言：系统提示和工具定义在所有轮次里逐字相等、消息只准在尾部增长、系统提示里不许出现当前年份。写这条测试时顺手做了变异验证 —— 往系统提示里注入 `Instant.now()`，两个用例都会红。

### M13 — token 预算与用量 · 已完成

**管理员敢不敢把 Key 配上去，多半取决于「失控了会怎样」有没有答案。** 此前一个 API Key 一放，
没有任何东西能拦住一次跑飞的循环或者一个把额度当无限用的人 —— 只有事后翻审计能看出花了多少。

- 两道额度，都存在 `ai_settings`（V17 迁移）：**全站每日**和**每人每日**，0 表示不限制。
  只有全站额度挡不住一个人把大家的份额用完，所以第二道必须存在。
- **闸门挂在 `AiSettingsService.requireEnabled(actor)` 上**，也就是每条 AI 路径的第一行，而不是
  散在各个入口。闸门只要有第二个地方可以绕开，迟早会被绕开。两道额度都是 0 时直接返回，
  连库都不查 —— 没设预算的部署不该为这个功能付出每次请求一次查询的代价。
- **额度只在请求开始前检查**，因为一次请求要花多少 token 得等它跑完才知道。所以最后那一次
  请求可以把额度冲过头：上限是「超了就不再开新的」，不是硬性截断。想严格不超只能预扣估算值，
  而估算一条 Agent 请求的 token 本身就不可靠，那只会把额度提前用掉。
- **用量落在 `ai_usage_daily`，不从审计 detail 里解析。** 那串 `key=value` 是给人看的，用正则在
  几百万行审计上做聚合既慢又会随措辞一起碎掉；而这张表每天最多长出「人数 × 模型数」行，
  闸门每次请求都要读它，得是一个能走索引的小查询。
- **两条路记的是同一样东西。** 单轮问答此前只记 `promptChars`／`answerChars` —— 字符不是计费
  单位，也没法和 Agent 那条路的数相加。现在两边都记 token，审计 detail 里也补上了。
- **取消和失败一样记账**：token 在被打断之前已经花出去了。这条与 `AI_AGENT_CHAT` 审计同源，
  漏了它，最容易失控的那类请求（反复重试、每次都烧 token）刚好能绕开额度。
- 计入额度的口径是**输入 + 输出**，缓存读单独一列不计 —— 它说明的是省了多少，混进总数会让
  「今天为什么贵了」永远看不出来。额度按**服务器本地自然日**算，跨零点清零；模型的账单周期
  不是这个，所以这里卡的是「今天别失控」，不是对账。
- 界面在 AI 设置里加了一张用量卡片：今日用量与额度、最近十四天按天的输入/输出/缓存读、
  用得最多的十个人。**不画图表** —— 一张表在这个宽度里比折线好读，也不必为它把图表依赖
  拉进首屏。

### M14 — 执行计划解读并入 Agent · 已完成

**只把计划文本发给模型，它看不到这张表上真实存在哪些索引。** M4 那条单轮问答拿到的是 SQL、
计划和规则结论，然后就要回答「该建什么索引」—— 缺的恰恰是最关键的一半：现有索引、字段类型、
基数，以及这个库里同类查询实际怎么写。结果只能是泛泛的「给 ORDER_STATUS 加个索引」，
而那个索引很可能已经有了。

所以走 M9 的老路：并进 Agent，删掉 `/api/ai/sql/explain-insight` 与它的流式变体。留着两条解读路、
其中一条还更差，是比多一个端点更糟的事。

- 计划现场经 `AiChatRequest.plan` 传入（`AiExecutionPlan`：SQL、计划文本、`explainInsights.ts` 的
  规则结论），由 `AiChatPrompt` 加上不可信标注后才进模型 —— 计划文本里的表名和注释都来自目标库。
- **带着计划进来时强制重新检查结构**（与失败、结果复盘一致）：不读出真实索引，「该建什么索引」
  就只能靠猜。
- **这一轮允许模型给出一条 `CREATE INDEX`**，那正是计划解读最有价值的产出。但也只到建索引为止：
  `AiPlanAdvice.isIndexScript` 之外的 DDL 与写操作一律打回，只能用文字说明。删一个索引是否安全，
  取决于这个库上还有谁在用它 —— 那是人的判断，不该被顺手写进编辑器。
- **索引脚本不做编译校验**：`compileQuery` 只接 SELECT，而 prepare 一条 DDL 在某些驱动上就等于
  执行它。所以证据面板直说「未做编译校验，也不会自动执行」，脚本按原样交给用户，在 SQL 工作台
  走正常执行路径 —— 生产确认与审计都在那条路上。
- 系统提示里新增的那条是**静态**的，和其他模式的说明并列，不随模式切换 —— 模式相关的内容一律走
  用户消息。系统提示一变，整段 prompt cache 前缀就作废，`AiPromptCachePrefixTest` 会直接拦下来。
- 审计里 `mode=explain`，与 `generate|diagnose|review` 并列；`AI_EXPLAIN_INSIGHT` 不再写入，
  但 `auditLog.ts` 里的中文名要留着 —— 历史记录里还有这个码。

### 评测集 — 改这套 Agent 之前先跑一遍

用例、固定库结构和打分逻辑都在 `backend/src/test/java/com/example/dbadmin/service/ai/eval/`，固定库是 `src/test/resources/ai-eval-schema.sql`。

**主要校验「这条 SQL 命中了哪些表」，不比对 SQL 文本。** 同一个需求有无数种写法，比对文本量的是模型的风格；而找没找对表恰恰是这套 Agent 存在的理由 —— 表错了，后面写得再漂亮都是错的。判「通过」要同时满足四条：期望的表一张不少、禁用的干扰表一张没有、`expectedTokens` 里决定口径的字段都用上了、SQL 通过目标库编译校验。多带一张表只记录不扣分。

固定库里埋了三处真实项目常见的麻烦：`T_CRM_0021` 的物理名毫无语义（只能靠表注释或业务词典找到）、`APP_USER_ARCHIVE` 是 `APP_USER` 的归档表（最容易选错的干扰项）、金额和时间的列名前后不统一（`AMT` / `TOTAL_AMOUNT`、`ORDER_DATE` / `CREATED_AT`，不读真实字段就会猜错）。

两种跑法：

| | 跑什么 | 什么时候跑 |
| --- | --- | --- |
| `AiSqlAgentLoopTest`、`AiEvalReportTest` | 剧本化的假模型 + 真的工具、校验、会话、审计 | 每次 `mvn test`，不需要 API Key |
| `AiSqlAgentEvalTest` | 真模型跑完整用例集，出 `target/ai-eval-report.md` | 手动，改 prompt / 换模型 / 调工具之后 |

```bash
AI_EVAL_API_KEY=sk-... mvn test -Dtest=AiSqlAgentEvalTest
AI_EVAL_API_KEY=... AI_EVAL_MODEL=... AI_EVAL_BASE_URL=https://自建网关/v1 mvn test -Dtest=AiSqlAgentEvalTest
```

用独立的 `AI_EVAL_API_KEY` 而不是复用 `ANTHROPIC_API_KEY`：后者很可能只是开发机上给别的工具配的，不该有人跑一次 `mvn test` 就意外花掉几十次模型调用。

报告里除了通过率，更值得盯两个数：**平均工具调用次数**说明模型要摸索多久才敢下笔，**平均缓存读 token** 说明 prompt cache 有没有真的命中。这个数是 0 有两种可能，要分清：一是稳定前缀里混进了每次都变的内容，二是这家网关根本不报缓存用量 —— 兼容协议下 OpenAI 放在 `prompt_tokens_details.cached_tokens`、DeepSeek 放在 `prompt_cache_hit_tokens`，两个字段都没有就只能是 0。

#### 已有基线

`deepseek-v4-flash`（DeepSeek 兼容协议），10 条用例：

| 日期 | 通过率 | 平均轮次 | 平均工具调用 | 输入 token | 输出 token | 缓存读 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-09-04 优化前 | 10/10 | 5.0 | 6.4 | 17729 | 1877 | 14912 | 14.9 秒 |
| 2026-09-04 优化后 | 10/10 | 4.0 | **3.4** | **11208** | 1628 | 8653 | 12.6 秒 |

**优化靠的是工具序列，不是调 prompt 措辞。** 加上逐条用例的调用序列之后，两处浪费一眼就看出来了：

- `search_schema` 平均 2.6 次，每条用例都是 `×2` 或 `×3`。原因不在模型 —— 参数只收单个 `query`，而一个问题里通常有好几个业务实体（「每个客户的订单总金额」是客户加订单加金额），它只能一个一个搜，每次都是一个完整的模型往返。改成收 `queries` 数组后降到 1.1。检索本身只是本地目录扫描，合并成一次几乎不花额外代价。
- `validate_sql` 每次必调，而服务端本来就对每个候选答案做编译校验、不通过会把错误原文发回模型。留着这个工具等于每次多花一个往返换同一个结论，删掉之后校验闭环一点没少。

结果是工具调用降 47%、输入 token 降 37%，通过率没动。十条用例里有七条现在走的是最短序列：`search_schema → describe_objects → search_query_history`。

三点读法：

- **缓存读占输入的 77%**（兼容协议的 `prompt_tokens` 含缓存，所以每次真正新增的只有约 2.5k）。这说明多轮之间的前缀确实是稳定增长的，没有被每次都变的内容写脏 —— 系统提示里不放时间戳这条约定是有效的。轮次减少后这个比例会略降，因为可缓存的前缀本身变短了。
- **通过率已经满了，说明用例偏简单，测不出模型之间的差距。** 下一步要么加难度（自关联的类目树、需要窗口函数的排名、跨表口径冲突、故意含糊需要反问的），要么就只把它当回归用。
- 同一套用例两次跑出来的工具调用是 6.5 和 6.4、输入 token 是 17484 和 17729，**单次结果有噪声**，比较时看趋势不看小数点。

用例集是基线，**改一条已有用例就意味着历史分数不再可比**。要扩覆盖面就加新用例，或者往固定库里加表。

## 9. 验收清单

每个里程碑合并前：

- `cd backend && mvn test` 与 `cd frontend && npm run build` 通过（后者含体积预算校验）
- 新增纯逻辑模块都有同名测试：后端 `*Test`，前端 `*.test.ts`
- 新增审计动作码在 `auditLog.ts` 有中文名（`AuditActionLabelCoverageTest`）
- 关闭 AI 功能时，所有相关入口在界面上不可见，且后端返回 `AI_DISABLED` 而不是 500
- 连接策略为 `NONE` 时，任何 AI 接口都取不到该连接的结构
- Agent 工具只能读取当前请求绑定的连接与 Schema，不能查询业务行或执行 SQL
- 编译校验不得在目标库上真正取数：新增方言时确认 `compileQuery` 的驱动行为，别默认继承
- Agent 请求无论成功、失败还是取消，都要在审计里留下一条 `AI_AGENT_CHAT`
- 改动 Agent 的 prompt、工具或循环后，跑一次 `AiSqlAgentEvalTest` 并对比上一次的通过率与 token
- 系统提示、工具定义与历史消息构成 prompt cache 的前缀：只许在尾部追加，`AiPromptCachePrefixTest` 会卡住

## 10. 已定决策

以下四条在方案评审时已经拍板，写在这里是为了让后续实现不必重新讨论。

| # | 问题 | 决策 | 影响 |
| --- | --- | --- | --- |
| 1 | 桌面模式的 Provider 配置怎么存 | **与 Web 模式一致处理**，各存各的元数据库 | 桌面版需单独配一次 Provider，与连接/Agent/备份现状一致，不做特例 |
| 2 | 多用户模式下 API Key 的归属 | **全局一份 + 管理员维护** | `ai_settings` 不带 `user_id`；读接口一律掩码，普通用户看不到 Key |
| 3 | 自然语言与对话是否落 SQL 历史 | **不落**；对话仅保留在前端内存，最终 SQL 仅在用户执行后按原路径记录 | AI 接口不写 `sql_history`；刷新页面即丢失对话 |
| 4 | 本地 / 兼容协议模型的实现时机 | **放 M1**，与 `AnthropicLlmClient` 同期 | provider 抽象要有第二个实现才谈得上验证，延后必然返工接口形状 |

第 3 条还有一个连带约束：自然语言原文既不进 `sql_history`，也不进审计（见 7.7）。它在服务端只活在一次请求的生命周期里，不落任何一张表。
