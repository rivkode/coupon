package com.promotion.serverc.application;

import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Server B 의 @Scheduled 가 호출하는 internal GET 의 백엔드. */
@Service
@RequiredArgsConstructor
public class UserCouponQueryService {

    private final UserCouponJpaRepository repository;

    @Transactional(readOnly = true)
    public Optional<UserCouponJpaEntity> findByUserIdAndCouponTypeId(long userId, long couponTypeId) {
        return repository.findByUserIdAndCouponTypeId(userId, couponTypeId);
    }
}
