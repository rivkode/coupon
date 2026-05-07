package com.promotion.serverc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class CouponIngestServiceTest {

    private static final String CODE = "ABCDEFGHJKMN";
    private static final long USER_ID = 4242L;
    private static final long EVENT_ID = 1L;
    private static final String IDEM = "idem-xyz";
    private static final Instant ISSUED_AT = Instant.parse("2026-05-07T10:00:00Z");

    @Test
    @DisplayName("정상 payload — Coupon 도메인 생성 + repository.save 호출")
    void ingest_persists_coupon() {
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));
        CouponIngestService service = new CouponIngestService(repo);

        CouponIssuedEventPayload payload = newPayload();
        service.ingest(payload);

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(repo).save(captor.capture());
        Coupon saved = captor.getValue();
        assertThat(saved.getCode().value()).isEqualTo(CODE);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getIdempotencyKey()).isEqualTo(IDEM);
        assertThat(saved.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(saved.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("UNIQUE 위반 — DataIntegrityViolationException 그대로 throw (caller 가 흡수)")
    void ingest_propagates_unique_violation() {
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.save(any(Coupon.class))).thenThrow(new DataIntegrityViolationException("dup"));
        CouponIngestService service = new CouponIngestService(repo);

        assertThatThrownBy(() -> service.ingest(newPayload()))
            .isInstanceOf(DataIntegrityViolationException.class);
        // 이유: @Transactional 안에서 catch 하면 rollback-only 마크로 UnexpectedRollbackException 발생.
        // 흡수는 listener (트랜잭션 경계 밖) 책임 — CouponConsumer 가 catch 한다.
    }

    private static CouponIssuedEventPayload newPayload() {
        return new CouponIssuedEventPayload(EVENT_ID, USER_ID, CODE, IDEM, ISSUED_AT);
    }
}
