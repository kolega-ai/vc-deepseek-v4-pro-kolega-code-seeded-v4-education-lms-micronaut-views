package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Material attached to a course: an uploaded document or an external link. */
@Entity
@Table(name = "course_resource")
public class CourseResource {

    public static final String KIND_READING = "reading";
    public static final String KIND_SLIDES = "slides";
    public static final String KIND_SYLLABUS = "syllabus";
    public static final String KIND_LINK = "link";
    public static final String KIND_VIDEO = "video_link";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 32)
    private String kind = KIND_READING;

    @Column(name = "external_url", length = 500)
    private String externalUrl;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "added_by")
    private Long addedBy;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
