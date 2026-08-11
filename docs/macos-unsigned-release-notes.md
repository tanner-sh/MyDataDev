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
