package com.brightpath.decision.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.decision.domain.PrincipalSession;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrincipalSessionRepository extends CrudRepository<PrincipalSession, UUID> {

    List<PrincipalSession> findByPrincipalId(Long principalId);
}
