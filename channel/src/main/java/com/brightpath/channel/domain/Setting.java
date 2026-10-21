package com.brightpath.channel.domain;

import io.micronaut.data.annotation.DateUpdated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Key/value organization settings, seeded with defaults in migration V3. */
@Entity
@Table(name = "settings")
public class Setting {

    @Id
    @Column(length = 64)
    private String key;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @DateUpdated
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Setting() {
    }

    public Setting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
