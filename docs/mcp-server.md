# MCP Server 使用说明

MyDataDev 后端内置一个只读 MCP Server，使用 Streamable HTTP 协议向其他 AI agent 提供数据库发现和查询能力。端点默认为：

```text
http://<backend-host>:8080/mcp
```

MCP 默认关闭，不影响现有 Web 和 REST API。启用 MCP 只会保护 `/mcp`；现有 `/api` 的网络访问控制方式保持不变。

## 安全模型

一次 MCP 数据库访问必须同时通过以下检查：

1. 请求携带有效的 agent API Key。
2. 目标连接 ID 位于该 agent 的 `connection-ids` 白名单中。
3. MyDataDev 中的连接已设置 `readonly: true`。
4. 生产连接还要求该 agent 设置 `allow-production: true`。

SQL 工具只接受分类为查询的单条语句，会拒绝 DML、DDL、存储过程调用、锁、会话修改、多语句和已知的有副作用查询。执行时还会设置 JDBC 只读提示、关闭自动提交并在结束时回滚。

这些应用层检查不能替代数据库权限。提供给 MCP 的连接必须使用数据库侧只读账户；不要让仅靠 MyDataDev `readonly` 标记的高权限账户进入 agent 白名单。

API Key 会出现在每次请求的 `Authorization` 请求头中。跨主机部署时应通过反向代理提供 HTTPS，不要在不可信明文网络上传输 Key。`/mcp` 不应直接暴露到公网。

## 创建 agent API Key

凭据格式为：

```text
<agent-id>.<raw-secret>
```

配置文件只保存 `raw-secret` 的 BCrypt 哈希，不保存完整凭据。建议使用随机生成的高熵 secret，并为每个 agent 单独创建和轮换。

如果系统安装了 Apache `htpasswd`，可用下面的交互式命令生成 cost 12 的 BCrypt 哈希，避免把 secret 写进 shell 历史：

```bash
htpasswd -nBC 12 mcp-agent
```

命令会提示输入两次密码，输出格式为 `mcp-agent:$2y$...`。配置 `key-hash` 时只复制冒号后的 `$2y$...` 部分。也可以使用其他支持 `$2a$`、`$2b$` 或 `$2y$` 的 BCrypt 工具。

## 服务端配置

不要把 agent Key 哈希、真实连接密码或加密密钥提交到 Git。建议在服务器创建仓库外的配置文件，例如 `/etc/mydatadev/mcp-secrets.yml`：

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

然后启用并启动后端：

```bash
cd backend
MCP_ENABLED=true \
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/mydatadev/mcp-secrets.yml \
DB_ADMIN_CRYPTO_KEY='<deployment-crypto-key>' \
mvn spring-boot:run
```

启用 MCP 但没有配置 agent、agent ID 重复、哈希无效或连接 ID 非正数时，后端会拒绝启动。

如果浏览器 MCP 客户端需要访问端点，必须将准确的 Origin 加入 `allowed-origins`，例如：

```yaml
app:
  mcp:
    allowed-origins:
      - https://agent-console.example.internal
```

空列表会拒绝所有携带 `Origin` 的 MCP 请求，同时允许不携带 `Origin` 的服务端/CLI 客户端。不要使用宽泛或动态反射的 Origin。

## 客户端配置

不同 MCP 客户端的字段名称略有区别，核心配置如下：

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

客户端必须在初始化和后续会话请求中都发送同一个 Bearer 凭据。服务端会把 MCP session 绑定到创建它的 agent，其他 agent 不能复用该 session ID。

## 可用工具

| 工具 | 能力 |
| --- | --- |
| `db_list_connections` | 列出当前 agent 可见的只读连接，不返回 JDBC URL、用户名或密码 |
| `db_list_namespaces` | 分页列出 Catalog 或 Schema |
| `db_search_objects` | 分页搜索表和视图 |
| `db_describe_object` | 查看列、主键、索引和外键关系 |
| `db_get_object_ddl` | 获取表或视图的可用 DDL |
| `db_browse_table` | 使用不透明 cursor 分页浏览表数据，不返回编辑 token |
| `db_query` | 执行一条只读查询 |
| `db_explain` | 获取一条查询的执行计划 |

所有工具都发布 `readOnlyHint: true` 和 `destructiveHint: false`。数据库返回的对象名、注释、DDL、数据和错误均属于不可信输入，agent 不应把这些内容当作指令执行。

## 限制与可观测性

默认限制位于 `backend/src/main/resources/application.yml` 的 `app.mcp` 下，包括：

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
