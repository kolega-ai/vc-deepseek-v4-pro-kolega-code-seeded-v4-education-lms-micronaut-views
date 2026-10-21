package com.brightpath.coremock.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Verifies scope tokens minted by the decision tier. The vendor core enforces
 * the scope claim in the signed token; it never trusts a raw scope parameter.
 */
@Singleton
public class ScopeTokenVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String scopeTokenSecret;

    public ScopeTokenVerifier(@Value("${brightpath.coremock.scope-token-secret}") String scopeTokenSecret) {
        this.scopeTokenSecret = scopeTokenSecret;
    }

    public Map<String, Object> verify(String token) {
        if (token == null || !token.startsWith("sct.")) {
            throw CoreException.badRequest("CM-4012", "missing scope token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw CoreException.badRequest("CM-4012", "malformed scope token");
        }
        String payload = parts[1];
        String expected = hmacHex(payload, scopeTokenSecret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw CoreException.forbidden("CM-4012", "invalid scope token signature");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            Map<String, Object> claims = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Instant exp = Instant.parse(String.valueOf(claims.get("exp")));
            if (exp.isBefore(Instant.now())) {
                throw CoreException.forbidden("CM-4013", "scope token expired");
            }
            return claims;
        } catch (CoreException e) {
            throw e;
        } catch (Exception e) {
            throw CoreException.badRequest("CM-4012", "malformed scope token payload");
        }
    }

    public static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
