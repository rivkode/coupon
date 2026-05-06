// 발급 요청 공통 페이로드/헤더 빌더.

import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export function buildBody(overrides = {}) {
    return JSON.stringify({
        eventId: 1,
        deviceId: 'k6-device',
        channel: 'WEB',
        requestedAt: new Date().toISOString(),
        clientVersion: '1.0.0',
        region: 'KR',
        language: 'ko',
        marketingConsent: true,
        campaignSource: 'k6',
        metadata: '{}',
        ...overrides,
    });
}

export function buildHeaders(userId, idempotencyKey = uuidv4()) {
    return {
        'X-User-Id': String(userId),
        'Idempotency-Key': idempotencyKey,
        'Content-Type': 'application/json',
    };
}

export function newIdempotencyKey() {
    return uuidv4();
}
