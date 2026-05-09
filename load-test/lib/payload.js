// 신규 설계 — 발급 요청 본문 10 필드 (README §발급 요청).
// 헤더 X-User-Id 로 사용자 식별, body 에는 발급 컨텍스트만.

const ONE_MONTH_MS = 30 * 24 * 60 * 60 * 1000;

export function buildBody(eventId, couponTypeId) {
    const now = new Date();
    return JSON.stringify({
        country: 'KR',
        eventId,
        couponTypeId,
        issuedAt: now.toISOString(),
        expireAt: new Date(now.getTime() + ONE_MONTH_MS).toISOString(),
        channel: 'APP',
        deviceId: 'k6-device',
        clientVersion: '1.0.0',
        language: 'ko',
        marketingConsent: true,
    });
}

export function buildHeaders(userId) {
    return {
        'X-User-Id': String(userId),
        'Content-Type': 'application/json',
    };
}

export function buildRedeemHeaders(userId) {
    return { 'X-User-Id': String(userId) };
}
