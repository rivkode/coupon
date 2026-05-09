#!/usr/bin/env bash
# 통합 e2e 시나리오 실행. 신규 설계 — 발급 시나리오 1개 (issue-1k-tps).
#
# 사전 조건:
#   docker compose up -d --build
#   docker compose ps                 # 모두 healthy
#
# 사용법:
#   ./load-test/run-integrated.sh
#   BASE_URL=http://localhost:8080 EVENT_ID=1 COUPON_TYPE_ID=1 ./load-test/run-integrated.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SERVER_B_URL="${SERVER_B_URL:-http://localhost:8081}"
SERVER_C_URL="${SERVER_C_URL:-http://localhost:8082}"
EVENT_ID="${EVENT_ID:-1}"
COUPON_TYPE_ID="${COUPON_TYPE_ID:-1}"

echo "=========================================="
echo "  Integrated load test"
echo "  base-url=$BASE_URL event=$EVENT_ID couponType=$COUPON_TYPE_ID"
echo "=========================================="

# 헬스체크
for svc in "$BASE_URL" "$SERVER_B_URL" "$SERVER_C_URL"; do
    if ! curl -sf "$svc/actuator/health" >/dev/null; then
        echo "FAIL — $svc not healthy"
        exit 1
    fi
done
echo "[ok] all services healthy"

# Redis 정리 (이전 run 의 pending hash 폐기)
docker exec promotion-redis redis-cli FLUSHDB >/dev/null
echo "[ok] redis flushed"

# k6 부하 시나리오
BASE_URL="$BASE_URL" EVENT_ID="$EVENT_ID" COUPON_TYPE_ID="$COUPON_TYPE_ID" \
    k6 run load-test/scenarios/issue-1k-tps.js
