package com.brightpath.channel.service;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.CohortStaff;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CohortStaffRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.workflow.EnrollmentWorkflow;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/** Enrollment lifecycle: request, approve, drop, complete — with decision scope sync. */
@Singleton
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final CohortRepository cohorts;
    private final CourseRepository courses;
    private final CohortStaffRepository cohortStaff;
    private final UserRepository users;
    private final DecisionClient decisionClient;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MailService mail;
    private final SettingsService settings;
    private final CohortInviteService invites;

    public EnrollmentService(EnrollmentRepository enrollments, CohortRepository cohorts,
                             CourseRepository courses, CohortStaffRepository cohortStaff,
                             UserRepository users, DecisionClient decisionClient, AuditService audit,
                             NotificationService notifications, MailService mail, SettingsService settings,
                             CohortInviteService invites) {
        this.enrollments = enrollments;
        this.cohorts = cohorts;
        this.courses = courses;
        this.cohortStaff = cohortStaff;
        this.users = users;
        this.decisionClient = decisionClient;
        this.audit = audit;
        this.notifications = notifications;
        this.mail = mail;
        this.settings = settings;
        this.invites = invites;
    }

    public List<Enrollment> listForStudent(Long studentId) {
        return enrollments.findByStudentId(studentId);
    }

    public List<Enrollment> listForCohort(Long cohortId) {
        return enrollments.findByCohortId(cohortId);
    }

    public List<Enrollment> pendingForCohort(Long cohortId) {
        return enrollments.findByCohortIdAndStatus(cohortId, Enrollment.STATUS_PENDING);
    }

    /** Students enroll themselves (pending) in an open cohort; staff/admins approve. */
    @Transactional
    public Enrollment requestEnrollment(SessionUser student, Long cohortId) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        Course course = courses.findById(cohort.getCourseId())
                .orElseThrow(() -> AppException.notFound("Course not found."));
        if (!Course.STATUS_ACTIVE.equals(course.getStatus()) || !course.isEnrollmentOpen()) {
            throw AppException.forbidden("Enrollment is not open for this course.");
        }
        if (!Cohort.STATUS_UPCOMING.equals(cohort.getStatus()) && !Cohort.STATUS_ACTIVE.equals(cohort.getStatus())) {
            throw AppException.badRequest("This cohort is not open for enrollment.");
        }
        long activeCount = enrollments.countByCohortIdAndStatus(cohortId, Enrollment.STATUS_ACTIVE);
        if (activeCount >= cohort.getMaxStudents()) {
            throw AppException.conflict("This cohort is full.");
        }
        enrollments.findByCohortIdAndStudentId(cohortId, student.id()).ifPresent(existing -> {
            throw AppException.conflict("You already have an enrollment for this cohort.");
        });
        Enrollment enrollment = new Enrollment();
        enrollment.setCohortId(cohortId);
        enrollment.setStudentId(student.id());
        enrollment.setStatus(Enrollment.STATUS_PENDING);
        enrollments.save(enrollment);

        cohortStaff.findByCohortId(cohortId).forEach(staff ->
                notifications.notify(staff.getUserId(), NotificationService.KIND_ENROLLMENT_REQUESTED,
                        "New enrollment request",
                        student.displayName() + " requested to join " + cohort.getName() + ".",
                        "/cohorts/" + cohortId));
        audit.record(student, "enrollment.requested", "enrollment", String.valueOf(enrollment.getId()),
                null, "cohortId=" + cohortId + ",status=pending");
        return enrollment;
    }

    @Transactional
    public void approve(SessionUser actor, Long enrollmentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> AppException.notFound("Enrollment not found."));
        Cohort cohort = cohorts.findById(enrollment.getCohortId())
                .orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.canManageCourse(cohort.getCourseId())) {
            throw AppException.forbidden("You cannot approve enrollments for this cohort.");
        }
        EnrollmentWorkflow.requireTransition(enrollment.getStatus(), Enrollment.STATUS_ACTIVE);
        long activeCount = enrollments.countByCohortIdAndStatus(enrollment.getCohortId(), Enrollment.STATUS_ACTIVE);
        if (activeCount >= cohort.getMaxStudents()) {
            throw AppException.conflict("This cohort is full.");
        }
        enrollment.setStatus(Enrollment.STATUS_ACTIVE);
        enrollments.update(enrollment);

        Course course = courses.findById(cohort.getCourseId()).orElse(null);
        User student = users.findById(enrollment.getStudentId()).orElse(null);
        if (student != null) {
            decisionClient.syncScope(student.getUsername(), cohort.getCourseId(), Roles.STUDENT, true);
            notifications.notify(student.getId(), NotificationService.KIND_ENROLLMENT_APPROVED,
                    "Enrollment approved",
                    "You are now enrolled in " + cohort.getName()
                            + (course != null ? " (" + course.getTitle() + ")" : "") + ".",
                    "/courses/" + cohort.getCourseId());
            mail.queue(student.getEmail(), "Enrollment approved — " + cohort.getName(),
                    "Hi " + student.getDisplayName() + ",\n\nYour enrollment in " + cohort.getName()
                            + " has been approved. Your cohort starts on " + cohort.getStartDate() + ".\n\n"
                            + settings.orgName() + " team",
                    MailService.TEMPLATE_ENROLLMENT_APPROVED);
        }
        audit.record(actor, "enrollment.approved", "enrollment", String.valueOf(enrollment.getId()),
                "status=pending", "status=active");
    }

    @Transactional
    public void drop(SessionUser actor, Long enrollmentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> AppException.notFound("Enrollment not found."));
        Cohort cohort = cohorts.findById(enrollment.getCohortId())
                .orElseThrow(() -> AppException.notFound("Cohort not found."));
        boolean selfDrop = actor.isStudent() && enrollment.getStudentId().equals(actor.id());
        if (!selfDrop && !actor.canManageCourse(cohort.getCourseId())) {
            throw AppException.forbidden("You cannot modify this enrollment.");
        }
        if (Enrollment.STATUS_DROPPED.equals(enrollment.getStatus())) {
            throw AppException.badRequest("This enrollment is already dropped.");
        }
        boolean wasActive = Enrollment.STATUS_ACTIVE.equals(enrollment.getStatus());
        EnrollmentWorkflow.requireTransition(enrollment.getStatus(), Enrollment.STATUS_DROPPED);
        enrollment.setStatus(Enrollment.STATUS_DROPPED);
        enrollment.setDroppedAt(Instant.now());
        enrollments.update(enrollment);

        User student = users.findById(enrollment.getStudentId()).orElse(null);
        if (student != null && wasActive) {
            decisionClient.syncScope(student.getUsername(), cohort.getCourseId(), Roles.STUDENT, false);
        }
        audit.record(actor, "enrollment.dropped", "enrollment", String.valueOf(enrollment.getId()),
                "status=" + (wasActive ? "active" : "pending"), "status=dropped");
    }

    @Transactional
    public void complete(SessionUser actor, Long enrollmentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> AppException.notFound("Enrollment not found."));
        Cohort cohort = cohorts.findById(enrollment.getCohortId())
                .orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.canManageCourse(cohort.getCourseId())) {
            throw AppException.forbidden("You cannot complete enrollments for this cohort.");
        }
        EnrollmentWorkflow.requireTransition(enrollment.getStatus(), Enrollment.STATUS_COMPLETED);
        enrollment.setStatus(Enrollment.STATUS_COMPLETED);
        enrollment.setCompletedAt(Instant.now());
        enrollments.update(enrollment);
        User student = users.findById(enrollment.getStudentId()).orElse(null);
        if (student != null) {
            decisionClient.syncScope(student.getUsername(), cohort.getCourseId(), Roles.STUDENT, false);
        }
        audit.record(actor, "enrollment.completed", "enrollment", String.valueOf(enrollment.getId()),
                "status=active", "status=completed");
    }

    /**
     * Confirms an imported student's seat using the invitation code emailed by the roster import.
     * The code is the student's proof that the seat belongs to them, so it activates immediately.
     */
    @Transactional
    public void claimInvite(SessionUser student, String code) {
        CohortInviteService.Entry entry = invites.claim(code)
                .orElseThrow(() -> AppException.badRequest("That invitation code is invalid or has expired."));
        if (!entry.studentId().equals(student.id())) {
            throw AppException.forbidden("This invitation code belongs to a different student.");
        }
        Enrollment enrollment = enrollments.findByCohortIdAndStudentId(entry.cohortId(), student.id())
                .orElseThrow(() -> AppException.notFound("Enrollment not found."));
        if (!Enrollment.STATUS_PENDING.equals(enrollment.getStatus())) {
            throw AppException.badRequest("This seat has already been confirmed.");
        }
        enrollment.setStatus(Enrollment.STATUS_ACTIVE);
        enrollments.update(enrollment);
        Cohort cohort = cohorts.findById(entry.cohortId()).orElse(null);
        decisionClient.syncScope(student.username(), entry.cohortId(), Roles.STUDENT, true);
        if (cohort != null) {
            notifications.notify(student.id(), NotificationService.KIND_ENROLLMENT_APPROVED,
                    "Seat confirmed", "You are now enrolled in " + cohort.getName() + ".",
                    "/courses/" + cohort.getCourseId());
        }
        audit.record(student, "enrollment.invite_claimed", "enrollment", String.valueOf(enrollment.getId()),
                "status=pending", "status=active");
    }

    public List<CohortStaff> staffForCohort(Long cohortId) {
        return cohortStaff.findByCohortId(cohortId);
    }

    /**
     * Support-tool correction: directly sets an enrollment's status, for cases
     * like a mis-drop that the normal approve/drop/complete actions can't fix
     * (those enforce {@link EnrollmentWorkflow}'s transition graph). Bypasses
     * that transition check by design so a support admin can jump straight to
     * the target state.
     */
    @Transactional
    public void resolveState(SessionUser actor, Long enrollmentId, String targetState) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> AppException.notFound("Enrollment not found."));
        Cohort cohort = cohorts.findById(enrollment.getCohortId())
                .orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.canManageCourse(cohort.getCourseId())) {
            throw AppException.forbidden("You cannot modify enrollments for this cohort.");
        }
        if (!List.of(Enrollment.STATUS_PENDING, Enrollment.STATUS_ACTIVE,
                Enrollment.STATUS_COMPLETED, Enrollment.STATUS_DROPPED).contains(targetState)) {
            throw AppException.badRequest("Unknown enrollment status.");
        }
        String before = enrollment.getStatus();
        enrollment.setStatus(targetState);
        enrollments.update(enrollment);
        User student = users.findById(enrollment.getStudentId()).orElse(null);
        if (student != null) {
            decisionClient.syncScope(student.getUsername(), cohort.getCourseId(), Roles.STUDENT,
                    Enrollment.STATUS_ACTIVE.equals(targetState));
        }
        audit.record(actor, "enrollment.state_resolved", "enrollment", String.valueOf(enrollmentId),
                "status=" + before, "status=" + targetState);
    }

    /**
     * Roster convenience for the cohort dashboard's "preview another cohort in
     * this course" quick-switch: management rights are checked on the cohort
     * the caller is already viewing, but the roster returned is the one named
     * in the request body.
     */
    public List<Enrollment> rosterSync(SessionUser actor, Long viewingCohortId, Long targetCohortId) {
        Cohort viewing = cohorts.findById(viewingCohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.canManageCourse(viewing.getCourseId())) {
            throw AppException.forbidden("You do not manage this cohort.");
        }
        Long lookupId = targetCohortId != null ? targetCohortId : viewingCohortId;
        return enrollments.findByCohortId(lookupId);
    }
}
