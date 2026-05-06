package com.promotion.serverb.infrastructure.redis;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis Lua 스크립트 호출 wrapper. 본 시스템의 재고 권위 (CLAUDE.md ADR-003).
 *
 * <p>두 스크립트:
 * <ul>
 *   <li>{@code issue-coupon.lua} — idem 캐시 검사 → 코드 충돌 검사 → 재고 차감 → 캐시 등록 (atomic)</li>
 *   <li>{@code compensate.lua} — Outbox INSERT 실패 시 Redis 변경을 모두 되돌림</li>
 * </ul>
 *
 * <p>Lua 는 Redis single-thread 위에서 atomic 실행되므로 race 없음. Java 측은 호출 + 결과 파싱만 담당.
 */
@Component
public class RedisStockClient {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ISSUE_SCRIPT = loadListScript("redis/issue-coupon.lua");

    private static final RedisScript<String> COMPENSATE_SCRIPT = loadStringScript("redis/compensate.lua");

    private final StringRedisTemplate redis;

    public RedisStockClient(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 발급 시도. CouponCode 는 호출자(Application Service) 가 미리 SecureRandom 으로 생성하여 전달
     * — Lua 는 그것을 atomic 등록 + 충돌 검증.
     *
     * @param eventId         이벤트 식별자
     * @param userId          사용자 식별자 (샤드 라우팅에 사용)
     * @param idempotencyKey  멱등 키 (server-a 가 forward)
     * @param couponCode      Java 에서 생성된 후보 코드
     * @param ttl             idem + hash TTL (초)
     * @param now             발급 시각 (Hash 의 issuedAtMs)
     */
    public LuaIssueResult tryIssue(
        long eventId,
        long userId,
        String idempotencyKey,
        CouponCode couponCode,
        long ttlSeconds,
        Instant now
    ) {
        int shardId = RedisKeys.shardIdFor(userId);
        List<String> keys = List.of(
            RedisKeys.stockShard(eventId, shardId),
            RedisKeys.couponCode(couponCode.value()),
            RedisKeys.idempotencyKey(idempotencyKey)
        );
        Object[] args = new Object[]{
            couponCode.value(),
            String.valueOf(userId),
            String.valueOf(eventId),
            String.valueOf(ttlSeconds),
            String.valueOf(now.toEpochMilli())
        };

        @SuppressWarnings({"rawtypes", "unchecked"})
        List<Object> result = (List<Object>) (List) redis.execute(ISSUE_SCRIPT, keys, args);
        if (result == null || result.size() != 2) {
            throw new IllegalStateException(
                "unexpected lua result: " + result + " (expected 2-element list)");
        }
        String statusRaw = String.valueOf(result.get(0));
        String codeRaw = result.get(1) == null ? "" : String.valueOf(result.get(1));

        LuaIssueStatus status = LuaIssueStatus.fromLua(statusRaw);
        return switch (status) {
            case ISSUED -> LuaIssueResult.issued(new CouponCode(codeRaw));
            case ALREADY_ISSUED -> LuaIssueResult.alreadyIssued(new CouponCode(codeRaw));
            case SOLD_OUT -> LuaIssueResult.soldOut();
            case CODE_COLLISION -> LuaIssueResult.codeCollision();
        };
    }

    /**
     * 보상. tryIssue 가 ISSUED 를 반환한 후 MySQL Outbox INSERT 가 실패한 경우 호출.
     * idem 캐시가 다른 코드를 가리키면 SKIPPED — 그 발급은 다른 요청의 정상 발급이므로
     * 재고 INCR 하면 안 된다.
     */
    public CompensationResult compensate(
        long eventId,
        long userId,
        String idempotencyKey,
        CouponCode couponCode
    ) {
        int shardId = RedisKeys.shardIdFor(userId);
        List<String> keys = List.of(
            RedisKeys.stockShard(eventId, shardId),
            RedisKeys.couponCode(couponCode.value()),
            RedisKeys.idempotencyKey(idempotencyKey)
        );
        String result = redis.execute(COMPENSATE_SCRIPT, keys, couponCode.value());
        if (result == null) {
            throw new IllegalStateException("compensate lua returned null");
        }
        return CompensationResult.fromLua(result);
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> loadListScript(String classpathLocation) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<String> loadStringScript(String classpathLocation) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(String.class);
        return script;
    }
}
