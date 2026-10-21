package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Minimal read-only view of channel's cohort table (batch jobs). */
@Entity
@Table(name = "cohort")
public class Cohort {

    @Id
    private Long id;

    @Column(name = "course_id")
    private Long courseId;

    public Long getId() { return id; }
    public Long getCourseId() { return courseId; }
}
