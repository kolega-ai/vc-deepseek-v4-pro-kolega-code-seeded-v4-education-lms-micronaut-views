package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Result of a CSV import run (enrollment or user import). */
@Entity
@Table(name = "import_job")
public class ImportJob {

    public static final String KIND_ENROLLMENTS = "enrollment";
    public static final String KIND_USERS = "users";
    public static final String KIND_TRANSCRIPT = "transcript";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "file_id")
    private Long fileId;

    @Column(nullable = false, length = 24)
    private String status = STATUS_PENDING;

    @Column(name = "total_rows", nullable = false)
    private int totalRows = 0;

    @Column(name = "ok_rows", nullable = false)
    private int okRows = 0;

    @Column(name = "error_rows", nullable = false)
    private int errorRows = 0;

    @Column(name = "error_report", columnDefinition = "text")
    private String errorReport;

    @Column(name = "created_by")
    private Long createdBy;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getOkRows() { return okRows; }
    public void setOkRows(int okRows) { this.okRows = okRows; }
    public int getErrorRows() { return errorRows; }
    public void setErrorRows(int errorRows) { this.errorRows = errorRows; }
    public String getErrorReport() { return errorReport; }
    public void setErrorReport(String errorReport) { this.errorReport = errorReport; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
