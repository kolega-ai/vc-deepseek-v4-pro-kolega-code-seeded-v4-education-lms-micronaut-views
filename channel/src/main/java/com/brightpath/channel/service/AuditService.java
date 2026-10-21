package com.brightpath.channel.service;

import com.brightpath.channel.domain.AuditEvent;
import com.brightpath.channel.repository.AuditEventRepository;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.server.util.HttpClientAddressResolver;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes append-only audit events. Every state transition and privileged
 * action flows through here; no code path updates or deletes audit rows
 * (the database trigger enforces this too).
 */
@Singleton
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final HttpClientAddressResolver addressResolver;

    public AuditService(AuditEventRepository repository, HttpClientAddressResolver addressResolver) {
        this.repository = repository;
        this.addressResolver = addressResolver;
    }

    public void record(@Nullable SessionUser actor, String action, String resourceType,
                       @Nullable String resourceId, @Nullable String before, @Nullable String after) {
        AuditEvent event = new AuditEvent();
        if (actor != null) {
            event.setActorId(actor.id());
            event.setActorUsername(actor.username());
            event.setActorRole(actor.role());
            event.setAuthzBasis(actor.authzBasis());
        } else {
            event.setActorUsername("system");
            event.setActorRole("SYSTEM");
            event.setAuthzBasis("scheduled-job");
        }
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setBeforeState(before);
        event.setAfterState(after);
        try {
            event.setIpAddress(resolveIp());
        } catch (Exception e) {
            LOG.debug("could not resolve request ip for audit: {}", e.getMessage());
        }
        repository.save(event);
    }

    /** Read-auditing: exactly one event per request naming the resource class and the count returned. */
    public void recordRead(@Nullable SessionUser actor, String resourceClass, int count) {
        record(actor, "read", resourceClass, null, null, "count=" + count);
    }

    private String resolveIp() {
        return ServerRequestContext.currentRequest()
                .map(addressResolver::resolve)
                .orElse(null);
    }
}
