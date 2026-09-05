#!/usr/bin/env bash
# 一键初始化客户数据（幂等，可重复执行）：
# 向 docker compose Postgres（ea-postgres，端口 5433）的 demo 租户批量灌入客户画像，
# 供客户列表分页 / DSL 筛选（tags CONTAINS、attributes.* EXISTS）/ 人群 / 灰度 / Agent 查询演示。
# 语义：确保租户存在 seed-1..seed-N（默认 N=500）这批客户；已存在跳过，重复执行不翻倍。
# 幂等：以 (tenant_id, external_id) 唯一键 ON CONFLICT DO NOTHING。
#
# 用法:
#   ./scripts/init-customers.sh                 # 默认 500 个
#   ./scripts/init-customers.sh 2000            # 指定数量
#   TENANT_ID=2 ./scripts/init-customers.sh 50  # 指定租户（默认自动取 name='demo'）
#
# 前置: docker compose up -d postgres（仅需 DB，应用无需启动；demo 租户需先由应用种子创建）
# 环境变量覆盖: EA_DB_USER / EA_DB_NAME / EA_DB_SERVICE / TENANT_ID
# 不使用 compose 时直连: psql -h localhost -p 5433 -U eaagent -d eaagent \
#   -v tenant_id=1 -v count=500 -f scripts/seed-customers.sql

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

COUNT="${1:-500}"
DB_USER="${EA_DB_USER:-eaagent}"
DB_NAME="${EA_DB_NAME:-eaagent}"
DB_SERVICE="${EA_DB_SERVICE:-postgres}"

command -v docker >/dev/null 2>&1 || { echo "错误: 未找到 docker 命令"; exit 1; }
cd "$REPO_ROOT"

psql_exec() { docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" "$@"; }

# 租户解析（默认 demo；可 TENANT_ID=n 覆盖）
TENANT_ID="${TENANT_ID:-}"
if [ -z "$TENANT_ID" ]; then
    TENANT_ID="$(psql_exec -tA -c "SELECT id FROM tenant WHERE name = 'demo' LIMIT 1" | tr -d '[:space:]')"
fi
case "$TENANT_ID" in
    '' | *[!0-9]*)
        echo "错误: 未找到 demo 租户。先启动应用完成种子（docker compose up -d ea-app 等 postgres/redis healthy），或用 TENANT_ID=n 指定租户。"
        exit 1
        ;;
esac

echo "==> 初始化客户数据: tenant_id=$TENANT_ID count=$COUNT"
BEFORE="$(psql_exec -tA -c "SELECT count(*) FROM customer WHERE tenant_id = $TENANT_ID" | tr -d '[:space:]')"
psql_exec -v ON_ERROR_STOP=1 -v tenant_id="$TENANT_ID" -v count="$COUNT" < "$SCRIPT_DIR/seed-customers.sql"
AFTER="$(psql_exec -tA -c "SELECT count(*) FROM customer WHERE tenant_id = $TENANT_ID" | tr -d '[:space:]')"

echo "==> 完成: 该租户客户数 ${BEFORE} → ${AFTER}（新增 $((AFTER - BEFORE))，重复执行的已存在批次被跳过）"
echo "==> 预览:"
psql_exec -c "SELECT external_id, phone, email, status, tags FROM customer WHERE tenant_id = $TENANT_ID AND external_id LIKE 'seed-%' ORDER BY id DESC LIMIT 3"