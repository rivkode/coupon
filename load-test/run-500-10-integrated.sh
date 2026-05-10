#!/usr/bin/env bash
# 500 TPS × 10 초 — 짧은 통합 시나리오 (튜닝 사이클용).
# run-integrated.sh 를 SCENARIO 토글로 호출.
#
# 사전 조건 / 환경변수 / 결과 해석은 run-integrated.sh 와 동일.
#
# 사용법:
#   ./load-test/run-500-10-integrated.sh
#   TOTAL_INVENTORY=10000 ./load-test/run-500-10-integrated.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec env SCENARIO="load-test/scenarios/issue-500-tps-10s.js" \
    "$SCRIPT_DIR/run-integrated.sh" "$@"
