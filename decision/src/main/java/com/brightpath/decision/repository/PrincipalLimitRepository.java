package com.brightpath.decision.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.decision.domain.PrincipalLimit;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrincipalLimitRepository extends CrudRepository<PrincipalLimit, Long> {

    List<PrincipalLimit> findByPrincipalId(Long principalId);

    Optional<PrincipalLimit> findByPrincipalIdAndLimitKey(Long principalId, String limitKey);
}
