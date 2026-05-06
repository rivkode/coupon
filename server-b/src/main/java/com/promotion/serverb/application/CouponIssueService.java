package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.IssueResult;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import com.promotion.serverb.domain.CouponIssuedEvent;
import com.promotion.serverb.infrastructure.redis.CompensationResult;
import com.promotion.serverb.infrastructure.redis.LuaIssueResult;
import com.promotion.serverb.infrastructure.redis.LuaIssueStatus;
import com.promotion.serverb.infrastructure.redis.RedisStockClient;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발급 orchestration (server-b 핵심 흐름).
 *
 * <p><b>흐름</b> (CLAUDE.md ADR-002 / docs/decisions/outbox-mysql-vs-redis-streams.md §8):
 * <ol>
 *   <li>CouponCode 후보 생성 (SecureRandom)</li>
 *   <li>Lua tryIssue — atomic: idem 검사 → 코드 충돌 검사 → 재고 차감 → 캐시 등록</li>
 *   <li>CODE_COLLISION → 새 코드로 재시도 (max N 회)</li>
 *   <li>SOLD_OUT / ALREADY_ISSUED → Outbox INSERT 없이 즉시 반환</li>
 *   <li>ISSUED → Outbox INSERT (TransactionTemplate)</li>
 *   <li>INSERT 실패 (DataAccessException) → 보상 Lua → IssueTemporarilyUnavailableException</li>
 * </ol>
 *
 * <p><b>트랜잭션 경계</b> — Lua 호출 / 보상 호출은 트랜잭션 밖, Outbox INSERT 만 안.
 * 외부 호출을 트랜잭션 안에 두지 않음 (CLAUDE.md §10 안티패턴 회피).
 *
 * <p><b>알려진 위험</b> — Lua ISSUED 성공 후 JVM 크래시 시 유령 재고. 본 PR 은 reconciliation
 * 미구현, README 트레이드오프 섹션에 명시.
 */
@Service
public class CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueService.class);

    private final RedisStockClient redis;
    private final CouponIssueOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final long ttlSeconds;
    private final int maxRetries;

    public CouponIssueService(
        RedisStockClient redis,
        CouponIssueOutboxRepository outboxRepository,
        TransactionTemplate transactionTemplate,
        @Value("${app.coupon-issue.ttl-seconds:86400}") long ttlSeconds,
        @Value("${app.coupon-issue.code-collision-max-retries:3}") int maxRetries
    ) {
        this.redis = redis;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = transactionTemplate;
        this.ttlSeconds = ttlSeconds;
        this.maxRetries = maxRetries;
    }

    public IssueResult issue(IssueCommand cmd) {
        Instant now = Instant.now();
        LuaIssueResult lua = tryIssueWithRetry(cmd, now);

        return switch (lua.status()) {
            case SOLD_OUT -> IssueResult.soldOut();
            case ALREADY_ISSUED -> IssueResult.alreadyIssued(lua.couponCode());
            case CODE_COLLISION -> throw new IllegalStateException(
                "coupon code collision exhausted retries (" + maxRetries + ")");
            case ISSUED -> persistOutboxOrCompensate(cmd, lua.couponCode(), now);
        };
    }

    private IssueResult persistOutboxOrCompensate(IssueCommand cmd, CouponCode code, Instant now) {
        CouponIssuedEvent event = new CouponIssuedEvent(
            cmd.eventId(), cmd.userId(), code, cmd.idempotencyKey(), now);
        CouponIssueOutbox outbox = CouponIssueOutbox.create(event, now);

        try {
            transactionTemplate.executeWithoutResult(status -> outboxRepository.save(outbox));
        } catch (DataAccessException ex) {
            CompensationResult comp = redis.compensate(
                cmd.eventId(), cmd.userId(), cmd.idempotencyKey(), code);
            log.error(
                "outbox insert failed, redis compensated ({}): code={} idem={} userId={} eventId={}",
                comp, code.value(), cmd.idempotencyKey(), cmd.userId(), cmd.eventId(), ex);
            throw new IssueTemporarilyUnavailableException(
                "outbox insert failed; redis compensated", ex);
        }
        return IssueResult.issued(code);
    }

    private LuaIssueResult tryIssueWithRetry(IssueCommand cmd, Instant now) {
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            CouponCode candidate = CouponCode.generate();
            LuaIssueResult result = redis.tryIssue(
                cmd.eventId(), cmd.userId(), cmd.idempotencyKey(),
                candidate, ttlSeconds, now);
            if (result.status() != LuaIssueStatus.CODE_COLLISION) {
                return result;
            }
            log.warn("coupon code collision; retrying: attempt={} idem={}",
                attempt, cmd.idempotencyKey());
        }
        return LuaIssueResult.codeCollision();
    }
}
