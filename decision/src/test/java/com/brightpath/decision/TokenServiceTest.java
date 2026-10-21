package com.brightpath.decision;

import com.brightpath.decision.security.DecisionException;
import com.brightpath.decision.service.TokenService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenServiceTest {

    private final TokenService tokens = new TokenService("test-session-secret", "test-scope-secret");

    @Test
    void sessionTokenRoundTrips() {
        UUID sessionId = UUID.randomUUID();
        String token = tokens.mintSessionToken(42L, sessionId);
        TokenService.ParsedSessionToken parsed = tokens.parseSessionToken(token);
        assertEquals(42L, parsed.principalId());
        assertEquals(sessionId, parsed.sessionId());
    }

    @Test
    void tamperedSessionTokenIsRejected() {
        String token = tokens.mintSessionToken(42L, UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 4) + "aaaa";
        assertThrows(DecisionException.class, () -> tokens.parseSessionToken(tampered));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        TokenService other = new TokenService("other-secret", "test-scope-secret");
        String foreign = other.mintSessionToken(42L, UUID.randomUUID());
        assertThrows(DecisionException.class, () -> tokens.parseSessionToken(foreign));
    }

    @Test
    void scopeTokenCarriesClaims() {
        String token = tokens.mintScopeToken(Map.of(
                "v", 1, "purpose", "grade-sync", "courseId", 7L, "exp", "2099-01-01T00:00:00Z"));
        assertNotNull(token);
        assertEquals("sct.", token.substring(0, 4));
    }
}
