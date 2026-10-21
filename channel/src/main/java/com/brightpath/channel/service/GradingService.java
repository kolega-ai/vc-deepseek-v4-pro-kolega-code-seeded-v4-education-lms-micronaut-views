package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.GradeChange;
import com.brightpath.channel.domain.GradeSyncEvent;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.GradeChangeRepository;
import com.brightpath.channel.repository.GradeSyncEventRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.workflow.SubmissionWorkflow;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grading workflow: draft grades (instructor/TA), release to student
 * (instructor/admin only), resubmission requests, and grade change history.
 * Draft grades and private notes are never visible to students.
 */
@Singleton
public class GradingService {

    private final SubmissionRepository submissions;
    private final AssignmentRepository assignments;
    private final GradeChangeRepository gradeChanges;
    private final GradeSyncEventRepository syncEvents;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MailService mail;
    private final SettingsService settings;
    private final WebhookService webhooks;
    private final DecisionClient decisionClient;
    private final CoreMockClient coreMockClient;

    public GradingService(SubmissionRepository submissions, AssignmentRepository assignments,
                          GradeChangeRepository gradeChanges, GradeSyncEventRepository syncEvents,
                          UserRepository users, AuditService audit, NotificationService notifications,
                          MailService mail, SettingsService settings, WebhookService webhooks,
                          DecisionClient decisionClient, CoreMockClient coreMockClient) {
        this.submissions = submissions;
        this.assignments = assignments;
        this.gradeChanges = gradeChanges;
        this.syncEvents = syncEvents;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
        this.mail = mail;
        this.settings = settings;
        this.webhooks = webhooks;
        this.decisionClient = decisionClient;
        this.coreMockClient = coreMockClient;
    }

    /**
     * Saves a draft grade. Teaching assistants may draft grades/feedback but
     * never release them. Points are validated against the rubric server-side.
     */
    @Transactional
    public Submission saveDraft(SessionUser actor, Long submissionId, Map<String, BigDecimal> rubricScores,
                                BigDecimal overallPoints, String feedback, String privateNote) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> AppException.notFound("Submission not found."));
        Assignment assignment = assignments.findById(submission.getAssignmentId())
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.staffCourses().containsKey(assignment.getCourseId()) && !actor.isAdmin()) {
            throw AppException.forbidden("This submission is outside your assigned courses.");
        }
        if (!Set.of(Submission.STATUS_SUBMITTED, Submission.STATUS_LATE, Submission.STATUS_GRADED)
                .contains(submission.getStatus())) {
            throw AppException.badRequest("This submission cannot be graded in its current state.");
        }

        BigDecimal points = computePoints(assignment, rubricScores, overallPoints);

        BigDecimal oldGrade = submission.getDraftGrade();
        String oldFeedback = submission.getDraftFeedback();
        String oldStatus = submission.getStatus();
        submission.setDraftGrade(points);
        submission.setDraftFeedback(feedback == null ? null : feedback.trim());
        submission.setDraftPrivateNote(privateNote == null ? null : privateNote.trim());
        submission.setDraftGradedBy(actor.id());
        submission.setDraftGradedAt(Instant.now());
        if (!Submission.STATUS_GRADED.equals(oldStatus)) {
            SubmissionWorkflow.requireTransition(oldStatus, Submission.STATUS_GRADED);
            submission.setStatus(Submission.STATUS_GRADED);
        }
        submissions.update(submission);

        GradeChange change = new GradeChange();
        change.setSubmissionId(submissionId);
        change.setChangedBy(actor.id());
        change.setChangeType(GradeChange.TYPE_DRAFT);
        change.setOldGrade(oldGrade);
        change.setNewGrade(points);
        change.setOldFeedback(oldFeedback);
        change.setNewFeedback(submission.getDraftFeedback());
        gradeChanges.save(change);

        audit.record(actor, "submission.grade_drafted", "submission", String.valueOf(submissionId),
                "grade=" + oldGrade + ",status=" + oldStatus,
                "grade=" + points + ",status=" + submission.getStatus());
        return submission;
    }

    /** Releases the draft grade to the student. Instructor/admin only. Triggers mail, webhook and SIS sync. */
    @Transactional
    public Submission release(SessionUser actor, Long submissionId) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> AppException.notFound("Submission not found."));
        Assignment assignment = assignments.findById(submission.getAssignmentId())
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.canPublishOrRelease(assignment.getCourseId())) {
            throw AppException.forbidden("Teaching assistants cannot release grades; only instructors or administrators can.");
        }
        if (!Submission.STATUS_GRADED.equals(submission.getStatus()) || submission.getDraftGrade() == null) {
            throw AppException.badRequest("Save a draft grade before releasing it.");
        }
        SubmissionWorkflow.requireTransition(submission.getStatus(), Submission.STATUS_RELEASED);
        BigDecimal grade = submission.getDraftGrade();
        submission.setStatus(Submission.STATUS_RELEASED);
        submission.setReleasedGrade(grade);
        submission.setReleasedFeedback(submission.getDraftFeedback());
        submission.setReleasedAt(Instant.now());
        submission.setReleasedBy(actor.id());
        submissions.update(submission);

        GradeChange change = new GradeChange();
        change.setSubmissionId(submissionId);
        change.setChangedBy(actor.id());
        change.setChangeType(GradeChange.TYPE_RELEASE);
        change.setNewGrade(grade);
        change.setNewFeedback(submission.getReleasedFeedback());
        gradeChanges.save(change);

        audit.record(actor, "submission.grade_released", "submission", String.valueOf(submissionId),
                "status=graded", "status=released,grade=" + grade);

        User student = users.findById(submission.getStudentId()).orElse(null);
        if (student != null) {
            notifications.notify(student.getId(), NotificationService.KIND_GRADE_RELEASED,
                    "Grade released: " + assignment.getTitle(),
                    "You received " + grade + " / " + assignment.getMaxPoints() + ".",
                    "/assignments/" + assignment.getId());
            mail.queue(student.getEmail(), "Your grade for " + assignment.getTitle(),
                    "Hi " + student.getDisplayName() + ",\n\nYour grade for \"" + assignment.getTitle()
                            + "\" has been released: " + grade + " / " + assignment.getMaxPoints()
                            + ".\n\nFeedback:\n" + (submission.getReleasedFeedback() == null ? "(none)" : submission.getReleasedFeedback())
                            + "\n\n" + settings.orgName() + " team",
                    MailService.TEMPLATE_GRADE_RELEASED);
        }

        webhooks.dispatch("grade.released", Map.of(
                "submissionId", submissionId,
                "assignmentId", assignment.getId(),
                "courseId", assignment.getCourseId(),
                "studentId", submission.getStudentId(),
                "grade", grade.toPlainString(),
                "maxPoints", assignment.getMaxPoints().toPlainString(),
                "releasedAt", submission.getReleasedAt().toString()));

        syncToVendorCore(actor, submission, assignment, grade);
        return submission;
    }

    /** Requests a new version from the student; allowed from submitted/late/graded. */
    @Transactional
    public Submission requestResubmission(SessionUser actor, Long submissionId, String note) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> AppException.notFound("Submission not found."));
        Assignment assignment = assignments.findById(submission.getAssignmentId())
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.staffCourses().containsKey(assignment.getCourseId()) && !actor.isAdmin()) {
            throw AppException.forbidden("This submission is outside your assigned courses.");
        }
        if (!Set.of(Submission.STATUS_SUBMITTED, Submission.STATUS_LATE, Submission.STATUS_GRADED)
                .contains(submission.getStatus())) {
            throw AppException.badRequest("A resubmission cannot be requested in this state.");
        }
        String oldStatus = submission.getStatus();
        SubmissionWorkflow.requireTransition(oldStatus, Submission.STATUS_NEEDS_RESUBMISSION);
        submission.setStatus(Submission.STATUS_NEEDS_RESUBMISSION);
        submissions.update(submission);

        GradeChange change = new GradeChange();
        change.setSubmissionId(submissionId);
        change.setChangedBy(actor.id());
        change.setChangeType(GradeChange.TYPE_REQUEST_RESUBMISSION);
        change.setNote(note);
        gradeChanges.save(change);

        audit.record(actor, "submission.resubmission_requested", "submission", String.valueOf(submissionId),
                "status=" + oldStatus, "status=needs_resubmission");

        User student = users.findById(submission.getStudentId()).orElse(null);
        if (student != null) {
            notifications.notify(student.getId(), NotificationService.KIND_RESUBMISSION_REQUESTED,
                    "Resubmission requested: " + assignment.getTitle(),
                    note == null || note.isBlank()
                            ? "Your instructor asked you to submit a new version."
                            : note,
                    "/assignments/" + assignment.getId());
        }
        return submission;
    }

    public List<GradeChange> history(Long submissionId) {
        return gradeChanges.findBySubmissionIdOrderByCreatedAtDesc(submissionId);
    }

    private BigDecimal computePoints(Assignment assignment, Map<String, BigDecimal> rubricScores, BigDecimal overallPoints) {
        List<RubricSupport.RubricCriterion> criteria;
        try {
            criteria = RubricSupport.parse(assignment.getRubric());
        } catch (RubricSupport.RubricParseException e) {
            criteria = List.of();
        }
        BigDecimal points;
        if (criteria.isEmpty()) {
            if (overallPoints == null) {
                throw AppException.badRequest("Please enter the points awarded.");
            }
            points = overallPoints;
        } else {
            if (rubricScores == null || rubricScores.size() != criteria.size()) {
                throw AppException.badRequest("Please score every rubric criterion.");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (RubricSupport.RubricCriterion criterion : criteria) {
                BigDecimal score = rubricScores.get(criterion.key());
                if (score == null) {
                    throw AppException.badRequest("Missing score for \"" + criterion.name() + "\".");
                }
                if (score.signum() < 0 || score.compareTo(criterion.points()) > 0) {
                    throw AppException.badRequest("Score for \"" + criterion.name()
                            + "\" must be between 0 and " + criterion.points() + ".");
                }
                sum = sum.add(score);
            }
            points = sum;
        }
        if (points.signum() < 0 || points.compareTo(assignment.getMaxPoints()) > 0) {
            throw AppException.badRequest("Points must be between 0 and " + assignment.getMaxPoints() + ".");
        }
        return points;
    }

    /**
     * Syncs the released grade to the vendor core (SIS mock). The channel asks
     * decision to mint a scope token; the core mock verifies that token and
     * enforces its scope claim — it never trusts a raw scope parameter.
     */
    private void syncToVendorCore(SessionUser actor, Submission submission, Assignment assignment, BigDecimal grade) {
        GradeSyncEvent event = new GradeSyncEvent();
        event.setCourseId(assignment.getCourseId());
        event.setSubmissionIds(String.valueOf(submission.getId()));
        try {
            String scopeToken = actor.decisionToken() != null
                    ? decisionClient.mintScopeToken(actor.decisionToken(), "grade-sync", assignment.getCourseId(), 300)
                    : decisionClient.mintScopeTokenInternal(actor.username(), "grade-sync", assignment.getCourseId(), 300);
            CoreMockClient.SyncResult result = coreMockClient.syncGrades(scopeToken, assignment.getCourseId(),
                    List.of(new CoreMockClient.GradeRecord(
                            submission.getStudentId(), assignment.getId(), grade.toPlainString(),
                            assignment.getMaxPoints().toPlainString(),
                            submission.getReleasedAt() == null ? Instant.now() : submission.getReleasedAt())));
            event.setSyncId(result.syncId());
            event.setStatus(result.rejected().isEmpty() ? GradeSyncEvent.STATUS_SUCCESS : GradeSyncEvent.STATUS_PARTIAL);
            event.setResponseText("accepted=" + result.accepted() + ",rejected=" + result.rejected().size());
        } catch (Exception e) {
            event.setStatus(GradeSyncEvent.STATUS_FAILED);
            event.setResponseText("sync error: " + e.getMessage());
        }
        syncEvents.save(event);
        audit.record(actor, "submission.grade_synced", "grade_sync", event.getSyncId(),
                null, "courseId=" + assignment.getCourseId() + ",status=" + event.getStatus());
    }
}
