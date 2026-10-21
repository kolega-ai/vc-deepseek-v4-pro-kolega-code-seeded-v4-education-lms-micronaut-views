package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Submission;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubmissionRepository extends PageableRepository<Submission, Long> {

    Optional<Submission> findByAssignmentIdAndStudentIdAndAttempt(Long assignmentId, Long studentId, int attempt);

    List<Submission> findByAssignmentId(Long assignmentId);

    List<Submission> findByStudentIdOrderBySubmittedAtDesc(Long studentId);

    List<Submission> findByAssignmentIdAndStatusIn(Long assignmentId, Collection<String> statuses);

    long countByAssignmentId(Long assignmentId);

    long countByAssignmentIdAndStatus(Long assignmentId, String status);

    @Query("SELECT count(s) FROM Submission s, Assignment a "
            + "WHERE a.id = s.assignmentId AND a.courseId = :courseId AND s.status IN (:statuses)")
    long countByCourseAndStatusIn(Long courseId, Collection<String> statuses);

    // ---- Grading queue. The sort column is chosen from a bounded whitelist in SubmissionService;
    //      these three parameterised queries are the only orderings available. ----

    @Query(value = """
            SELECT s FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY s.submittedAt DESC
            """,
            countQuery = """
            SELECT count(s) FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<Submission> queueOrderByRecent(@Nullable Long courseId, @Nullable Long assignmentId,
                                        @Nullable String status, @Nullable String q, Pageable pageable);

    @Query(value = """
            SELECT s FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY lower(u.displayName) ASC, s.submittedAt DESC
            """,
            countQuery = """
            SELECT count(s) FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<Submission> queueOrderByStudent(@Nullable Long courseId, @Nullable Long assignmentId,
                                         @Nullable String status, @Nullable String q, Pageable pageable);

    @Query(value = """
            SELECT s FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY s.status ASC, s.submittedAt DESC
            """,
            countQuery = """
            SELECT count(s) FROM Submission s, Assignment a, User u
            WHERE a.id = s.assignmentId AND u.id = s.studentId
              AND (:courseId IS NULL OR a.courseId = :courseId)
              AND (:assignmentId IS NULL OR s.assignmentId = :assignmentId)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%'))
                          OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<Submission> queueOrderByStatus(@Nullable Long courseId, @Nullable Long assignmentId,
                                        @Nullable String status, @Nullable String q, Pageable pageable);
}
