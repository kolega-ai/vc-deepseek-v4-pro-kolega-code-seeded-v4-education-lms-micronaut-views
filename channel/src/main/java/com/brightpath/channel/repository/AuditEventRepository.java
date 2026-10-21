package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.AuditEvent;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.time.Instant;

/** Read-only repository. The audit log is append-only and has no update/delete API. */
@Repository
public interface AuditEventRepository extends PageableRepository<AuditEvent, Long> {

    @Query(value = """
            SELECT a FROM AuditEvent a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:actor IS NULL OR lower(a.actorUsername) LIKE lower(concat('%', cast(:actor as text), '%')))
              AND (:resourceType IS NULL OR a.resourceType = :resourceType)
              AND (:resourceId IS NULL OR a.resourceId = :resourceId)
              AND a.createdAt >= :from
              AND a.createdAt <= :to
            ORDER BY a.createdAt DESC, a.id DESC
            """,
            countQuery = """
            SELECT count(a) FROM AuditEvent a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:actor IS NULL OR lower(a.actorUsername) LIKE lower(concat('%', cast(:actor as text), '%')))
              AND (:resourceType IS NULL OR a.resourceType = :resourceType)
              AND (:resourceId IS NULL OR a.resourceId = :resourceId)
              AND a.createdAt >= :from
              AND a.createdAt <= :to
            """)
    Page<AuditEvent> search(@Nullable String action, @Nullable String actor,
                            @Nullable String resourceType, @Nullable String resourceId,
                            @Nullable java.time.Instant from, @Nullable java.time.Instant to,
                            Pageable pageable);
}
