package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.DiscussionThread;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface DiscussionThreadRepository extends PageableRepository<DiscussionThread, Long> {

    @Query(value = """
            SELECT t FROM DiscussionThread t
            WHERE t.courseId = :courseId AND t.deletedAt IS NULL
              AND (:q IS NULL
                   OR lower(t.title) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(t.body) LIKE lower(concat('%', cast(:q as text), '%')))
            ORDER BY t.pinned DESC, t.updatedAt DESC
            """,
            countQuery = """
            SELECT count(t) FROM DiscussionThread t
            WHERE t.courseId = :courseId AND t.deletedAt IS NULL
              AND (:q IS NULL
                   OR lower(t.title) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(t.body) LIKE lower(concat('%', cast(:q as text), '%')))
            """)
    Page<DiscussionThread> search(Long courseId, @Nullable String q, Pageable pageable);
}
