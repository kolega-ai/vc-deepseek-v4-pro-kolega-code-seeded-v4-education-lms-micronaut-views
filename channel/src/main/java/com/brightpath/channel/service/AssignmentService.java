package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.workflow.AssignmentWorkflow;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Assignment lifecycle: create, edit drafts, publish/close/archive, due-date extensions. */
@Singleton
public class AssignmentService {

    public static final Set<String> RUBRIC_REQUIRED_STATUSES = Set.of(Assignment.STATUS_DRAFT);

    private final AssignmentRepository assignments;
    private final CohortRepository cohorts;
    private final EnrollmentRepository enrollments;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MailService mail;
    private final SettingsService settings;

    public AssignmentService(AssignmentRepository assignments, CohortRepository cohorts,
                             EnrollmentRepository enrollments, SubmissionRepository submissions,
                             UserRepository users, AuditService audit, NotificationService notifications,
                             MailService mail, SettingsService settings) {
        this.assignments = assignments;
        this.cohorts = cohorts;
        this.enrollments = enrollments;
        this.submissions = submissions;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
        this.mail = mail;
        this.settings = settings;
    }

    public Page<Assignment> search(Long courseId, String status, String q, Pageable pageable) {
        return assignments.search(courseId, status, q, pageable);
    }

    public List<Assignment> listForCourse(Long courseId) {
        return assignments.findByCourseIdOrderByDueAtAsc(courseId);
    }

    @Transactional
    public Assignment create(SessionUser actor, Long courseId, Long cohortId, String title, String summary,
                             String instructions, Instant dueAt, Instant availableAt, boolean allowLate,
                             BigDecimal maxPoints, String rubricJson, String allowedFileTypes, int maxFiles) {
        if (!actor.canPublishOrRelease(courseId)) {
            throw AppException.forbidden("Only instructors or administrators can create assignments.");
        }
        if (title == null || title.isBlank() || title.length() > 200) {
            throw AppException.badRequest("Please provide a title (max 200 characters).");
        }
        if (instructions == null || instructions.isBlank()) {
            throw AppException.badRequest("Please provide instructions for students.");
        }
        if (maxPoints == null || maxPoints.signum() <= 0 || maxPoints.compareTo(new BigDecimal("1000")) > 0) {
            throw AppException.badRequest("Max points must be between 0 and 1000.");
        }
        validateRubric(rubricJson, maxPoints);
        validateAllowedFileTypes(allowedFileTypes);
        if (cohortId != null) {
            cohorts.findById(cohortId)
                    .filter(c -> c.getCourseId().equals(courseId))
                    .orElseThrow(() -> AppException.badRequest("Cohort does not belong to this course."));
        }
        Assignment assignment = new Assignment();
        assignment.setCourseId(courseId);
        assignment.setCohortId(cohortId);
        assignment.setTitle(title.trim());
        assignment.setSummary(summary);
        assignment.setInstructions(instructions);
        assignment.setDueAt(dueAt);
        assignment.setAvailableAt(availableAt);
        assignment.setAllowLate(allowLate);
        assignment.setMaxPoints(maxPoints);
        assignment.setRubric(rubricJson == null || rubricJson.isBlank() ? "[]" : rubricJson);
        assignment.setAllowedFileTypes(normalizeFileTypes(allowedFileTypes));
        assignment.setMaxFiles(Math.min(Math.max(maxFiles, 1), 10));
        assignment.setStatus(Assignment.STATUS_DRAFT);
        assignment.setCreatedBy(actor.id());
        assignments.save(assignment);
        audit.record(actor, "assignment.created", "assignment", String.valueOf(assignment.getId()),
                null, "courseId=" + courseId + ",status=draft");
        return assignment;
    }

    @Transactional
    public Assignment update(SessionUser actor, Long assignmentId, String title, String summary,
                             String instructions, Instant dueAt, Instant availableAt, boolean allowLate,
                             BigDecimal maxPoints, String rubricJson, String allowedFileTypes, int maxFiles) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.canPublishOrRelease(assignment.getCourseId())) {
            throw AppException.forbidden("You cannot edit this assignment.");
        }
        if (!Assignment.STATUS_DRAFT.equals(assignment.getStatus())) {
            throw AppException.badRequest("Only draft assignments can be edited. Extend the due date or archive instead.");
        }
        if (maxPoints == null || maxPoints.signum() <= 0 || maxPoints.compareTo(new BigDecimal("1000")) > 0) {
            throw AppException.badRequest("Max points must be between 0 and 1000.");
        }
        validateRubric(rubricJson, maxPoints);
        validateAllowedFileTypes(allowedFileTypes);
        assignment.setTitle(title.trim());
        assignment.setSummary(summary);
        assignment.setInstructions(instructions);
        assignment.setDueAt(dueAt);
        assignment.setAvailableAt(availableAt);
        assignment.setAllowLate(allowLate);
        assignment.setMaxPoints(maxPoints);
        assignment.setRubric(rubricJson == null || rubricJson.isBlank() ? "[]" : rubricJson);
        assignment.setAllowedFileTypes(normalizeFileTypes(allowedFileTypes));
        assignment.setMaxFiles(Math.min(Math.max(maxFiles, 1), 10));
        assignments.update(assignment);
        audit.record(actor, "assignment.updated", "assignment", String.valueOf(assignmentId), null, null);
        return assignment;
    }

    /** State transition (publish/close/archive) through the explicit edge list. */
    @Transactional
    public void transition(SessionUser actor, Long assignmentId, String target) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.canPublishOrRelease(assignment.getCourseId())) {
            throw AppException.forbidden("Only instructors or administrators can change assignment status.");
        }
        AssignmentWorkflow.requireTransition(assignment.getStatus(), target);
        String before = assignment.getStatus();
        assignment.setStatus(target);
        Instant now = Instant.now();
        switch (target) {
            case Assignment.STATUS_PUBLISHED -> assignment.setPublishedAt(now);
            case Assignment.STATUS_CLOSED -> assignment.setClosedAt(now);
            case Assignment.STATUS_ARCHIVED -> assignment.setArchivedAt(now);
            default -> {
            }
        }
        assignments.update(assignment);
        audit.record(actor, "assignment.status_changed", "assignment", String.valueOf(assignmentId),
                "status=" + before, "status=" + target);
        if (Assignment.STATUS_PUBLISHED.equals(target)) {
            notifyStudentsOfPublish(assignment);
        }
    }

    @Transactional
    public void extendDueDate(SessionUser actor, Long assignmentId, Instant newDueAt, String reason) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        if (!actor.canPublishOrRelease(assignment.getCourseId())) {
            throw AppException.forbidden("Only instructors or administrators can extend due dates.");
        }
        if (Assignment.STATUS_ARCHIVED.equals(assignment.getStatus())) {
            throw AppException.badRequest("Archived assignments cannot be extended.");
        }
        if (!newDueAt.isAfter(assignment.getDueAt())) {
            throw AppException.badRequest("The new due date must be later than the current one.");
        }
        Instant before = assignment.getDueAt();
        assignment.setDueAt(newDueAt);
        assignments.update(assignment);
        audit.record(actor, "assignment.due_extended", "assignment", String.valueOf(assignmentId),
                "dueAt=" + before, "dueAt=" + newDueAt + ",reason=" + reason);
        notifyStudentsOfExtension(assignment, reason);
    }

    private void notifyStudentsOfPublish(Assignment assignment) {
        for (Long studentId : enrolledStudentIds(assignment)) {
            User student = users.findById(studentId).orElse(null);
            if (student == null) {
                continue;
            }
            notifications.notify(studentId, NotificationService.KIND_ASSIGNMENT_PUBLISHED,
                    "New assignment: " + assignment.getTitle(),
                    "A new assignment has been published in your course. Due " + assignment.getDueAt() + ".",
                    "/assignments/" + assignment.getId());
            mail.queue(student.getEmail(), "New assignment — " + assignment.getTitle(),
                    "Hi " + student.getDisplayName() + ",\n\nA new assignment has been published: "
                            + assignment.getTitle() + ".\nDue: " + assignment.getDueAt()
                            + "\n\nOpen it in " + settings.orgName() + " to submit your work.\n\n"
                            + settings.orgName() + " team",
                    MailService.TEMPLATE_ASSIGNMENT_PUBLISHED);
        }
    }

    private void notifyStudentsOfExtension(Assignment assignment, String reason) {
        for (Long studentId : enrolledStudentIds(assignment)) {
            notifications.notify(studentId, NotificationService.KIND_ASSIGNMENT_EXTENDED,
                    "Due date extended: " + assignment.getTitle(),
                    "The due date is now " + assignment.getDueAt() + "."
                            + (reason == null || reason.isBlank() ? "" : " Reason: " + reason),
                    "/assignments/" + assignment.getId());
        }
    }

    /** Students notified are active enrollments of the assignment's course (or its cohort when cohort-scoped). */
    public List<Long> enrolledStudentIds(Assignment assignment) {
        List<Enrollment> enrollments;
        if (assignment.getCohortId() != null) {
            enrollments = this.enrollments.findByCohortId(assignment.getCohortId());
        } else {
            enrollments = cohorts.findByCourseIdOrderByIdAsc(assignment.getCourseId()).stream()
                    .flatMap(c -> this.enrollments.findByCohortId(c.getId()).stream())
                    .toList();
        }
        List<Long> ids = new ArrayList<>();
        for (Enrollment e : enrollments) {
            if (Enrollment.STATUS_ACTIVE.equals(e.getStatus()) && !ids.contains(e.getStudentId())) {
                ids.add(e.getStudentId());
            }
        }
        return ids;
    }

    public void validateRubric(String rubricJson, BigDecimal maxPoints) {
        if (rubricJson == null || rubricJson.isBlank() || "[]".equals(rubricJson.trim())) {
            return; // no rubric criteria: total points entered directly
        }
        try {
            List<RubricSupport.RubricCriterion> criteria = RubricSupport.parse(rubricJson);
            BigDecimal sum = BigDecimal.ZERO;
            Set<String> keys = new java.util.HashSet<>();
            for (RubricSupport.RubricCriterion c : criteria) {
                if (c.name() == null || c.name().isBlank()) {
                    throw AppException.badRequest("Every rubric criterion needs a name.");
                }
                if (c.points() == null || c.points().signum() < 0) {
                    throw AppException.badRequest("Rubric criterion points must be non-negative.");
                }
                if (!keys.add(c.key())) {
                    throw AppException.badRequest("Rubric criteria keys must be unique.");
                }
                sum = sum.add(c.points());
            }
            if (sum.compareTo(maxPoints) > 0) {
                throw AppException.badRequest("Rubric criteria total (" + sum + ") exceeds max points (" + maxPoints + ").");
            }
        } catch (RubricSupport.RubricParseException e) {
            throw AppException.badRequest("The rubric is not valid: " + e.getMessage());
        }
    }

    private void validateAllowedFileTypes(String allowedFileTypes) {
        for (String ext : normalizeFileTypes(allowedFileTypes).split(",")) {
            if (ext.isBlank() || !ext.matches("[a-z0-9]{1,8}")) {
                throw AppException.badRequest("Allowed file types must be comma separated extensions like \"pdf,docx,zip\".");
            }
        }
    }

    public static String normalizeFileTypes(String allowedFileTypes) {
        if (allowedFileTypes == null || allowedFileTypes.isBlank()) {
            return "pdf,docx,zip";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : allowedFileTypes.toLowerCase(Locale.ROOT).split(",")) {
            String ext = part.trim().replaceAll("^\\.", "");
            if (!ext.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(ext);
            }
        }
        return sb.length() == 0 ? "pdf,docx,zip" : sb.toString();
    }

    public Set<String> allowedFileTypesOf(Assignment assignment) {
        return Set.of(assignment.getAllowedFileTypes().split(","));
    }
}
