package com.brightpath.decision.service;

import com.brightpath.decision.security.DecisionException;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HMAC-signed opaque tokens for decision sessions and vendor scope tokens.
 * Tokens carry a random nonce; verification is signature + stored state,
 * never trust in the payload alone.
 */
@Singleton
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String sessionSigningSecret;
    private final String scopeTokenSecret;

    public TokenService(@Value("${brightpath.decision.session-signing-secret}") String sessionSigningSecret,
                        @Value("${brightpath.decision.scope-token-secret}") String scopeTokenSecret) {
        this.sessionSigningSecret = sessionSigningSecret;
        this.scopeTokenSecret = scopeTokenSecret;
    }

    /** session token: v1.<principalId>.<sessionUuid>.<nonce>.<hmac> */
    public String mintSessionToken(Long principalId, UUID sessionId) {
        String nonce = nonce();
        String payload = "v1." + principalId + "." + sessionId + "." + nonce;
        return payload + "." + hmacHex(payload, sessionSigningSecret);
    }

    public ParsedSessionToken parseSessionToken(String token) {
        if (token == null) {
            throw DecisionException.unauthorized("missing token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 5 || !"v1".equals(parts[0])) {
            throw DecisionException.unauthorized("malformed token");
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        if (!constantTimeEquals(parts[4], hmacHex(payload, sessionSigningSecret))) {
            throw DecisionException.unauthorized("bad token signature");
        }
        try {
            long principalId = Long.parseLong(parts[1]);
            UUID sessionId = UUID.fromString(parts[2]);
            return new ParsedSessionToken(principalId, sessionId);
        } catch (Exception e) {
            throw DecisionException.unauthorized("malformed token");
        }
    }

    /** vendor scope token: sct.<b64url(json)>.<hmac> signed with the scope-token secret shared with core-mock. */
    public String mintScopeToken(Map<String, Object> claims) {
        String json = toJson(claims);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "sct." + payload + "." + hmacHex(payload, scopeTokenSecret);
    }

    public record ParsedSessionToken(long principalId, UUID sessionId) {
    }

    private static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String toJson(Map<String, Object> claims) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String || value instanceof Instant) {
                sb.append('"').append(value).append('"');
            } else if (value instanceof java.util.List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(list.get(i));
                }
                sb.append(']');
            } else {
                sb.append(value);
            }
        }
        return sb.append('}').toString();
    }
}
