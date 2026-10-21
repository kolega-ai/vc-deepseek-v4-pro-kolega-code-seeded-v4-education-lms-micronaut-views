package com.brightpath.decision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A course scope for a principal (STUDENT, INSTRUCTOR or TA). */
@Entity
@Table(name = "principal_scope",
        uniqueConstraints = @UniqueConstraint(columnNames = {"principal_id", "course_id", "scope_role"}))
public class PrincipalScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "scope_role", nullable = false, length = 24)
    private String scopeRole;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long principalId) { this.principalId = principalId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getScopeRole() { return scopeRole; }
    public void setScopeRole(String scopeRole) { this.scopeRole = scopeRole; }
}
