package com.brightpath.channel.service;

import com.brightpath.channel.domain.ApiKey;
import com.brightpath.channel.repository.ApiKeyRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scoped API keys for the JSON integration surface. Only a SHA-256 digest of
 * each key is stored; the plaintext is shown once at creation time.
 */
@Singleton
public class ApiKeyService {

    public static final String SCOPE_READ_COURSES = "read:courses";
    public static final String SCOPE_READ_GRADES = "read:grades";

    private static final Set<String> VALID_SCOPES = Set.of(SCOPE_READ_COURSES, SCOPE_READ_GRADES);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;
    private final AuditService audit;

    public ApiKeyService(ApiKeyRepository keys, AuditService audit) {
        this.keys = keys;
        this.audit = audit;
    }

    public List<ApiKey> list() {
        return keys.findAllOrderByCreatedAtDesc();
    }

    /** Creates a key; returns the plaintext exactly once. */
    @Transactional
    public String create(SessionUser actor, String name, List<String> scopes) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage API keys.");
        }
        if (name == null || name.isBlank()) {
            throw AppException.badRequest("Please name the key.");
        }
        String normalized = normalizeScopes(scopes);
        String plaintext = "bp_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes(32));
        ApiKey key = new ApiKey();
        key.setName(name.trim());
        key.setPrefix(plaintext.substring(0, 12));
        key.setKeyHash(sha256Hex(plaintext));
        key.setScopes(normalized);
        key.setCreatedBy(actor.id());
        keys.save(key);
        audit.record(actor, "apikey.created", "api_key", String.valueOf(key.getId()),
                null, "scopes=" + normalized);
        return plaintext;
    }

    @Transactional
    public void setEnabled(SessionUser actor, Long keyId, boolean enabled) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage API keys.");
        }
        ApiKey key = keys.findById(keyId).orElseThrow(() -> AppException.notFound("API key not found."));
        key.setEnabled(enabled);
        keys.update(key);
        audit.record(actor, enabled ? "apikey.enabled" : "apikey.disabled", "api_key", String.valueOf(keyId), null, null);
    }

    @Transactional
    public void revoke(SessionUser actor, Long keyId) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage API keys.");
        }
        keys.findById(keyId).orElseThrow(() -> AppException.notFound("API key not found."));
        keys.deleteById(keyId);
        audit.record(actor, "apikey.revoked", "api_key", String.valueOf(keyId), null, null);
    }

    /** Looks up and validates a presented key; constant-time hash comparison; updates last-used. */
    public Optional<ApiKey> authenticate(String presented) {
        if (presented == null || presented.length() < 16) {
            return Optional.empty();
        }
        String hash = sha256Hex(presented);
        Optional<ApiKey> key = keys.findByKeyHash(hash);
        key.filter(ApiKey::isEnabled).ifPresent(k -> {
            k.setLastUsedAt(Instant.now());
            keys.update(k);
        });
        return key.filter(ApiKey::isEnabled);
    }

    public static String normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw AppException.badRequest("Select at least one scope.");
        }
        StringBuilder sb = new StringBuilder();
        for (String scope : scopes) {
            if (!VALID_SCOPES.contains(scope)) {
                throw AppException.badRequest("Unknown API scope: " + scope);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(scope);
        }
        return sb.toString();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
