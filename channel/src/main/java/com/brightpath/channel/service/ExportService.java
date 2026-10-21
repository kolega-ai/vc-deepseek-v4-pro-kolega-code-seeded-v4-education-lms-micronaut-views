package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.GradingScale;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.GradingScaleRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Sensitive CSV exports: grades, submissions and progress.
 * Every export is authorization-checked per course/cohort and read-audited
 * with ONE audit event naming the resource class and the count returned.
 */
@Singleton
public class ExportService {

    private final UserRepository users;
    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final GradingScaleRepository gradingScales;
    private final AccessControlService access;
    private final AuditService audit;

    public ExportService(UserRepository users, CourseRepository courses, CohortRepository cohorts,
                         EnrollmentRepository enrollments, AssignmentRepository assignments,
                         SubmissionRepository submissions, GradingScaleRepository gradingScales,
                         AccessControlService access, AuditService audit) {
        this.users = users;
        this.courses = courses;
        this.cohorts = cohorts;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.submissions = submissions;
        this.gradingScales = gradingScales;
        this.access = access;
        this.audit = audit;
    }

    /** Grades export: admin (any course) or staff (scoped course). */
    public String gradesCsv(SessionUser actor, Long courseId, Long cohortId) {
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You cannot export grades for this course.");
        }
        Cohort cohort = null;
        if (cohortId != null) {
            cohort = cohorts.findById(cohortId)
                    .filter(c -> c.getCourseId().equals(courseId))
                    .orElseThrow(() -> AppException.notFound("Cohort not found."));
        }
        List<Assignment> courseAssignments = assignments.findByCourseIdOrderByDueAtAsc(courseId);
        List<Long> studentIds = enrolledStudentIds(cohort, courseId);
        List<GradingScale> scale = gradingScales.findAllOrderByMinPercentDesc();

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"student_email", "student_name", "assignment", "status",
                "points", "max_points", "percent", "letter", "released_at", "late"});
        for (Long studentId : studentIds) {
            User student = users.findById(studentId).orElse(null);
            if (student == null) {
                continue;
            }
            for (Assignment assignment : courseAssignments) {
                submissions.findByAssignmentIdAndStudentIdAndAttempt(assignment.getId(), studentId, 1)
                        .ifPresent(submission -> {
                            BigDecimal grade = submission.getReleasedGrade();
                            boolean late = Submission.STATUS_LATE.equals(submission.getStatus())
                                    || (submission.getSubmittedAt() != null && submission.getSubmittedAt().isAfter(assignment.getDueAt()));
                            rows.add(new String[]{
                                    student.getEmail(), student.getDisplayName(), assignment.getTitle(),
                                    submission.getStatus(),
                                    grade == null ? "" : grade.toPlainString(),
                                    assignment.getMaxPoints().toPlainString(),
                                    grade == null ? "" : percent(grade, assignment.getMaxPoints()),
                                    grade == null ? "" : letter(percentValue(grade, assignment.getMaxPoints()), scale),
                                    submission.getReleasedAt() == null ? "" : submission.getReleasedAt().toString(),
                                    late ? "yes" : "no"});
                        });
            }
        }
        audit.record(actor, "export.run", "grades_export", null, null,
                "courseId=" + courseId + ",rows=" + (rows.size() - 1));
        return CsvUtil.render(rows);
    }

    /** Submissions export with metadata (statuses, timestamps, file counts). */
    public String submissionsCsv(SessionUser actor, Long courseId, Long cohortId) {
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You cannot export submissions for this course.");
        }
        Cohort cohort = null;
        if (cohortId != null) {
            cohort = cohorts.findById(cohortId)
                    .filter(c -> c.getCourseId().equals(courseId))
                    .orElseThrow(() -> AppException.notFound("Cohort not found."));
        }
        List<Assignment> courseAssignments = assignments.findByCourseIdOrderByDueAtAsc(courseId);
        List<Long> studentIds = enrolledStudentIds(cohort, courseId);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"student_email", "student_name", "assignment", "status",
                "submitted_at", "due_at", "attempt", "has_text", "released_at"});
        for (Long studentId : studentIds) {
            User student = users.findById(studentId).orElse(null);
            if (student == null) {
                continue;
            }
            for (Assignment assignment : courseAssignments) {
                submissions.findByAssignmentIdAndStudentIdAndAttempt(assignment.getId(), studentId, 1)
                        .ifPresent(submission -> rows.add(new String[]{
                                student.getEmail(), student.getDisplayName(), assignment.getTitle(),
                                submission.getStatus(),
                                submission.getSubmittedAt() == null ? "" : submission.getSubmittedAt().toString(),
                                assignment.getDueAt().toString(),
                                String.valueOf(submission.getAttempt()),
                                submission.getTextContent() == null || submission.getTextContent().isBlank() ? "no" : "yes",
                                submission.getReleasedAt() == null ? "" : submission.getReleasedAt().toString()}));
            }
        }
        audit.record(actor, "export.run", "submissions_export", null, null,
                "courseId=" + courseId + ",rows=" + (rows.size() - 1));
        return CsvUtil.render(rows);
    }

    /** Attendance-like progress export: enrollment state plus on-time/late/missing counts per student. */
    public String progressCsv(SessionUser actor, Long courseId, Long cohortId) {
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You cannot export progress for this course.");
        }
        Cohort cohort = null;
        if (cohortId != null) {
            cohort = cohorts.findById(cohortId)
                    .filter(c -> c.getCourseId().equals(courseId))
                    .orElseThrow(() -> AppException.notFound("Cohort not found."));
        }
        List<Assignment> courseAssignments = assignments.findByCourseIdOrderByDueAtAsc(courseId);
        List<Long> studentIds = enrolledStudentIds(cohort, courseId);
        Cohort selectedCohort = cohort;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"student_email", "student_name", "enrollment_status", "enrolled_at",
                "submitted", "on_time", "late", "missing", "released", "average_percent"});
        for (Long studentId : studentIds) {
            User student = users.findById(studentId).orElse(null);
            if (student == null) {
                continue;
            }
            String enrollmentStatus = enrollments.findByStudentId(studentId).stream()
                    .filter(e -> e.getCohortId().equals(selectedCohort == null ? -1 : selectedCohort.getId())
                            || (selectedCohort == null && cohorts.findById(e.getCohortId())
                            .map(c -> c.getCourseId().equals(courseId)).orElse(false)))
                    .findFirst().map(Enrollment::getStatus).orElse("unknown");
            String enrolledAt = enrollments.findByStudentId(studentId).stream()
                    .filter(e -> cohorts.findById(e.getCohortId()).map(c -> c.getCourseId().equals(courseId)).orElse(false))
                    .findFirst().map(e -> e.getEnrolledAt() == null ? "" : e.getEnrolledAt().toString()).orElse("");
            int submitted = 0;
            int onTime = 0;
            int late = 0;
            BigDecimal percentSum = BigDecimal.ZERO;
            int graded = 0;
            for (Assignment assignment : courseAssignments) {
                var submission = submissions.findByAssignmentIdAndStudentIdAndAttempt(assignment.getId(), studentId, 1);
                if (submission.isPresent()) {
                    submitted++;
                    boolean isLate = Submission.STATUS_LATE.equals(submission.get().getStatus())
                            || (submission.get().getSubmittedAt() != null
                            && submission.get().getSubmittedAt().isAfter(assignment.getDueAt()));
                    if (isLate) {
                        late++;
                    } else {
                        onTime++;
                    }
                    if (submission.get().getReleasedGrade() != null) {
                        percentSum = percentSum.add(
                                submission.get().getReleasedGrade()
                                        .multiply(BigDecimal.valueOf(100))
                                        .divide(assignment.getMaxPoints(), 2, RoundingMode.HALF_UP));
                        graded++;
                    }
                }
            }
            int missing = courseAssignments.size() - submitted;
            String average = graded == 0 ? ""
                    : percentSum.divide(BigDecimal.valueOf(graded), 1, RoundingMode.HALF_UP).toPlainString();
            rows.add(new String[]{student.getEmail(), student.getDisplayName(), enrollmentStatus, enrolledAt,
                    String.valueOf(submitted), String.valueOf(onTime), String.valueOf(late),
                    String.valueOf(missing), String.valueOf(graded), average});
        }
        audit.record(actor, "export.run", "progress_export", null, null,
                "courseId=" + courseId + ",rows=" + (rows.size() - 1));
        return CsvUtil.render(rows);
    }

    private List<Long> enrolledStudentIds(Cohort cohort, Long courseId) {
        List<Long> ids = new ArrayList<>();
        if (cohort != null) {
            for (Enrollment e : enrollments.findByCohortId(cohort.getId())) {
                if (Enrollment.STATUS_ACTIVE.equals(e.getStatus()) || Enrollment.STATUS_COMPLETED.equals(e.getStatus())) {
                    ids.add(e.getStudentId());
                }
            }
        } else {
            for (Cohort c : cohorts.findByCourseIdOrderByIdAsc(courseId)) {
                for (Enrollment e : enrollments.findByCohortId(c.getId())) {
                    if ((Enrollment.STATUS_ACTIVE.equals(e.getStatus()) || Enrollment.STATUS_COMPLETED.equals(e.getStatus()))
                            && !ids.contains(e.getStudentId())) {
                        ids.add(e.getStudentId());
                    }
                }
            }
        }
        return ids;
    }

    private static String percent(BigDecimal grade, BigDecimal max) {
        return percentValue(grade, max) + "%";
    }

    private static double percentValue(BigDecimal grade, BigDecimal max) {
        return grade.multiply(BigDecimal.valueOf(100))
                .divide(max, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String letter(double percent, List<GradingScale> scale) {
        for (GradingScale s : scale) {
            if (percent >= s.getMinPercent().doubleValue()) {
                return s.getLetter();
            }
        }
        return "F";
    }
}
