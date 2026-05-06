#!/usr/bin/env bash
# 통합 e2e 시나리오 일괄 실행 — 단일 부팅 환경 가정.
#
# 사전 조건 (모두 동시 부팅):
#   docker compose up -d mysql redis
#   ./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
#   ./gradlew :server-a:bootRun &     # default profile (RestClient 활성, base-url=8081)
#
# 사용법: ./load-test/run-integrated.sh
#
# 환경변수:
#   BASE_URL          (default: http://localhost:8080)
#   EVENT_ID          (default: 1)
#   STOCK_PER_SHARD   (default: 100, 10 샤드 × 100 = 1,000 stock)
#   WARMUP            (default: 5)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EVENT_ID="${EVENT_ID:-1}"
STOCK_PER_SHARD="${STOCK_PER_SHARD:-100}"
WARMUP="${WARMUP:-5}"
TOTAL_STOCK=$((STOCK_PER_SHARD * 10))

echo "=========================================="
echo "  Integrated test setup"
echo "  base-url=$BASE_URL event=$EVENT_ID stock=$TOTAL_STOCK warmup=$WARMUP"
echo "=========================================="

# 1) Stock seed (운영 endpoint 미구현 — redis-cli 직접 SET).
for i in 0 1 2 3 4 5 6 7 8 9; do
    docker exec promotion-redis redis-cli SET "event:${EVENT_ID}:stock:${i}" "$STOCK_PER_SHARD" >/dev/null
done

# 2) MySQL outbox cleanup (이전 run 잔여 행 제거). 실패하면 사후 검증이 무의미.
if ! docker exec promotion-mysql mysql -upromotion -ppromotion server_b \
    -e "TRUNCATE TABLE coupon_issue_outbox" 2>/dev/null; then
    echo "outbox truncate failed (mysql 컨테이너 / schema 문제)"
    exit 1
fi

# 3) CB warmup — server-a 의 cold start 첫 호출이 read-timeout 200ms 를 초과해 CB OPEN 되는 것을 방지.
#    JIT + connection pool 워밍업까지 한 번에 흡수.
echo "Warming up server-a Circuit Breaker..."
for i in 1 2 3 4 5; do
    KEY=$(uuidgen)
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/coupons/issue-requests" \
        -H "X-User-Id: $((900000 + i))" \
        -H "Idempotency-Key: $KEY" \
        -H "Content-Type: application/json" \
        -d "{\"eventId\":$EVENT_ID,\"deviceId\":\"warmup\",\"channel\":\"WEB\",\"requestedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"clientVersion\":\"1\",\"region\":\"KR\",\"language\":\"ko\",\"marketingConsent\":true}")
    echo "  warmup $i: $HTTP_STATUS"
    sleep 0.3
done

# 4) 시나리오 일괄 실행. STOCK / WARMUP 을 day2-04 burst 에 명시 주입.
SCENARIOS=(
    "load-test/scenarios/phase9-01-issue-success.js"
    "load-test/scenarios/phase9-02-idempotency.js"
    "load-test/scenarios/phase9-03-cross-user.js"
    "load-test/scenarios/phase9-04-rate-limit.js"
    "load-test/scenarios/phase9-05-missing-header.js"
    "load-test/scenarios/phase9-06-validation.js"
    "load-test/scenarios/day2-04-burst.js"
)

FAILED=()
for s in "${SCENARIOS[@]}"; do
    echo "=========================================="
    echo "  Running: $s"
    echo "=========================================="
    if ! k6 run \
        -e "BASE_URL=$BASE_URL" \
        -e "STOCK=$TOTAL_STOCK" \
        -e "WARMUP=$WARMUP" \
        "$s"; then
        FAILED+=("$s")
    fi
    echo
done

# 5) 사후 검증 — Outbox 행 수 = warmup curl + phase9-01..04 의 SUCCEEDED + day2-04 의 SUCCEEDED.
ISSUED_DB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_b -N \
    -e "SELECT COUNT(*) FROM coupon_issue_outbox" 2>/dev/null || echo "?")
echo "[verify] coupon_issue_outbox rows = $ISSUED_DB"

if [[ ${#FAILED[@]} -ne 0 ]]; then
    echo "FAILED scenarios:"
    printf '  - %s\n' "${FAILED[@]}"
    exit 1
fi

echo "All integrated scenarios passed."
