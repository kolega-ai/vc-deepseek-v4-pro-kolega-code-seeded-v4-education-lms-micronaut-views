package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Minimal read-only view of channel's users table (batch jobs). */
@Entity
@Table(name = "users")
public class User {

    @Id
    private Long id;

    @Column(length = 64)
    private String username;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(length = 255)
    private String email;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
}
