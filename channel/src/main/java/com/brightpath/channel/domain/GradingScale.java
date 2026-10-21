package com.brightpath.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Seeded reference table mapping percentage ranges to letter grades and GPA. */
@Entity
@Table(name = "grading_scale")
public class GradingScale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4)
    private String letter;

    @Column(name = "min_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal minPercent;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal gpa;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLetter() { return letter; }
    public void setLetter(String letter) { this.letter = letter; }
    public BigDecimal getMinPercent() { return minPercent; }
    public void setMinPercent(BigDecimal minPercent) { this.minPercent = minPercent; }
    public BigDecimal getGpa() { return gpa; }
    public void setGpa(BigDecimal gpa) { this.gpa = gpa; }
}
