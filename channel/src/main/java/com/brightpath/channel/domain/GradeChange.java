package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Append-only history of every grade or feedback mutation on a submission. */
@Entity
@Table(name = "grade_change")
public class GradeChange {

    public static final String TYPE_DRAFT = "draft";
    public static final String TYPE_RELEASE = "release";
    public static final String TYPE_REQUEST_RESUBMISSION = "request_resubmission";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "change_type", nullable = false, length = 32)
    private String changeType;

    @Column(name = "old_grade", precision = 6, scale = 2)
    private BigDecimal oldGrade;

    @Column(name = "new_grade", precision = 6, scale = 2)
    private BigDecimal newGrade;

    @Column(name = "old_feedback", columnDefinition = "text")
    private String oldFeedback;

    @Column(name = "new_feedback", columnDefinition = "text")
    private String newFeedback;

    @Column(columnDefinition = "text")
    private String note;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Long getChangedBy() { return changedBy; }
    public void setChangedBy(Long changedBy) { this.changedBy = changedBy; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public BigDecimal getOldGrade() { return oldGrade; }
    public void setOldGrade(BigDecimal oldGrade) { this.oldGrade = oldGrade; }
    public BigDecimal getNewGrade() { return newGrade; }
    public void setNewGrade(BigDecimal newGrade) { this.newGrade = newGrade; }
    public String getOldFeedback() { return oldFeedback; }
    public void setOldFeedback(String oldFeedback) { this.oldFeedback = oldFeedback; }
    public String getNewFeedback() { return newFeedback; }
    public void setNewFeedback(String newFeedback) { this.newFeedback = newFeedback; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
