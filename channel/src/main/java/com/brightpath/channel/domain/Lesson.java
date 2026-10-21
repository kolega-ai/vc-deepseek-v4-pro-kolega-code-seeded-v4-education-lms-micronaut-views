package com.brightpath.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single lesson inside a module. */
@Entity
@Table(name = "lesson")
public class Lesson {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_MARKDOWN = "markdown";
    public static final String TYPE_VIDEO = "video";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module_id", nullable = false)
    private Long moduleId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType = TYPE_TEXT;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "video_url", length = 500)
    private String videoUrl;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getModuleId() { return moduleId; }
    public void setModuleId(Long moduleId) { this.moduleId = moduleId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
}
