package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Minimal read-only view of channel's assignment table (batch jobs). */
@Entity
@Table(name = "assignment")
public class Assignment {

    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_CLOSED = "closed";

    @Id
    private Long id;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "allow_late")
    private boolean allowLate;

    @Column(name = "max_points", precision = 6, scale = 2)
    private java.math.BigDecimal maxPoints;

    @Column(nullable = false, length = 24)
    private String status;

    public Long getId() { return id; }
    public Long getCourseId() { return courseId; }
    public Long getCohortId() { return cohortId; }
    public String getTitle() { return title; }
    public Instant getDueAt() { return dueAt; }
    public boolean isAllowLate() { return allowLate; }
    public java.math.BigDecimal getMaxPoints() { return maxPoints; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
