package com.brightpath.decision.domain;

import io.micronaut.data.annotation.DateCreated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An opaque decision session. The signed token carries this id; nothing else is trusted. */
@Entity
@Table(name = "principal_session")
public class PrincipalSession {

    @Id
    private UUID id;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @DateCreated
    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long principalId) { this.principalId = principalId; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
