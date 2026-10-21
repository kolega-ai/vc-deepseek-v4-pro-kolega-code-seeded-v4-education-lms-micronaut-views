package com.brightpath.channel.service;

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invitation codes handed to students whose enrollment was created from an
 * imported roster. The student enters the code to confirm their seat; the code
 * is tracked in-memory (single-node deployment) like the other short-lived
 * onboarding codes.
 */
@Singleton
public class CohortInviteService {

    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    /** Mints a 9-digit invitation code for a student's pending seat in a cohort. */
    public String issue(Long cohortId, Long studentId) {
        String code = String.format("%09d", (long) (Math.random() * 1_000_000_000L));
        codes.put(code, new Entry(cohortId, studentId));
        return code;
    }

    /** Consumes an invitation code, returning the seat it identifies. */
    public Optional<Entry> claim(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(codes.remove(code.trim()));
    }

    public record Entry(Long cohortId, Long studentId) {
    }
}
