package com.brightpath.coremock;

import com.brightpath.coremock.security.CoreException;
import com.brightpath.coremock.security.ScopeTokenVerifier;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeTokenVerifierTest {

    private final ScopeTokenVerifier verifier = new ScopeTokenVerifier("test-scope-secret");

    private String mint(Map<String, Object> claims) {
        String json = "{\"v\":1,\"purpose\":\"grade-sync\",\"courseId\":7,\"principalId\":3,\"exp\":\"" + claims.get("exp") + "\"}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        return "sct." + payload + "." + ScopeTokenVerifier.hmacHex(payload, "test-scope-secret");
    }

    @Test
    void verifiesValidToken() {
        String token = mint(Map.of("exp", "2099-01-01T00:00:00Z"));
        Map<String, Object> claims = verifier.verify(token);
        assertEquals("grade-sync", claims.get("purpose"));
        assertEquals(7, ((Number) claims.get("courseId")).intValue());
    }

    @Test
    void rejectsTamperedSignature() {
        String token = mint(Map.of("exp", "2099-01-01T00:00:00Z"));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(CoreException.class, () -> verifier.verify(tampered));
    }

    @Test
    void rejectsExpiredToken() {
        String token = mint(Map.of("exp", "2020-01-01T00:00:00Z"));
        CoreException e = assertThrows(CoreException.class, () -> verifier.verify(token));
        assertEquals("CM-4013", e.getCode());
    }

    @Test
    void rejectsTokenSignedWithForeignSecret() {
        String json = "{\"v\":1,\"purpose\":\"grade-sync\",\"courseId\":7,\"exp\":\"2099-01-01T00:00:00Z\"}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        String foreign = "sct." + payload + "." + ScopeTokenVerifier.hmacHex(payload, "foreign-secret");
        assertThrows(CoreException.class, () -> verifier.verify(foreign));
    }
}
