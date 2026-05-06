#!/usr/bin/env bash
# Phase 9 시나리오 1~6 (Stub profile) 일괄 실행.
# 사용법: ./load-test/run-phase9-stub.sh
#       BASE_URL 환경변수로 호스트 변경 가능.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCENARIOS=(
    "load-test/scenarios/phase9-01-issue-success.js"
    "load-test/scenarios/phase9-02-idempotency.js"
    "load-test/scenarios/phase9-03-cross-user.js"
    "load-test/scenarios/phase9-04-rate-limit.js"
    "load-test/scenarios/phase9-05-missing-header.js"
    "load-test/scenarios/phase9-06-validation.js"
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

echo "All Phase 9 stub scenarios passed."
