#!/usr/bin/env bash
# Only for the disposable container created by start-dameng-test.sh.
set -euo pipefail
container_name="${DM_TEST_CONTAINER:-mydatadev-dm-test}"
mode="${DM_TEST_MODE:-0}"
[[ "$mode" == 0 || "$mode" == 2 ]] || { echo 'DM_TEST_MODE 仅支持 0 或 2' >&2; exit 1; }
output=$(docker exec -i "$container_name" /opt/dmdbms/bin/disql 'SYSDBA/"MyDataDev_Test2026!"' <<SQL
CREATE USER MYDATADEV_TEST IDENTIFIED BY "MyDataDev_Test2026!";
GRANT RESOURCE TO MYDATADEV_TEST;
SP_SET_PARA_VALUE(2, 'COMPATIBLE_MODE', $mode);
EXIT;
SQL
)
printf '%s\n' "$output"
if printf '%s' "$output" | grep -Eq '错误|Error|error'; then exit 1; fi
if [[ "$mode" == 2 ]]; then docker restart "$container_name" >/dev/null; fi
for ((attempt=0; attempt<90; attempt++)); do
  output=$(docker exec -i "$container_name" /opt/dmdbms/bin/disql 'SYSDBA/"MyDataDev_Test2026!"' <<SQL 2>&1 || true
SELECT 'READY_MODE_$mode' FROM DUAL WHERE SF_GET_PARA_VALUE(1, 'COMPATIBLE_MODE')=$mode;
EXIT;
SQL
)
  if printf '%s' "$output" | grep -Eq "^[[:space:]]*1[[:space:]]+READY_MODE_$mode"; then exit 0; fi
  sleep 2
done
echo '达梦普通账号或兼容模式未就绪' >&2
exit 1
