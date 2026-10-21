package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Assignment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AssignmentRepository extends PageableRepository<Assignment, Long> {

    List<Assignment> findByCourseIdOrderByDueAtAsc(Long courseId);

    List<Assignment> findByCourseIdAndStatus(Long courseId, String status);

    List<Assignment> findByStatus(String status);

    List<Assignment> findByStatusAndDueAtLessThan(String status, Instant dueAt);

    long countByCourseIdAndStatus(Long courseId, String status);

    @Query(value = """
            SELECT a FROM Assignment a
            WHERE (:courseId IS NULL OR a.courseId = :courseId)
              AND (:status IS NULL OR a.status = :status)
              AND (:q IS NULL OR lower(a.title) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY a.dueAt DESC
            """,
            countQuery = """
            SELECT count(a) FROM Assignment a
            WHERE (:courseId IS NULL OR a.courseId = :courseId)
              AND (:status IS NULL OR a.status = :status)
              AND (:q IS NULL OR lower(a.title) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<Assignment> search(@Nullable Long courseId, @Nullable String status, @Nullable String q, Pageable pageable);
}
