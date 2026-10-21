package com.brightpath.channel.security;

import java.util.Map;
import java.util.Set;

/**
 * The acting user as resolved by the decision tier on every request.
 * Scope and limits come exclusively from the decision response; the channel
 * tier never trusts its own copy of these values for authorization.
 */
public record SessionUser(
        Long id,
        String username,
        String displayName,
        String email,
        String role,
        boolean enabled,
        /** Course ids the student is actively enrolled in (from decision). */
        Set<Long> studentCourses,
        /** Course id -> staff scope role (INSTRUCTOR or TA), from decision. */
        Map<Long, String> staffCourses,
        Map<String, String> limits,
        String authzBasis,
        String decisionToken) {

    public boolean isStudent() {
        return Roles.STUDENT.equals(role);
    }

    public boolean isInstructor() {
        return Roles.INSTRUCTOR.equals(role);
    }

    public boolean isTeachingAssistant() {
        return Roles.TEACHING_ASSISTANT.equals(role);
    }

    public boolean isAdmin() {
        return Roles.SCHOOL_ADMIN.equals(role);
    }

    public boolean isStaff() {
        return isInstructor() || isTeachingAssistant();
    }

    /** Staff (or admin) with management rights over a course. TAs also pass this, but publish/release are restricted further. */
    public boolean canManageCourse(Long courseId) {
        return isAdmin() || staffCourses.containsKey(courseId);
    }

    public boolean isCourseInstructor(Long courseId) {
        return Roles.INSTRUCTOR.equals(staffCourses.get(courseId));
    }

    /** Instructors and admins may publish assignments and release grades; TAs may only draft. */
    public boolean canPublishOrRelease(Long courseId) {
        return isAdmin() || isCourseInstructor(courseId);
    }

    public boolean isEnrolledIn(Long courseId) {
        return studentCourses.contains(courseId);
    }

    public int uploadMaxMb() {
        return intLimit("upload_mb", 10);
    }

    public int maxSubmissionFiles() {
        return intLimit("max_submission_files", 3);
    }

    private int intLimit(String key, int fallback) {
        try {
            return Integer.parseInt(limits.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
