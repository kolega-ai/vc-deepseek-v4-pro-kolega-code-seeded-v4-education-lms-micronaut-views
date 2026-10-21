package com.brightpath.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only view of channel settings (job tuning). */
@Entity
@Table(name = "settings")
public class Setting {

    @Id
    @Column(length = 64)
    private String key;

    @Column(columnDefinition = "text")
    private String value;

    public String getKey() { return key; }
    public String getValue() { return value; }
}
