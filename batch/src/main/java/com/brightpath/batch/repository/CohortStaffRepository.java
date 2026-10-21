package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.CohortStaff;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface CohortStaffRepository extends CrudRepository<CohortStaff, Long> {

    List<CohortStaff> findByCohortId(Long cohortId);
}
