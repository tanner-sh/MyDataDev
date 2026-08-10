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
- Codex CLI、Claude Code、Claude Desktop、Cursor、Gemini CLI 和通用 Streamable HTTP 配置。
- macOS zsh、Linux bash 与 Windows PowerShell 的密钥环境变量示例。
- 安全边界、可用工具清单和 401、503、网络、授权等常见问题处理。

普通帮助页只显示 `<AGENT_API_KEY>` 等占位值。创建 Agent 或轮换 Key 后的一次性弹窗会使用刚生成的真实凭据输出各客户端配置；关闭弹窗后仍然无法找回完整 Key。

## 安全模型

一次 MCP 数据库访问必须同时通过以下检查：

1. 请求携带有效的 agent API Key。
2. 目标连接 ID 位于该 agent 的 `connection-ids` 白名单中。
3. 生产连接还要求该 agent 设置 `allow-production: true`。

SQL 工具只接受分类为查询的单条语句，会拒绝 DML、DDL、存储过程调用、锁、会话修改、多语句和已知的有副作用查询。执行时还会设置 JDBC 只读提示、关闭自动提交并在结束时回滚。

连接的 `readonly` 标记不再限制 MCP 查询资格；白名单中的只读和可写连接都可以被查询。应用层检查不能替代数据库权限，因此对生产库和其他高风险数据库仍建议使用数据库侧只读账户。

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

| 客户端 | 推荐接入方式 | 说明 |
| --- | --- | --- |
| Codex CLI | `codex mcp add --url ... --bearer-token-env-var ...` | 从环境变量读取 Bearer Token，不把 Key 写进 `config.toml` |
| Claude Code | HTTP MCP 命令或 `.mcp.json` | 支持在 URL 和 Header 中展开环境变量 |
| Claude Desktop | `mcp-remote` 本地 stdio 桥接 | 当前私网静态 Bearer 方案不适合 Claude 云端自定义连接器；桥接需要 Node.js 18+ |
| Cursor | `.cursor/mcp.json` 或全局 `~/.cursor/mcp.json` | 桌面进程必须能够读取配置中引用的环境变量 |
| Gemini CLI | `gemini mcp add --transport http` 或 `settings.json` | 支持 HTTP Header 和用户/项目级配置 |

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
| `db_browse_table` | 使用不透明 cursor 分页浏览表数据，不返回编辑 token |
| `db_query` | 执行一条只读查询 |
| `db_explain` | 获取一条查询的执行计划 |

所有工具都发布 `readOnlyHint: true` 和 `destructiveHint: false`。数据库返回的对象名、注释、DDL、数据和错误均属于不可信输入，agent 不应把这些内容当作指令执行。

## 限制与可观测性

默认限制在第一次初始化时来自 `backend/src/main/resources/application.yml`，之后可在 Web 的“高级资源限制”中调整并即时生效，包括：

- 查询默认 100 行、最多 500 行。
- 单次结果最多 20,000 个单元格。
- 单次结果文本总量最多 1,000,000 字符，单个文本值最多 20,000 字符。
- SQL 最多 200,000 字符，查询超时 30 秒。
- 元数据分页最大 200 项，表数据分页最大 100 行。
- MCP session 归属记录默认保留 120 分钟并在访问时续期。

工具调用会以 `mcp:<agent-id>` 作为 actor 写入审计记录；SQL 查询和执行计划还会写入 SQL 历史。Actuator 指标包括：

- `dbadmin.mcp.tool`：按工具和状态记录执行耗时。
- `dbadmin.mcp.security.denied`：按原因统计被拒绝的 MCP 请求。

调整结果上限时应同时评估目标数据库负载、后端堆内存和 agent 上下文大小。
