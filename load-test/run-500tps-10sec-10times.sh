#!/usr/bin/env bash
# 500 TPS × 10 초 시나리오를 N 회 반복 실행 후 결과 요약 (기본 10 회).
# docs/reports/infra-sizing.md §1.2 의 반복 측정 방법론과 동일.
#
# 매 회차마다:
#   1) run-integrated.sh (Redis FLUSHDB + 마스터 데이터 시드 + k6 부하) 호출
#   2) /tmp/k6-summary.json 을 별도 파일로 보존
#   3) 다음 회차 사이 1 초 sleep — JIT 안정화 / Kafka consumer rebalance idle 보장
#
# 모든 run 종료 후:
#   - 회차별 RPS / p50 / p90 / p95 / avg / dropped / accepted / 503 / result 표
#   - 회차 중간값 (median) 행 — 운영 baseline 으로 활용
#
# 사전 조건:
#   docker compose up -d --build
#   docker compose ps                 # 모두 healthy
#
# 사용법:
#   ./load-test/run-500tps-10sec-10times.sh
#   ITERATIONS=5 ./load-test/run-500tps-10sec-10times.sh                # 회차 변경
#   SLEEP_SECONDS=3 ./load-test/run-500tps-10sec-10times.sh             # 회차 사이 대기 변경
#   TOTAL_INVENTORY=1000000 ./load-test/run-500tps-10sec-10times.sh

set -uo pipefail

ITERATIONS="${ITERATIONS:-10}"
SLEEP_SECONDS="${SLEEP_SECONDS:-1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCENARIO="load-test/scenarios/issue-500-tps-10s.js"

# threshold 기준 (시나리오 thresholds 와 동일)
THRESHOLD_P95_MAX=200      # ms
THRESHOLD_FAIL_RATE_MAX=0.005

RESULTS_DIR="$(mktemp -d -t k6-5x-XXXXXX)"
trap 'rm -rf "$RESULTS_DIR"' EXIT

echo "=========================================="
echo "  500 TPS × 10s × ${ITERATIONS} 회 반복 측정"
echo "  scenario=${SCENARIO}"
echo "  sleep=${SLEEP_SECONDS}s (회차 사이)"
echo "  threshold=p95<${THRESHOLD_P95_MAX}ms, fail-rate<${THRESHOLD_FAIL_RATE_MAX}"
echo "  results-dir=${RESULTS_DIR}"
echo "=========================================="
echo ""

if ! command -v jq >/dev/null 2>&1; then
    echo "[ERROR] jq 가 필요합니다. 'brew install jq' 후 재실행하세요."
    exit 1
fi

# 부동소수 비교 (awk — bc 의존성 회피).
# float_ge a b → a >= b 면 0 (true), 아니면 1 (false).
float_ge() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 >= b + 0) }'; }

# 회차 결과를 PASS / FAIL 로 판정.
compute_result() {
    local p95="$1"
    local fail_rate="$2"
    local result="PASS"
    if float_ge "${p95}" "${THRESHOLD_P95_MAX}"; then result="FAIL"; fi
    if float_ge "${fail_rate}" "${THRESHOLD_FAIL_RATE_MAX}"; then result="FAIL"; fi
    echo "${result}"
}

# k6 summary JSON 에서 metric 추출. null / 누락 시 0.
extract() {
    jq -r "$2 // 0" "$1" 2>/dev/null || echo "0"
}

# ---------------- 회차별 실행 ----------------

for i in $(seq 1 "${ITERATIONS}"); do
    LOG="${RESULTS_DIR}/run-${i}.log"
    printf "▶ run %d/%d ... " "$i" "${ITERATIONS}"

    set +e
    SCENARIO="${SCENARIO}" "${SCRIPT_DIR}/run-integrated.sh" >"${LOG}" 2>&1
    RC=$?
    set -e

    if [[ -f /tmp/k6-summary.json ]]; then
        cp /tmp/k6-summary.json "${RESULTS_DIR}/summary-${i}.json"
        F="${RESULTS_DIR}/summary-${i}.json"
        # 즉시 핵심 지표 표시 (p50 = .med, threshold 는 실제 값에서 직접 판정).
        RPS=$(extract "${F}" '.metrics.http_reqs.rate' | awk '{printf "%.0f", $1}')
        P95_RAW=$(extract "${F}" '.metrics.http_req_duration."p(95)"')
        P95=$(echo "${P95_RAW}" | awk '{printf "%.0f", $1}')
        FAIL_RATE=$(extract "${F}" '.metrics.http_req_failed.value')
        RESULT=$(compute_result "${P95_RAW}" "${FAIL_RATE}")
        echo "완료 (RPS=${RPS} p95=${P95}ms ${RESULT})"
    else
        echo "실패 — summary 없음 (log: ${LOG})"
    fi

    # 마지막 회차 뒤에는 sleep skip
    if [[ "$i" -lt "${ITERATIONS}" ]] && [[ "${SLEEP_SECONDS}" -gt 0 ]]; then
        sleep "${SLEEP_SECONDS}"
    fi
done

# ---------------- 집계 ----------------

echo ""
echo "=========================================="
echo "  결과 요약 (${ITERATIONS} 회 반복)"
echo "=========================================="
echo ""

# 표 헤더 — infra-sizing.md §1.2 와 정합 (p50 / p90 / p95 / avg).
printf "%-7s %-7s %-8s %-8s %-8s %-8s %-8s %-9s %-6s %-6s\n" \
    "회차" "RPS" "p50" "p90" "p95" "avg" "dropped" "accepted" "503" "result"
printf "%-7s %-7s %-8s %-8s %-8s %-8s %-8s %-9s %-6s %-6s\n" \
    "------" "------" "------" "------" "------" "------" "------" "--------" "-----" "-----"

# 각 회차 데이터 누적 (median 계산용).
RPS_LIST=()
P50_LIST=()
P90_LIST=()
P95_LIST=()
AVG_LIST=()
DROPPED_LIST=()
ACCEPTED_LIST=()

for i in $(seq 1 "${ITERATIONS}"); do
    F="${RESULTS_DIR}/summary-${i}.json"
    if [[ ! -f "${F}" ]]; then
        printf "%-7s %s\n" "$i" "(summary 없음)"
        continue
    fi

    RPS=$(extract "${F}" '.metrics.http_reqs.rate' | awk '{printf "%.0f", $1}')
    # p50 = .med (k6 default 키), p99 는 default summaryTrendStats 미포함 → 표시 X.
    P50=$(extract "${F}" '.metrics.http_req_duration.med' | awk '{printf "%.0f", $1}')
    P90=$(extract "${F}" '.metrics.http_req_duration."p(90)"' | awk '{printf "%.0f", $1}')
    P95_RAW=$(extract "${F}" '.metrics.http_req_duration."p(95)"')
    P95=$(echo "${P95_RAW}" | awk '{printf "%.0f", $1}')
    AVG=$(extract "${F}" '.metrics.http_req_duration.avg' | awk '{printf "%.0f", $1}')
    DROPPED=$(extract "${F}" '.metrics.dropped_iterations.count')
    ACCEPTED=$(extract "${F}" '.metrics.issue_accepted.count')
    INT_ERR=$(extract "${F}" '.metrics.issue_internal_error.count')
    OTH_ERR=$(extract "${F}" '.metrics.issue_other.count')
    ERR=$((INT_ERR + OTH_ERR))
    FAIL_RATE=$(extract "${F}" '.metrics.http_req_failed.value')
    RESULT=$(compute_result "${P95_RAW}" "${FAIL_RATE}")

    printf "%-7s %-7s %-8s %-8s %-8s %-8s %-8s %-9s %-6s %-6s\n" \
        "$i" "${RPS}" "${P50}ms" "${P90}ms" "${P95}ms" "${AVG}ms" "${DROPPED}" "${ACCEPTED}" "${ERR}" "${RESULT}"

    RPS_LIST+=("${RPS}")
    P50_LIST+=("${P50}")
    P90_LIST+=("${P90}")
    P95_LIST+=("${P95}")
    AVG_LIST+=("${AVG}")
    DROPPED_LIST+=("${DROPPED}")
    ACCEPTED_LIST+=("${ACCEPTED}")
done

# 정수 median (회차 수가 짝수면 두 중간값의 평균).
median() {
    local sorted=()
    while IFS= read -r line; do
        sorted+=("$line")
    done < <(printf "%s\n" "$@" | sort -n)
    local n=${#sorted[@]}
    [[ "${n}" -eq 0 ]] && { echo "0"; return; }
    local mid=$((n / 2))
    if (( n % 2 == 1 )); then
        echo "${sorted[${mid}]}"
    else
        local lo=${sorted[$((mid - 1))]}
        local hi=${sorted[${mid}]}
        echo $(((lo + hi) / 2))
    fi
}

if [[ "${#RPS_LIST[@]}" -gt 0 ]]; then
    printf "%-7s %-7s %-8s %-8s %-8s %-8s %-8s %-9s %-6s %-6s\n" \
        "------" "------" "------" "------" "------" "------" "------" "--------" "-----" "-----"
    printf "%-7s %-7s %-8s %-8s %-8s %-8s %-8s %-9s %-6s %-6s\n" \
        "median" \
        "$(median "${RPS_LIST[@]}")" \
        "$(median "${P50_LIST[@]}")ms" \
        "$(median "${P90_LIST[@]}")ms" \
        "$(median "${P95_LIST[@]}")ms" \
        "$(median "${AVG_LIST[@]}")ms" \
        "$(median "${DROPPED_LIST[@]}")" \
        "$(median "${ACCEPTED_LIST[@]}")" \
        "-" "-"
fi

echo ""
echo "→ 1 회차는 cold start (JIT 컴파일 / HikariCP 풀 / Kafka 메타데이터 캐시 워밍)."
echo "→ steady-state 회차의 median 이 운영 baseline."
echo "→ result PASS = (p95 < ${THRESHOLD_P95_MAX}ms) AND (fail-rate < ${THRESHOLD_FAIL_RATE_MAX})."
echo "→ 상세 분석: docs/reports/infra-sizing.md §1.2"
echo ""
echo "[로그 / summary] ${RESULTS_DIR} 에 보존 (스크립트 종료 시 자동 정리)"
