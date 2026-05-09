package com.promotion.serverb.infrastructure.redis;

import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.domain.PendingIssue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Redis 자료구조 (pending hash + ZSet) 추상화. */
@Component
public class RedisIssueRequestStore {

    private static final String F_REQUEST_ID = "requestId";
    private static final String F_USER_ID = "userId";
    private static final String F_EVENT_ID = "eventId";
    private static final String F_COUPON_TYPE_ID = "couponTypeId";
    private static final String F_STATUS = "status";
    private static final String F_CREATED_AT = "createdAt";
    private static final String F_CODE = "code";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIssueRequestStore(StringRedisTemplate redis,
                                  @Value("${app.redis.pending-ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 동일 (user, couponType) 신청이 이미 있으면 false. 없으면 hash + zset 등록 후 true.
     * EXISTS + HSET + ZADD 가 atomic 은 아니지만 single client 의 race 정도라 충분 (멱등성 권위는 C UNIQUE).
     */
    public boolean savePendingIfAbsent(String requestId, long userId, long eventId,
                                       long couponTypeId, Instant createdAt) {
        String hashKey = RedisKeys.pendingHash(userId, couponTypeId);
        Boolean firstField = redis.opsForHash().putIfAbsent(hashKey, F_USER_ID, Long.toString(userId));
        if (Boolean.FALSE.equals(firstField)) {
            return false;
        }
        Map<String, String> fields = Map.of(
                F_REQUEST_ID, requestId,
                F_USER_ID, Long.toString(userId),
                F_EVENT_ID, Long.toString(eventId),
                F_COUPON_TYPE_ID, Long.toString(couponTypeId),
                F_STATUS, IssuePendingStatus.PENDING.name(),
                F_CREATED_AT, Long.toString(createdAt.toEpochMilli())
        );
        redis.opsForHash().putAll(hashKey, fields);
        redis.expire(hashKey, ttl);
        redis.opsForZSet().add(RedisKeys.PENDING_ZSET,
                RedisKeys.pendingZsetMember(userId, couponTypeId),
                createdAt.toEpochMilli());
        return true;
    }

    /** Kafka result consumer 또는 스케줄러가 결과를 반영. */
    public void markResult(long userId, long couponTypeId, IssuePendingStatus status, String code) {
        String hashKey = RedisKeys.pendingHash(userId, couponTypeId);
        redis.opsForHash().put(hashKey, F_STATUS, status.name());
        if (code != null) {
            redis.opsForHash().put(hashKey, F_CODE, code);
        }
        redis.expire(hashKey, ttl);
        // 종료 상태는 zset 에서 제거 — 스케줄러가 다시 잡지 않도록.
        redis.opsForZSet().remove(RedisKeys.PENDING_ZSET,
                RedisKeys.pendingZsetMember(userId, couponTypeId));
    }

    /** ADR-008: cutoff(epoch ms) 이전 createdAt 인 PENDING 신청을 batch 만큼 가져온다. */
    public List<PendingIssue> findPendingOlderThan(long cutoffEpochMs, int batchSize) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .rangeByScoreWithScores(RedisKeys.PENDING_ZSET, 0, cutoffEpochMs, 0, batchSize);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<PendingIssue> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String member = t.getValue();
            if (member == null) continue;
            String[] parts = member.split(":");
            if (parts.length != 2) continue;
            long userId;
            long couponTypeId;
            try {
                userId = Long.parseLong(parts[0]);
                couponTypeId = Long.parseLong(parts[1]);
            } catch (NumberFormatException nfe) {
                continue;
            }
            String hashKey = RedisKeys.pendingHash(userId, couponTypeId);
            Map<Object, Object> entries = redis.opsForHash().entries(hashKey);
            if (entries.isEmpty()) {
                redis.opsForZSet().remove(RedisKeys.PENDING_ZSET, member);
                continue;
            }
            String requestId = (String) entries.get(F_REQUEST_ID);
            String eventIdStr = (String) entries.get(F_EVENT_ID);
            String statusStr = (String) entries.get(F_STATUS);
            String createdAtStr = (String) entries.get(F_CREATED_AT);
            if (requestId == null || eventIdStr == null || statusStr == null || createdAtStr == null) {
                continue;
            }
            result.add(new PendingIssue(
                    requestId,
                    userId,
                    Long.parseLong(eventIdStr),
                    couponTypeId,
                    IssuePendingStatus.valueOf(statusStr),
                    Instant.ofEpochMilli(Long.parseLong(createdAtStr))
            ));
        }
        return result;
    }
}
