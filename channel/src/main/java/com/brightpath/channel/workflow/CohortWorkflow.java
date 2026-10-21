package com.brightpath.channel.workflow;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.security.AppException;

import java.util.Map;
import java.util.Set;

/** Cohort lifecycle: explicit edge list. */
public final class CohortWorkflow {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
            Cohort.STATUS_UPCOMING, Set.of(Cohort.STATUS_ACTIVE, Cohort.STATUS_CANCELLED),
            Cohort.STATUS_ACTIVE, Set.of(Cohort.STATUS_COMPLETED, Cohort.STATUS_CANCELLED),
            Cohort.STATUS_COMPLETED, Set.of(),
            Cohort.STATUS_CANCELLED, Set.of());

    private CohortWorkflow() {
    }

    public static void requireTransition(String from, String to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw AppException.badRequest("Illegal cohort transition: " + from + " -> " + to + ".");
        }
    }
}
