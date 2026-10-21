package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.AuditEvent;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.AuditEventRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates the role-specific dashboards. */
@Singleton
public class DashboardService {

    private static final Set<String> NEEDS_GRADING = Set.of(Submission.STATUS_SUBMITTED, Submission.STATUS_LATE);

    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final AuditEventRepository auditEvents;

    public DashboardService(CourseRepository courses, CohortRepository cohorts,
                            EnrollmentRepository enrollments, AssignmentRepository assignments,
                            SubmissionRepository submissions, UserRepository users,
                            AuditEventRepository auditEvents) {
        this.courses = courses;
        this.cohorts = cohorts;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.submissions = submissions;
        this.users = users;
        this.auditEvents = auditEvents;
    }

    public record StudentCourseCard(Course course, Cohort cohort, String enrollmentStatus,
                                    int assignmentsTotal, int assignmentsSubmitted, int gradesReleased) {
    }

    public record DueAssignment(Assignment assignment, Course course, Submission submission) {
    }

    public record StaffCourseCard(Course course, int cohorts, int drafts, int published,
                                  long needingGrading, long totalSubmissions) {
    }

    public Map<String, Object> studentDashboard(SessionUser user) {
        List<StudentCourseCard> cards = new ArrayList<>();
        List<DueAssignment> due = new ArrayList<>();
        List<Map<String, Object>> recentGrades = new ArrayList<>();

        for (Enrollment enrollment : enrollments.findByStudentId(user.id())) {
            Cohort cohort = cohorts.findById(enrollment.getCohortId()).orElse(null);
            if (cohort == null) {
                continue;
            }
            Course course = courses.findById(cohort.getCourseId()).orElse(null);
            if (course == null) {
                continue;
            }
            List<Assignment> courseAssignments = assignments.findByCourseIdOrderByDueAtAsc(course.getId());
            int total = 0;
            int done = 0;
            int gradedCount = 0;
            for (Assignment assignment : courseAssignments) {
                if (!Assignment.STATUS_PUBLISHED.equals(assignment.getStatus())
                        && !Assignment.STATUS_CLOSED.equals(assignment.getStatus())) {
                    continue;
                }
                total++;
                submissions.findByAssignmentIdAndStudentIdAndAttempt(assignment.getId(), user.id(), 1)
                        .ifPresent(submission -> {
                            if (!Submission.STATUS_NEEDS_RESUBMISSION.equals(submission.getStatus())) {
                                // counted as done below via status list
                            }
                        });
                var submission = submissions.findByAssignmentIdAndStudentIdAndAttempt(assignment.getId(), user.id(), 1);
                if (submission.isPresent()
                        && !Submission.STATUS_NEEDS_RESUBMISSION.equals(submission.get().getStatus())) {
                    done++;
                }
                if (submission.isPresent() && submission.get().getReleasedGrade() != null) {
                    gradedCount++;
                }
                if (assignment.getDueAt().isAfter(Instant.now())
                        && (submission.isEmpty() || NEEDS_GRADING.contains(submission.get().getStatus())
                        || Submission.STATUS_NEEDS_RESUBMISSION.equals(submission.get().getStatus()))) {
                    due.add(new DueAssignment(assignment, course, submission.orElse(null)));
                }
            }
            cards.add(new StudentCourseCard(course, cohort, enrollment.getStatus(), total, done, gradedCount));
        }
        due.sort(Comparator.comparing(d -> d.assignment().getDueAt()));

        for (Submission submission : submissions.findByStudentIdOrderBySubmittedAtDesc(user.id())) {
            if (submission.getReleasedGrade() == null) {
                continue;
            }
            Assignment assignment = assignments.findById(submission.getAssignmentId()).orElse(null);
            Course course = assignment == null ? null : courses.findById(assignment.getCourseId()).orElse(null);
            recentGrades.add(Map.of(
                    "title", assignment == null ? "Assignment" : assignment.getTitle(),
                    "courseTitle", course == null ? "" : course.getTitle(),
                    "grade", submission.getReleasedGrade().toPlainString(),
                    "maxPoints", assignment == null ? "" : assignment.getMaxPoints().toPlainString(),
                    "releasedAt", submission.getReleasedAt() == null ? "" : submission.getReleasedAt().toString(),
                    "assignmentId", submission.getAssignmentId()));
            if (recentGrades.size() >= 5) {
                break;
            }
        }

        return Map.of(
                "cards", cards,
                "due", due.stream().limit(6).toList(),
                "recentGrades", recentGrades,
                "submittedCount", cards.stream().mapToInt(StudentCourseCard::assignmentsSubmitted).sum(),
                "gradesReleasedCount", cards.stream().mapToInt(StudentCourseCard::gradesReleased).sum());
    }

    public Map<String, Object> staffDashboard(SessionUser user) {
        List<StaffCourseCard> cards = new ArrayList<>();
        List<Map<String, Object>> queuePreview = new ArrayList<>();
        long pendingEnrollments = 0;
        int dueSoon = 0;

        List<Long> courseIds = new ArrayList<>(user.staffCourses().keySet());
        courseIds.sort(Long::compareTo);
        for (Long courseId : courseIds) {
            Course course = courses.findById(courseId).orElse(null);
            if (course == null) {
                continue;
            }
            List<Cohort> courseCohorts = cohorts.findByCourseIdOrderByIdAsc(courseId);
            long drafts = assignments.countByCourseIdAndStatus(courseId, Assignment.STATUS_DRAFT);
            long published = assignments.countByCourseIdAndStatus(courseId, Assignment.STATUS_PUBLISHED);
            long needingGrading = submissions.countByCourseAndStatusIn(courseId, NEEDS_GRADING);
            long totalSubmissions = submissions.countByCourseAndStatusIn(courseId,
                    Set.of(Submission.STATUS_SUBMITTED, Submission.STATUS_LATE, Submission.STATUS_GRADED,
                            Submission.STATUS_RELEASED, Submission.STATUS_NEEDS_RESUBMISSION));
            cards.add(new StaffCourseCard(course, courseCohorts.size(), (int) drafts, (int) published,
                    needingGrading, totalSubmissions));
            for (Cohort cohort : courseCohorts) {
                pendingEnrollments += enrollments.countByCohortIdAndStatus(cohort.getId(), Enrollment.STATUS_PENDING);
            }
            for (Assignment assignment : assignments.findByCourseIdOrderByDueAtAsc(courseId)) {
                if (Assignment.STATUS_PUBLISHED.equals(assignment.getStatus())
                        && assignment.getDueAt().isAfter(Instant.now())
                        && assignment.getDueAt().isBefore(Instant.now().plusSeconds(3 * 24 * 3600L))) {
                    dueSoon++;
                }
            }
        }
        var recent = submissions.queueOrderByRecent(
                courseIds.isEmpty() ? null : courseIds.get(0), null, null, null, Pageable.from(0, 8));
        for (Submission submission : recent.getContent()) {
            Assignment assignment = assignments.findById(submission.getAssignmentId()).orElse(null);
            queuePreview.add(Map.of(
                    "id", submission.getId(),
                    "studentName", users.findById(submission.getStudentId())
                            .map(u -> u.getDisplayName()).orElse("Unknown"),
                    "assignmentTitle", assignment == null ? "" : assignment.getTitle(),
                    "assignmentId", submission.getAssignmentId(),
                    "status", submission.getStatus(),
                    "submittedAt", submission.getSubmittedAt()));
        }
        return Map.of(
                "cards", cards,
                "queuePreview", queuePreview,
                "pendingEnrollments", pendingEnrollments,
                "dueSoon", dueSoon,
                "needingGrading", cards.stream().mapToLong(StaffCourseCard::needingGrading).sum());
    }

    public Map<String, Object> adminDashboard() {
        Map<String, Long> usersByRole = new HashMap<>();
        for (String role : Roles.ALL) {
            usersByRole.put(role, users.countByRole(role));
        }
        long activeCourses = courses.countByStatus(Course.STATUS_ACTIVE);
        long activeCohorts = cohorts.findByStatus(Cohort.STATUS_ACTIVE).size() + cohorts.findByStatus(Cohort.STATUS_UPCOMING).size();
        long pendingEnrollments = 0;
        for (Cohort cohort : cohorts.findByStatus(Cohort.STATUS_UPCOMING)) {
            pendingEnrollments += enrollments.countByCohortIdAndStatus(cohort.getId(), Enrollment.STATUS_PENDING);
        }
        for (Cohort cohort : cohorts.findByStatus(Cohort.STATUS_ACTIVE)) {
            pendingEnrollments += enrollments.countByCohortIdAndStatus(cohort.getId(), Enrollment.STATUS_PENDING);
        }
        List<AuditEvent> recentAudit = auditEvents.search(null, null, null, null, null, null,
                Pageable.from(0, 8)).getContent();
        var recentUsers = users.search(null, null, null, Pageable.from(0, 5)).getContent();
        return Map.of(
                "usersByRole", usersByRole,
                "activeCourses", activeCourses,
                "activeCohorts", activeCohorts,
                "pendingEnrollments", pendingEnrollments,
                "recentAudit", recentAudit,
                "recentUsers", recentUsers,
                "totalUsers", users.count());
    }
}
