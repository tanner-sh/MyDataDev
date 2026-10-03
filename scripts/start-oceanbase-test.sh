#!/usr/bin/env bash
set -euo pipefail
container_name="${OB_TEST_CONTAINER:-mydatadev-ob-test}"
port="${OB_TEST_PORT:-12881}"
if docker container inspect "$container_name" >/dev/null 2>&1; then
  echo "测试容器已存在：$container_name" >&2
  exit 1
fi
docker run -d --name "$container_name" -p "127.0.0.1:${port}:2881" \
  -e MODE=mini -e OB_TENANT_PASSWORD='MyDataDev_Test2026!' \
  oceanbase/oceanbase-ce:4.3.5-lts@sha256:31086a6900c21c479c2bcd942b6a28c53b17a51f4e9b9eb8eafcc596adfcd2e3
for ((attempt=0; attempt<150; attempt++)); do
  if docker logs "$container_name" 2>&1 | grep -q 'boot success!'; then
    docker exec "$container_name" obclient -h127.0.0.1 -P2881 -uroot@test '-pMyDataDev_Test2026!' \
      -e 'CREATE DATABASE IF NOT EXISTS mydatadev_test'
    echo "OceanBase 测试租户已就绪：jdbc:oceanbase://127.0.0.1:${port}/mydatadev_test"
    exit 0
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]]; then break; fi
  sleep 2
done
docker logs --tail 80 "$container_name"
exit 1
