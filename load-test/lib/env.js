// k6 환경 설정. `__ENV.BASE_URL` 등 환경변수 우선, 없으면 로컬 기본값.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const ISSUE_ENDPOINT = '/api/v1/coupons/issue-request';
export const SERVER_C_BASE_URL = __ENV.SERVER_C_BASE_URL || 'http://localhost:8082';

/** Redeem endpoint path 빌더. POST /api/v1/coupons/{code}/redeem. */
export function redeemPath(code) {
    return `/api/v1/coupons/${code}/redeem`;
}
