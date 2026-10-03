#!/usr/bin/env bash
# 独立测试实例：只监听本机，不挂载生产数据，不需要 privileged。
set -euo pipefail
container_name="${DM_TEST_CONTAINER:-mydatadev-dm-test}"
port="${DM_TEST_PORT:-15239}"
archive="${DM_TEST_ARCHIVE:-${TMPDIR:-/tmp}/mydatadev-dm8-20241022.tar}"
image='dm8_single:dm8_20241022_rev244896_x86_rh6_64'
# Registry copy of the same official archive, pinned by manifest (see docs/dameng-enhancements.md).
registry_image='docker.io/greyhawk/dm8_single@sha256:9a2d9aecc31372c8f7c765625f36987a54ce69aa015eb3d856cfa1e29b06811b'
sha256='97cc976b618e0eb75a20c831fb4e258c74ccc574ffa3e59b187c0c9bb90f019c'
if docker container inspect "$container_name" >/dev/null 2>&1; then
  echo "容器 $container_name 已存在，请另设 DM_TEST_CONTAINER 或先清理自己的测试实例。" >&2
  exit 1
fi
use_registry=false
if [[ ! -f "$archive" ]]; then
  if curl -fL --retry 2 --connect-timeout 20 --max-time 600 -o "$archive.part" \
      https://download.dameng.com/eco/dm8/dm8_20241022_x86_rh6_64_single.tar; then
    mv "$archive.part" "$archive"
  else
    rm -f -- "$archive.part"
    echo '官方归档下载失败，改用已核对官方内容的固定摘要镜像。' >&2
    use_registry=true
  fi
fi
if [[ "$use_registry" == true ]]; then
  docker pull --platform linux/amd64 "$registry_image"
  image="$registry_image"
else
  # A corrupt archive is an integrity failure, not a reason to switch sources.
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$sha256" "$archive" | sha256sum -c -
  else
    printf '%s  %s\n' "$sha256" "$archive" | shasum -a 256 -c -
  fi
  docker load -i "$archive"
fi
docker run -d --platform linux/amd64 --name "$container_name" \
  -p "127.0.0.1:$port:5236" \
  -e PAGE_SIZE=16 -e EXTENT_SIZE=16 -e LOG_SIZE=256 \
  -e UNICODE_FLAG=1 -e CASE_SENSITIVE=Y \
  -e SYSDBA_PWD='MyDataDev_Test2026!' -e LD_LIBRARY_PATH=/opt/dmdbms/bin "$image"
for ((attempt=0; attempt<90; attempt++)); do
  if docker logs "$container_name" 2>&1 | grep -q 'SYSTEM IS READY'; then
    echo "达梦测试实例已就绪：jdbc:dm://127.0.0.1:${port}（SYSDBA，测试专用密码）"
    exit 0
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != 'true' ]]; then
    break
  fi
  sleep 2
done
docker logs --tail 100 "$container_name"
echo '达梦启动失败；保留容器以便查看日志。' >&2
exit 1
