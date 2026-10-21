package com.brightpath.channel.security;

import java.util.Set;

/** Canonical role names. The decision tier is the authoritative source of these values. */
public final class Roles {

    public static final String STUDENT = "STUDENT";
    public static final String INSTRUCTOR = "INSTRUCTOR";
    public static final String TEACHING_ASSISTANT = "TEACHING_ASSISTANT";
    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";

    public static final Set<String> ALL = Set.of(STUDENT, INSTRUCTOR, TEACHING_ASSISTANT, SCHOOL_ADMIN);
    public static final Set<String> STAFF = Set.of(INSTRUCTOR, TEACHING_ASSISTANT);
    public static final Set<String> STAFF_OR_ADMIN = Set.of(INSTRUCTOR, TEACHING_ASSISTANT, SCHOOL_ADMIN);

    private Roles() {
    }

    public static boolean isValid(String role) {
        return role != null && ALL.contains(role);
    }

    public static boolean isStaff(String role) {
        return role != null && STAFF.contains(role);
    }

    public static String label(String role) {
        if (role == null) {
            return "Unknown";
        }
        return switch (role) {
            case STUDENT -> "Student";
            case INSTRUCTOR -> "Instructor";
            case TEACHING_ASSISTANT -> "Teaching assistant";
            case SCHOOL_ADMIN -> "School administrator";
            default -> role;
        };
    }
}
