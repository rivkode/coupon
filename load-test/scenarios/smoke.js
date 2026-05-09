// Smoke test — 발급 흐름 1 회 호출로 서비스 정상 가동 확인.
//
// 부하 시나리오 실행 전에 환경(server-a/b/c + Redis + Kafka + MySQL master data) 이
// 준비되었는지 빠르게 검증.
//
// 실행:
//   k6 run load-test/scenarios/smoke.js

import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const COUPON_TYPE_ID = Number(__ENV.COUPON_TYPE_ID || 1);

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    const userId = Date.now();
    const res = http.post(
        `${BASE_URL}${ISSUE_ENDPOINT}`,
        buildBody(EVENT_ID, COUPON_TYPE_ID),
        { headers: buildHeaders(userId) }
    );

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    const ok = check(res, {
        'status is 200': (r) => r.status === 200,
        'response success=true': () => body && body.success === true,
        'status ACCEPTED or DUPLICATE': () =>
            body && body.data && (body.data.status === 'ACCEPTED' || body.data.status === 'DUPLICATE'),
        'requestId is present': () => body && body.data && typeof body.data.requestId === 'string',
    });

    console.log(`[smoke] userId=${userId} status=${res.status} body=${res.body}`);

    if (!ok) {
        fail(`smoke failed — status=${res.status} body=${res.body}`);
    }
}
