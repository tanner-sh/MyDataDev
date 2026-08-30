# MyDataDev 桌面版开发与发行说明

桌面版使用 Electron 承载现有 React 页面，并将生产前端、Spring Boot 可执行 JAR 和精简 Java Runtime 一起打入安装包。用户安装后直接打开 MyDataDev 即可使用，不需要另外安装 Node.js、Java，也不需要分别启动前后端。

## 与 Web 模式的关系

两种运行方式会长期并存：

- Web 模式仍按 `backend` 和 `frontend` 的原有方式开发、部署，默认后端端口为 `8080`。
- 桌面模式只监听本机 `127.0.0.1:5173`，应用窗口和 MCP 共用该入口。
- 两种模式使用不同的元数据库和文件目录，连接、密码、Agent、API Key、审计、SQL 历史、备份任务与备份文件均不会自动共享。
- 桌面版同一时间只允许运行一个实例。关闭窗口会隐藏到托盘；从托盘退出或操作系统退出应用时，内置后端会先执行优雅关闭。

如果 Web 开发服务器或其他程序占用了 `5173`，桌面版不会接管或终止该进程，而会提示先释放端口。

## 桌面数据目录

正式桌面版按操作系统保存独立数据：

| 系统 | 默认目录 |
| --- | --- |
| macOS | `~/Library/Application Support/MyDataDev` |
| Windows | `%LOCALAPPDATA%\MyDataDev` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/MyDataDev` |

目录中的主要内容包括：

- `data/`：H2 元数据库，保存连接配置、Agent、审计和任务等本地状态。
- `backups/`：桌面版生成的备份文件。
- `sql-files/`：SQL 文件执行所需的本地文件。
- `logs/`：Spring Boot 和桌面启动器日志。
- `desktop-secret.json`：经操作系统安全存储加密后的桌面密钥，不包含明文密钥。

macOS 使用 Keychain，Windows 使用 DPAPI，Linux 使用可用的 Secret Service。Linux 环境退化到 Electron 的 `basic_text` 存储后，应用会显示安全提醒，并将密钥文件权限限制为当前用户可读写。如果已有密钥无法解密，应用会停止启动，不会静默生成新密钥导致已保存的数据库密码失效。

## MCP 接入

桌面应用运行时，MCP 地址固定为：

```text
http://localhost:5173/mcp
```

在桌面页面右上角进入 **MCP** 设置，创建 Agent、选择允许查询的连接并复制 API Key，再按照页面内帮助配置 Codex CLI 或其他 AI agent。由于桌面版与 Web 模式不共享数据，在 Web 模式创建的 Agent API Key 不能直接用于桌面版，反之亦然。

隐藏桌面窗口不会停止 MCP；只有明确退出 MyDataDev 才会停止 MCP 和内置后端。

## 开发环境

需要准备：

- Node.js 22 或兼容版本
- Java 17 JDK（必须包含 `jlink`）
- Maven 3.9 或兼容版本

首次安装前端和桌面端依赖：

```bash
cd frontend
npm install

cd ../desktop
npm install
```

启动桌面开发模式：

```bash
cd desktop
npm run dev
```

该命令会编排 Spring Boot、Vite 和 Electron。开发数据固定写入 `desktop/.dev-data`，不会读取正式桌面数据或 Web 模式数据；后端开发端口为 `8080`，页面仍由 `5173` 提供。

运行桌面端测试和类型检查：

```bash
cd desktop
npm test
npm run build:main
```

## 本机打包

桌面安装包必须在目标操作系统上原生构建。资源准备过程会依次构建生产前端、用 Maven `desktop` profile 生成包含静态页面的 Spring Boot JAR、生成图标，再通过当前 JDK 的 `jlink` 创建随应用分发的 Java Runtime。

只生成可运行应用目录：

```bash
cd desktop
npm run package
```

生成当前平台的安装包：

```bash
cd desktop
npm run make
```

第一版发行目标为：

| 系统 | 架构 | 安装包 |
| --- | --- | --- |
| macOS | arm64、x64 | DMG |
| Windows | x64 | Setup.exe |
| Linux | x64 | DEB、RPM |

Linux 构建机还需要 `fakeroot` 和 `rpm`。生成物位于 `desktop/out`；正式发行流水线会将安装包整理到 `desktop/release-assets`。

## GitHub Actions 发布

`.github/workflows/release.yml` 在四种原生 runner 上并行构建。每个平台都会运行后端、前端和桌面端测试，生成安装包后启动未安装的应用进行首页与 MCP 未认证烟测，再上传产物。同一个工作流还有一个 `web` 作业构建 Web 发行包，详见 [Web 部署说明](web-deploy.md)。

项目的 `backend/pom.xml`、`frontend/package.json` 和 `desktop/package.json` 版本必须一致。推送同版本标签即可创建 GitHub Release，例如当前版本为 `0.5.0` 时：

```bash
git tag v0.5.0
git push origin v0.5.0
```

标签构建成功后会发布四个平台的安装包和 Web 发行包，并附带 `SHA256SUMS.txt`。也可以从 Actions 页面手动运行工作流；手动运行只保存构建产物，不创建 Release。

## 无 Apple 开发者账号的发布策略

当前流水线不使用付费的 Apple Developer ID。macOS 应用会在打包时完成全量 ad-hoc 签名，确保 Electron、内嵌 Java Runtime 和应用资源构成一个签名结构完整的 `.app`。这会消除 `code has no resources but signature indicates they must be present` 这类产物自身的签名结构错误，但 ad-hoc 签名不能证明开发者身份，也不包含 Apple 公证。Gatekeeper 仍可能根据 macOS 版本显示“无法验证开发者”或“应用已损坏”。

- macOS 用户首次启动仍会被 Gatekeeper 阻止。确认下载文件的 SHA-256 与 Release 中的 `SHA256SUMS.txt` 一致后，先尝试打开一次，再进入“系统设置 → 隐私与安全性”点击“仍要打开”。
- 如果系统没有显示“仍要打开”，可在确认校验和后仅移除 MyDataDev 的下载隔离标记：

  ```bash
  sudo xattr -dr com.apple.quarantine "/Applications/MyDataDev.app"
  open "/Applications/MyDataDev.app"
  ```

- Windows 可能显示 SmartScreen 警告，用户需要确认来源后继续运行。
- Linux DEB/RPM 未使用发行仓库签名，应通过 Release 中的 `SHA256SUMS.txt` 校验下载文件。

每次 macOS 构建都会对 DMG 内的应用执行 `codesign --verify --deep --strict`，并检查 bundle ID、ad-hoc 签名和资源密封。当前策略不能提供“下载后直接双击”的无警告体验；如果未来需要面向非开发用户无提示分发，仍需改为 Apple Developer ID 签名和公证。
