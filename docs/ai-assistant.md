# AI 助手接入方案

本文是 MyDataDev 内置 AI 能力的推进依据。目标是让产品自己会调用大模型，而不只是把数据库能力暴露给外部 AI agent（后者已由 `docs/mcp-server.md` 的 MCP Server 完成）。

## 1. 目标与非目标

**目标**

- 在 SQL 工作台内提供自然语言生成 SQL、执行报错诊断、执行计划解读三项核心能力。
- 结构元数据的出网范围由用户显式控制，默认不发送任何业务行数据。
- AI 生成的一切 SQL 走与界面完全相同的执行路径，生产确认、未限定范围写确认、审计一个都不能少。
- Provider 可插拔：默认 Claude，允许接入兼容协议的自建服务或本地模型，离线环境可以完全不启用。

**非目标（本期不做）**

- 不做自动执行：AI 永远只把 SQL 写进编辑器，由人按下执行。
- 不做多轮 agent 循环与工具调用编排。第一期是「一次请求一次回答」的工作流，不是 agent。
- 不做对话历史的长期存储与检索（会话保留在前端内存，刷新即丢）。
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
  └── repo/AiSettingsRepository        JdbcTemplate 持久化（Provider 配置、连接级开关）
```

DTO 进 `dto/ApiDtos.java`，模型用 `record`。

**硬约束**：`AiAssistantService` 里不允许出现 `if (dbType == MYSQL)` 这类分支，方言差异一律问 `DatabaseDialect` 要。

### 3.2 前端

- 新面板 `components/AiAssistantPanel.tsx`，挂在 SQL 工作台右侧，`React.lazy` 懒加载。
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
| `POST` | `/api/ai/sql/diagnose` | 执行报错诊断 |
| `POST` | `/api/ai/sql/explain-insight` | 执行计划解读 |
| `POST` | `/api/ai/sql/{action}/stream` | 上述三项的 SSE 流式变体 |
| `GET` | `/api/ai/status` | 可用性快照（所有登录用户可读，不含配置细节） |

两处与最初草图的偏差：

- **流式入口是 POST，不是 GET。** 诊断要带上整条 SQL 与报错原文，塞进查询串既有长度上限，也会被访问日志和反向代理原样记下来。代价是前端不能用 `EventSource`，改用 `fetch` 读 `ReadableStream`。
- **多了 `GET /api/ai/status`。** 设置面板是管理员的，但「这条连接上要不要显示 AI 按钮」是每个用户都要知道的事，所以单开一个只说「功能开没开、哪些连接被授权」的只读接口，不含任何配置细节。

错误一律用 `ApiProblemException(status, code, message, details)` 抛出。新增错误码需要在 `frontend/src/api.ts` 的 `ApiError` 侧同步识别：

- `AI_DISABLED` — 未启用或未配置 Provider
- `AI_CONNECTION_NOT_SHARED` — 该连接的共享策略为 `NONE`
- `AI_PROVIDER_ERROR` — 上游返回错误（透传状态码与简要原因）

## 6. 模型与 Provider

### 6.1 选型

- 默认模型 **`claude-opus-5`**（1M 上下文，$5 / $25 每 MTok）。
- thinking 用 `{type: "adaptive"}`；`output_config.effort` 分场景：生成 SQL 与报错诊断用 `high`，注释生成之类的批量轻任务用 `low`。
- 长输出（文档生成、结果解读）一律流式，避免 HTTP 超时。
- 换更便宜的模型是产品取舍，不作为默认。

### 6.2 SDK

模型调用用官方 **`com.anthropic:anthropic-java`**（`AnthropicOkHttpClient`），不扩用 Spring AI 的 model starter。理由：

1. pom 里的 Spring AI BOM 目前只为 MCP server 服务，两件事不必绑在一起；
2. adaptive thinking、`effort`、prompt caching 这些参数在官方 SDK 上是第一手支持；
3. 多 provider 由我们自己的 `LlmClient` 接口收敛，比套第三方抽象更可控。

### 6.3 成本

结构摘要是稳定前缀，放进系统提示并打 `cache_control` 断点，用户问题放在断点之后 —— 同一连接的连续提问基本都是 cache read。摘要带一个版本号，`MetadataCacheService` 的 `evict*` 触发时推进版本，前缀随之失效重建。上线后用 `usage.cache_read_input_tokens` 验证命中，长期为零说明前缀里混进了变量。

## 7. 安全与隐私边界

1. **默认不出网**：连接级策略默认 `NONE`，管理员逐条开启。
2. **默认只出结构**：`STRUCTURE` 档只发表名、列名、类型、可空、主外键、索引、注释，不发任何行数据。
3. **采样受限**：`STRUCTURE_AND_SAMPLE` 才发样本行，条数受 `sample_row_limit` 约束，生产连接禁用该档。
4. **不给 AI 执行权**：`/api/ai/*` 下没有任何执行入口，产出只能回到编辑器。
5. **Key 与数据库密码同级**：`CryptoService` 加密后入库，桌面模式自然落进操作系统安全存储。
6. **权限**：AI 设置与连接策略均为 `requiresAdmin`，API Key 只由管理员维护、普通用户看不到（读接口一律掩码）；调用侧要求连接的 `QUERY` 权限。
7. **审计**：每次调用写审计，走 `AuditRepository.onConnection(...)`，记录动作、连接、共享档位与模型，不记录用户输入的自然语言原文（避免二次泄露），只记长度与是否带样本。

新增动作码（需同步 `frontend/src/auditLog.ts`，否则 `AuditActionLabelCoverageTest` 会失败），建议新增审计分类 `ai`：

| 动作码 | 中文名 |
| --- | --- |
| `AI_GENERATE_SQL` | AI 生成 SQL |
| `AI_DIAGNOSE_ERROR` | AI 诊断执行报错 |
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

### M4 — 执行计划解读 · 已完成

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

## 9. 验收清单

每个里程碑合并前：

- `cd backend && mvn test` 与 `cd frontend && npm run build` 通过（后者含体积预算校验）
- 新增纯逻辑模块都有同名测试：后端 `*Test`，前端 `*.test.ts`
- 新增审计动作码在 `auditLog.ts` 有中文名（`AuditActionLabelCoverageTest`）
- 关闭 AI 功能时，所有相关入口在界面上不可见，且后端返回 `AI_DISABLED` 而不是 500
- 连接策略为 `NONE` 时，任何 AI 接口都取不到该连接的结构

## 10. 已定决策

以下四条在方案评审时已经拍板，写在这里是为了让后续实现不必重新讨论。

| # | 问题 | 决策 | 影响 |
| --- | --- | --- | --- |
| 1 | 桌面模式的 Provider 配置怎么存 | **与 Web 模式一致处理**，各存各的元数据库 | 桌面版需单独配一次 Provider，与连接/Agent/备份现状一致，不做特例 |
| 2 | 多用户模式下 API Key 的归属 | **全局一份 + 管理员维护** | `ai_settings` 不带 `user_id`；读接口一律掩码，普通用户看不到 Key |
| 3 | 自然语言输入是否落 SQL 历史 | **不落**，只落最终生成的 SQL | AI 接口不写 `sql_history`；用户在编辑器里执行时，按现有路径正常落库 |
| 4 | 本地 / 兼容协议模型的实现时机 | **放 M1**，与 `AnthropicLlmClient` 同期 | provider 抽象要有第二个实现才谈得上验证，延后必然返工接口形状 |

第 3 条还有一个连带约束：自然语言原文既不进 `sql_history`，也不进审计（见 7.7）。它在服务端只活在一次请求的生命周期里，不落任何一张表。
