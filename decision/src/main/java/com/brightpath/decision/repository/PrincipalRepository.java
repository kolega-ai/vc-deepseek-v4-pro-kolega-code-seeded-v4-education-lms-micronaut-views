package com.brightpath.decision.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.decision.domain.Principal;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@Repository
public interface PrincipalRepository extends CrudRepository<Principal, Long> {

    Optional<Principal> findByUsername(String username);
}
