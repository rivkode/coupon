package com.promotion.serverc.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverc.api.exception.GlobalExceptionHandler;
import com.promotion.serverc.application.RedeemCommand;
import com.promotion.serverc.application.RedeemCouponService;
import com.promotion.serverc.application.RedeemResult;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RedeemCouponController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class RedeemCouponControllerTest {

    private static final String CODE = "ABCDEFGHJKMN";
    private static final long USER_ID = 4242L;
    private static final String IDEM = "idem-redeem";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RedeemCouponService redeemCouponService;

    @Test
    @DisplayName("정상 redeem → 200 + success body + newlyRedeemed=true")
    void redeem_returns_200_on_success() throws Exception {
        Instant redeemedAt = Instant.parse("2026-05-07T10:00:00Z");
        when(redeemCouponService.redeem(any(RedeemCommand.class)))
            .thenReturn(RedeemResult.succeeded(new CouponCode(CODE), USER_ID, redeemedAt));

        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", CODE)
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", IDEM))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.code").value(CODE))
            .andExpect(jsonPath("$.data.userId").value(USER_ID))
            .andExpect(jsonPath("$.data.newlyRedeemed").value(true));
    }

    @Test
    @DisplayName("코드 없음 / 다른 user — 404 NOT_FOUND")
    void redeem_returns_404_when_not_found() throws Exception {
        when(redeemCouponService.redeem(any(RedeemCommand.class)))
            .thenThrow(new CouponNotFoundException("not found"));

        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", CODE)
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", IDEM))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("낙관락 race — 409 RACE_RETRY")
    void redeem_returns_409_on_optimistic_lock() throws Exception {
        when(redeemCouponService.redeem(any(RedeemCommand.class)))
            .thenThrow(new OptimisticLockingFailureException("stale"));

        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", CODE)
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", IDEM))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("RACE_RETRY"));
    }

    @Test
    @DisplayName("X-User-Id 누락 — 400 MISSING_HEADER")
    void redeem_returns_400_when_user_id_missing() throws Exception {
        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", CODE)
                .header("Idempotency-Key", IDEM))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MISSING_HEADER"))
            .andExpect(jsonPath("$.error.message").value(
                org.hamcrest.Matchers.containsString("X-User-Id")));
    }

    @Test
    @DisplayName("Idempotency-Key 누락 — 400 MISSING_HEADER")
    void redeem_returns_400_when_idempotency_key_missing() throws Exception {
        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", CODE)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("CouponCode 길이 invalid — 400 INVALID_ARGUMENT")
    void redeem_returns_400_when_code_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/coupons/{code}/redeem", "TOOSHORT")
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", IDEM))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        // CouponCode VO 의 length 검증이 controller path variable binding 직후에 발생.
    }
}
