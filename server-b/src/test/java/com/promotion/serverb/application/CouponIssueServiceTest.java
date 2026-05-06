package com.promotion.serverb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import com.promotion.serverb.infrastructure.redis.CompensationResult;
import com.promotion.serverb.infrastructure.redis.LuaIssueResult;
import com.promotion.serverb.infrastructure.redis.RedisStockClient;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CouponIssueServiceTest {

    private RedisStockClient redis;
    private CouponIssueOutboxRepository outboxRepository;
    private TransactionTemplate transactionTemplate;
    private CouponIssueService service;

    @BeforeEach
    void setUp() {
        redis = Mockito.mock(RedisStockClient.class);
        outboxRepository = Mockito.mock(CouponIssueOutboxRepository.class);
        transactionTemplate = Mockito.mock(TransactionTemplate.class);

        // 기본: TransactionTemplate.executeWithoutResult 가 callback 을 그대로 실행
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new CouponIssueService(
            redis, outboxRepository, transactionTemplate,
            3600L, 3
        );
    }

    @Test
    @DisplayName("ISSUED — Outbox.save 호출, 보상 호출 없음")
    void issued_persists_outbox() {
        CouponCode issuedCode = CouponCode.generate();
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.issued(issuedCode));

        IssueResult result = service.issue(new IssueCommand(1L, 100L, "idem-1"));

        assertThat(result.status()).isEqualTo(IssueStatus.ISSUED);
        verify(outboxRepository, times(1)).save(any(CouponIssueOutbox.class));
        verify(redis, never()).compensate(anyLong(), anyLong(), anyString(), any(CouponCode.class));
    }

    @Test
    @DisplayName("ALREADY_ISSUED — Outbox.save / compensate 모두 호출 안 함")
    void already_issued_skips_outbox() {
        CouponCode cachedCode = CouponCode.generate();
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.alreadyIssued(cachedCode));

        IssueResult result = service.issue(new IssueCommand(1L, 100L, "idem-2"));

        assertThat(result.status()).isEqualTo(IssueStatus.ALREADY_ISSUED);
        assertThat(result.couponCode()).isEqualTo(cachedCode);
        verify(outboxRepository, never()).save(any());
        verify(redis, never()).compensate(anyLong(), anyLong(), anyString(), any(CouponCode.class));
    }

    @Test
    @DisplayName("SOLD_OUT — Outbox.save / compensate 모두 호출 안 함")
    void sold_out_skips_outbox() {
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.soldOut());

        IssueResult result = service.issue(new IssueCommand(1L, 100L, "idem-3"));

        assertThat(result.status()).isEqualTo(IssueStatus.SOLD_OUT);
        verify(outboxRepository, never()).save(any());
        verify(redis, never()).compensate(anyLong(), anyLong(), anyString(), any(CouponCode.class));
    }

    @Test
    @DisplayName("ISSUED + Outbox INSERT 실패 → compensate 호출 + IssueTemporarilyUnavailableException")
    void compensates_when_outbox_insert_fails() {
        CouponCode issuedCode = CouponCode.generate();
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.issued(issuedCode));
        doThrow(new DataIntegrityViolationException("simulated UNIQUE violation"))
            .when(outboxRepository).save(any(CouponIssueOutbox.class));
        when(redis.compensate(anyLong(), anyLong(), anyString(), any(CouponCode.class)))
            .thenReturn(CompensationResult.OK);

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 100L, "idem-4")))
            .isInstanceOf(IssueTemporarilyUnavailableException.class)
            .hasMessageContaining("outbox insert failed");

        verify(redis, times(1)).compensate(eq(1L), eq(100L), eq("idem-4"), eq(issuedCode));
    }

    @Test
    @DisplayName("CODE_COLLISION 1회 후 ISSUED — 재시도 성공, Outbox INSERT 1회")
    void retries_on_code_collision_then_issued() {
        CouponCode issuedCode = CouponCode.generate();
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.codeCollision())
            .thenReturn(LuaIssueResult.issued(issuedCode));

        IssueResult result = service.issue(new IssueCommand(1L, 100L, "idem-5"));

        assertThat(result.status()).isEqualTo(IssueStatus.ISSUED);
        verify(redis, times(2)).tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any());
        verify(outboxRepository, times(1)).save(any(CouponIssueOutbox.class));
    }

    @Test
    @DisplayName("CODE_COLLISION 재시도 모두 소진 → IllegalStateException (시스템 결함)")
    void exhausts_retries_then_throws() {
        when(redis.tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any()))
            .thenReturn(LuaIssueResult.codeCollision());

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 100L, "idem-6")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code collision");

        // maxRetries=3 + 첫 시도 = 4회 호출
        verify(redis, times(4)).tryIssue(anyLong(), anyLong(), anyString(), any(CouponCode.class), anyLong(), any());
        verify(outboxRepository, never()).save(any());
        verify(redis, never()).compensate(anyLong(), anyLong(), anyString(), any(CouponCode.class));
    }
}
