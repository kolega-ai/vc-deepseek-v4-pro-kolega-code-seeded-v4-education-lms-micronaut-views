package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.CohortStaff;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CohortStaffRepository extends CrudRepository<CohortStaff, Long> {

    List<CohortStaff> findByCohortId(Long cohortId);

    List<CohortStaff> findByUserId(Long userId);

    Optional<CohortStaff> findByCohortIdAndUserId(Long cohortId, Long userId);

    List<CohortStaff> findByCohortIdAndStaffRole(Long cohortId, String staffRole);

    void deleteByCohortIdAndUserId(Long cohortId, Long userId);
}
