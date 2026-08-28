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
export DB_ADMIN_CRYPTO_KEY='<32 位以上的强随机字符串>'
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web
```

打开 <http://localhost:8080>。`--spring.profiles.active=web` 不是可选项：它关闭 H2 控制台、启用优雅停机并写出 `./logs/mydatadev.log`，缺少它会退回面向本地开发的默认配置。

JAR 使用**当前工作目录**存放数据，请固定在一个目录里启动：

| 目录 | 内容 |
| --- | --- |
| `./data` | H2 元数据库：连接配置、加密后的密码、SQL 历史、审计、MCP Agent、任务记录。 |
| `./backups` | 备份文件与远端上传失败的暂存文件。 |
| `./sql-files` | 大 SQL 文件执行任务的上传文件。 |
| `./logs` | 应用日志。 |

`DB_ADMIN_CRYPTO_KEY` 是连接密码和文件服务凭据的加密密钥。**必须在首次启动前设置**，且此后不能更改 —— 换密钥会导致已保存的密文无法解密。Web 模式不再提供默认密钥，未设置时后端会拒绝启动。

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
Environment=DB_ADMIN_CRYPTO_KEY=<32 位以上的强随机字符串>
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
        # SQL 执行、备份与恢复都是长请求；MCP 和后台任务进度（/api/restores/operations/stream）
        # 是流式响应，proxy_buffering off 对它们是必需的，否则界面收不到实时进度。
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

前端也可以在构建期通过 `VITE_API_BASE_URL` 指向另一个后端地址，但这需要自行从源码构建前端，发行包中的资源使用默认的 `/api`。

## 安全边界

Web 模式与桌面模式的安全模型不同，部署前必须确认：

- **`/api` 没有用户认证。** 前端发送的 `X-User: admin` 只是审计标签，不是身份凭证。任何能访问端口的人都拥有全部权限，因此必须部署在可信网络中，并由外层反向代理承担身份认证（如 OIDC、Basic Auth 或 mTLS）。
- **不要把 `/api` 或 `/mcp` 直接暴露到公网。** 跨主机访问一律使用 HTTPS。
- `/mcp` 由 Agent API Key 认证，工具全部只读，但仍受连接白名单和生产连接授权约束，详见 [MCP Server 说明](mcp-server.md)。
- 应用层的生产确认与只读连接保护不能替代数据库权限，目标库仍应使用最小权限账号。
- H2 控制台在 `web` profile 下是关闭的，不要为了排查问题把它打开。

## 升级

1. 停止旧进程。
2. 备份数据目录（至少 `./data`）。
3. 换上新版本 JAR，用同一个工作目录和同一个 `DB_ADMIN_CRYPTO_KEY` 启动。

元数据库 schema 由 Flyway 在启动时自动迁移，不需要手动执行 SQL。迁移不支持回退，降级到旧版本前请从备份恢复数据目录。

## 本地构建

不使用 Release 产物时，可以自行构建同样的两个文件：

```bash
cd frontend && npm ci && cd ..
node scripts/build-web-bundle.mjs
```

产物输出到仓库根目录的 `release-assets/`。脚本会先校验 `backend/pom.xml`、`frontend/package.json`、`desktop/package.json` 三处版本号一致，再执行前端构建和 `mvn -Pweb -DskipTests clean package`，并确认前端确实被打进了 JAR 的 `static/`。
