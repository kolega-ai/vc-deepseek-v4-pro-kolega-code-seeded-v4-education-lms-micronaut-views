package com.brightpath.channel.controller;

import com.brightpath.channel.repository.AuditEventRepository;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.views.View;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Read-only audit log viewer. The log is append-only; there is no edit or delete path. */
@Controller("/admin/audit")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AuditController {

    private final AuditEventRepository auditEvents;
    private final ViewSupport viewSupport;

    public AuditController(AuditEventRepository auditEvents, ViewSupport viewSupport) {
        this.auditEvents = auditEvents;
        this.viewSupport = viewSupport;
    }

    @Get
    @View("admin/audit")
    public Map<String, Object> list(@Nullable @QueryValue("action") String action,
                                    @Nullable @QueryValue("actor") String actor,
                                    @Nullable @QueryValue("resourceType") String resourceType,
                                    @Nullable @QueryValue("resourceId") String resourceId,
                                    @Nullable @QueryValue("from") String from,
                                    @Nullable @QueryValue("to") String to,
                                    @QueryValue(defaultValue = "0") int page,
                                    HttpRequest<?> request) {
        // Sentinels instead of NULL for the date bounds: Hibernate binds null java.time
        // parameters as bytea on Postgres, which fails in comparison expressions.
        Instant fromInstant = parseDate(from, true);
        Instant toInstant = parseDate(to, false);
        if (fromInstant == null) {
            fromInstant = Instant.EPOCH;
        }
        if (toInstant == null) {
            toInstant = Instant.now().plusSeconds(86400);
        }
        var result = auditEvents.search(blankToNull(action), blankToNull(actor),
                blankToNull(resourceType), blankToNull(resourceId), fromInstant, toInstant,
                Pageable.from(page, 25));
        return viewSupport.render(request, "Audit log", Map.of(
                "events", result.getContent(),
                "action", action == null ? "" : action,
                "actor", actor == null ? "" : actor,
                "resourceType", resourceType == null ? "" : resourceType,
                "resourceId", resourceId == null ? "" : resourceId,
                "from", from == null ? "" : from,
                "to", to == null ? "" : to,
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant parseDate(String value, boolean startOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return startOfDay ? date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    : date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
