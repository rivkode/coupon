package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class IssueRequestRepositoryImpl implements IssueRequestRepository {

    private final IssueRequestJpaRepository jpaRepository;

    @Override
    public IssueRequest save(IssueRequest req) {
        IssueRequestJpaEntity saved = jpaRepository.save(IssueRequestMapper.toEntity(req));
        return IssueRequestMapper.toDomain(saved);
    }
}
