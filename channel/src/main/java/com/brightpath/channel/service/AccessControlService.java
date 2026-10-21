package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.FileRef;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.FileRefRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import jakarta.inject.Singleton;

/**
 * Central, shared authorization guard. All ownership and scope checks for
 * detail, edit, download and export routes live here — one call frame above
 * the controller methods that use them — so no controller performs these
 * checks inline and none can skip them. Role and scope data come from the
 * decision tier response, never from request input.
 */
@Singleton
public class AccessControlService {

    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final FileRefRepository fileRefs;
    private final EnrollmentRepository enrollments;

    public AccessControlService(CourseRepository courses, CohortRepository cohorts,
                                AssignmentRepository assignments, SubmissionRepository submissions,
                                FileRefRepository fileRefs, EnrollmentRepository enrollments) {
        this.courses = courses;
        this.cohorts = cohorts;
        this.assignments = assignments;
        this.submissions = submissions;
        this.fileRefs = fileRefs;
        this.enrollments = enrollments;
    }

    public Course requireCourse(Long courseId) {
        return courses.findById(courseId)
                .orElseThrow(() -> AppException.notFound("Course not found."));
    }

    public Cohort requireCohort(Long cohortId) {
        return cohorts.findById(cohortId)
                .orElseThrow(() -> AppException.notFound("Cohort not found."));
    }

    public Assignment requireAssignment(Long assignmentId) {
        return assignments.findById(assignmentId)
                .orElseThrow(() -> AppException.notFound("Assignment not found."));
    }

    public Submission requireSubmission(Long submissionId) {
        return submissions.findById(submissionId)
                .orElseThrow(() -> AppException.notFound("Submission not found."));
    }

    /** Course content access: admin, staff scoped to the course, or an enrolled student. */
    public void assertCanViewCourse(SessionUser user, Long courseId) {
        if (user.isAdmin() || user.staffCourses().containsKey(courseId) || user.isEnrolledIn(courseId)) {
            return;
        }
        throw AppException.forbidden("You are not enrolled in this course.");
    }

    /** Course management: admin or staff with a scope on the course. */
    public Course assertCanManageCourse(SessionUser user, Long courseId) {
        Course course = requireCourse(courseId);
        if (!user.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        return course;
    }

    public Cohort assertCanViewCohort(SessionUser user, Long cohortId) {
        Cohort cohort = requireCohort(cohortId);
        if (user.isAdmin() || user.staffCourses().containsKey(cohort.getCourseId())) {
            return cohort;
        }
        if (user.isStudent() && enrollments.findByCohortIdAndStudentId(cohortId, user.id())
                .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()) || Enrollment.STATUS_COMPLETED.equals(e.getStatus()))
                .orElse(false)) {
            return cohort;
        }
        throw AppException.forbidden("You are not part of this cohort.");
    }

    /** Assignment visibility: students see published/closed assignments of enrolled courses; staff see everything scoped. */
    public Assignment assertCanViewAssignment(SessionUser user, Long assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        if (user.isAdmin() || user.staffCourses().containsKey(assignment.getCourseId())) {
            return assignment;
        }
        if (user.isEnrolledIn(assignment.getCourseId())
                && (Assignment.STATUS_PUBLISHED.equals(assignment.getStatus())
                || Assignment.STATUS_CLOSED.equals(assignment.getStatus()))) {
            return assignment;
        }
        throw AppException.forbidden("You cannot view this assignment.");
    }

    public Assignment assertCanManageAssignment(SessionUser user, Long assignmentId) {
        Assignment assignment = requireAssignment(assignmentId);
        if (!user.canManageCourse(assignment.getCourseId())) {
            throw AppException.forbidden("You do not manage this assignment's course.");
        }
        return assignment;
    }

    /**
     * Submission access. Students may only reach their own submissions;
     * staff are scoped to the assignment's course; admins see everything.
     */
    public Submission assertCanAccessSubmission(SessionUser user, Long submissionId) {
        Submission submission = requireSubmission(submissionId);
        if (user.isAdmin()) {
            return submission;
        }
        if (user.isStaff()) {
            Assignment assignment = requireAssignment(submission.getAssignmentId());
            if (user.staffCourses().containsKey(assignment.getCourseId())) {
                return submission;
            }
            throw AppException.forbidden("This submission is outside your assigned courses.");
        }
        if (user.isStudent() && submission.getStudentId().equals(user.id())) {
            return submission;
        }
        throw AppException.forbidden("You can only access your own submissions.");
    }

    /**
     * File download authorization, resolved through the canonicalised base
     * directory in FileStorageService after this check passes.
     */
    public FileRef assertCanDownloadFile(SessionUser user, Long fileId) {
        FileRef file = fileRefs.findById(fileId)
                .orElseThrow(() -> AppException.notFound("File not found."));
        if (user.isAdmin()) {
            return file;
        }
        switch (file.getCategory()) {
            case FileRef.CATEGORY_SUBMISSION -> {
                Submission submission = submissions.findById(file.getSubmissionId())
                        .orElseThrow(() -> AppException.notFound("File not found."));
                Assignment assignment = requireAssignment(submission.getAssignmentId());
                if (user.isStaff() && user.staffCourses().containsKey(assignment.getCourseId())) {
                    return file;
                }
                if (submission.getStudentId().equals(user.id())) {
                    return file;
                }
                throw AppException.forbidden("You cannot download this file.");
            }
            case FileRef.CATEGORY_COURSE_RESOURCE -> {
                if (file.getCourseId() == null) {
                    throw AppException.notFound("File not found.");
                }
                assertCanViewCourse(user, file.getCourseId());
                return file;
            }
            case FileRef.CATEGORY_IMPORT -> throw AppException.forbidden("Import files are restricted to administrators.");
            case FileRef.CATEGORY_AVATAR -> {
                if (file.getOwnerId().equals(user.id())) {
                    return file;
                }
                throw AppException.forbidden("You cannot download this file.");
            }
            default -> throw AppException.notFound("File not found.");
        }
    }
}
