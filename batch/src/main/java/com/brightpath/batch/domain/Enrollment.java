package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Minimal read-only view of channel's enrollment table (batch jobs). */
@Entity
@Table(name = "enrollment")
public class Enrollment {

    public static final String STATUS_ACTIVE = "active";

    @Id
    private Long id;

    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(nullable = false, length = 24)
    private String status;

    public Long getId() { return id; }
    public Long getCohortId() { return cohortId; }
    public Long getStudentId() { return studentId; }
    public String getStatus() { return status; }
}
