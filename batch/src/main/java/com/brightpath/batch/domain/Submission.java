package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Minimal read-only view of channel's submission table (batch jobs). */
@Entity
@Table(name = "submission")
public class Submission {

    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_LATE = "late";
    public static final String STATUS_FAILED_SYNC = "failed";

    @Id
    private Long id;

    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "released_grade", precision = 6, scale = 2)
    private java.math.BigDecimal releasedGrade;

    @Column(name = "released_at")
    private Instant releasedAt;

    public Long getId() { return id; }
    public Long getAssignmentId() { return assignmentId; }
    public Long getStudentId() { return studentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public java.math.BigDecimal getReleasedGrade() { return releasedGrade; }
    public Instant getReleasedAt() { return releasedAt; }
}
