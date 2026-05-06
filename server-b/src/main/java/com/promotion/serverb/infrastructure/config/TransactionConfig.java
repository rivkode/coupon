package com.promotion.serverb.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CouponIssueService 의 Lua 호출 → Outbox INSERT 분리 패턴을 Spring AOP self-invocation 한계
 * 없이 구현하기 위한 명시적 TransactionTemplate (server-a 의 동일 패턴).
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
