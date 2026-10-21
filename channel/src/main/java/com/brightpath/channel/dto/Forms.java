package com.brightpath.channel.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.util.Map;

/** Explicit request DTOs. Controllers bind request bodies into these, never into entities. */
public final class Forms {

    private Forms() {
    }

    @Serdeable
@Introspected

    public record LoginForm(String username, String password) {
    }

    @Serdeable
@Introspected

    public record RegisterForm(String username, String email, String displayName, String password, String confirmPassword) {
    }

    @Serdeable
@Introspected

    public record ForgotForm(String email) {
    }

    @Serdeable
@Introspected

    public record ResetForm(String token, String password, String confirmPassword) {
    }

    @Serdeable
@Introspected

    public record ProfileForm(String displayName, String email, String timezone, String bio, String avatarUrl) {
    }

    @Serdeable
@Introspected

    public record PasswordChangeForm(String currentPassword, String newPassword, String confirmPassword) {
    }

    @Serdeable
@Introspected

    public record AdminUserForm(String username, String email, String displayName, String role,
                                Boolean enabled, String password) {
    }

    @Serdeable
@Introspected

    public record CourseForm(String code, String title, String subtitle, String description, String syllabus,
                             String category, String level, Boolean enrollmentOpen, String coverColor) {
    }

    @Serdeable
@Introspected

    public record ModuleForm(String title, String description) {
    }

    @Serdeable
@Introspected

    public record LessonForm(String title, String contentType, String content, String videoUrl, Integer durationMinutes) {
    }

    @Serdeable
@Introspected

    public record ResourceForm(String title, String kind, String externalUrl) {
    }

    @Serdeable
@Introspected

    public record CohortForm(Long courseId, String name, String scheduleSummary, String startDate, String endDate,
                             Integer maxStudents) {
    }

    @Serdeable
@Introspected

    public record CohortStaffForm(Long userId, String staffRole) {
    }

    @Serdeable
@Introspected

    public record AssignmentForm(Long courseId, Long cohortId, String title, String summary, String instructions,
                                 String dueAt, String availableAt, Boolean allowLate, BigDecimal maxPoints,
                                 String rubricJson, String allowedFileTypes, Integer maxFiles) {
    }

    @Serdeable
@Introspected

    public record ExtendDueForm(String newDueAt, String reason) {
    }

    @Serdeable
@Introspected

    public record SubmitForm(String text) {
    }

    @Serdeable
@Introspected

    public record ThreadForm(String title, String body, Boolean pinned) {
    }

    @Serdeable
@Introspected

    public record CommentForm(String body) {
    }

    @Serdeable
@Introspected

    public record ResubmissionRequestForm(String note) {
    }

    @Serdeable
@Introspected

    public record SettingsForm(Map<String, String> values) {
    }

    @Serdeable
@Introspected

    public record WebhookForm(String name, String url, String secret, java.util.List<String> eventTypes) {
    }

    @Serdeable
@Introspected

    public record ApiKeyForm(String name, java.util.List<String> scopes) {
    }

    @Serdeable
@Introspected

    /** Requests a one-time sign-in link (secondary auth path alongside password login). */
    public record MagicLinkRequestForm(String email) {
    }

    @Serdeable
@Introspected

    /** Body for the legacy SIS transcript sync accepted by the gradebook integration endpoint. */
    public record TranscriptSyncForm(String transcriptXml) {
    }

    @Serdeable
@Introspected

    public record EmailChangeRequestForm(String newEmail) {
    }

    @Serdeable
@Introspected

    public record EmailChangeConfirmForm(String newEmail, String code) {
    }

    @Serdeable
@Introspected

    /** Body for the cohort roster sync convenience action used by the cohort dashboard. */
    public record RosterSyncForm(Long cohortId) {
    }

    @Serdeable
@Introspected

    /** Directory search key supplied from the staff-search form on the user admin screen. */
    public record DirectoryQueryForm(String uid) {
    }

    @Serdeable
@Introspected

    /** Shared report code submitted to open a stored export. */
    public record ReportCodeForm(String code) {
    }

    @Serdeable
@Introspected

    /** Phrase supplied to the discussion board's message search. */
    public record ThreadSearchForm(String phrase) {
    }

    @Serdeable
@Introspected

    /** Editable certificate wording template submitted from the course editor. */
    public record CertificateForm(String template) {
    }

    @Serdeable
@Introspected

    /** Editable announcement wording submitted from the organization settings screen. */
    public record NoticeForm(String wording) {
    }

}
