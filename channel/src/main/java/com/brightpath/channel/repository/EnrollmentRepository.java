package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Enrollment;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends CrudRepository<Enrollment, Long> {

    List<Enrollment> findByCohortId(Long cohortId);

    List<Enrollment> findByStudentId(Long studentId);

    List<Enrollment> findByCohortIdAndStatus(Long cohortId, String status);

    List<Enrollment> findByStudentIdAndStatus(Long studentId, String status);

    Optional<Enrollment> findByCohortIdAndStudentId(Long cohortId, Long studentId);

    long countByCohortIdAndStatus(Long cohortId, String status);
}
