package com.brightpath.channel.workflow;

import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.security.AppException;

import java.util.Map;
import java.util.Set;

/**
 * Submission state machine. ALLOWED is an explicit edge list:
 *   submitted -> late               (submit happened after the due time; applied at submit or by the batch sweep)
 *   submitted -> graded, late -> graded                     (staff saved a draft grade)
 *   graded -> released                                      (instructor/admin released the grade)
 *   graded -> needs_resubmission                            (staff requested a new version)
 *   needs_resubmission -> submitted, needs_resubmission -> late  (student resubmitted)
 *   released: terminal
 */
public final class SubmissionWorkflow {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
            Submission.STATUS_SUBMITTED, Set.of(Submission.STATUS_LATE, Submission.STATUS_GRADED),
            Submission.STATUS_LATE, Set.of(Submission.STATUS_GRADED),
            Submission.STATUS_GRADED, Set.of(Submission.STATUS_RELEASED, Submission.STATUS_NEEDS_RESUBMISSION),
            Submission.STATUS_NEEDS_RESUBMISSION, Set.of(Submission.STATUS_SUBMITTED, Submission.STATUS_LATE),
            Submission.STATUS_RELEASED, Set.of());

    private SubmissionWorkflow() {
    }

    public static boolean canTransition(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw AppException.badRequest("Illegal submission transition: " + from + " -> " + to + ".");
        }
    }
}
