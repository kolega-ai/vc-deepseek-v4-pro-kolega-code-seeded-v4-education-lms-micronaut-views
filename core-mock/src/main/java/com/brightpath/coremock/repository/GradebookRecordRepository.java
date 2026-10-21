package com.brightpath.coremock.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.coremock.domain.GradebookRecord;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GradebookRecordRepository extends CrudRepository<GradebookRecord, Long> {

    List<GradebookRecord> findByCourseIdOrderByReceivedAtDesc(Long courseId);

    Optional<GradebookRecord> findByCourseIdAndAssignmentIdAndStudentId(Long courseId, Long assignmentId, Long studentId);
}
