# Web 发行包部署说明

Web 模式的发行产物有两个，随桌面安装包一起发布在同一个 GitHub Release 中：

| 文件 | 用途 |
| --- | --- |
| `MyDataDev-<version>-web.jar` | 内置前端的 Spring Boot 可执行 JAR，一个进程同时提供 UI、`/api` 和 `/mcp`。 |
| `MyDataDev-<version>-frontend-dist.tar.gz` | 前端静态资源，供 Nginx 等反向代理独立托管前端时使用。 |

绝大多数部署只需要 JAR。只有在必须由现有 Web 服务器托管静态资源时，才需要第二个包。

## 运行环境

- Java 17 或更高版本（JRE 即可，发行 JAR 不像桌面版那样自带 Runtime）。
- 原生备份与恢复还要求 `mysqldump` / `mysql`、`expdp` / `impdp` 等工具安装在运行后端的机器上。

## 单 JAR 部署

```bash
export DB_ADMIN_WEB_PASSWORD='<至少 12 位的强密码>'
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web
```

打开 <http://localhost:8080>。`--spring.profiles.active=web` 不是可选项：它启用 Web 登录、关闭 H2 控制台、启用优雅停机并写出 `./logs/mydatadev.log`，缺少它会退回面向本地开发的默认配置。

JAR 使用**当前工作目录**存放数据，请固定在一个目录里启动：

| 目录 | 内容 |
| --- | --- |
| `./data` | H2 元数据库：Web 用户、连接配置、加密后的密码、SQL 历史、审计、MCP Agent、任务记录。 |
| `./secrets` | 系统自动生成的主密钥；必须限制为服务账号可读，并与元数据库一起安全备份。 |
| `./backups` | 备份文件与远端上传失败的暂存文件。 |
| `./sql-files` | 大 SQL 文件执行任务的上传文件。 |
| `./logs` | 应用日志。 |

首次启动会用安全随机数生成 `./secrets/mydatadev-master.key`，以后启动自动读取同一个文件。应用会把目录和文件权限分别收紧为 `0700`、`0600`（文件系统支持 POSIX 权限时）。主密钥不是普通配置：丢失或替换它会使已保存的数据库密码、SSH 凭据和文件服务凭据无法解密。请把它与 `data` 一同备份，但分开控制访问权限。

部署平台也可以预先生成 Secret，以只读文件挂载后通过 `--app.crypto-key-file=/run/secrets/mydatadev-master-key` 指定路径。密钥本身不应放在环境变量或命令行参数中；配置项传递的只是文件路径。`DB_ADMIN_WEB_PASSWORD` 只是首个 Web 管理员的初始化密码，用户表为空时必须至少 12 位；已有用户后再启动不依赖这个环境变量。首次登录并确认账号可用后，建议将它从运行环境移除。

常用覆盖项（全部可以用 `--key=value` 或环境变量传入）：

```bash
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web \
  --server.port=9000 \
  --app.backup.directory=/data/mydatadev/backups \
  --app.sql-file.directory=/data/mydatadev/sql-files \
  --spring.datasource.url='jdbc:h2:file:/data/mydatadev/db-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;FILE_LOCK=FS;TRACE_LEVEL_FILE=0'
```

其余可调项见 `backend/src/main/resources/application.yml` 与 README 的「配置与安全」表。

### systemd 示例

```ini
[Unit]
Description=MyDataDev
After=network-online.target

[Service]
User=mydatadev
WorkingDirectory=/opt/mydatadev
# 仅第一次初始化管理员需要；确认可登录后从 unit 中删除。
Environment=DB_ADMIN_WEB_PASSWORD=<至少 12 位的强密码>
ExecStart=/usr/bin/java -jar /opt/mydatadev/MyDataDev-web.jar --spring.profiles.active=web
Restart=on-failure
# 大 SQL 文件与恢复任务可能长时间运行，停机时给足退出时间。
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
```

## 前后端分离部署

把 `MyDataDev-<version>-frontend-dist.tar.gz` 解压到 Web 服务器的站点目录，后端仍用同一个 JAR 启动（内置的静态资源不会被访问到）。前端默认请求同源的 `/api` 和 `/mcp`，因此**推荐让 Nginx 反代这两个前缀**，这样不涉及跨域：

```nginx
server {
    listen 443 ssl;
    server_name db.example.com;

    root /var/www/mydatadev;
    index index.html;

    location / {
        try_files $uri /index.html;
    }

    location /assets/ {
        # 文件名带 Vite 指纹，可以长期缓存。
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location ~ ^/(api|mcp)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # SQL 执行、备份与恢复都是长请求；MCP、后台任务进度（/api/restores/operations/stream）
        # 与 AI 回答（/api/ai/sql/*/stream）是流式响应，proxy_buffering off 对它们是必需的，
        # 否则界面收不到实时进度，AI 回答会一直空着直到整段生成完。
        proxy_read_timeout 7200s;
        proxy_send_timeout 7200s;
        proxy_buffering off;
        # 恢复与大 SQL 文件上传默认允许 20GB。
        client_max_body_size 20g;
    }
}
```

只有在前端与后端**不同源**时才需要放开 CORS，此时给后端设置真实来源，不要用通配端口：

```bash
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://db.example.com
```

启用认证（`APP_AUTH_MODE` 不为 `DISABLED`）时，主机名整个通配的写法会让进程启动失败：`*`、`http://*`、`https://*`、`*://*`。跨域请求会带上会话 Cookie，而 `/api/auth/status` 的响应里就有 CSRF 令牌，通配来源等于把令牌和全部写接口交给任意网页。子域通配（`https://*.corp.example`）是正常部署需求，不受限制。

前端也可以在构建期通过 `VITE_API_BASE_URL` 指向另一个后端地址，但这需要自行从源码构建前端，发行包中的资源使用默认的 `/api`。

## 连接配置迁移

桌面版与 Web 版使用完全独立的元数据库和加密密钥，连接不互通。需要搬迁时，在「管理 → 连接管理 → 导出 / 导入」里把连接打成一个归档文件：

- 归档用**你设置的口令**加密（PBKDF2-HMAC-SHA256 派生密钥 + AES-256-GCM），不使用本机托管的主密钥 —— 目标端的密钥必然不同，搬密文是解不开的。
- 因此归档内部是明文密码与 SSH 密钥材料，全靠口令保护：**口令必须与文件分开传递**，口令丢失后文件无法恢复。
- 导入时密码会用目标端自己的密钥重新加密入库。
- 仅管理员可用；导出与导入在两端都写审计，并按连接归属，可在审计日志里按连接筛出来。
- 同名连接默认跳过，不会覆盖本机已有配置；也可以选择改名后新建。

归档只包含连接。用户、MCP Agent、备份任务与审计不在其中：它们要么绑定本机路径，要么搬过去等于把整套凭据体系复制一份。

## 安全边界

Web 模式与桌面模式的安全模型不同，部署前必须确认：

- Web 包默认启用内置多用户认证，使用服务端 Session 和 CSRF 保护；审计用户由服务端登录主体写入，不信任浏览器的 `X-User`。`DB_ADMIN_WEB_USERNAME` 只决定空用户库中首个管理员的用户名。
- `ADMIN` 可管理账号、用户组和连接访问策略；`OPERATOR` 只能使用被授权的连接与功能。停用用户、修改角色/用户名或重置密码会使该用户已有会话在下一次请求时失效。
- HTTPS 终止在反向代理时设置 `APP_AUTH_COOKIE_SECURE=true`。只有外层已经提供可靠身份认证且网络完全可信时，才可显式设置 `APP_AUTH_MODE=DISABLED`。
- **不要把 `/api` 或 `/mcp` 直接暴露到公网。** 跨主机访问一律使用 HTTPS。
- `/mcp` 由 Agent API Key 认证，工具全部只读，但仍受连接白名单和生产连接授权约束，详见 [MCP Server 说明](mcp-server.md)。
- 应用层的生产确认与只读连接保护不能替代数据库权限，目标库仍应使用最小权限账号。
- H2 控制台在 `web` profile 下是关闭的，不要为了排查问题把它打开。

### 内置账号与 OIDC SSO

首个管理员登录后，在“管理 → 用户与权限”中创建每个人的独立账号，不要共享管理员密码。操作时的审计人来自已认证会话，因此可以区分真实用户。

在“管理 → 访问控制”中可创建用户组，并为每条连接选择共享或受限模式。受限模式可分别授予查看元数据、执行查询、修改数据、执行 DDL、导出、备份恢复和连接管理权限；也可以用“只读分析、开发人员、运维人员、连接管理员”模板快速填充。连接管理权限隐含该连接的全部权限。Web 用户新建的连接默认是受限模式且归创建者所有，管理员始终具有全部连接权限。

SQL 片段支持“仅自己”和“团队共享”；升级前已有片段统一保留为团队共享，不会猜测或改写所有者。SQL 历史新增“我的历史 / 连接全部”范围，升级后的记录使用稳定用户 ID 归属，旧历史仍保留在“连接全部”中。

审计日志记录登录成功/失败、权限拒绝、表数据浏览、导出和备份下载等事件，并附带实际 TCP 对端、单独保存的 `X-Forwarded-For`、User-Agent 与请求 ID。`X-Forwarded-For` 仅用于排查，不作为可信身份或授权依据；反向代理可传入合法的 `X-Request-ID` 便于串联代理日志。

发行包内置通用 OIDC Authorization Code 登录。身份使用 `issuer + sub` 稳定关联，不会因为用户名声明变化创建重复账号；与本地账号同名时不会自动合并，而是给 OIDC 用户追加稳定后缀，避免错误绑定。示例：

```bash
export APP_AUTH_MODE=OIDC
export APP_AUTH_OIDC_ISSUER_URI='https://id.example.com/realms/company'
export APP_AUTH_OIDC_CLIENT_ID='mydatadev'
export APP_AUTH_OIDC_CLIENT_SECRET='<client-secret>'
export APP_AUTH_OIDC_ADMIN_GROUPS='db-admins'
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web \
  --app.auth.oidc.group-mappings.finance='财务组' \
  --app.auth.oidc.group-mappings.dba='数据库运维组'
```

在身份平台登记回调地址 `https://<MyDataDev 地址>/login/oauth2/code/mydatadev`。`groups` 声明中命中 `admin-groups` 的用户映射为 `ADMIN`，其余用户为 `OPERATOR`；`group-mappings` 只替换 OIDC 自动同步的成员关系，不覆盖管理员手工分组。反向代理必须正确传递 `X-Forwarded-Proto` 和 `Host`。

`APP_AUTH_OIDC_ADMIN_GROUPS` 在 OIDC 模式下是必填的，留空会直接让进程启动失败。角色每次登录都按这个配置重算并写回账号，组为空意味着所有人都是 `OPERATOR` —— 管理、审计与 MCP 页面从此无人可进，而 SSO 账号的角色又不接受手工修改，只能改配置重启。同理，要确认身份平台真的会在 ID Token 里下发 `groups`（或按 `APP_AUTH_OIDC_GROUPS_CLAIM` 指定实际的声明名），否则交集永远为空。

用户组面板里由 SSO 同步来的成员标记为「SSO 同步」且不可增删：这类成员关系归身份提供器所有，要调整得去身份平台改组。管理员在这里删掉也不会生效，下一次登录就会同步回来。

审计事件用 SHA-256 前后串联，管理员可在审计页查看链完整性；保留期清理会推进链锚点。可选 Webhook 告警示例：

```bash
export APP_AUDIT_ALERT_ENABLED=true
export APP_AUDIT_ALERT_WEBHOOK_URL='https://security.example.com/hooks/mydatadev'
export APP_AUDIT_ALERT_SIGNING_SECRET='<shared-secret>'
```

Webhook 使用 `X-MyDataDev-Signature-256: sha256=<HMAC>` 验签。发送失败只写应用日志，不会让已经完成的数据库操作回滚。

## 升级

1. 停止旧进程。
2. 备份数据目录；已采用文件密钥的版本还要备份 `./secrets`。
3. 换上新版本 JAR，用同一个工作目录启动。

元数据库 schema 由 Flyway 在启动时自动迁移，不需要手动执行 SQL。迁移不支持回退，降级到旧版本前请从备份恢复数据目录。

### 从环境变量密钥升级

旧版本曾要求每次启动都提供 `DB_ADMIN_CRYPTO_KEY`。新版本不再读取这个环境变量；如果元数据库里已有加密凭据，应用也不会擅自生成新密钥。首次启动新版本前，在可交互终端中执行一次：

```bash
java -jar MyDataDev-<version>-web.jar crypto-key adopt
```

命令会隐藏输入并要求重复确认原来的密钥，只把它写入 `./secrets/mydatadev-master.key.pending`。随后正常启动：

```bash
unset DB_ADMIN_CRYPTO_KEY
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web
```

启动阶段会先用候选密钥逐条验证已有数据库密码、SSH 凭据和文件服务凭据。全部能解密后才原子提升为正式主密钥；任何一条验证失败都会拒绝启动，保留原数据和候选文件，修正原密钥后重新执行接管命令即可。这个过程沿用原密钥，不会重写历史密文。

如果旧库从未保存过任何加密凭据，可以直接正常启动，由系统自动生成新主密钥。不要在应用启动后手工修改主密钥文件：正在运行的进程不会热加载，重启后又会因历史密文校验失败而拒绝启动。

引入多用户的 V9 迁移只新建 `app_user` 表。V10 新建用户组、连接策略和授权表，并为所有升级前已存在的连接写入 `SHARED` 策略，同时给审计表增加请求上下文字段。两次迁移都不会修改或删除原有连接、加密密码、SQL 历史、审计内容、备份任务、备份文件或目标业务数据库数据；已有审计记录的新字段保持为空。升级后第一次启动会在用户表为空时插入一个初始管理员。

## 本地构建

不使用 Release 产物时，可以自行构建同样的两个文件：

```bash
cd frontend && npm ci && cd ..
node scripts/build-web-bundle.mjs
```

产物输出到仓库根目录的 `release-assets/`。脚本会先校验 `backend/pom.xml`、`frontend/package.json`、`desktop/package.json` 三处版本号一致，再执行前端构建和 `mvn -Pweb -DskipTests clean package`，并确认前端确实被打进了 JAR 的 `static/`。
