package com.brightpath.channel.security;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

/**
 * The authorization context returned by the decision tier. The channel tier
 * never supplies role, scope or limits itself: everything in this record comes
 * from decision's own database, resolved from the opaque session token.
 */
@Serdeable
@Introspected
public record DecisionAuthContext(
        Long userId,
        String username,
        String displayName,
        String email,
        String role,
        boolean enabled,
        List<DecisionScope> scopes,
        Map<String, String> limits,
        String authzBasis) {
}
