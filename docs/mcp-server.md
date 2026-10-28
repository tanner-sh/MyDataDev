# MCP Server 使用说明

MyDataDev 后端内置一个查询型 MCP Server，使用 Streamable HTTP 协议向其他 AI agent 提供数据库发现和查询能力。端点默认为：

```text
http://<backend-host>:8080/mcp
```

MCP 在新配置中默认开启，不影响现有 Web 和 REST API；升级时会尊重 H2 中已经保存的开关状态。管理员可完全通过 Web 配置和热启停 MCP，不需要额外执行生成密钥或 MCP 启动命令。启用 MCP 只会保护 `/mcp`；现有 `/api` 的网络访问控制方式保持不变。

## 通过 Web 开启

1. 正常启动 MyDataDev 后端和前端。
2. 在连接管理中确认需要提供给 AI agent 的数据库连接已经配置完成。
3. 点击页面右上角的 **MCP**，进入 **MCP Server 设置**。
4. 点击 **新建 Agent**，填写 Agent ID，选择允许查询的连接；生产连接需要额外确认生产权限。
5. 系统会自动生成高熵 API Key。立即复制完整凭据或客户端 JSON；关闭弹窗后无法找回，只能轮换 Key。
6. 如此前主动关闭过 MCP Server，可重新打开开关。所有修改都会即时生效，无需重启后端。

MCP 可以在没有 Agent 时保持开启，但此时没有任何有效 API Key，请求会返回 401。创建、停用和删除 Agent 不需要切换服务状态。

## Web 内置接入帮助

MCP 设置抽屉包含 **服务配置** 和 **接入帮助** 两个页签。接入帮助会使用当前部署的实际 MCP URL，提供：

- 当前服务状态、可用 Agent 数量和五步接入流程。
- Codex CLI、Claude Code、Claude Desktop、Cursor、Gemini CLI、Antigravity IDE 和通用 Streamable HTTP 配置，并明确区分全局与项目级范围。
- macOS zsh、Linux bash 与 Windows PowerShell 的密钥环境变量示例。
- 安全边界、可用工具清单和 401、503、网络、授权等常见问题处理。

普通帮助页只显示 `<AGENT_API_KEY>` 等占位值。创建 Agent 或轮换 Key 后的一次性弹窗会使用刚生成的真实凭据输出各客户端配置；关闭弹窗后仍然无法找回完整 Key。

## 安全模型

一次 MCP 数据库访问必须同时通过以下检查：

1. 请求携带有效的 agent API Key。
2. 目标连接位于该 agent 的白名单中。
3. 生产连接还要求该 agent 设置 `allow-production: true`。
4. 操作所需的访问档位不高于该连接上授予的档位。

### 访问档位

授权按**连接**而不是按 agent 授予，因此同一个 agent 可以在开发库上有写权限、在生产库上只读：

| 档位 | 能力 |
| --- | --- |
| `READ_ONLY` | 查看结构、浏览表、`db_query`、`db_explain` |
| `DATA_WRITE` | 只读能力，外加通过 `db_execute` 执行 INSERT / UPDATE / DELETE |
| `FULL` | 数据读写能力，外加 CREATE / ALTER / DROP 等结构变更 |

**升级不改变任何既有 agent 的能力**：已有授权一律落到 `READ_ONLY`，从配置文件导入的 agent 也是只读，写权限只能在 Web 的 MCP 设置里显式授予。

`db_query` 和 `db_explain` 只接受分类为查询的单条语句，会拒绝 DML、DDL、存储过程调用、锁、会话修改、多语句和已知的有副作用查询。执行时还会设置 JDBC 只读提示、关闭自动提交并在结束时回滚。写操作必须走 `db_execute`，两条路径不共用。

无论档位多高，写操作都要经过与 Web 界面完全相同的闸门：

- 标记为 `readonly` 的连接拒绝一切写入（只读连接在界面上也无法被授予写档位）。
- 生产连接上的每条语句都要求 `productionConfirmation` 等于连接名。这对 agent 来说算不上阻力（连接名可以从 `db_list_connections` 读到），它的作用是把意图固定进这次调用和审计记录里，而不是让 agent 只凭一个连接 ID 就误写生产库。**真正的把关是授予档位时的那次决定。**
- 不含顶层 WHERE 的 UPDATE / DELETE 需要 `unscopedMutationConfirmed=true`。
- 每次调用都写审计，写语句还会额外落 SQL 历史。

应用层检查不能替代数据库权限，因此对生产库和其他高风险数据库仍建议使用数据库侧只读账户，并只在确有需要时才授予高于 `READ_ONLY` 的档位。

API Key 会出现在每次请求的 `Authorization` 请求头中。跨主机部署时应通过反向代理提供 HTTPS，不要在不可信明文网络上传输 Key。`/mcp` 不应直接暴露到公网。

## Agent API Key

凭据格式为：

```text
<agent-id>.<raw-secret>
```

系统使用安全随机数自动生成 `raw-secret`，在 H2 元数据库中只保存 cost 12 的 BCrypt 哈希，不保存完整凭据。每个 Agent 应使用独立 Key；Web 中的“轮换 Key”会立即废止旧 Key，并清除该 Agent 已建立的 MCP session。

## 配置存储与旧配置迁移

Web 配置保存在 MyDataDev 自身的 H2 元数据库中，包括服务状态、Agent、BCrypt 哈希、连接白名单、生产权限、Origin 和资源限制。数据库中一旦存在 MCP 配置，后续启动都以数据库为准。

为兼容早期版本，升级后首次启动会将已有 `app.mcp` YAML 配置导入数据库一次。例如旧部署仍可保留：

```yaml
app:
  mcp:
    # 浏览器发起请求时才会带 Origin。CLI/服务端 agent 通常不需要配置。
    allowed-origins: []
    agents:
      - id: analytics-agent
        key-hash: "$2y$12$replace-with-the-bcrypt-hash-of-the-raw-secret"
        connection-ids: [1, 3]
        allow-production: false
      - id: production-observer
        key-hash: "$2y$12$replace-with-another-bcrypt-hash"
        connection-ids: [7]
        allow-production: true
```

导入完成后，YAML 的 MCP 配置不再覆盖 Web 中的修改，可以从部署配置中移除。不要把 Agent Key 哈希、真实连接密码或加密密钥提交到 Git。

如果浏览器 MCP 客户端需要访问端点，必须在 Web 设置中将准确的 Origin 加入允许列表，例如 `https://agent-console.example.internal`。

空列表会拒绝所有携带 `Origin` 的 MCP 请求，同时允许不携带 `Origin` 的服务端/CLI 客户端。不要使用宽泛或动态反射的 Origin。

## 客户端配置

创建或轮换 Agent 后，Web 会生成可直接复制的多客户端配置。不同 MCP 客户端的字段名称略有区别，通用配置如下：

```json
{
  "mcpServers": {
    "mydatadev": {
      "type": "streamable-http",
      "url": "https://dbadmin.example.internal/mcp",
      "headers": {
        "Authorization": "Bearer analytics-agent.<raw-secret>"
      }
    }
  }
}
```

### 配置范围与数据源隔离

全局配置会让当前用户的所有项目加载同一个 MCP Server；项目级配置只在对应项目中加载。配置范围只决定客户端在哪里加载 MCP，不决定数据库权限。真正可访问的数据源始终由 MyDataDev 中对应 Agent 的连接白名单控制。

如果不同项目需要访问不同数据源，应为每个项目创建独立的 MyDataDev Agent、API Key 和环境变量，并使用项目级配置。Web 示例统一使用 `MYDATADEV_MCP_TOKEN`，复制到项目后可改成项目专属名称，例如 `VALUATION_MYDATADEV_MCP_TOKEN`。`bearer_token_env_var` 等字段必须填写环境变量名称，不能直接填写完整 API Key。

| 客户端 | 全局配置 | 项目级配置 | 说明 |
| --- | --- | --- | --- |
| Codex CLI | `~/.codex/config.toml`；`codex mcp add` 默认写入全局配置 | `<项目根目录>/.codex/config.toml`，仅可信项目加载 | 推荐使用环境变量；也支持静态 `http_headers` |
| Claude Code | 用户级配置或 `--scope user` | `<项目根目录>/.mcp.json` | 支持在 Header 中展开环境变量 |
| Claude Desktop | `claude_desktop_config.json` | 不支持按代码项目自动切换 | 私网静态 Bearer 使用 `mcp-remote` 桥接，配置对整个应用可见 |
| Cursor | `~/.cursor/mcp.json` | `<项目根目录>/.cursor/mcp.json` | 桌面进程必须能够读取配置中引用的环境变量 |
| Gemini CLI | `~/.gemini/settings.json` 或 `--scope user` | `<项目根目录>/.gemini/settings.json` | 项目配置中不要提交真实 Key |
| Antigravity IDE | 在 MCP Servers → Manage MCP Servers → View raw config 打开实际配置（当前文档为 `~/.gemini/config/mcp_config.json`） | 支持工作区配置的版本使用 `.agents/mcp_config.json` | 使用专用 `serverUrl`、`headers` 模板，不包含 `httpUrl`、`trust` 或 `type` |

### Codex 静态 http_headers

如果不希望每次启动 Codex 前设置环境变量，可以把 Bearer 凭据通过静态 `http_headers` 写入全局或项目级 `config.toml`：

```toml
[mcp_servers.mydatadev]
url = "https://dbadmin.example.internal/mcp"
http_headers = { "Authorization" = "Bearer <AGENT_API_KEY>" }
enabled = true
```

普通 Web 帮助使用 `<AGENT_API_KEY>` 占位符；创建或轮换 Agent 后的一次性凭据弹窗会生成包含真实 Key 的静态 Header 配置。该方式无需环境变量，但完整 Key 会以明文保存在 `config.toml`。只应保存到本机私有配置，设置严格文件权限，并确保 `.codex/config.toml` 不进入 Git、工单、日志或聊天记录。环境变量方式仍是推荐方案。

Claude Desktop 的远程自定义连接器由 Anthropic 云端发起请求，通常要求公网可达并使用 OAuth。MyDataDev 默认面向私有网络并采用 Agent Bearer Key，因此 Web 帮助使用 `mcp-remote` 把 Desktop 的本地 stdio 请求桥接到 `/mcp`；HTTP 私网地址需要显式允许，跨主机仍应优先使用 HTTPS。

不要把含真实 Key 的 JSON、终端命令或环境文件提交到 Git。使用环境变量时，必须确保实际启动 AI Agent 的终端或桌面进程能够继承该变量。

本地开发时 Vite 会同时代理 `/api` 和 `/mcp`。生产环境若让前端与后端共用域名，反向代理也应同时转发这两个路径；若前端使用跨域的 `VITE_API_BASE_URL`，Web 会按该后端地址生成 MCP URL。

客户端必须在初始化和后续会话请求中都发送同一个 Bearer 凭据。服务端会把 MCP session 绑定到创建它的 agent，其他 agent 不能复用该 session ID。

## 可用工具

| 工具 | 能力 |
| --- | --- |
| `db_list_connections` | 列出当前 agent 白名单内的连接，不返回 JDBC URL、用户名或密码 |
| `db_list_namespaces` | 分页列出 Catalog 或 Schema |
| `db_search_objects` | 分页搜索表和视图 |
| `db_describe_object` | 查看列、主键、索引和外键关系 |
| `db_get_object_ddl` | 获取表或视图的可用 DDL |
| `db_list_schema_objects` | 分页列出存储过程、函数、触发器、序列、视图与物化视图，带数据库报告的状态（Oracle 为 VALID / INVALID），可用 `statusFilter=INVALID` 只看坏对象 |
| `db_get_schema_object` | 按字符分片查看对象源码，返回总长度、续读位置、源码版本、参数与编译错误 |
| `db_browse_table` | 使用不透明 cursor 分页浏览表数据，不返回编辑 token |
| `db_query` | 执行一条只读查询；因行数或单元格数量截断时返回 `nextOffset`，带 `offset` 取下一页；文本截断需按字符续读 |
| `db_explain` | 获取一条查询的执行计划 |
| `db_execute` | 执行一个完整写入单元，要求该连接上有 `DATA_WRITE` 或 `FULL` 档位 |
| `db_execute_script` | 按指定事务策略后台执行 SQL 文本，返回 taskId |
| `db_list_script_tasks` | 按连接分页找回当前 agent 的任务，默认只列活动任务；`activeOnly=false` 包含已完成与失败任务 |
| `db_script_status` | 查询当前 agent 创建的脚本任务状态；`waitSeconds`（最多 60）在服务端等到任务结束再返回 |
| `db_cancel_script` | 请求取消当前 agent 的脚本任务，已提交内容保留 |

`db_list_connections` 会返回当前 agent 在每条连接上的档位，agent 据此知道自己能做什么；同时返回同连接脚本并发上限与当前排队/运行中的脚本数。

工具报错一律以 `[错误码]` 开头，例如 `[PRODUCTION_CONFIRMATION_REQUIRED]`、`[UNSCOPED_MUTATION_CONFIRMATION_REQUIRED]`、`[SQL_FILE_ALREADY_RUNNING]`、`[SQL_TIMEOUT]`、`[SQL_ERROR]`、`[INVALID_REQUEST]`、`[CANCELLED]`，agent 据此决定补参数重试、换后台脚本，还是修改 SQL。`SQL_ERROR` 带数据库的 SQLState 与厂商错误码。

Oracle / OceanBase Oracle 上，`CREATE OR REPLACE` 或 `ALTER … COMPILE` 编译失败时语句本身仍然成功，对象以 INVALID 状态留在库里。`db_execute` 与 `db_execute_script` 会在这类语句之后回查 `ALL_ERRORS`，把错误放进结果的 `compilationErrors`（脚本照 SQL*Plus 的习惯继续执行后续语句）；读不到字典表时视为没有编译错误。

### 长过程正文的完整读回

`db_query` 的 `offset` 是**行偏移**。当 `truncatedReason=text_limit` 时，截掉的是单元格正文，增加 `maxRows` 或翻到下一行取不回这部分文本。OceanBase 的 `ALL_SOURCE` 可能把整个过程放在一条记录的 `TEXT` 中，所以不能根据 `LINE` 数量或预先固定的几个分片认定正文已经读完。

过程、函数和触发器应先通过 `db_list_schema_objects` 获取 `objectKey`，再用 `db_get_schema_object` 读回：

1. 首次传 `connectionId`、`objectKey`，省略 `sourceOffset`（默认 0）。`sourceMaxChars` 默认 8000，同时受 MCP 单元文本和结果文本总量上限约束，可主动设小以适应客户端工具输出上限。
2. 保存首次返回的 `sourceVersion`；若 `nextSourceOffset` 非 null，将其原样作为下一次的 `sourceOffset`，并传 `expectedSourceVersion=sourceVersion`。服务端每次重新读取对象，源码变更时拒绝拼接，需从 0 重读。
3. 按顺序拼接各次 `source`，直到 `nextSourceOffset=null`。核对从 0 开始累计的 UTF-16 长度等于 `sourceTotalChars`；也可核对拼接文本 UTF-8 字节的 SHA-256 等于 `sourceVersion`。`sourceTruncated` 表示当前分片后还有正文，最后一片为 false。

所有源码偏移和长度都以 UTF-16 单元计，服务端不会从一个补充 Unicode 字符中间切开；续读应使用返回的偏移，不要自行加固定步长。如果客户端仍截断响应，缩小 `sourceMaxChars` 并从该片的起点重试。`sourceAvailable=false` 时长度、版本、下一片位置都为 null，不代表已成功读完。底层 CLOB 超过 200 万字符读取上限时会明确返回源码不可用原因，不再把截短前缀当作完整定义。

这条对象读取路径在 Oracle / OceanBase Oracle 上使用 `DBMS_METADATA.GET_DDL`，无需依赖 `ALL_SOURCE` 的行布局（参见 [OceanBase 官方定义代码读取说明](https://www.oceanbase.com/knowledge-base/oceanbase-database-1000000000209962)）。必须直接查询 `ALL_SOURCE` 时，应限定 `OWNER`、`NAME`、`TYPE`，先查询各行 `LINE` 与 `LENGTH(TEXT)`，再对每一行用 `SUBSTR(TEXT, 起始位置, 分片长度)` 覆盖完整长度；SQL 的起始位置从 1 开始，长度采用数据库字符语义，每片同时受单元文本和结果总量限制。任一片仍报 `text_limit` 时需缩小并重读，不能跳过后继续拼接。

除 `db_execute`、`db_execute_script`、`db_cancel_script` 外的所有工具都发布 `readOnlyHint: true` 和 `destructiveHint: false`；这三个写操作工具发布 `readOnlyHint: false` 和 `destructiveHint: true`，客户端可以据此对它单独要求人工批准。数据库返回的对象名、注释、DDL、数据和错误均属于不可信输入，agent 不应把这些内容当作指令执行。

## 限制与可观测性

默认限制在第一次初始化时来自 `backend/src/main/resources/application.yml`，之后可在 Web 的“高级资源限制”中调整并即时生效，包括：

- 查询默认 100 行、最多 500 行。
- 单次结果最多 20,000 个单元格。
- 单次结果文本总量最多 1,000,000 字符，单个文本值最多 20,000 字符。
- SQL 最多 200,000 字符；查询超时（`db_query`、`db_explain`）默认 30 秒，写操作超时（`db_execute`）默认 60 秒，两者都可在 MCP 设置里调整，上限 300 秒。后台脚本的单条语句超时由 `app.sql-file.statement-timeout-seconds` 决定（默认 7200 秒）。
- 元数据分页最大 200 项，表数据分页最大 100 行。
- MCP session 归属记录默认保留 120 分钟并在访问时续期。

Streamable HTTP 默认不启用服务端定时 ping。当前 SDK 对没有独立 GET 监听流的会话也会发 ping 并反复记录 `Stream unavailable`；只通过 POST 调用工具的客户端无需保持 GET 流。部署配置若覆盖了 `spring.ai.mcp.server.streamable-http.keep-alive-interval`，删除该项并重启即可恢复默认，不要设成 `0s`。客户端会话使用完毕后应带原认证和 `Mcp-Session-Id` 发送 `DELETE /mcp`；停止 ping 不会自动删除 SDK 内部遗留会话。

工具调用会以 `mcp:<agent-id>` 作为 actor 写入审计记录；SQL 查询和执行计划还会写入 SQL 历史。Actuator 指标包括：

- `dbadmin.mcp.tool`：按工具和状态记录执行耗时。
- `dbadmin.mcp.security.denied`：按原因统计被拒绝的 MCP 请求。

调整结果上限时应同时评估目标数据库负载、后端堆内存和 agent 上下文大小。

### 闲置与断线恢复

MCP 会话和目标数据库连接是两层独立状态。MCP 会话默认闲置 120 分钟后过期，MyDataDev 服务重启也会丢失会话。使用有效 Key 携带已过期或不存在的 `Mcp-Session-Id` 请求 `/mcp` 时，服务端返回 HTTP 404；客户端应丢弃旧 ID，不带会话头重新发送 `initialize`，再使用新会话调用工具。这遵循 [Streamable HTTP 会话管理规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)，支持这一流程的客户端可以重新建立会话，无需重启 MyDataDev。旧版本在此处返回 403，可能让客户端把闲置过期误判为权限失败。

目标数据库暂时停止或网络断开时，连接池会在后续借用连接时尝试连接；数据库恢复后可再次调用工具。正在执行的请求仍可能失败，服务端不会自动重放 SQL。写操作断线后应先核对结果，后台脚本先用 `db_list_script_tasks` / `db_script_status` 确认状态，避免重复执行已提交的修改。

“命令行窗口还开着，但 AI 调不到”不足以判定数据库宕机，可按实际响应区分：

| 响应 | 排查方向 |
| --- | --- |
| 携带旧会话 ID 返回 HTTP 404 | 重新初始化 MCP 会话；客户端未自动处理时，重新连接该 MCP 服务 |
| HTTP 401 | 检查 Key 是否正确、已轮换或 Agent 已停用 |
| HTTP 403 | 检查 Origin 白名单，以及是否混用了其他 Agent 的会话 ID |
| 工具错误 `[CONNECTION_UNAVAILABLE]` | 检查数据库、网络、SSH 隧道或连接池容量，恢复后再次调用 |
| HTTP 连接拒绝或超时 | 检查 MyDataDev 进程、监听端口及代理/网络 |

定位现场问题时保留 HTTP 状态码、工具错误、`X-Request-ID` 和同一时刻的服务日志；窗口仍然存在不代表服务仍能响应。

### 请求关联与超时诊断

HTTP 响应头 `X-Request-ID` 与审计事件中的 `requestId` 对应。客户端也可在发出请求前指定该头（1–120 个字母、数字或 `._:-`），这样即使未收到响应，也能按预先保存的编号查服务端审计。工具错误正文附带 `[requestId=...]`；连接、SQL、超时及内部错误的服务日志会记录相同编号、错误码和原始异常堆栈，包括收尾阶段的 suppressed 异常。成功工具正文没有单独的 `requestId` 字段，应保存响应头。

该编号与 MCP JSON-RPC 请求 `id`、后台脚本 `taskId` 是不同标识；发送 `notifications/cancelled` 时仍使用原 JSON-RPC 请求 `id`。客户端应同时保留工具名、连接 ID、SQL 指纹、分页 offset、原始错误 envelope 和这些关联编号，不能只包装成通用 `ReadError`。

查询超时配置通过 JDBC Statement 下发，不是整个 HTTP 请求的总时限。借连接、租户/Schema 初始化、结果与 CLOB 读取、回滚归还、响应发送还受连接池和驱动各自行为影响。`elapsedMs` 也不是独立的纯 SQL 执行耗时；当前工具尚未返回各阶段耗时或同步查询的可轮询最终状态。`SQL_TIMEOUT`/`CANCELLED` 回执不能确认数据库已经停止执行或完成回滚。

客户端 HTTP 读取超时或直接断开不等于发送了 `notifications/cancelled`；当前实现没有通用的断流即取消保证。需要取消在途请求时，应显式发送取消通知。取消回调已排队但对应语句/请求先结束时，回调会失效；已开始的 JDBC 取消退出后才允许关闭语句并归还连接，实际等待仍依赖驱动返回，不是数据库端取消成功的确认。

只读查询回滚或连接状态恢复失败时，服务端会记录带请求编号的 `ROLLBACK`/`RESTORE_STATE` 阶段日志，并安排淘汰该次借出的连接。回滚失败后不会继续恢复自动提交；查询本身的错误不会被后续清理错误覆盖，清理错误也不会被当作成功。

脚本模式、结束策略、失败和取消语义见 [SQL 脚本与文件事务](sql-script-transactions.md)。

### Antigravity IDE 配置格式

在客户端配置页选择 **Antigravity IDE**，将生成的 `mydatadev` 条目合并到实际配置的 `mcpServers` 中：

```json
{
  "mcpServers": {
    "mydatadev": {
      "serverUrl": "https://your-mydatadev-host/mcp",
      "headers": { "Authorization": "Bearer <AGENT_API_KEY>" }
    }
  }
}
```

替换服务地址和 Agent API Key 后保存并重新加载 MCP Servers。Gemini CLI 的 `httpUrl`、`trust` 不适用于 Antigravity；两者应分别使用各自模板。配置路径可能随客户端版本变化，以 IDE 的 **View raw config** 打开的文件为准。参见 [Antigravity 官方 MCP 文档](https://antigravity.google/docs/mcp)。

### 中断与长任务恢复

长时间运行的存储过程或匿名块应使用 `db_execute_script`，保留返回的 `taskId`，通过 `db_script_status` 查询进度；不要用反复执行写语句来探测是否完成。`executedCount == statementTotal` 即脚本的每条语句都已执行。

Oracle / OceanBase Oracle 脚本里的 PL/SQL 单元（`DECLARE`/`BEGIN` 匿名块，`CREATE PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE`）以 `END;` 加独占一行的 `/` 结束，文件最后一个单元可以省略 `/`。两个单元之间漏写 `/` 时，后一条 `CREATE` 会被并进前一条语句发给数据库，部分库只建出前一个对象却返回成功；因此遇到 `END;` 之后紧跟 `CREATE` 的情况，整份脚本会在执行前被拒绝并指出行号。匿名块里的 `EXCEPTION WHEN OTHERS THEN NULL` 会吞掉块内的任何错误，工具看到的是一次成功的执行 —— 排查「执行了但库里没变化」时先检查这一点。

同一连接的脚本任务数达到上限（MCP 设置里的「同连接脚本并发上限」，默认 1）时提交会被拒绝，脚本不会执行；报错里会列出当前 Agent 在该连接上进行中的 `taskId`，没有列出时说明占用者是其他 Agent 或界面任务。

Agent 中断或丢失任务 ID 后，先调用 `db_list_script_tasks(connectionId, activeOnly=false)` 查看当前身份的历史任务，再决定是否续跑。该工具的 `page` 从 0 开始，`pageSize` 默认 20，最大 100。任务列表、查询和取消均受当前 Agent 身份及连接授权约束。

服务端处理 `notifications/cancelled` 时，只取消同一 Agent、同一 MCP 会话、同一请求 ID 的在途调用。同步查询/写操作会请求 JDBC 取消；取消通知到达脚本提交期间时也会请求取消该脚本。后台任务已经成功提交并返回后，停止 Agent 不会自动撤销任务，需显式调用 `db_cancel_script(taskId)`。取消接口快速返回请求结果，任务要等数据库调用实际退出、回滚收尾后才释放并发名额；驱动响应取消的速度因数据库而异。超时或断线不代表已提交的修改被回滚，不应自动重跑写操作。
