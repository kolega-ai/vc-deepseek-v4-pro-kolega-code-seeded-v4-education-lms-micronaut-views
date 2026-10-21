package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.CohortStaff;
import com.brightpath.channel.domain.FileRef;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.SubmissionFile;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CohortStaffRepository;
import com.brightpath.channel.repository.FileRefRepository;
import com.brightpath.channel.repository.SubmissionFileRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.workflow.SubmissionWorkflow;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Student submissions: create, replace (pre-deadline, pre-grading), resubmit after a request. */
@Singleton
public class SubmissionService {

    /**
     * Bounded, whitelisted sort columns for the submission queue. The string
     * from the request is mapped to one of these fixed keys, each of which
     * selects a static, parameterised query in SubmissionRepository — a user
     * supplied column name can never reach the ORDER BY clause.
     */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "recent", "recent",
            "student", "student",
            "status", "status");

    private final SubmissionRepository submissions;
    private final SubmissionFileRepository submissionFiles;
    private final FileRefRepository fileRefs;
    private final AssignmentRepository assignments;
    private final CohortRepository cohorts;
    private final CohortStaffRepository cohortStaff;
    private final UserRepository users;
    private final FileStorageService fileStorage;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AssignmentService assignmentService;

    public SubmissionService(SubmissionRepository submissions, SubmissionFileRepository submissionFiles,
                             FileRefRepository fileRefs, AssignmentRepository assignments,
                             CohortRepository cohorts, CohortStaffRepository cohortStaff, UserRepository users,
                             FileStorageService fileStorage, AuditService audit,
                             NotificationService notifications, AssignmentService assignmentService) {
        this.submissions = submissions;
        this.submissionFiles = submissionFiles;
        this.fileRefs = fileRefs;
        this.assignments = assignments;
        this.cohorts = cohorts;
        this.cohortStaff = cohortStaff;
        this.users = users;
        this.fileStorage = fileStorage;
        this.audit = audit;
        this.notifications = notifications;
        this.assignmentService = assignmentService;
    }

    public Optional<Submission> findForStudent(Long assignmentId, Long studentId) {
        return submissions.findByAssignmentIdAndStudentIdAndAttempt(assignmentId, studentId, 1);
    }

    public List<Submission> listForStudent(Long studentId) {
        return submissions.findByStudentIdOrderBySubmittedAtDesc(studentId);
    }

    public Page<Submission> queue(SessionUser actor, @Nullable Long courseId, @Nullable Long assignmentId,
                                  @Nullable String status, @Nullable String q, String sort, Pageable pageable) {
        Long effectiveCourseId = courseId;
        if (assignmentId != null) {
            Assignment assignment = assignments.findById(assignmentId)
                    .orElseThrow(() -> AppException.notFound("Assignment not found."));
            if (!actor.canManageCourse(assignment.getCourseId())) {
                throw AppException.forbidden("You cannot view this queue.");
            }
            effectiveCourseId = effectiveCourseId == null ? assignment.getCourseId() : effectiveCourseId;
        } else if (effectiveCourseId != null && !actor.canManageCourse(effectiveCourseId)) {
            throw AppException.forbidden("You cannot view this queue.");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status;
        String normalizedQ = q == null || q.isBlank() ? null : q.trim();
        String column = SORT_WHITELIST.getOrDefault(sort == null ? "recent" : sort, "recent");
        return switch (column) {
            case "student" -> submissions.queueOrderByStudent(effectiveCourseId, assignmentId, normalizedStatus, normalizedQ, pageable);
            case "status" -> submissions.queueOrderByStatus(effectiveCourseId, assignmentId, normalizedStatus, normalizedQ, pageable);
            default -> submissions.queueOrderByRecent(effectiveCourseId, assignmentId, normalizedStatus, normalizedQ, pageable);
        };
    }

    /**
     * Submit (or replace, or resubmit) an assignment as a student.
     * Replacement is allowed only before the due time and only while grading
     * has not started (no draft grade). Late submissions are marked "late".
     */
    @Transactional
    public Submission submit(SessionUser actor, Long assignmentId, String text, List<CompletedFileUpload> uploads)
            throws IOException {
        if (!actor.isStudent()) {
            throw AppException.forbidden("Only students can submit assignments.");
        }
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.isEnrolledIn(assignment.getCourseId())) {
            throw AppException.forbidden("You are not enrolled in this assignment's course.");
        }
        if (!Assignment.STATUS_PUBLISHED.equals(assignment.getStatus())) {
            throw AppException.badRequest("This assignment is not open for submissions.");
        }
        if (assignment.getAvailableAt() != null && assignment.getAvailableAt().isAfter(Instant.now())) {
            throw AppException.badRequest("This assignment is not available yet.");
        }
        String trimmedText = text == null ? null : text.trim();
        List<CompletedFileUpload> effectiveUploads = uploads == null ? List.of() : uploads;
        if ((trimmedText == null || trimmedText.isBlank()) && effectiveUploads.isEmpty()) {
            throw AppException.badRequest("Add some text or attach at least one file before submitting.");
        }
        int maxFiles = Math.min(assignment.getMaxFiles(), actor.maxSubmissionFiles());
        if (effectiveUploads.size() > maxFiles) {
            throw AppException.badRequest("You can attach at most " + maxFiles + " file(s) to this assignment.");
        }

        Submission existing = findForStudent(assignmentId, actor.id()).orElse(null);
        boolean late = Instant.now().isAfter(assignment.getDueAt());
        String targetStatus = late ? Submission.STATUS_LATE : Submission.STATUS_SUBMITTED;
        Submission submission;
        String action;

        if (existing == null) {
            submission = new Submission();
            submission.setAssignmentId(assignmentId);
            submission.setStudentId(actor.id());
            submission.setAttempt(1);
            submission.setStatus(targetStatus);
            submission.setSubmittedAt(Instant.now());
            submission.setTextContent(trimmedText);
            submissions.save(submission);
            action = late ? "submission.submitted_late" : "submission.submitted";
        } else {
            switch (existing.getStatus()) {
                case Submission.STATUS_SUBMITTED, Submission.STATUS_LATE -> {
                    if (existing.getDraftGrade() != null) {
                        throw AppException.conflict("Grading has already started for this submission; it can no longer be replaced.");
                    }
                    if (!Instant.now().isBefore(assignment.getDueAt())) {
                        throw AppException.badRequest("The due date has passed; this submission can no longer be replaced.");
                    }
                    replaceContent(existing, trimmedText, effectiveUploads, actor, assignment);
                    existing.setStatus(existing.getStatus());
                    existing.setSubmittedAt(Instant.now());
                    submissions.update(existing);
                    action = "submission.replaced";
                    submission = existing;
                }
                case Submission.STATUS_NEEDS_RESUBMISSION -> {
                    replaceContent(existing, trimmedText, effectiveUploads, actor, assignment);
                    existing.setStatus(targetStatus);
                    existing.setSubmittedAt(Instant.now());
                    existing.setDraftGrade(null);
                    existing.setDraftFeedback(null);
                    existing.setDraftPrivateNote(null);
                    existing.setDraftGradedBy(null);
                    existing.setDraftGradedAt(null);
                    submissions.update(existing);
                    action = "submission.resubmitted";
                    submission = existing;
                }
                case Submission.STATUS_GRADED ->
                        throw AppException.conflict("Grading has already started; this submission can no longer be changed.");
                default -> throw AppException.conflict("This submission has been released and can no longer be changed.");
            }
        }

        Set<String> allowedExtensions = assignmentService.allowedFileTypesOf(assignment);
        for (CompletedFileUpload upload : effectiveUploads) {
            FileRef file = fileStorage.store(upload, actor, FileRef.CATEGORY_SUBMISSION,
                    assignment.getCourseId(), assignmentId, submission.getId(), allowedExtensions);
            SubmissionFile link = new SubmissionFile();
            link.setSubmissionId(submission.getId());
            link.setFileId(file.getId());
            submissionFiles.save(link);
        }

        notifyStaff(assignment, actor.displayName() + " " + actionForNotify(action) + " for " + assignment.getTitle());
        audit.record(actor, action, "submission", String.valueOf(submission.getId()),
                null, "assignmentId=" + assignmentId + ",status=" + submission.getStatus());
        return submission;
    }

    private void replaceContent(Submission submission, String text, List<CompletedFileUpload> uploads,
                                SessionUser actor, Assignment assignment) {
        submission.setTextContent(text);
        // Remove previous attachments (rows, metadata and disk files).
        for (SubmissionFile link : submissionFiles.findBySubmissionId(submission.getId())) {
            fileRefs.findById(link.getFileId()).ifPresent(file -> {
                if (FileRef.CATEGORY_SUBMISSION.equals(file.getCategory()) && file.getOwnerId().equals(actor.id())) {
                    fileStorage.delete(file);
                    fileRefs.deleteById(file.getId());
                }
            });
        }
        submissionFiles.deleteBySubmissionId(submission.getId());
    }

    private void notifyStaff(Assignment assignment, String message) {
        for (CohortStaff staff : staffOf(assignment)) {
            notifications.notify(staff.getUserId(), NotificationService.KIND_SUBMISSION_RECEIVED,
                    "New submission activity", message, "/assignments/" + assignment.getId() + "/submissions");
        }
    }

    private List<CohortStaff> staffOf(Assignment assignment) {
        if (assignment.getCohortId() != null) {
            return cohortStaff.findByCohortId(assignment.getCohortId());
        }
        List<CohortStaff> result = new ArrayList<>();
        for (var cohort : cohorts.findByCourseIdOrderByIdAsc(assignment.getCourseId())) {
            for (CohortStaff staff : cohortStaff.findByCohortId(cohort.getId())) {
                if (result.stream().noneMatch(s -> s.getUserId().equals(staff.getUserId()))) {
                    result.add(staff);
                }
            }
        }
        return result;
    }

    private String actionForNotify(String action) {
        return switch (action) {
            case "submission.submitted_late" -> "submitted (late)";
            case "submission.replaced" -> "replaced their submission";
            case "submission.resubmitted" -> "resubmitted";
            default -> "submitted";
        };
    }

    public List<SubmissionFile> filesFor(Long submissionId) {
        return submissionFiles.findBySubmissionId(submissionId);
    }

    /** A copy safe for students: draft grade/feedback/private notes are stripped entirely. */
    public static Submission studentVisibleCopy(Submission submission) {
        Submission copy = new Submission();
        copy.setId(submission.getId());
        copy.setAssignmentId(submission.getAssignmentId());
        copy.setStudentId(submission.getStudentId());
        copy.setAttempt(submission.getAttempt());
        copy.setStatus(submission.getStatus());
        copy.setSubmittedAt(submission.getSubmittedAt());
        copy.setTextContent(submission.getTextContent());
        copy.setReleasedGrade(submission.getReleasedGrade());
        copy.setReleasedFeedback(submission.getReleasedFeedback());
        copy.setReleasedAt(submission.getReleasedAt());
        return copy;
    }

    public User studentOf(Submission submission) {
        return users.findById(submission.getStudentId()).orElse(null);
    }
}
