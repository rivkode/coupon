#!/usr/bin/env bash
# 통합 e2e 시나리오 실행. 기본은 1000 TPS (issue-1k-tps.js).
# 다른 시나리오는 SCENARIO 환경변수로 토글, 또는 wrapper (run-5k-integrated.sh) 사용.
#
# 사전 조건:
#   docker compose up -d --build
#   docker compose ps                 # 모두 healthy
#
# 사용법:
#   ./load-test/run-integrated.sh                                          # 1000 TPS
#   ./load-test/run-5k-integrated.sh                                       # 500 TPS
#   SCENARIO=load-test/scenarios/issue-500-tps.js ./load-test/run-integrated.sh
#   TOTAL_INVENTORY=10000 ./load-test/run-integrated.sh                    # CLAUDE.md §1 명세
#   TOTAL_INVENTORY=1000000 ./load-test/run-integrated.sh                  # 매진 없이 TPS 한계만 측정
#
# 결과 집계:
#   - k6 의 issue_accepted 카운터 = "A 가 200 으로 접수한 횟수" (= B 의 Redis 적재 + Kafka publish 까지)
#   - server-c 의 user_coupon SUCCESS 수 = "실제 발급된 쿠폰 건수"
#   - 두 값의 차이 = Kafka → C 처리 잔여 (drain 단계가 없으므로 정상)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SERVER_B_URL="${SERVER_B_URL:-http://localhost:8081}"
SERVER_C_URL="${SERVER_C_URL:-http://localhost:8082}"
EVENT_ID="${EVENT_ID:-1}"
COUPON_TYPE_ID="${COUPON_TYPE_ID:-1}"
TOTAL_INVENTORY="${TOTAL_INVENTORY:-10000}"
SCENARIO="${SCENARIO:-load-test/scenarios/issue-1k-tps.js}"

# 시드/집계 대상은 server-c 의 MySQL — 마스터 데이터 / 재고 / user_coupon / outbox 모두 promotion-mysql-c 에 위치.
# server-a 의 issue_request 는 audit 용이라 별도 시드 불필요 (요청 흐름으로 자연 적재).
MYSQL_CONTAINER="${MYSQL_CONTAINER:-promotion-mysql-c}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-rootpassword}"

# MYSQL_PWD 를 컨테이너 안으로 전달 — `-p` CLI 옵션의 password warning 제거.
mysql_exec() {
    docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" mysql -uroot -N -B "$@"
}

echo "=========================================="
echo "  Integrated load test"
echo "  scenario=$SCENARIO"
echo "  base-url=$BASE_URL event=$EVENT_ID couponType=$COUPON_TYPE_ID"
echo "  total_inventory=$TOTAL_INVENTORY"
echo "=========================================="

# 1) 헬스체크
for svc in "$BASE_URL" "$SERVER_B_URL" "$SERVER_C_URL"; do
    if ! curl -sf "$svc/actuator/health" >/dev/null; then
        echo "FAIL — $svc not healthy"
        exit 1
    fi
done
echo "[ok] all services healthy"

# 2) Redis 정리 (이전 run 의 pending hash 폐기)
docker exec promotion-redis redis-cli FLUSHDB >/dev/null
echo "[ok] redis flushed"

# 3) 마스터 데이터 시드 + 재고 reset (idempotent)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
sed "s/\${TOTAL_INVENTORY}/$TOTAL_INVENTORY/g" "$SCRIPT_DIR/seed.sql" | mysql_exec
echo "[ok] seeded server_c (inventory reset to $TOTAL_INVENTORY, user_coupon/outbox cleared)"

# 4) k6 부하 시나리오
#  --quiet         : 매초 progress 누적 출력 제거 (final summary 는 정상 출력)
#  --summary-export: summary 만 별도 JSON 으로 저장 → metric 추출에 사용
#  threshold 위반 시 exit code 가 0이 아니지만, 결과 집계는 무조건 진행.
SUMMARY_FILE="/tmp/k6-summary.json"
rm -f "$SUMMARY_FILE"
set +e
BASE_URL="$BASE_URL" EVENT_ID="$EVENT_ID" COUPON_TYPE_ID="$COUPON_TYPE_ID" \
    k6 run --quiet --summary-export="$SUMMARY_FILE" "$SCENARIO"
K6_EXIT="$?"
set -e

# 5) drain 단계는 의도적으로 제거.
#    측정 시점은 k6 종료 직후 — Kafka consumer 가 처리 중인 메시지가 있을 수 있어
#    [입구] issue_accepted > [출구] user_coupon TOTAL 인 게 정상이다.
#    완전한 drain 후 정합성을 보고 싶다면 측정 전에 별도로 대기하거나
#    Grafana / Kafka UI 에서 lag 가 0 으로 떨어진 시점을 확인할 것.

# 6) 결과 집계 — k6 summary (입구) vs server-c MySQL (출구)
echo ""
echo "=========================================="
echo "  결과 집계"
echo "=========================================="

# k6 측 — summary JSON 에서 추출 (jq 없으면 grep fallback).
extract_metric() {
    local key="$1"
    if command -v jq >/dev/null 2>&1 && [[ -f "$SUMMARY_FILE" ]]; then
        jq -r ".metrics.\"$key\".count // .metrics.\"$key\".values.count // 0" "$SUMMARY_FILE" 2>/dev/null
    elif [[ -f "$SUMMARY_FILE" ]]; then
        # naive grep — "key":{...,"count":N,...}
        grep -oE "\"$key\"[^}]*\"count\"[^,}]*" "$SUMMARY_FILE" \
            | head -1 | grep -oE '[0-9]+' | tail -1
    else
        echo "0"
    fi
}
HTTP_REQS=$(extract_metric "http_reqs")
ISSUE_ACCEPTED=$(extract_metric "issue_accepted")
ISSUE_DUPLICATE=$(extract_metric "issue_duplicate")
ISSUE_INTERNAL_ERROR=$(extract_metric "issue_internal_error")
ISSUE_OTHER=$(extract_metric "issue_other")

# server-c 측 — 한 번의 SQL 로 묶어서 측정 시점 일관성 확보.
read -r USER_COUPON_TOTAL USER_COUPON_SUCCESS USER_COUPON_SOLD_OUT USER_COUPON_FAILED \
        INVENTORY_AVAILABLE INVENTORY_TOTAL OUTBOX_PENDING OUTBOX_PUBLISHED \
    < <(mysql_exec -e "
        SELECT
          (SELECT COUNT(*) FROM server_c.user_coupon),
          (SELECT COUNT(*) FROM server_c.user_coupon WHERE status='SUCCESS'),
          (SELECT COUNT(*) FROM server_c.user_coupon WHERE status='SOLD_OUT'),
          (SELECT COUNT(*) FROM server_c.user_coupon WHERE status='FAILED'),
          (SELECT available_count FROM server_c.coupon_type_inventory WHERE event_id=$EVENT_ID AND coupon_type_id=$COUPON_TYPE_ID),
          (SELECT total_inventory FROM server_c.coupon_type_inventory WHERE event_id=$EVENT_ID AND coupon_type_id=$COUPON_TYPE_ID),
          (SELECT COUNT(*) FROM server_c.outbox_event WHERE status='PENDING'),
          (SELECT COUNT(*) FROM server_c.outbox_event WHERE status='PUBLISHED');
    ")
INVENTORY_DECREMENTED=$((INVENTORY_TOTAL - INVENTORY_AVAILABLE))

printf "\n[입구] Server A 진입\n"
printf "  HTTP 요청 (http_reqs)        : %s\n" "${HTTP_REQS:-?}"
printf "  접수 성공 (issue_accepted)   : %s   ← B 의 Redis 적재+Kafka publish 완료\n" "${ISSUE_ACCEPTED:-?}"
printf "  중복 (issue_duplicate)       : %s\n" "${ISSUE_DUPLICATE:-0}"
printf "  503 (issue_internal_error)   : %s   ← Circuit Breaker / B 일시 장애\n" "${ISSUE_INTERNAL_ERROR:-0}"
printf "  기타 오류 (issue_other)       : %s\n" "${ISSUE_OTHER:-0}"

printf "\n[출구] Server C 발급 결과 (user_coupon)\n"
printf "  TOTAL                        : %s\n" "$USER_COUPON_TOTAL"
printf "  SUCCESS  (실제 발급)          : %s   ← 사용자가 받은 쿠폰\n" "$USER_COUPON_SUCCESS"
printf "  SOLD_OUT (재고 소진)          : %s\n" "$USER_COUPON_SOLD_OUT"
printf "  FAILED   (이벤트 종료 등)     : %s\n" "$USER_COUPON_FAILED"

printf "\n[재고] coupon_type_inventory\n"
printf "  total_inventory              : %s\n" "$INVENTORY_TOTAL"
printf "  available_count (현재)       : %s\n" "$INVENTORY_AVAILABLE"
printf "  차감량                        : %s\n" "$INVENTORY_DECREMENTED"

printf "\n[Outbox]\n"
printf "  PENDING                      : %s\n" "$OUTBOX_PENDING"
printf "  PUBLISHED                    : %s\n" "$OUTBOX_PUBLISHED"

# 7) 정합성 / 갭 안내
#  drain 단계를 의도적으로 제거했으므로 Kafka consume 이 진행중일 수 있다.
#  차감량 / SUCCESS / TOTAL 의 미세한 어긋남은 비정합성 신호가 아니라 측정 race 일 수 있음 — INFO 로 표기.
echo ""
if [[ "$INVENTORY_DECREMENTED" == "$USER_COUPON_SUCCESS" ]]; then
    echo "[ok] 재고 차감량($INVENTORY_DECREMENTED) == SUCCESS($USER_COUPON_SUCCESS)"
else
    echo "[INFO] 차감량($INVENTORY_DECREMENTED) vs SUCCESS($USER_COUPON_SUCCESS) — drain 미적용, Kafka consume 진행중 가능"
fi

if [[ -n "${ISSUE_ACCEPTED:-}" ]] && [[ "$ISSUE_ACCEPTED" -gt 0 ]]; then
    GAP=$((ISSUE_ACCEPTED - USER_COUPON_TOTAL))
    if [[ "$GAP" -gt 0 ]]; then
        echo "[INFO] 접수($ISSUE_ACCEPTED) - 발급처리($USER_COUPON_TOTAL) = $GAP — Kafka consume 잔여 (정상)"
    fi
fi

# k6 threshold 통과 여부를 마지막에 노출.
echo ""
if [[ "${K6_EXIT:-0}" -ne 0 ]]; then
    echo "[WARN] k6 threshold 위반 (exit=$K6_EXIT) — k6 final summary 의 'THRESHOLDS' 섹션 확인"
fi
exit "${K6_EXIT:-0}"
