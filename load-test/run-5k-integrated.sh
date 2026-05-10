#!/usr/bin/env bash
# 500 TPS 통합 시나리오 — issue-500-tps.js 사용. 내부적으로 run-integrated.sh 를 SCENARIO 토글로 호출.
#
# 사전 조건 / 환경변수 / 결과 해석은 run-integrated.sh 와 동일.
#
# 사용법:
#   ./load-test/run-5k-integrated.sh
#   TOTAL_INVENTORY=10000 ./load-test/run-5k-integrated.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec env SCENARIO="load-test/scenarios/issue-500-tps.js" \
    "$SCRIPT_DIR/run-integrated.sh" "$@"
