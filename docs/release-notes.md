<!--
  发行说明里跨版本不变的部分。每个版本自己的「更新说明」由 scripts/release-notes.mjs
  从 CHANGELOG.md 里抽出来拼在前面 —— 之前这里写死了一节版本说明，结果 0.5.0 发出去时
  还挂着 0.4.0 的内容。同一份信息只在 CHANGELOG 维护一次。
-->

## 发行包说明

| 产物 | 适用场景 |
| --- | --- |
| `MyDataDev-<version>-macos-arm64.dmg` / `-macos-x64.dmg` | macOS 桌面版，无需安装 Java。 |
| `MyDataDev-<version>-windows-x64-Setup.exe` | Windows 桌面版。 |
| `MyDataDev-<version>-linux-x64.deb` / `.rpm` | Linux 桌面版。 |
| `MyDataDev-<version>-web.jar` | Web 服务端，内置前端，需要 Java 17+。 |
| `MyDataDev-<version>-frontend-dist.tar.gz` | 前端静态资源，仅前后端分离部署需要。 |

请使用 `SHA256SUMS.txt` 校验下载文件。

## Web 服务端快速启动

```bash
export DB_ADMIN_CRYPTO_KEY='<32 位以上的强随机字符串>'
export DB_ADMIN_WEB_PASSWORD='<至少 12 位的强密码>'
java -jar MyDataDev-<version>-web.jar --spring.profiles.active=web
```

打开 <http://localhost:8080>。数据写入启动目录下的 `data`、`backups`、`sql-files` 和 `logs`，请固定工作目录启动。

`DB_ADMIN_CRYPTO_KEY` 必须在首次启动前设置且此后不再更改，否则已保存的数据库密码无法解密。Web 模式默认启用服务端 Session 认证；`DB_ADMIN_WEB_PASSWORD` 只用于空用户库的首个管理员，必须至少 12 位。仍建议只在可信网络中通过 HTTPS 反向代理部署。完整部署、Nginx 配置与升级步骤见[Web 发行包部署说明](https://github.com/tanner-sh/MyDataDev/blob/main/docs/web-deploy.md)。

## macOS 首次启动说明

macOS 版已完成应用包级别的 ad-hoc 签名，但没有使用付费的 Apple Developer ID，也没有经过 Apple 公证。因此首次打开时 Gatekeeper 仍会要求用户手动确认，不同 macOS 版本可能显示“无法验证开发者”或“应用已损坏”。

1. 使用本 Release 的 `SHA256SUMS.txt` 校验已下载的 DMG。
2. 将 MyDataDev 拖入“应用程序”并尝试打开一次。
3. 进入“系统设置 → 隐私与安全性”，在安全性区域点击“仍要打开”。

如果系统没有显示“仍要打开”，请先确认 SHA-256 校验和一致，然后执行：

```bash
sudo xattr -dr com.apple.quarantine "/Applications/MyDataDev.app"
open "/Applications/MyDataDev.app"
```

`xattr` 命令只会移除 MyDataDev 的下载隔离标记，不会关闭系统级 Gatekeeper。
