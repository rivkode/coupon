-- 발급 atomic Lua 스크립트 (CLAUDE.md ADR-003 / docs/decisions/outbox-mysql-vs-redis-streams.md §8).
-- Redis 는 single-threaded + Lua 는 atomic 실행이라 한 사용자의 발급 흐름 (idem 캐시 검사 →
-- 코드 충돌 검사 → 재고 차감 → 캐시 등록) 이 한 번에 끝난다.
--
-- KEYS[1] = stock shard       (event:{eventId}:stock:{shardId}, INTEGER)
-- KEYS[2] = coupon hash       (coupon:code:{couponCode}, HASH — 임시 발급 정보)
-- KEYS[3] = idempotency key   (coupon:idem:{userId}:{idempotencyKey}, STRING — user-scoped 캐시)
--
-- ARGV[1] = couponCode (12자, Java 에서 SecureRandom 으로 생성됨)
-- ARGV[2] = userId
-- ARGV[3] = eventId
-- ARGV[4] = ttlSeconds (idem + hash TTL — 동일)
-- ARGV[5] = nowEpochMillis
--
-- return: { status, couponCode }
--   status = 'ISSUED'         정상 발급, couponCode = ARGV[1]
--   status = 'ALREADY_ISSUED' idem 캐시 hit, couponCode = 이전 발급 코드
--   status = 'SOLD_OUT'       재고 0, couponCode = ''
--   status = 'CODE_COLLISION' 코드 충돌 (확률 ~ 0, 호출자 재시도 필요), couponCode = ''

-- 1. idempotency 1차 캐시 검사 — server-a 의 IdempotencyFilter 가 분산 캐시여도 race 가능.
local cached = redis.call('GET', KEYS[3])
if cached then
    return {'ALREADY_ISSUED', cached}
end

-- 2. 쿠폰 코드 충돌 검사. HSETNX 가 0 을 반환하면 같은 코드가 이미 점유됨.
local registered = redis.call('HSETNX', KEYS[2], 'userId', ARGV[2])
if registered == 0 then
    return {'CODE_COLLISION', ''}
end

-- 코드 등록 성공 — hash 의 나머지 필드 + TTL.
redis.call('HSET', KEYS[2], 'eventId', ARGV[3], 'issuedAtMs', ARGV[5])
redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[4]) * 1000)

-- 3. 재고 차감. DECR 결과가 음수면 즉시 INCR 로 복구 (Lua atomic 안에서 net-zero).
local newStock = redis.call('DECR', KEYS[1])
if tonumber(newStock) < 0 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[2])
    return {'SOLD_OUT', ''}
end

-- 4. idempotency 캐시 등록. 같은 idem 으로 다음 호출은 step 1 에서 ALREADY_ISSUED 반환.
redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[4]))

return {'ISSUED', ARGV[1]}
