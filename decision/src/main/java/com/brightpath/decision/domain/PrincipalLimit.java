package com.brightpath.decision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A named limit for a principal (e.g. upload_mb=10, max_submission_files=3). */
@Entity
@Table(name = "principal_limit",
        uniqueConstraints = @UniqueConstraint(columnNames = {"principal_id", "limit_key"}))
public class PrincipalLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "limit_key", nullable = false, length = 48)
    private String limitKey;

    @Column(name = "limit_value", nullable = false, length = 48)
    private String limitValue;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long principalId) { this.principalId = principalId; }
    public String getLimitKey() { return limitKey; }
    public void setLimitKey(String limitKey) { this.limitKey = limitKey; }
    public String getLimitValue() { return limitValue; }
    public void setLimitValue(String limitValue) { this.limitValue = limitValue; }
}
