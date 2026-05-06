// k6 환경 설정. `__ENV.BASE_URL` 등 환경변수 우선, 없으면 로컬 기본값.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const ISSUE_ENDPOINT = '/api/v1/coupons/issue-requests';
export const ACTUATOR_CB_ENDPOINT = '/actuator/circuitbreakers';

export const DEFAULT_EVENT_ID = Number(__ENV.EVENT_ID || 1);
