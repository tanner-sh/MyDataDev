# MyDataDev CLI（`mdd`）使用说明

`mdd` 是与 Web 和 Desktop 同层的命令行入口，支持**交互模式**（终端连续查询）和**单次命令**（脚本与 CI/CD）。CLI 本身只是轻量的 HTTP 客户端，数据库驱动、凭据加密、连接授权、生产确认和审计链都保留在后端服务中。

## 一、安装

### 1. 已安装 MyDataDev Desktop（推荐本机开发者）

Desktop 安装包已内置 `mdd`（复用 Electron 自带的 Node 运行时，无需额外安装 Node 或 npm 包）：

- **macOS**：点击托盘菜单 **安装命令行工具 mdd**（软链到 `/usr/local/bin/mdd`，未授权时退回 `~/.local/bin/mdd`）。
- **Windows**：安装或升级时自动写入稳定的中转脚本 `%LOCALAPPDATA%\MyDataDev\bin\mdd.cmd` 并加入用户级 `PATH`（升级版本号不会导致 `PATH` 失效）；也可通过托盘菜单手动安装。安装后请重新打开终端。
- **Linux**：点击托盘菜单 **安装命令行工具 mdd**（软链到 `~/.local/bin/mdd`）。

> 若本机同时安装了 Desktop 内置的 `mdd` 与全局 npm 包，以 `PATH` 中排在前面的版本为准。

### 2. 通过 npm 安装（CI 或连接团队服务）

要求 **Node.js >= 22**：

```bash
npm install -g @mydatadev/cli
```

无法访问公共 npm 的内网环境，可从 GitHub Release 下载 `MyDataDev-<version>-cli.tgz` 离线安装：

```bash
npm install -g ./MyDataDev-1.0.0-cli.tgz
```

---

## 二、实例与凭据绑定

`mdd` 把「实例地址 + 凭据（PAT）+ 网关请求头 + TLS/mTLS 证书」作为**不可拆分的一组**，绝不会把某个实例的令牌发给另一个实例：

解析优先级（命中后不可达直接退出码 `5`，不回退到下一级）：

1. `--profile <name>`：使用该 profile 的完整配置。
2. `--server <url>`：临时实例，仅在同时设置 `MDD_TOKEN` 时使用该令牌，不继承任何 profile 的凭据。
3. 环境变量 `MDD_SERVER`（与 `MDD_TOKEN` 成对使用；单独设置 `MDD_TOKEN` 而未指定服务端会直接报错退出码 `2`）。
4. 配置文件中的默认 profile。
5. 本机运行中的 MyDataDev Desktop（读取 `<desktopHome>/runtime/cli-endpoint.json` 并校验进程存活与 `mode=desktop`）。

如果本机 Desktop 未启动且未配置团队 profile，`mdd` 会提示先启动 MyDataDev 并返回退出码 `5`。

CLI 只跟随同源 HTTP 重定向。使用同名 profile 登录新地址时，需要为新地址重新配置网关认证和 TLS。
已同时设置 `MDD_SERVER` 和 `MDD_TOKEN` 时，使用指向另一实例的 `--server` 会报错。

---

## 三、登录与个人访问令牌（PAT）

### 1. 创建令牌（在 Web 端操作）

登录 MyDataDev Web 后，点击顶栏右上角钥匙图标（**个人访问令牌**）创建令牌：

- 令牌格式为 `mdd_pat_<id>_<secret>`，明文**仅展示一次**。
- 有效期默认 90 天，最长 365 天，不支持永不过期。
- **连接白名单（默认开启）**：建议选择「仅指定连接」。有效权限取「账号当前权限」与「白名单」的**交集** —— 即使系统里存在「共享」连接，白名单之外的连接也无法访问。
- 令牌不能调用 `/api/admin/**`、`/api/audit/**`、`/api/mcp/**` 等全局管理接口，也不能再签发新令牌。
- 账号修改密码、角色变更或被停用时，旧令牌立即失效。
- **SSO / OIDC 边界说明**：OIDC 账号的角色与分组在用户通过 SSO 登录时同步；如 IdP 一侧刚移除某人权限并需要立刻生效，请由管理员在「用户管理 → CLI 访问令牌」或个人令牌页直接吊销。

### 2. 登录与 profile 管理

```bash
# 交互粘贴令牌（输入隐藏，不留命令历史）
mdd login --server https://db.corp.example --profile team

# 从标准输入传入令牌
printf '%s' "$TOKEN" | mdd login --server https://db.corp.example --profile team --token-stdin

# 查看与切换 profile
mdd status
mdd profile ls
mdd profile use local     # 切回默认连接本机 Desktop
mdd profile rm team
mdd logout --profile team
```

### 3. Web 未启用用户管理（`APP_AUTH_MODE=DISABLED`）

当服务端显式关闭认证时，`mdd login --server` 会自动探测并跳过令牌步骤（永远不发送 PAT），按与 Web 端一致的默认身份访问，并在 stderr 打印提醒。

若外层有反向代理或网关认证，可在对应 profile 上配置网关请求头（值支持 `env:环境变量名`）、Basic Auth 或自定义 CA / mTLS 客户端证书：

```bash
mdd profile set lab --header "Authorization=env:PROXY_TOKEN"
mdd profile set lab --ca ./corp-ca.pem --cert ./client.pem --key ./client.key
```

---

## 四、命令参考

全局参数：

- `--profile <name>` / `--server <url>`
- `--format table|json|ndjson|csv`（stdout 非终端时默认 `json`）
- `--timeout <seconds>`（默认 60）
- `-q, --quiet`、`--no-color`

常用命令：

```bash
# 交互模式（终端中直接输入 mdd 即可进入）
mdd
mdd repl

# 实例状态与连接
mdd status
mdd conn ls
mdd conn info 订单库-dev
mdd conn test 订单库-dev

# 只读查询（服务端强制只读范围，拒绝任何写入/DDL）
mdd sql query 订单库-dev -c "select id, status from orders limit 5"
mdd sql query 订单库-dev -f ./report.sql --format json
cat ./report.sql | mdd sql query 订单库-dev

# 写操作与执行计划
mdd sql exec 订单库-dev -c "update orders set status='CLOSED' where id=1"
mdd sql exec 订单库-dev -c "delete from temp_logs" --allow-unbounded-write
mdd sql exec 订单库-prod -f fix.sql --confirm-production 订单库-prod
mdd sql explain 订单库-dev -c "select * from orders where user_id = 42"

# 手动事务（将 begin 返回的 id 填入 TX_ID）
mdd sql tx begin 订单库-dev
mdd sql tx status 订单库-dev
mdd sql tx exec "$TX_ID" -c "update orders set status='CLOSED' where id=1"
mdd sql tx rollback "$TX_ID"

# 后台脚本（将 submit 返回的 id 填入 TASK_ID）
mdd sql script submit 订单库-dev -f deploy.sql
mdd sql script status "$TASK_ID"
mdd sql script cancel "$TASK_ID"

# 元数据浏览
mdd meta namespaces 订单库-dev
mdd meta objects 订单库-dev --schema public --type table
mdd meta objects 订单库-dev --schema public --type table --page 1 --page-size 50
mdd meta describe 订单库-dev orders
mdd meta ddl 订单库-dev orders > orders.sql
mdd meta search 订单库-dev user_id

# 导出（--format 指定格式，-o 指定输出文件）
mdd export 订单库-dev --table orders --format csv --bom -o orders.csv
mdd export 订单库-dev -c "select * from orders" --format json > orders.json
mdd export 订单库-dev --table orders --format xlsx -o orders.xlsx
mdd export 订单库-dev -c "select id, status from orders" --format sql --target-table orders -o orders.sql

# 调用当前身份有权访问的业务 API（仅接受 /api/ 相对路径）
mdd api GET /api/backups
mdd api POST /api/sql/format -f sql="select * from t where a=1"
mdd api POST /api/schema-diff --data @diff-request.json
```

> 说明：`mdd api` 用于调用当前身份有权访问的 JSON/文本类业务 API，不包含需要 Web 会话才能调用的管理/令牌接口，也不承诺覆盖流式上传或 SSE。

`meta objects --page` 从 0 开始，搭配 `--type` 时先按类型筛选再分页。`meta ddl` 默认输出可重定向到文件的 SQL 文本，可用 `--format json` 获取结构化结果。

连接参数中的纯数字按连接 ID 解析。用 `name:123` 可按名称选择名为 `123` 的连接。REPL 按已选连接的 ID 执行 SQL 和元数据命令。

`mdd status` 在已配置的 PAT 验证失败时返回退出码 `3`。`mdd api` 保留服务端响应中的数值文本，`-f` 支持精确传入大整数及小数。

`sql exec -c` 执行单条 SQL。`sql exec -f` 提交整个文件，由服务端按数据库方言解析并顺序执行。普通语句按自动提交模式执行，遇错停止，已提交的语句会保留。同步文件只有一个成功结果时沿用单条 SQL 的输出格式。多个结果在 JSON 输出中包含 `status`、`executedCount` 和 `results`。脚本失败时退出码为 `1`。可以用 `--delimiter` 指定自定义分隔符，或用 `--allow-large-script` 确认同步运行达到提醒阈值的脚本。

`sql query` 和 `sql exec` 请求超时时会尝试取消服务端执行，并提示是否收到服务端确认。重试写入前请核对执行结果。查询结果截断时，CSV 和 NDJSON 会在 stderr 提示。NDJSON 会为重复列名添加数字后缀，并保留原有列名。

`sql tx exec`、`sql tx commit` 和 `sql tx rollback` 使用同一事务 ID，并须连接同一实例、使用同一账号。手动事务支持查询和普通 DML。超过服务端空闲时限后，事务会自动回滚。

`sql script submit` 支持文件或 SQL 文本，提交成功后返回后台任务 ID。通过 `sql script status` 的 `status` 和 `successCount` 核对结果。默认逐条提交，取消时已提交的语句会保留。Oracle 和 OceanBase Oracle 支持 `--transaction-mode script`，可用 `--end-of-file commit` 指定正常结束时提交剩余事务，默认回滚。后台脚本中的对象名按连接的默认 Schema 解析，可以用限定名指定其他 Schema。Oracle DDL 的隐式提交仍按数据库行为处理。

将查询结果导出为 SQL 时，如果 JDBC 无法提供唯一来源表，可用 `--target-table <table>` 指定 `INSERT` 目标。可使用一到三段限定名。

使用 `-o` 时，只有响应格式与所选格式匹配且下载成功后才替换文件。若输出路径为符号链接，CLI 会更新链接指向的文件并保留链接。达到服务器导出上限时，CLI 会在 stderr 提示结果已截断，退出码仍为 `0`。

---

## 五、退出码约定

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功 |
| `1` | 执行失败（SQL 错误、`conn test` 返回 `ok:false`、缺少生产确认、后端 5xx 等） |
| `2` | 参数或用法错误（如仅设 `MDD_TOKEN`、缺少 `--allow-unbounded-write`、非法路径） |
| `3` | 未认证或权限不足（HTTP 401/403，包括令牌过期、已吊销、超出白名单） |
| `4` | 预留：差异或漂移检测命中 |
| `5` | 目标实例不可达，或本机 Desktop 未启动 |
| `6` | CLI 与后端 `apiVersion` 不兼容 |
| `130` | 用户中断（`sql query` 和 `sql exec` 会同时通知后端取消当前执行） |

结果数据写入 `stdout`，提示与错误写入 `stderr`；`--format json` 下错误也以 JSON 输出到 `stderr`。

---

## 六、CI/CD 示例

```yaml
- name: Install MyDataDev CLI
  run: npm install -g @mydatadev/cli

- name: Run database verification query
  env:
    MDD_SERVER: https://db.corp.example
    MDD_TOKEN: ${{ secrets.MDD_CI_TOKEN }}
  run: |
    mdd sql query 数仓-staging -f checks/verify.sql --format json > verify.json
    mdd export 数仓-staging -c "select * from daily_summary" --format csv -o summary.csv
```

## 七、当前限制

- 本期不支持离线 / 嵌入式运行：CI 中只能操作团队服务上**已登记且服务端网络可达**的数据库连接，无法直接访问 CI runner 本地临时启动的容器数据库。
- 交互模式本期提供最小可用集（`conn`、`meta`、`sql`、直接输入以 `;` 结尾的只读 SQL、`exit`）；多行编辑、历史持久化与补全将在后续版本提供。
