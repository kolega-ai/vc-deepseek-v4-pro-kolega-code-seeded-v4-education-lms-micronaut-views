package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Minimal read-only view of channel's cohort_staff table (grade-sync retry). */
@Entity
@Table(name = "cohort_staff")
public class CohortStaff {

    @Id
    private Long id;

    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "staff_role", length = 24)
    private String staffRole;

    public Long getId() { return id; }
    public Long getCohortId() { return cohortId; }
    public Long getUserId() { return userId; }
    public String getStaffRole() { return staffRole; }
}
