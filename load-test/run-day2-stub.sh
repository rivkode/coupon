#!/usr/bin/env bash
# Day 2 시나리오 1~3 (Stub profile) 일괄 실행.
# 사전 조건: server-a 가 local profile 로 부팅 중.
#   ./gradlew :server-a:bootRun --args='--spring.profiles.active=local'
#
# 사용법: ./load-test/run-day2-stub.sh
#       BASE_URL 환경변수로 호스트 변경 가능.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCENARIOS=(
    "load-test/scenarios/day2-01-issue-success.js"
    "load-test/scenarios/day2-02-idempotency.js"
    "load-test/scenarios/day2-03-sold-out.js"
)

FAILED=()
for s in "${SCENARIOS[@]}"; do
    echo "=========================================="
    echo "  Running: $s"
    echo "=========================================="
    if ! k6 run -e "BASE_URL=$BASE_URL" "$s"; then
        FAILED+=("$s")
    fi
    echo
done

if [[ ${#FAILED[@]} -ne 0 ]]; then
    echo "FAILED scenarios:"
    printf '  - %s\n' "${FAILED[@]}"
    exit 1
fi

echo "All Day 2 stub scenarios passed."
