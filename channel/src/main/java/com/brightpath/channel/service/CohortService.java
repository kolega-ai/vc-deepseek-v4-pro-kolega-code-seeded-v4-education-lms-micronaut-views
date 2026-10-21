package com.brightpath.channel.service;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.CohortStaff;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CohortStaffRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.workflow.CohortWorkflow;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Cohorts: schedules, rosters, staff assignment and lifecycle transitions. */
@Singleton
public class CohortService {

    private final CohortRepository cohorts;
    private final CohortStaffRepository cohortStaff;
    private final CourseRepository courses;
    private final UserRepository users;
    private final DecisionClient decisionClient;
    private final AuditService audit;

    public CohortService(CohortRepository cohorts, CohortStaffRepository cohortStaff,
                         CourseRepository courses, UserRepository users,
                         DecisionClient decisionClient, AuditService audit) {
        this.cohorts = cohorts;
        this.cohortStaff = cohortStaff;
        this.courses = courses;
        this.users = users;
        this.decisionClient = decisionClient;
        this.audit = audit;
    }

    public List<Cohort> listForCourse(Long courseId) {
        return cohorts.findByCourseIdOrderByIdAsc(courseId);
    }

    public Page<Cohort> search(Long courseId, String status, String q, Pageable pageable) {
        return cohorts.search(courseId, status, q, pageable);
    }

    public List<CohortStaff> staffForCohort(Long cohortId) {
        return cohortStaff.findByCohortId(cohortId);
    }

    @Transactional
    public Cohort create(SessionUser actor, Long courseId, String name, String scheduleSummary,
                         LocalDate startDate, LocalDate endDate, int maxStudents) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can create cohorts.");
        }
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (endDate.isBefore(startDate)) {
            throw AppException.badRequest("End date must not be before the start date.");
        }
        Cohort cohort = new Cohort();
        cohort.setCourseId(courseId);
        cohort.setName(name.trim());
        cohort.setScheduleSummary(scheduleSummary);
        cohort.setStartDate(startDate);
        cohort.setEndDate(endDate);
        cohort.setMaxStudents(maxStudents);
        cohorts.save(cohort);
        audit.record(actor, "cohort.created", "cohort", String.valueOf(cohort.getId()), null, "courseId=" + courseId);
        return cohort;
    }

    @Transactional
    public Cohort update(SessionUser actor, Long cohortId, String name, String scheduleSummary,
                         LocalDate startDate, LocalDate endDate, int maxStudents) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.canManageCourse(cohort.getCourseId())) {
            throw AppException.forbidden("You do not manage this cohort.");
        }
        if (endDate.isBefore(startDate)) {
            throw AppException.badRequest("End date must not be before the start date.");
        }
        cohort.setName(name.trim());
        cohort.setScheduleSummary(scheduleSummary);
        cohort.setStartDate(startDate);
        cohort.setEndDate(endDate);
        cohort.setMaxStudents(maxStudents);
        cohorts.update(cohort);
        audit.record(actor, "cohort.updated", "cohort", String.valueOf(cohortId), null, null);
        return cohort;
    }

    @Transactional
    public void transition(SessionUser actor, Long cohortId, String targetStatus) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.isAdmin() && !actor.isCourseInstructor(cohort.getCourseId())) {
            throw AppException.forbidden("Only administrators or the course instructor can change cohort status.");
        }
        CohortWorkflow.requireTransition(cohort.getStatus(), targetStatus);
        String before = cohort.getStatus();
        cohort.setStatus(targetStatus);
        cohorts.update(cohort);
        audit.record(actor, "cohort.status_changed", "cohort", String.valueOf(cohortId),
                "status=" + before, "status=" + targetStatus);
    }

    /** Assigns an instructor or assistant to a cohort; this is what grants staff course scopes. */
    @Transactional
    public void assignStaff(SessionUser actor, Long cohortId, Long userId, String staffRole) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.isAdmin() && !actor.isCourseInstructor(cohort.getCourseId())) {
            throw AppException.forbidden("Only administrators or the course instructor can assign staff.");
        }
        if (!CohortStaff.ROLE_INSTRUCTOR.equals(staffRole) && !CohortStaff.ROLE_ASSISTANT.equals(staffRole)) {
            throw AppException.badRequest("Unknown staff role.");
        }
        User user = users.findById(userId).orElseThrow(() -> AppException.notFound("User not found."));
        if (!Roles.isStaff(user.getRole())) {
            throw AppException.badRequest("Only instructors or teaching assistants can be assigned to a cohort.");
        }
        cohortStaff.findByCohortIdAndUserId(cohortId, userId).ifPresent(existing -> {
            throw AppException.conflict("This staff member is already assigned to the cohort.");
        });
        CohortStaff staff = new CohortStaff();
        staff.setCohortId(cohortId);
        staff.setUserId(userId);
        staff.setStaffRole(staffRole);
        cohortStaff.save(staff);
        String scopeRole = CohortStaff.ROLE_ASSISTANT.equals(staffRole) ? "TA" : Roles.INSTRUCTOR;
        decisionClient.syncScope(user.getUsername(), cohort.getCourseId(), scopeRole, true);
        audit.record(actor, "cohort.staff_assigned", "cohort_staff", String.valueOf(staff.getId()),
                null, "userId=" + userId + ",role=" + staffRole + ",cohortId=" + cohortId);
    }

    /** Changes an existing staff member's role on a cohort (e.g. promotes an assistant to instructor). */
    @Transactional
    public CohortStaff updateStaffRole(SessionUser actor, Long cohortId, Long userId, String staffRole) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.isAdmin() && !actor.isCourseInstructor(cohort.getCourseId())) {
            throw AppException.forbidden("Only administrators or the course instructor can change staff roles.");
        }
        if (!CohortStaff.ROLE_INSTRUCTOR.equals(staffRole) && !CohortStaff.ROLE_ASSISTANT.equals(staffRole)) {
            throw AppException.badRequest("Unknown staff role.");
        }
        CohortStaff staff = cohortStaff.findByCohortIdAndUserId(cohortId, userId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        User user = users.findById(userId).orElseThrow(() -> AppException.notFound("User not found."));
        staff.setStaffRole(staffRole);
        cohortStaff.update(staff);
        String scopeRole = CohortStaff.ROLE_ASSISTANT.equals(staffRole) ? "TA" : Roles.INSTRUCTOR;
        decisionClient.syncScope(user.getUsername(), cohort.getCourseId(), scopeRole, true);
        return staff;
    }

    @Transactional
    public void removeStaff(SessionUser actor, Long cohortId, Long userId) {
        Cohort cohort = cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found."));
        if (!actor.isAdmin() && !actor.isCourseInstructor(cohort.getCourseId())) {
            throw AppException.forbidden("Only administrators or the course instructor can remove staff.");
        }
        CohortStaff staff = cohortStaff.findByCohortIdAndUserId(cohortId, userId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
        cohortStaff.deleteByCohortIdAndUserId(cohortId, userId);
        User user = users.findById(userId).orElse(null);
        if (user != null) {
            String scopeRole = CohortStaff.ROLE_ASSISTANT.equals(staff.getStaffRole()) ? "TA" : Roles.INSTRUCTOR;
            decisionClient.syncScope(user.getUsername(), cohort.getCourseId(), scopeRole, false);
        }
        audit.record(actor, "cohort.staff_removed", "cohort_staff", String.valueOf(staff.getId()),
                null, "userId=" + userId + ",cohortId=" + cohortId);
    }

    public List<User> staffCandidates() {
        return users.findByRole(Roles.INSTRUCTOR).stream()
                .filter(User::isEnabled)
                .sorted(java.util.Comparator.comparing(User::getDisplayName))
                .toList();
    }
}
