package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A student's membership in a cohort.
 * Status transitions (explicit edge list):
 * pending -> active, pending -> dropped,
 * active -> completed, active -> dropped.
 */
@Entity
@Table(name = "enrollment", uniqueConstraints = @UniqueConstraint(columnNames = {"cohort_id", "student_id"}))
public class Enrollment {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_DROPPED = "dropped";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cohort_id", nullable = false)
    private Long cohortId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false, length = 24)
    private String status = STATUS_PENDING;

    @DateCreated
    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "dropped_at")
    private Instant droppedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCohortId() { return cohortId; }
    public void setCohortId(Long cohortId) { this.cohortId = cohortId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant enrolledAt) { this.enrolledAt = enrolledAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getDroppedAt() { return droppedAt; }
    public void setDroppedAt(Instant droppedAt) { this.droppedAt = droppedAt; }
}
