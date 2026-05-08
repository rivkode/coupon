#!/usr/bin/env bash
# Day 4 부하 시나리오 4종 일괄 실행 + Prometheus metric snapshot 수집.
#
# 사전 조건:
#   - docker compose up -d --build (server-a/b/c + prometheus + grafana 모두 healthy)
#   - 9 컨테이너 모두 healthy 까지 대기 — `docker compose ps` 로 확인
#
# 사용법: ./load-test/run-day4.sh
#
# 결과: 각 시나리오 완료 후
#   - k6 표준출력에 SUCCEEDED / FAILED / RATE_LIMITED / CB_503 / OTHER + p50/p95/p99
#   - Grafana (http://localhost:3000 → Promotion Overview) 가 5 패널 시계열 표시
#   - 시작 / 종료 timestamp 출력 — Grafana 캡처 시점 명시
#
# 환경변수:
#   BASE_URL              (default: http://localhost:8080) — server-a
#   STOCK_PER_SHARD       (default: 1000, 10 샤드 × 1000 = 10,000 stock — CLAUDE.md §2 정확)
#   PROMETHEUS_URL        (default: http://localhost:9090)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SERVER_B_BASE_URL="${SERVER_B_BASE_URL:-http://localhost:8081}"
SERVER_C_BASE_URL="${SERVER_C_BASE_URL:-http://localhost:8082}"
EVENT_ID="${EVENT_ID:-1}"
STOCK_PER_SHARD="${STOCK_PER_SHARD:-1000}"
TOTAL_STOCK=$((STOCK_PER_SHARD * 10))
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
# Prometheus scrape interval — 시나리오 종료 후 마지막 scrape 흡수 시간. prometheus.yml 의 5s 와 동기화.
PROMETHEUS_SCRAPE_INTERVAL_SEC="${PROMETHEUS_SCRAPE_INTERVAL_SEC:-5}"

REPORT_DIR="${REPORT_DIR:-load-test/results}"
mkdir -p "$REPORT_DIR"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

echo "=========================================="
echo "  Day 4 load test runner"
echo "  base-url=$BASE_URL stock=$TOTAL_STOCK report-dir=$REPORT_DIR"
echo "  start=$(ts)"
echo "=========================================="

# 사전 헬스 체크 — 부팅 미완료 인스턴스 fail-fast.
for url in "$BASE_URL/actuator/health" "$SERVER_B_BASE_URL/actuator/health" "$SERVER_C_BASE_URL/actuator/health" "$PROMETHEUS_URL/-/ready"; do
    if ! curl -fs "$url" >/dev/null 2>&1; then
        echo "health check failed: $url"
        echo "docker compose 가 모두 healthy 인지 확인하세요 (docker compose ps)"
        exit 1
    fi
done
echo "  all services healthy"

# 시나리오 사이 stock reseed + DB 정리 — 각 시나리오가 깨끗한 상태에서 시작.
reseed() {
    docker exec promotion-redis redis-cli FLUSHDB >/dev/null
    docker exec promotion-mysql mysql -upromotion -ppromotion -e \
        "USE server_a; TRUNCATE TABLE issue_request; \
         USE server_b; TRUNCATE TABLE coupon_issue_outbox; \
         USE server_c; TRUNCATE TABLE coupon;" 2>&1 | grep -v "^mysql:" || true
    for i in 0 1 2 3 4 5 6 7 8 9; do
        docker exec promotion-redis redis-cli SET "event:${EVENT_ID}:stock:${i}" "$STOCK_PER_SHARD" >/dev/null
    done
}

# CB warmup — cold start 의 read-timeout 200ms 초과로 CB OPEN 되는 함정 회피.
cb_warmup() {
    for i in 1 2 3 4 5; do
        KEY=$(uuidgen)
        curl -s -o /dev/null \
            -X POST "$BASE_URL/api/v1/coupons/issue-requests" \
            -H "X-User-Id: $((900000 + i))" \
            -H "Idempotency-Key: $KEY" \
            -H "Content-Type: application/json" \
            -d "{\"eventId\":$EVENT_ID,\"deviceId\":\"warmup\",\"channel\":\"WEB\",\"requestedAt\":\"$(ts)\",\"clientVersion\":\"1\",\"region\":\"KR\",\"language\":\"ko\",\"marketingConsent\":true}" \
            || true
        sleep 0.2
    done
}

# Prometheus 쿼리 1회 — instant value snapshot (시나리오 종료 직후).
snap() {
    local label="$1"; shift
    local query="$1"
    curl -sS --data-urlencode "query=$query" "$PROMETHEUS_URL/api/v1/query" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('data', {}).get('result', [])
print(f'  $label:')
if not results:
    print('    (no series)')
for r in results:
    app = r['metric'].get('application', r['metric'].get('job','?'))
    val = r['value'][1]
    try:
        f = float(val)
        if f != f:  # NaN
            val = 'NaN'
        else:
            val = f'{f:.4f}' if f < 1 else f'{f:.2f}'
    except (ValueError, TypeError):
        pass
    print(f'    {app}: {val}')
"
}

run_scenario() {
    local file="$1"
    local label="$2"
    local extra_env="${3:-}"

    echo
    echo "=========================================="
    echo "  Scenario: $label"
    echo "  start=$(ts)"
    echo "=========================================="
    reseed
    cb_warmup
    sleep 2
    echo "  --- k6 output ---"
    if ! k6 run \
        -e "BASE_URL=$BASE_URL" \
        -e "STOCK=$TOTAL_STOCK" \
        $extra_env \
        "$file"; then
        echo "  k6 thresholds failed (측정은 완료 — 보고서 참조)"
    fi
    sleep "$PROMETHEUS_SCRAPE_INTERVAL_SEC"  # 마지막 scrape 흡수 — prometheus.yml 의 scrape_interval 과 동기화
    echo
    echo "  --- metric snapshot (post-scenario) ---"
    snap "process_cpu_usage" 'process_cpu_usage{application=~"server-[abc]"}'
    snap "jvm_memory_used_bytes (heap)" 'sum by (application) (jvm_memory_used_bytes{area="heap",application=~"server-[abc]"})'
    snap "hikaricp_connections_active" 'hikaricp_connections_active{application=~"server-[abc]"}'
    snap "hikaricp_connections_pending" 'hikaricp_connections_pending{application=~"server-[abc]"}'
    snap "tomcat_threads_busy" 'tomcat_threads_busy_threads{application=~"server-[abc]"}'
    snap "http_server p95 (1m)" 'histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{application=~"server-[abc]"}[1m])))'
    echo "  end=$(ts)"
}

# 4 시나리오 순차 실행. spike 가 가장 무거우므로 마지막.
run_scenario "load-test/scenarios/day4-smoke.js"        "day4-smoke (1 VU × 60s, baseline)"
run_scenario "load-test/scenarios/day4-per-instance.js" "day4-per-instance (100 VU × 3min, ~1000 TPS)"
run_scenario "load-test/scenarios/day4-real-scenario.js" "day4-real-scenario (1000 VU × 100 reqs, CLAUDE.md §2)"
run_scenario "load-test/scenarios/day4-spike.js"        "day4-spike (0→500 VU 10s ramp + 10s steady, 5s ramp-down — 호스트 한계로 의도된 1000→500 축소)"

echo
echo "=========================================="
echo "  Day 4 runner complete"
echo "  end=$(ts)"
echo "=========================================="
echo "  Grafana: http://localhost:3000 → Promotion 폴더 → Promotion Overview"
echo "  Prometheus: http://localhost:9090"
echo "  결과 분석: docs/reports/01.load-test-results.md"
