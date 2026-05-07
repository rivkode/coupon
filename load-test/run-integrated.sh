#!/usr/bin/env bash
# 통합 e2e 시나리오 일괄 실행 — 단일 부팅 환경 가정.
#
# 사전 조건 (모두 동시 부팅):
#   docker compose up -d mysql redis kafka
#   ./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
#   ./gradlew :server-a:bootRun &     # default profile (RestClient 활성, base-url=8081)
#   ./gradlew :server-c:bootRun &     # default profile (Kafka consumer 활성)
#
# 사용법: ./load-test/run-integrated.sh
#
# 환경변수:
#   BASE_URL              (default: http://localhost:8080) — server-a
#   SERVER_C_BASE_URL     (default: http://localhost:8082)
#   EVENT_ID              (default: 1)
#   STOCK_PER_SHARD       (default: 100, 10 샤드 × 100 = 1,000 stock)
#   WARMUP                (default: 5)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SERVER_B_BASE_URL="${SERVER_B_BASE_URL:-http://localhost:8081}"
SERVER_C_BASE_URL="${SERVER_C_BASE_URL:-http://localhost:8082}"
EVENT_ID="${EVENT_ID:-1}"
STOCK_PER_SHARD="${STOCK_PER_SHARD:-100}"
WARMUP="${WARMUP:-5}"
TOTAL_STOCK=$((STOCK_PER_SHARD * 10))

echo "=========================================="
echo "  Integrated test setup"
echo "  base-url=$BASE_URL server-c=$SERVER_C_BASE_URL event=$EVENT_ID stock=$TOTAL_STOCK warmup=$WARMUP"
echo "=========================================="

# 1) Redis FLUSHDB — 이전 run 의 rate-limit bucket / idem 캐시 / coupon hash 모두 정리.
#    Stock 도 날아가므로 이 직후 재seed.
docker exec promotion-redis redis-cli FLUSHDB >/dev/null

# 2) MySQL 정리 — server_a.issue_request + server_b.outbox + server_c.coupon.
#    stderr 는 그대로 노출 — schema 미생성 / 권한 오류 등을 진단할 수 있게.
if ! docker exec promotion-mysql mysql -upromotion -ppromotion -e \
    "USE server_a; TRUNCATE TABLE issue_request; \
     USE server_b; TRUNCATE TABLE coupon_issue_outbox; \
     USE server_c; TRUNCATE TABLE coupon;"; then
    echo "mysql truncate failed (위 stderr 참조)"
    exit 1
fi

# 3) Stock seed (운영 endpoint 미구현 — redis-cli 직접 SET).
for i in 0 1 2 3 4 5 6 7 8 9; do
    docker exec promotion-redis redis-cli SET "event:${EVENT_ID}:stock:${i}" "$STOCK_PER_SHARD" >/dev/null
done

# 4) 헬스체크 — 세 서비스 모두 UP 인지 확인. 부팅이 늦은 인스턴스가 있으면 시나리오 첫 호출이 실패.
for url in "$BASE_URL/actuator/health" "$SERVER_B_BASE_URL/actuator/health" "$SERVER_C_BASE_URL/actuator/health"; do
    if ! curl -fs "$url" | grep -q '"status":"UP"'; then
        echo "health check failed: $url"
        exit 1
    fi
done

# 5) CB warmup — server-a 의 cold start 첫 호출이 read-timeout 200ms 를 초과해 CB OPEN 되는 것을 방지.
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

# 6) 시나리오 일괄 실행. STOCK / WARMUP 을 day2-04 burst 에 명시 주입.
#    phase9 + day2-04 burst 가 Outbox 에 ~수백 건을 누적. day3 의 redeem 호출 전 server-c 가
#    그것들을 모두 consume 해 영속할 때까지 대기 (cpus=1 환경에서 server-c 처리량 < producer rate).
PHASE9_AND_BURST=(
    "load-test/scenarios/phase9-01-issue-success.js"
    "load-test/scenarios/phase9-02-idempotency.js"
    "load-test/scenarios/phase9-03-cross-user.js"
    "load-test/scenarios/phase9-04-rate-limit.js"
    "load-test/scenarios/phase9-05-missing-header.js"
    "load-test/scenarios/phase9-06-validation.js"
    "load-test/scenarios/day2-04-burst.js"
)
DAY3=(
    "load-test/scenarios/day3-01-redeem-success.js"
    "load-test/scenarios/day3-02-redeem-idempotent.js"
    "load-test/scenarios/day3-03-redeem-ownership-mask.js"
    "load-test/scenarios/day3-04-redeem-not-found.js"
    "load-test/scenarios/day3-05-redeem-missing-header.js"
)

run_scenarios() {
    local label="$1"; shift
    local list=("$@")
    for s in "${list[@]}"; do
        echo "=========================================="
        echo "  [$label] Running: $s"
        echo "=========================================="
        if ! k6 run \
            -e "BASE_URL=$BASE_URL" \
            -e "SERVER_C_BASE_URL=$SERVER_C_BASE_URL" \
            -e "STOCK=$TOTAL_STOCK" \
            -e "WARMUP=$WARMUP" \
            "$s"; then
            FAILED+=("$s")
        fi
        echo
    done
}

FAILED=()
run_scenarios "phase9+burst" "${PHASE9_AND_BURST[@]}"

# 6-A) day3 시작 전 server-c consume catchup 대기.
#     outbox.published=true 행 수 == server_c.coupon 행 수 가 될 때까지 polling.
#     cpus=1 환경에선 day2-04 burst 의 ~100건이 서버 시작 후 ~10~30초에 걸쳐 consume 됨.
echo "=========================================="
echo "  Waiting for server-c consume catchup before day3 ..."
echo "=========================================="
for i in $(seq 1 60); do
    OUTBOX_PUB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_b -N \
        -e "SELECT COUNT(*) FROM coupon_issue_outbox WHERE published=1" 2>/dev/null || echo 0)
    COUPON_CNT=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_c -N \
        -e "SELECT COUNT(*) FROM coupon" 2>/dev/null || echo 0)
    if [[ "$OUTBOX_PUB" == "$COUPON_CNT" && "$OUTBOX_PUB" != "0" ]]; then
        echo "  caught up: outbox.published=$OUTBOX_PUB == server_c.coupon=$COUPON_CNT"
        break
    fi
    echo "  outbox.published=$OUTBOX_PUB server_c.coupon=$COUPON_CNT (waiting ${i}s)"
    sleep 1
done

# 6-B) CB recovery — day2-04 burst 가 server-a 의 Resilience4j sliding-window 를 OPEN 시킬 수 있어
#     day3 발급 호출이 즉시 503 받는 케이스. 정상 호출 N 번으로 sliding-window 재정상화.
echo "=========================================="
echo "  Server-a CB warmup before day3 ..."
echo "=========================================="
for i in 1 2 3 4 5 6 7 8 9 10; do
    KEY=$(uuidgen)
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/coupons/issue-requests" \
        -H "X-User-Id: $((950000 + i))" \
        -H "Idempotency-Key: $KEY" \
        -H "Content-Type: application/json" \
        -d "{\"eventId\":$EVENT_ID,\"deviceId\":\"warmup-d3\",\"channel\":\"WEB\",\"requestedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"clientVersion\":\"1\",\"region\":\"KR\",\"language\":\"ko\",\"marketingConsent\":true}")
    echo "  warmup-d3 $i: $HTTP_STATUS"
    sleep 0.3
done

run_scenarios "day3" "${DAY3[@]}"

# 7) 사후 검증 — Outbox 행 수 + server_c.coupon 행 수.
#    Outbox = warmup curl + phase9-01..04 의 SUCCEEDED + day2-04 의 SUCCEEDED + day3 발급분.
#    server_c.coupon ≈ Outbox (Kafka consume lag 만큼 적게 보일 수 있음).
ISSUED_DB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_b -N \
    -e "SELECT COUNT(*) FROM coupon_issue_outbox" 2>/dev/null || echo "?")
COUPON_DB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_c -N \
    -e "SELECT COUNT(*) FROM coupon" 2>/dev/null || echo "?")
REDEEMED_DB=$(docker exec promotion-mysql mysql -upromotion -ppromotion server_c -N \
    -e "SELECT COUNT(*) FROM coupon WHERE used_at IS NOT NULL" 2>/dev/null || echo "?")
echo "[verify] coupon_issue_outbox rows = $ISSUED_DB"
echo "[verify] server_c.coupon rows     = $COUPON_DB (used_at NOT NULL: $REDEEMED_DB)"

if [[ ${#FAILED[@]} -ne 0 ]]; then
    echo "FAILED scenarios:"
    printf '  - %s\n' "${FAILED[@]}"
    exit 1
fi

echo "All integrated scenarios passed."
