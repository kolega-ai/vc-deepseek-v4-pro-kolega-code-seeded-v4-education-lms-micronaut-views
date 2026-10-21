package com.brightpath.coremock.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/** A grade record accepted by the vendor core (SIS) gradebook. */
@Entity
@Table(name = "gradebook_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "assignment_id", "student_id"}))
public class GradebookRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_id", length = 64)
    private String syncId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal points;

    @Column(name = "max_points", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxPoints;

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    @DateCreated
    @Column(name = "received_at")
    private Instant receivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSyncId() { return syncId; }
    public void setSyncId(String syncId) { this.syncId = syncId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long assignmentId) { this.assignmentId = assignmentId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public BigDecimal getPoints() { return points; }
    public void setPoints(BigDecimal points) { this.points = points; }
    public BigDecimal getMaxPoints() { return maxPoints; }
    public void setMaxPoints(BigDecimal maxPoints) { this.maxPoints = maxPoints; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
