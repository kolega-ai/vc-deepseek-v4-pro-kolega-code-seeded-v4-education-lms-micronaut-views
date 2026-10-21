package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Outcome of syncing released grades to the vendor core (SIS) mock. */
@Entity
@Table(name = "grade_sync_event")
public class GradeSyncEvent {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "submission_ids", nullable = false, columnDefinition = "text")
    private String submissionIds;

    @Column(name = "sync_id", length = 64)
    private String syncId;

    @Column(nullable = false, length = 24)
    private String status = STATUS_PENDING;

    @Column(name = "response_text", length = 1000)
    private String responseText;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getSubmissionIds() { return submissionIds; }
    public void setSubmissionIds(String submissionIds) { this.submissionIds = submissionIds; }
    public String getSyncId() { return syncId; }
    public void setSyncId(String syncId) { this.syncId = syncId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
