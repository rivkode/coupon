// Day 3 Scenario 3 — 다른 user 의 redeem 시도는 404 NOT_FOUND 로 마스킹 (보안)
//
// 검증: server-c RedeemCouponService 의 소유권 마스킹 — "코드 없음" 과 "다른 user 의 코드" 를
// 구분하지 않고 동일 응답 (코드 존재 여부 누설 방지). PR #17 의 핵심 결정.
//
// 기대:
//   - user A 가 발급
//   - user B 가 redeem 시도 → 404 + error.code=NOT_FOUND
//   - user A 의 후속 redeem 은 정상 200 (사이드이펙트 없음 검증)
//
// 실행: k6 run -e BASE_URL=http://localhost:8080 -e SERVER_C_BASE_URL=http://localhost:8082 \
//        load-test/scenarios/day3-03-redeem-ownership-mask.js

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
    const ownerId = `9303${__ITER}1`;
    const otherId = `9303${__ITER}2`;

    // 1) owner 가 발급
    const issueRes = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(ownerId),
    });
    let issueBody;
    try { issueBody = issueRes.json(); } catch (_) { issueBody = null; }
    if (!check(issueRes, { '[d3-03] issue 200 + SUCCEEDED': () => issueRes.status === 200 && issueBody?.data?.status === 'SUCCEEDED' })) {
        return;
    }
    const couponCode = issueBody.data.couponCode;

    // 2) owner 의 첫 redeem — used_at 을 set + server-c consume 영속 확인 (lag 흡수 retry).
    //    의도: 마스킹 검증 (3) 직전에 코드가 server-c 에 영속됐다는 보장.
    const ownerFirst = redeemOwnerWithRetry(couponCode, ownerId);
    let ownerFirstBody;
    try { ownerFirstBody = ownerFirst.json(); } catch (_) { ownerFirstBody = null; }
    if (!check(ownerFirst, { '[d3-03] owner first redeem 200 (영속 확인)': (r) => r.status === 200 })) return;

    // 3) 다른 user 가 같은 code 로 redeem 시도 — 404 마스킹
    const maskRes = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
        headers: buildRedeemHeaders(otherId, newIdempotencyKey()),
    });
    let maskBody;
    try { maskBody = maskRes.json(); } catch (_) { maskBody = null; }

    check(maskRes, {
        '[d3-03] other-user HTTP 404': (r) => r.status === 404,
        '[d3-03] error.code=NOT_FOUND': () => maskBody?.error?.code === 'NOT_FOUND',
        '[d3-03] success=false': () => maskBody?.success === false,
    });

    // 4) 사이드 이펙트 없음 — owner 가 두 번 더 호출 (둘 다 idem 분기). 두 응답이 일치하면 otherId 의
    //    시도가 used_at 을 변경하지 않았다는 의미. ownerFirst 는 in-memory micros 정밀도라 DB ms 와
    //    rounding (.123456 → .124) 으로 어긋날 수 있어 직접 비교 회피 — 두 idem 응답을 비교한다.
    const ownerSecond = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
        headers: buildRedeemHeaders(ownerId, newIdempotencyKey()),
    });
    let ownerSecondBody;
    try { ownerSecondBody = ownerSecond.json(); } catch (_) { ownerSecondBody = null; }

    const ownerThird = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
        headers: buildRedeemHeaders(ownerId, newIdempotencyKey()),
    });
    let ownerThirdBody;
    try { ownerThirdBody = ownerThird.json(); } catch (_) { ownerThirdBody = null; }

    check(ownerSecond, {
        '[d3-03] owner second redeem 200': (r) => r.status === 200,
        '[d3-03] owner third redeem 200': () => ownerThird.status === 200,
        '[d3-03] owner second newlyRedeemed=false (멱등)': () => ownerSecondBody?.data?.newlyRedeemed === false,
        '[d3-03] owner third newlyRedeemed=false (멱등)': () => ownerThirdBody?.data?.newlyRedeemed === false,
        // 둘 다 DB 에서 읽은 used_at — otherId 의 시도가 변경하지 않았다면 정확히 일치.
        '[d3-03] owner second.redeemedAt == third.redeemedAt': () => ownerSecondBody?.data?.redeemedAt === ownerThirdBody?.data?.redeemedAt,
    });
}

function redeemOwnerWithRetry(couponCode, ownerId) {
    let res;
    for (let attempt = 0; attempt < REDEEM_RETRY_COUNT; attempt++) {
        res = http.post(`${SERVER_C_BASE_URL}${redeemPath(couponCode)}`, null, {
            headers: buildRedeemHeaders(ownerId, newIdempotencyKey()),
        });
        if (res.status === 200) return res;
        if (res.status !== 404) return res;
        sleep(REDEEM_RETRY_INTERVAL_SEC);
    }
    return res;
}
