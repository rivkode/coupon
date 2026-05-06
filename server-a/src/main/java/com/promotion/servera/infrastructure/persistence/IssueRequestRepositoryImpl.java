package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class IssueRequestRepositoryImpl implements IssueRequestRepository {

    private final IssueRequestJpaRepository jpaRepository;

    @Override
    public IssueRequest save(IssueRequest issueRequest) {
        IssueRequestJpaEntity entity = IssueRequestMapper.toEntity(issueRequest);
        IssueRequestJpaEntity saved = jpaRepository.save(entity);
        return IssueRequestMapper.toDomain(saved);
    }

    @Override
    public Optional<IssueRequest> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            .map(IssueRequestMapper::toDomain);
    }

    @Override
    public Optional<IssueRequest> findByRequestId(String requestId) {
        return jpaRepository.findByRequestId(requestId)
            .map(IssueRequestMapper::toDomain);
    }
}
