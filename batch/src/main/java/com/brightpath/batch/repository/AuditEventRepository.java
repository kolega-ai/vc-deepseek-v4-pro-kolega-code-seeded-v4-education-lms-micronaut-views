package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.AuditEvent;
import io.micronaut.data.repository.CrudRepository;

@Repository
public interface AuditEventRepository extends CrudRepository<AuditEvent, Long> {

    /** Batch jobs write audit events with the system actor. */
    default void record(String action, String resourceType, String resourceId, String before, String after) {
        AuditEvent event = new AuditEvent();
        event.setActorUsername("system");
        event.setActorRole("SYSTEM");
        event.setAuthzBasis("scheduled-job");
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setBeforeState(before);
        event.setAfterState(after);
        save(event);
    }
}
