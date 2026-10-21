package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Local email mock. All outbound mail is written here; a local "sender" job
 * (batch module) may flip rows to sent. No external SMTP service is used.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "to_address", nullable = false, length = 255)
    private String toAddress;

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    private String bodyText;

    @Column(name = "template_key", length = 64)
    private String templateKey;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @DateCreated
    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
