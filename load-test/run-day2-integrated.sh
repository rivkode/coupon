#!/usr/bin/env bash
# Day 2 burst 시나리오 (통합 모드) 실행.
# server-a (default profile, RestClient 활성화) + server-b 모두 부팅된 상태에서 실행.
#
# 사전 조건:
#   docker compose up -d mysql redis
#   ./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
#   ./gradlew :server-a:bootRun &  # default profile (RestClient 활성, base-url=8081)
#
# 사용법: ./load-test/run-day2-integrated.sh
#       EVENT_ID 환경변수로 다른 이벤트 사용 가능.
#       STOCK_PER_SHARD 환경변수로 샤드별 재고 변경 가능.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EVENT_ID="${EVENT_ID:-1}"
STOCK_PER_SHARD="${STOCK_PER_SHARD:-10}"    # 10 샤드 × 10 = 100 (e2e 정합성 검증 규모)
WARMUP="${WARMUP:-5}"                       # k6 setup() warmup 호출 수 (CB sliding-window 정상화용)
TOTAL_STOCK=$((STOCK_PER_SHARD * 10))

# 1) Stock seed (운영 endpoint 미구현 — redis-cli 직접 SET).
echo "=========================================="
echo "  Seeding stock: event=$EVENT_ID 10 shards × $STOCK_PER_SHARD"
echo "=========================================="
for i in 0 1 2 3 4 5 6 7 8 9; do
    docker exec promotion-redis redis-cli SET "event:${EVENT_ID}:stock:${i}" "$STOCK_PER_SHARD" >/dev/null
done

# 2) MySQL outbox cleanup (이전 burst run 잔여 행 제거). 실패하면 사후 검증이 무의미하므로 fail-fast.
if ! docker exec promotion-mysql mysql -upromotion -ppromotion server_b \
    -e "TRUNCATE TABLE coupon_issue_outbox" 2>/dev/null; then
    echo "outbox truncate failed (mysql container or schema 문제)"
    exit 1
fi

# 3) k6 burst 실행. STOCK / WARMUP 을 시나리오에 명시 주입.
echo "=========================================="
echo "  Running: load-test/scenarios/day2-04-burst.js (stock=$TOTAL_STOCK warmup=$WARMUP)"
echo "=========================================="
if ! k6 run \
    -e "BASE_URL=$BASE_URL" \
    -e "STOCK=$TOTAL_STOCK" \
    -e "WARMUP=$WARMUP" \
    load-test/scenarios/day2-04-burst.js; then
    echo "Day 2 burst FAILED."
    exit 1
fi

# 4) 사후 검증 — Outbox 행 수 확인 (참고용).
ISSUED_DB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_b -N -e \
    "SELECT COUNT(*) FROM coupon_issue_outbox" 2>/dev/null || echo "?")
echo "[verify] coupon_issue_outbox rows = $ISSUED_DB (expected = SUCCEEDED count from k6 summary)"

echo "Day 2 burst scenario passed."
