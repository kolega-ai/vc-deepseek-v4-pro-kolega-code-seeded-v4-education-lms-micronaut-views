package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Cohort;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;

@Repository
public interface CohortRepository extends PageableRepository<Cohort, Long> {

    List<Cohort> findByCourseIdOrderByIdAsc(Long courseId);

    List<Cohort> findByCourseIdAndStatus(Long courseId, String status);

    List<Cohort> findByStatus(String status);

    @Query(value = """
            SELECT c FROM Cohort c
            WHERE (:courseId IS NULL OR c.courseId = :courseId)
              AND (:status IS NULL OR c.status = :status)
              AND (:q IS NULL OR lower(c.name) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY c.startDate DESC
            """,
            countQuery = """
            SELECT count(c) FROM Cohort c
            WHERE (:courseId IS NULL OR c.courseId = :courseId)
              AND (:status IS NULL OR c.status = :status)
              AND (:q IS NULL OR lower(c.name) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<Cohort> search(@Nullable Long courseId, @Nullable String status, @Nullable String q, Pageable pageable);
}
