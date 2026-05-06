package com.promotion.servera.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IssueRequestService 의 split-tx (RECEIVED 영속화 → 외부 호출 → SUCCEEDED/FAILED 마감) 패턴을
 * Spring AOP self-invocation 한계 없이 구현하기 위한 명시적 TransactionTemplate.
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
