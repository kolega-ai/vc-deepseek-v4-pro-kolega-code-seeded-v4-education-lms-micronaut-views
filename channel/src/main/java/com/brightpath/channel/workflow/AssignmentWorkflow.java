package com.brightpath.channel.workflow;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.security.AppException;

import java.util.Map;
import java.util.Set;

/**
 * Assignment state machine. ALLOWED is an explicit edge list; any transition
 * not listed here is rejected by requireTransition.
 */
public final class AssignmentWorkflow {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
            Assignment.STATUS_DRAFT, Set.of(Assignment.STATUS_PUBLISHED, Assignment.STATUS_ARCHIVED),
            Assignment.STATUS_PUBLISHED, Set.of(Assignment.STATUS_CLOSED, Assignment.STATUS_ARCHIVED),
            Assignment.STATUS_CLOSED, Set.of(Assignment.STATUS_ARCHIVED),
            Assignment.STATUS_ARCHIVED, Set.of());

    private AssignmentWorkflow() {
    }

    public static boolean canTransition(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw AppException.badRequest("Illegal assignment transition: " + from + " -> " + to + ".");
        }
    }
}
