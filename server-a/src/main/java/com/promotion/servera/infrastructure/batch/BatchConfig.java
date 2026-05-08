package com.promotion.servera.infrastructure.batch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase C — batch insert 인프라 활성화 (보고서 §5.1).
 *
 * <p>{@link EnableScheduling}: {@link IssueRequestBatchFlusher} 의 {@code @Scheduled} 와
 * {@link HeapMonitor} 의 {@code @Scheduled} 활성화.
 *
 * <p>{@link EnableConfigurationProperties}: {@link BatchProperties} 의 {@code app.batch.*} 매핑 활성화.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BatchProperties.class)
class BatchConfig {
}
