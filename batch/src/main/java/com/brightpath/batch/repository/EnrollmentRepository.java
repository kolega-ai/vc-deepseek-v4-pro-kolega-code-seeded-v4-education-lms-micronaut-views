package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Enrollment;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface EnrollmentRepository extends CrudRepository<Enrollment, Long> {

    List<Enrollment> findByCohortIdAndStatus(Long cohortId, String status);
}
