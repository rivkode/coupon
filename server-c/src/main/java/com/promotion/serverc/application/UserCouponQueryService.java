package com.promotion.serverc.application;

import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** UserCoupon 단일/목록 조회. internal (B 의 스케줄러) + public (사용자 본인 목록) 양쪽 백엔드. */
@Service
@RequiredArgsConstructor
public class UserCouponQueryService {

    private final UserCouponJpaRepository repository;

    @Transactional(readOnly = true)
    public Optional<UserCouponJpaEntity> findByUserIdAndCouponTypeId(long userId, long couponTypeId) {
        return repository.findByUserIdAndCouponTypeId(userId, couponTypeId);
    }

    @Transactional(readOnly = true)
    public List<UserCouponJpaEntity> findAllByUserId(long userId) {
        return repository.findAllByUserIdOrderByIssuedAtDesc(userId);
    }
}
