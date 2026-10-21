package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateUpdated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A student's work for one assignment.
 * Status transitions (explicit edge list, enforced by SubmissionWorkflow):
 * submitted -> late           (batch sweep; also applied directly when the submit call happens after the due time)
 * submitted -> graded, late -> graded            (staff saves a draft grade)
 * graded -> released                              (instructor/admin releases; grade becomes visible to the student)
 * graded -> needs_resubmission                    (staff requests a new version)
 * needs_resubmission -> submitted, needs_resubmission -> late   (student resubmits; draft grade is cleared)
 * A student may replace a submitted/late submission before the due time only while no draft grade exists.
 */
@Entity
@Table(name = "submission", uniqueConstraints = @UniqueConstraint(columnNames = {"assignment_id", "student_id", "attempt"}))
public class Submission {

    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_LATE = "late";
    public static final String STATUS_NEEDS_RESUBMISSION = "needs_resubmission";
    public static final String STATUS_GRADED = "graded";
    public static final String STATUS_RELEASED = "released";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(nullable = false, length = 24)
    private String status = STATUS_SUBMITTED;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    /** Draft grade: visible only to scoped staff until release. */
    @Column(name = "draft_grade", precision = 6, scale = 2)
    private BigDecimal draftGrade;

    @Column(name = "draft_feedback", columnDefinition = "text")
    private String draftFeedback;

    /** Instructor-only private note, never shown to students even after release. */
    @Column(name = "draft_private_note", columnDefinition = "text")
    private String draftPrivateNote;

    @Column(name = "draft_graded_by")
    private Long draftGradedBy;

    @Column(name = "draft_graded_at")
    private Instant draftGradedAt;

    @Column(name = "released_grade", precision = 6, scale = 2)
    private BigDecimal releasedGrade;

    @Column(name = "released_feedback", columnDefinition = "text")
    private String releasedFeedback;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    @DateUpdated
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long assignmentId) { this.assignmentId = assignmentId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public BigDecimal getDraftGrade() { return draftGrade; }
    public void setDraftGrade(BigDecimal draftGrade) { this.draftGrade = draftGrade; }
    public String getDraftFeedback() { return draftFeedback; }
    public void setDraftFeedback(String draftFeedback) { this.draftFeedback = draftFeedback; }
    public String getDraftPrivateNote() { return draftPrivateNote; }
    public void setDraftPrivateNote(String draftPrivateNote) { this.draftPrivateNote = draftPrivateNote; }
    public Long getDraftGradedBy() { return draftGradedBy; }
    public void setDraftGradedBy(Long draftGradedBy) { this.draftGradedBy = draftGradedBy; }
    public Instant getDraftGradedAt() { return draftGradedAt; }
    public void setDraftGradedAt(Instant draftGradedAt) { this.draftGradedAt = draftGradedAt; }
    public BigDecimal getReleasedGrade() { return releasedGrade; }
    public void setReleasedGrade(BigDecimal releasedGrade) { this.releasedGrade = releasedGrade; }
    public String getReleasedFeedback() { return releasedFeedback; }
    public void setReleasedFeedback(String releasedFeedback) { this.releasedFeedback = releasedFeedback; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Long getReleasedBy() { return releasedBy; }
    public void setReleasedBy(Long releasedBy) { this.releasedBy = releasedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
