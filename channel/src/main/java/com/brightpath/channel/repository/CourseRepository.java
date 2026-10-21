package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Course;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;

@Repository
public interface CourseRepository extends PageableRepository<Course, Long> {

    List<Course> findByStatus(String status);

    List<Course> findByStatusOrderByTitleAsc(String status);

    long countByStatus(String status);

    @Query(value = """
            SELECT c FROM Course c
            WHERE (:q IS NULL
                   OR lower(c.title) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(c.code) LIKE lower(concat('%', cast(:q as text), '%')))
              AND (:category IS NULL OR c.category = :category)
              AND (:level IS NULL OR c.level = :level)
              AND (:status IS NULL OR c.status = :status)
            ORDER BY c.title
            """,
            countQuery = """
            SELECT count(c) FROM Course c
            WHERE (:q IS NULL
                   OR lower(c.title) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(c.code) LIKE lower(concat('%', cast(:q as text), '%')))
              AND (:category IS NULL OR c.category = :category)
              AND (:level IS NULL OR c.level = :level)
              AND (:status IS NULL OR c.status = :status)
            """)
    Page<Course> search(@Nullable String q, @Nullable String category, @Nullable String level, @Nullable String status, Pageable pageable);
}
