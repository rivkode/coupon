package com.promotion.serverc.api;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.UserCouponResponse;
import com.promotion.serverc.application.UserCouponQueryService;
import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * UserCouponController 단위 테스트.
 *
 * <p>X-User-Id 헤더가 사용자를 결정하는 `/me` 패턴 — path 에 userId 없음. ownership 마스킹 로직도
 * 필요 없음 (다른 사용자 자원 노출 경로 자체가 없음).
 */
@ExtendWith(MockitoExtension.class)
class UserCouponControllerTest {

    @Mock
    private UserCouponQueryService queryService;

    @InjectMocks
    private UserCouponController controller;

    @Test
    void returnsMappedListForCaller() {
        long userId = 1L;
        UserCouponJpaEntity uc1 = new UserCouponJpaEntity(
                "CODE00000001", userId, 100L, 10L, UserCouponStatus.SUCCESS, LocalDateTime.now());
        UserCouponJpaEntity uc2 = new UserCouponJpaEntity(
                "CODE00000002", userId, 101L, 11L, UserCouponStatus.USED, LocalDateTime.now());
        when(queryService.findAllByUserId(userId)).thenReturn(List.of(uc1, uc2));

        ResponseEntity<ApiResponse<List<UserCouponResponse>>> response = controller.findMine(userId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponse<List<UserCouponResponse>> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.success());
        assertEquals(2, body.data().size());
        assertEquals("CODE00000001", body.data().get(0).code());
        assertEquals(UserCouponStatus.USED, body.data().get(1).status());
    }

    @Test
    void returnsEmptyListWhenNoCoupons() {
        long userId = 42L;
        when(queryService.findAllByUserId(userId)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<UserCouponResponse>>> response = controller.findMine(userId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().data().isEmpty());
    }
}
