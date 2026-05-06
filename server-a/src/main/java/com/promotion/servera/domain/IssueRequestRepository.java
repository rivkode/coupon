package com.promotion.servera.domain;

import java.util.Optional;

/**
 * Server A 의 IssueRequest 저장소. 구현은 infrastructure/persistence 에 위치.
 * Spring Data JpaRepository 를 application 레이어에 직접 노출하지 않기 위한 분리.
 */
public interface IssueRequestRepository {

    IssueRequest save(IssueRequest issueRequest);

    Optional<IssueRequest> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<IssueRequest> findByRequestId(String requestId);
}
