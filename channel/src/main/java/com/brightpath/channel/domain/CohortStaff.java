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

/** Assigns a staff member (instructor or teaching assistant) to a cohort. This is the source of staff course scopes. */
@Entity
@Table(name = "cohort_staff", uniqueConstraints = @UniqueConstraint(columnNames = {"cohort_id", "user_id"}))
public class CohortStaff {

    public static final String ROLE_INSTRUCTOR = "instructor";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cohort_id", nullable = false)
    private Long cohortId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "staff_role", nullable = false, length = 24)
    private String staffRole = ROLE_INSTRUCTOR;

    @DateCreated
    @Column(name = "assigned_at")
    private Instant assignedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCohortId() { return cohortId; }
    public void setCohortId(Long cohortId) { this.cohortId = cohortId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStaffRole() { return staffRole; }
    public void setStaffRole(String staffRole) { this.staffRole = staffRole; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
}
