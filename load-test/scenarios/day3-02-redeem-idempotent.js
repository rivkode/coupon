// Day 3 Scenario 2 — 같은 user 의 두 번째 redeem 은 도메인 자체 멱등 (200 + newlyRedeemed=false)
//
// 검증: 도메인 자체 멱등성 (CLAUDE.md ADR-004 / ADR-007 — 별도 idem 캐시/테이블 미도입).
// 첫 호출이 used_at 을 set 하고, 두 번째 호출은 그대로 200 으로 기존 redeemedAt 반환.
//
// 기대:
//   - 첫 redeem: newlyRedeemed=true
//   - 두 번째 redeem (다른 idempotency-key): newlyRedeemed=false + redeemedAt 동일
//
// 실행: k6 run -e BASE_URL=http://localhost:8080 -e SERVER_C_BASE_URL=http://localhost:8082 \
//        load-test/scenarios/day3-02-redeem-idempotent.js

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

const REDEEM_RETRY_COUNT = Number(__ENV.REDEEM_RETRY_COUNT || 20);
const REDEEM_RETRY_INTERVAL_SEC = Number(__ENV.REDEEM_RETRY_INTERVAL_SEC || 0.5);

export default function () {
    const userId = `9302${__ITER}`;
    const issueRes = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId),
    });

    let issueBody;
    try { issueBody = issueRes.json(); } catch (_) { issueBody = null; }
    if (!check(issueRes, { '[d3-02] issue 200 + SUCCEEDED': () => issueRes.status === 200 && issueBody?.data?.status === 'SUCCEEDED' })) {
        return;
    }
    const couponCode = issueBody.data.couponCode;

    // 첫 redeem — used_at 을 set. 응답은 in-memory micros 정밀도.
    const first = redeemWithRetry(couponCode, userId);
    let firstBody;
    try { firstBody = first.json(); } catch (_) { firstBody = null; }
    if (!check(first, { '[d3-02] first redeem 200': (r) => r.status === 200 })) return;

    // 두 번째와 세 번째 — 둘 다 idem 분기 (DB 읽은 ms 정밀도). 두 응답을 비교해 정밀도 mismatch 회피.
    const second = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
        headers: buildRedeemHeaders(userId, newIdempotencyKey()),
    });
    let secondBody;
    try { secondBody = second.json(); } catch (_) { secondBody = null; }

    const third = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
        headers: buildRedeemHeaders(userId, newIdempotencyKey()),
    });
    let thirdBody;
    try { thirdBody = third.json(); } catch (_) { thirdBody = null; }

    check(second, {
        '[d3-02] second redeem HTTP 200': (r) => r.status === 200,
        '[d3-02] third redeem HTTP 200': () => third.status === 200,
        '[d3-02] first newlyRedeemed=true': () => firstBody?.data?.newlyRedeemed === true,
        '[d3-02] second newlyRedeemed=false': () => secondBody?.data?.newlyRedeemed === false,
        '[d3-02] third newlyRedeemed=false': () => thirdBody?.data?.newlyRedeemed === false,
        // 두 idem 응답은 DB 에서 읽은 used_at — 정확히 일치해야 함.
        '[d3-02] second.redeemedAt == third.redeemedAt (도메인 멱등)': () => secondBody?.data?.redeemedAt === thirdBody?.data?.redeemedAt,
        '[d3-02] code 동일': () => firstBody?.data?.code === secondBody?.data?.code && secondBody?.data?.code === couponCode,
    });
}

function redeemWithRetry(couponCode, userId) {
    let res;
    for (let attempt = 0; attempt < REDEEM_RETRY_COUNT; attempt++) {
        res = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
            headers: buildRedeemHeaders(userId, newIdempotencyKey()),
        });
        if (res.status === 200) return res;
        if (res.status !== 404) return res;
        sleep(REDEEM_RETRY_INTERVAL_SEC);
    }
    return res;
}
