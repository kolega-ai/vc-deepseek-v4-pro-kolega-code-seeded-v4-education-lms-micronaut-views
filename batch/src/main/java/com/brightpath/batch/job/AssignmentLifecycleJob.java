package com.brightpath.batch.job;

import com.brightpath.batch.domain.Assignment;
import com.brightpath.batch.domain.AuditEvent;
import com.brightpath.batch.domain.Submission;
import com.brightpath.batch.repository.AssignmentRepository;
import com.brightpath.batch.repository.AuditEventRepository;
import com.brightpath.batch.repository.SettingRepository;
import com.brightpath.batch.repository.SubmissionRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Scheduled assignment lifecycle:
 *  - published assignments whose due time plus the configured grace period
 *    has passed are closed (explicit edge: published -> closed);
 *  - submissions recorded after the due time are swept to "late".
 * Both transitions are legal states of the workflow edge lists, and each
 * change writes an append-only audit event with the "system" actor.
 */
@Singleton
public class AssignmentLifecycleJob {

    private static final Logger LOG = LoggerFactory.getLogger(AssignmentLifecycleJob.class);

    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final AuditEventRepository audit;
    private final SettingRepository settings;

    public AssignmentLifecycleJob(AssignmentRepository assignments, SubmissionRepository submissions,
                                  AuditEventRepository audit, SettingRepository settings) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.audit = audit;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = "60s", initialDelay = "20s")
    @Transactional
    public void run() {
        int closed = closeOverdueAssignments();
        int markedLate = sweepLateSubmissions();
        if (closed > 0 || markedLate > 0) {
            LOG.info("lifecycle run: closed={}, markedLate={}", closed, markedLate);
        }
    }

    private int closeOverdueAssignments() {
        int graceHours = settings.findById("assignment_auto_close_grace_hours")
                .map(s -> parse(s.getValue(), 72)).orElse(72);
        Instant cutoff = Instant.now().minusSeconds(graceHours * 3600L);
        int count = 0;
        for (Assignment assignment : assignments.findOverdue(Assignment.STATUS_PUBLISHED, cutoff)) {
            assignment.setStatus(Assignment.STATUS_CLOSED);
            assignments.update(assignment);
            audit.record("assignment.auto_closed", "assignment", String.valueOf(assignment.getId()),
                    "status=published", "status=closed");
            count++;
        }
        return count;
    }

    private int sweepLateSubmissions() {
        int count = 0;
        for (Submission submission : submissions.findLateMarkedCandidates()) {
            submission.setStatus(Submission.STATUS_LATE);
            submissions.update(submission);
            audit.record("submission.marked_late", "submission", String.valueOf(submission.getId()),
                    "status=submitted", "status=late");
            count++;
        }
        return count;
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
