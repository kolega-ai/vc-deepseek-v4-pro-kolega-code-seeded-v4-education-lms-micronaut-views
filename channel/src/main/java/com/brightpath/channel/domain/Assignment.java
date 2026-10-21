package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An assignment published into a course (optionally scoped to one cohort).
 * Status transitions (explicit edge list, enforced by AssignmentWorkflow):
 * draft -> published, draft -> archived,
 * published -> closed, published -> archived,
 * closed -> archived.
 */
@Entity
@Table(name = "assignment")
public class Assignment {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String summary;

    @Column(name = "instructions", nullable = false, columnDefinition = "text")
    private String instructions;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "allow_late", nullable = false)
    private boolean allowLate = false;

    @Column(name = "max_points", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxPoints;

    /** Rubric as JSON (array of criteria with name, points, description). Stored as text; parsed server-side. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String rubric = "[]";

    /** Comma separated lowercase extensions, e.g. "pdf,docx,zip". Validated server-side. */
    @Column(name = "allowed_file_types", length = 255)
    private String allowedFileTypes = "pdf,docx,zip";

    @Column(name = "max_files", nullable = false)
    private int maxFiles = 3;

    @Column(nullable = false, length = 24)
    private String status = STATUS_DRAFT;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    @DateUpdated
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getCohortId() { return cohortId; }
    public void setCohortId(Long cohortId) { this.cohortId = cohortId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public boolean isAllowLate() { return allowLate; }
    public void setAllowLate(boolean allowLate) { this.allowLate = allowLate; }
    public BigDecimal getMaxPoints() { return maxPoints; }
    public void setMaxPoints(BigDecimal maxPoints) { this.maxPoints = maxPoints; }
    public String getRubric() { return rubric; }
    public void setRubric(String rubric) { this.rubric = rubric; }
    public String getAllowedFileTypes() { return allowedFileTypes; }
    public void setAllowedFileTypes(String allowedFileTypes) { this.allowedFileTypes = allowedFileTypes; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
