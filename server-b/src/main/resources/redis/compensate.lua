-- 보상 atomic Lua 스크립트 (CLAUDE.md ADR-002 / docs/decisions/outbox-mysql-vs-redis-streams.md §5).
-- 호출 시점: issue-coupon.lua 가 ISSUED 반환 후 MySQL Outbox INSERT 가 실패한 경우.
-- 책임: Redis 측 변경 (재고 차감 + idem 캐시 + coupon hash) 을 모두 되돌린다.
--
-- "이미 다른 요청이 같은 idem 으로 캐시 등록한 경우" 는 절대 INCR 하지 않는다 — 그 발급은 정상이고
-- 우리만 실패한 것이므로 재고를 복구하면 안 된다. 본 시나리오는 동시성 race 에서 발생 가능.
--
-- KEYS[1] = stock shard
-- KEYS[2] = coupon hash
-- KEYS[3] = idempotency key       (coupon:idem:{userId}:{idempotencyKey}, user-scoped)
--
-- ARGV[1] = couponCode (보상 대상 — 우리가 발급했던 코드)
--
-- return: 'OK' (보상 완료) | 'SKIPPED' (다른 요청 발급 또는 idem 만료, 보상 안 함)

-- 1) idem 캐시가 비어있으면 (TTL 만료 또는 외부 보상 선행) 보상 안 함 — INCR 이중 적용 위험 방지.
local cached = redis.call('GET', KEYS[3])
if not cached then
    return 'SKIPPED'
end

-- 2) idem 캐시가 다른 코드를 가리키면 그 발급은 다른 요청이 정상 처리. INCR 하면 재고 부풀림.
if cached ~= ARGV[1] then
    return 'SKIPPED'
end

-- 3) coupon hash 가 우리 발급 흔적이 맞는지 추가 확인 (방어적). 흔적 없으면 외부 보상이 이미 처리한 상태.
if redis.call('HEXISTS', KEYS[2], 'userId') == 0 then
    redis.call('DEL', KEYS[3])  -- idem 캐시는 정리 (코드가 일치한 상태였으므로 우리 거)
    return 'SKIPPED'
end

-- 우리 발급 흔적 정리 — idem 과 coupon hash.
redis.call('DEL', KEYS[3])
redis.call('DEL', KEYS[2])

-- 재고 복구.
redis.call('INCR', KEYS[1])

return 'OK'
