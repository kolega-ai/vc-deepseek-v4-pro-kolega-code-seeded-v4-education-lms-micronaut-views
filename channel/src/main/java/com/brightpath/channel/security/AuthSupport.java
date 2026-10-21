package com.brightpath.channel.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.security.utils.SecurityService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Static helpers to turn a Micronaut authentication into the typed SessionUser. */
public final class AuthSupport {

    private AuthSupport() {
    }

    public static Optional<SessionUser> current(SecurityService securityService) {
        return securityService.getAuthentication()
                .filter(a -> a instanceof UserAuthentication)
                .map(a -> ((UserAuthentication) a).user());
    }

    /** Returns the acting user or throws 401; every controller uses this rather than trusting path/body data. */
    public static SessionUser require(SecurityService securityService) {
        return current(securityService)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    public static SessionUser fromContext(DecisionAuthContext ctx, String decisionToken) {
        Set<Long> studentCourses = new HashSet<>();
        Map<Long, String> staffCourses = new HashMap<>();
        for (DecisionScope scope : ctx.scopes()) {
            if (Roles.STUDENT.equals(scope.scopeRole())) {
                studentCourses.add(scope.courseId());
            } else {
                staffCourses.put(scope.courseId(), scope.scopeRole());
            }
        }
        return new SessionUser(
                ctx.userId(), ctx.username(), ctx.displayName(), ctx.email(), ctx.role(), ctx.enabled(),
                Set.copyOf(studentCourses), Map.copyOf(staffCourses),
                ctx.limits() == null ? Map.of() : ctx.limits(),
                ctx.authzBasis(), decisionToken);
    }
}
