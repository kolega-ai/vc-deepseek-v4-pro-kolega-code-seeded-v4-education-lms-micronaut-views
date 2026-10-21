package com.brightpath.channel.workflow;

import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.security.AppException;

import java.util.Map;
import java.util.Set;

/** Enrollment lifecycle: explicit edge list. */
public final class EnrollmentWorkflow {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
            Enrollment.STATUS_PENDING, Set.of(Enrollment.STATUS_ACTIVE, Enrollment.STATUS_DROPPED),
            Enrollment.STATUS_ACTIVE, Set.of(Enrollment.STATUS_COMPLETED, Enrollment.STATUS_DROPPED),
            Enrollment.STATUS_COMPLETED, Set.of(),
            Enrollment.STATUS_DROPPED, Set.of());

    private EnrollmentWorkflow() {
    }

    public static void requireTransition(String from, String to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw AppException.badRequest("Illegal enrollment transition: " + from + " -> " + to + ".");
        }
    }
}
