package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Submission;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubmissionRepository extends CrudRepository<Submission, Long> {

    @Query("SELECT s FROM Submission s, Assignment a "
            + "WHERE a.id = s.assignmentId AND s.status = 'submitted' AND s.submittedAt > a.dueAt")
    List<Submission> findLateMarkedCandidates();

    @Query("SELECT s FROM Submission s, Assignment a "
            + "WHERE a.id = s.assignmentId AND a.courseId = :courseId AND s.releasedGrade IS NOT NULL AND s.status = 'released'")
    List<Submission> findReleasedForCourse(Long courseId);

    @Query("SELECT s FROM Submission s, Assignment a "
            + "WHERE a.id = s.assignmentId AND a.courseId = :courseId AND a.id = :assignmentId "
            + "AND s.releasedGrade IS NOT NULL AND s.status = 'released'")
    Optional<Submission> findReleased(Long courseId, Long assignmentId);

    @Query("SELECT count(s) FROM Submission s WHERE s.assignmentId = :assignmentId AND s.studentId = :studentId")
    long countByAssignmentAndStudent(Long assignmentId, Long studentId);

    @Query("SELECT a.maxPoints FROM Submission s, Assignment a WHERE a.id = s.assignmentId AND s.id = :submissionId")
    Optional<java.math.BigDecimal> maxPointsOfSubmission(Long submissionId);
}
