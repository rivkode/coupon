package com.promotion.serverc.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponTypeInventoryJpaRepository extends JpaRepository<CouponTypeInventoryJpaEntity, Long> {

    /**
     * 비관적 락 차감용 조회. ADR-003: SELECT ... FOR UPDATE 로 행 락.
     * Kafka consumer 의 1 트랜잭션 안에서 호출.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CouponTypeInventoryJpaEntity i " +
            "where i.eventId = :eventId and i.couponTypeId = :couponTypeId")
    Optional<CouponTypeInventoryJpaEntity> findForUpdate(@Param("eventId") Long eventId,
                                                        @Param("couponTypeId") Long couponTypeId);

    Optional<CouponTypeInventoryJpaEntity> findByEventIdAndCouponTypeId(Long eventId, Long couponTypeId);
}
