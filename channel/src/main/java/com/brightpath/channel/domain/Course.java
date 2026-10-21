package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A course: the top-level unit students enroll in through a cohort. */
@Entity
@Table(name = "course")
public class Course {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 24)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200)
    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String syllabus;

    @Column(nullable = false, length = 64)
    private String category = "General";

    @Column(nullable = false, length = 32)
    private String level = "Intermediate";

    @Column(nullable = false, length = 24)
    private String status = STATUS_ACTIVE;

    @Column(name = "enrollment_open", nullable = false)
    private boolean enrollmentOpen = true;

    @Column(name = "cover_color", nullable = false, length = 16)
    private String coverColor = "indigo";

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    @DateUpdated
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSyllabus() { return syllabus; }
    public void setSyllabus(String syllabus) { this.syllabus = syllabus; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isEnrollmentOpen() { return enrollmentOpen; }
    public void setEnrollmentOpen(boolean enrollmentOpen) { this.enrollmentOpen = enrollmentOpen; }
    public String getCoverColor() { return coverColor; }
    public void setCoverColor(String coverColor) { this.coverColor = coverColor; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
