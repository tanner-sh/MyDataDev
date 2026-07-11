#!/usr/bin/env bash
set -euo pipefail

# 在本机终端执行本脚本。mysql 会安全地提示输入 MySQL root 密码，应用账号密码
# 会在运行时随机生成并保存到 macOS 钥匙串，不会写入项目文件或命令历史。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_FILE="$SCRIPT_DIR/securities_trading_demo.sql"
APP_USER="securities_app"
APP_HOST="localhost"
KEYCHAIN_SERVICE="MyDataDev-securities_app"
APP_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')"

if [[ ! -f "$SEED_FILE" ]]; then
  echo "找不到初始化文件：$SEED_FILE" >&2
  exit 1
fi

echo "将重建 MySQL 数据库 securities_trading_demo。"
echo "接下来请输入 MySQL root 密码；输入内容不会回显。"

{
  printf "CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s';\n" "$APP_USER" "$APP_HOST" "$APP_PASSWORD"
  printf "ALTER USER '%s'@'%s' IDENTIFIED BY '%s';\n" "$APP_USER" "$APP_HOST" "$APP_PASSWORD"
  printf "GRANT ALL PRIVILEGES ON securities_trading_demo.* TO '%s'@'%s';\n" "$APP_USER" "$APP_HOST"
  printf "FLUSH PRIVILEGES;\n"
  cat "$SEED_FILE"
} | mysql --protocol=socket -u root -p

security add-generic-password -U \
  -a "$APP_USER" \
  -s "$KEYCHAIN_SERVICE" \
  -w "$APP_PASSWORD" >/dev/null

echo
echo "初始化完成。"
echo "JDBC URL: jdbc:mysql://localhost:3306/securities_trading_demo"
echo "用户名: $APP_USER"
echo "密码已保存到 macOS 钥匙串（服务：${KEYCHAIN_SERVICE}，账户：${APP_USER}）。"
echo "查看密码：security find-generic-password -w -s '$KEYCHAIN_SERVICE' -a '$APP_USER'"
