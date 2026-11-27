# @mydatadev/cli (`mdd`)

MyDataDev 的官方命令行客户端，同时支持单次命令（适用于脚本与 CI/CD）和交互模式（终端连续查询）。

## 安装与环境要求

- 需要 **Node.js >= 22**。
- **已安装 MyDataDev Desktop 的用户无需重复安装 npm 包**：Desktop 安装包自带 `mdd`（可通过托盘菜单「安装命令行工具 mdd」添加到 PATH）。若同时安装了两者，PATH 中排在前面的版本生效。
- 在 CI 或其他机器连接团队服务时，可通过 npm 安装：

```bash
npm install -g @mydatadev/cli
```

## 快速开始

```bash
# 连接本机正在运行的 MyDataDev Desktop（无需登录）
mdd status
mdd

# 连接团队 Web 服务（在 Web「访问令牌」页面创建 PAT 后登录）
mdd login --server https://db.corp.example --profile team
mdd conn ls
mdd sql query 订单库-dev -c "select 1"
```

更多文档见项目仓库中的 `docs/cli.md`。
