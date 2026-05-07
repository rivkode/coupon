// Day 3 Scenario 1 — 발급 → Kafka consume 대기 → redeem 정상 (e2e 흐름)
//
// 검증: server-a 발급 → server-b Outbox → Kafka → server-c consume → redeem POST → 200.
// ADR-001 (A→B sync) + ADR-002 (B→C async) 의 e2e sanity.
//
// 기대:
//   - 발급 200 + status=SUCCEEDED + couponCode 12 chars
//   - redeem 200 + newlyRedeemed=true + 응답 code/userId 일치
//   - Kafka consume 까지의 lag 가 retry 윈도우 (default 20번 × 500ms = 10초) 안에 흡수
//
// 실행: k6 run -e BASE_URL=http://localhost:8080 -e SERVER_C_BASE_URL=http://localhost:8082 \
//        load-test/scenarios/day3-01-redeem-success.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT, SERVER_C_BASE_URL, redeemPath } from '../lib/env.js';
import { buildBody, buildHeaders, buildRedeemHeaders, newIdempotencyKey } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

// retry 총 윈도우 = COUNT × INTERVAL. day2-04 burst 직후의 outbox lag 흡수 위해 ~10초 윈도우.
const REDEEM_RETRY_COUNT = Number(__ENV.REDEEM_RETRY_COUNT || 20);
const REDEEM_RETRY_INTERVAL_SEC = Number(__ENV.REDEEM_RETRY_INTERVAL_SEC || 0.5);

export default function () {
    const userId = `9301${__ITER}`;
    const issueRes = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId),
    });

    let issueBody;
    try { issueBody = issueRes.json(); } catch (_) { issueBody = null; }

    const issueOk = check(issueRes, {
        '[d3-01] issue HTTP 200': (r) => r.status === 200,
        '[d3-01] issue status SUCCEEDED': () => issueBody?.data?.status === 'SUCCEEDED',
        '[d3-01] couponCode 12 chars': () => typeof issueBody?.data?.couponCode === 'string' && issueBody.data.couponCode.length === 12,
    });
    if (!issueOk) {
        return;
    }

    const couponCode = issueBody.data.couponCode;
    // server-b poller (200ms fixed-delay) + Kafka 발행 + server-c consume + JPA save 까지의 lag 흡수.
    const redeemRes = redeemWithRetry(couponCode, userId);

    let redeemBody;
    try { redeemBody = redeemRes.json(); } catch (_) { redeemBody = null; }

    check(redeemRes, {
        '[d3-01] redeem HTTP 200': (r) => r.status === 200,
        '[d3-01] redeem success=true': () => redeemBody?.success === true,
        '[d3-01] redeem code 일치': () => redeemBody?.data?.code === couponCode,
        '[d3-01] redeem userId 일치': () => String(redeemBody?.data?.userId) === userId,
        '[d3-01] newlyRedeemed=true': () => redeemBody?.data?.newlyRedeemed === true,
        '[d3-01] redeemedAt 존재': () => typeof redeemBody?.data?.redeemedAt === 'string',
    });
}

function redeemWithRetry(couponCode, userId) {
    let res;
    for (let attempt = 0; attempt < REDEEM_RETRY_COUNT; attempt++) {
        res = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
            headers: buildRedeemHeaders(userId, newIdempotencyKey()),
        });
        // 200 이면 성공, 404 면 server-c 가 아직 consume 안 함 → 짧게 대기 후 재시도.
        if (res.status === 200) return res;
        if (res.status !== 404) return res;
        sleep(REDEEM_RETRY_INTERVAL_SEC);
    }
    return res;
}
