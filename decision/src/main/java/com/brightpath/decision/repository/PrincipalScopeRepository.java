package com.brightpath.decision.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.decision.domain.PrincipalScope;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrincipalScopeRepository extends CrudRepository<PrincipalScope, Long> {

    List<PrincipalScope> findByPrincipalId(Long principalId);

    Optional<PrincipalScope> findByPrincipalIdAndCourseIdAndScopeRole(Long principalId, Long courseId, String scopeRole);
}
