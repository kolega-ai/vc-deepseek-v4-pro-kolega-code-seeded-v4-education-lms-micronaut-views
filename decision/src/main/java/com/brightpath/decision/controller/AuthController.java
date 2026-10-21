package com.brightpath.decision.controller;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import com.brightpath.decision.domain.Principal;
import com.brightpath.decision.domain.PrincipalLimit;
import com.brightpath.decision.domain.PrincipalScope;
import com.brightpath.decision.domain.PrincipalSession;
import com.brightpath.decision.repository.PrincipalLimitRepository;
import com.brightpath.decision.repository.PrincipalRepository;
import com.brightpath.decision.repository.PrincipalScopeRepository;
import com.brightpath.decision.repository.PrincipalSessionRepository;
import com.brightpath.decision.security.DecisionException;
import com.brightpath.decision.service.TokenService;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The authorization tier. It resolves the opaque session token against ITS OWN
 * database and returns the authoritative role, scopes and limits. It ignores
 * any user id, role, company, scope or limit supplied in a header, path, query,
 * form field or body — identity is resolved exclusively from the token.
 */
@Controller
public class AuthController {

    private static final Set<String> ROLES = Set.of("STUDENT", "INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN");
    private static final Set<String> SCOPE_ROLES = Set.of("STUDENT", "INSTRUCTOR", "TA");
    private static final Set<String> SCOPE_TOKEN_PURPOSES = Set.of("grade-sync");

    private final PrincipalRepository principals;
    private final PrincipalSessionRepository sessions;
    private final PrincipalScopeRepository scopes;
    private final PrincipalLimitRepository limits;
    private final TokenService tokens;
    private final Duration sessionTtl;

    public AuthController(PrincipalRepository principals, PrincipalSessionRepository sessions,
                          PrincipalScopeRepository scopes, PrincipalLimitRepository limits,
                          TokenService tokens,
                          @Value("${brightpath.decision.session-ttl:8h}") Duration sessionTtl) {
        this.principals = principals;
        this.sessions = sessions;
        this.scopes = scopes;
        this.limits = limits;
        this.tokens = tokens;
        this.sessionTtl = sessionTtl;
    }

    // ---- session lifecycle ---------------------------------------------------

    /** Channel calls this after verifying a password locally. Only the username is accepted. */
    @Post("/auth/session")
    @Transactional
    public HttpResponse<?> mintSession(@Body SessionMintRequest body) {
        Principal principal = principals.findByUsername(body.username() == null ? "" : body.username().trim())
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        if (!principal.isEnabled()) {
            throw DecisionException.forbidden("D-403: principal disabled");
        }
        PrincipalSession session = new PrincipalSession();
        session.setId(UUID.randomUUID());
        session.setPrincipalId(principal.getId());
        session.setExpiresAt(Instant.now().plus(sessionTtl));
        sessions.save(session);
        String token = tokens.mintSessionToken(principal.getId(), session.getId());
        return HttpResponse.created(Map.of("token", token, "expiresAt", session.getExpiresAt().toString()));
    }

    /** Every channel request resolves its session token here; the response is the authorization basis. */
    @Post("/auth/authorize")
    public HttpResponse<?> authorize(@Body AuthorizeRequest body) {
        TokenService.ParsedSessionToken parsed = tokens.parseSessionToken(body.token());
        PrincipalSession session = sessions.findById(parsed.sessionId())
                .orElseThrow(() -> DecisionException.unauthorized("D-401: unknown session"));
        if (session.getRevokedAt() != null) {
            throw DecisionException.unauthorized("D-401: session revoked");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw DecisionException.unauthorized("D-401: session expired");
        }
        if (!session.getPrincipalId().equals(parsed.principalId())) {
            throw DecisionException.unauthorized("D-401: session/principal mismatch");
        }
        Principal principal = principals.findById(parsed.principalId())
                .orElseThrow(() -> DecisionException.unauthorized("D-401: unknown principal"));
        if (!principal.isEnabled()) {
            throw DecisionException.forbidden("D-403: principal disabled");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", principal.getExternalId() != null ? principal.getExternalId() : principal.getId());
        result.put("username", principal.getUsername());
        result.put("displayName", principal.getDisplayName());
        result.put("email", principal.getEmail());
        result.put("role", principal.getRole());
        result.put("enabled", principal.isEnabled());
        result.put("scopes", scopes.findByPrincipalId(principal.getId()).stream()
                .map(s -> Map.of("courseId", s.getCourseId(), "scopeRole", s.getScopeRole()))
                .toList());
        Map<String, String> limitMap = new LinkedHashMap<>();
        for (PrincipalLimit limit : limits.findByPrincipalId(principal.getId())) {
            limitMap.put(limit.getLimitKey(), limit.getLimitValue());
        }
        result.put("limits", limitMap);
        result.put("authzBasis", "decision:principal=" + principal.getUsername() + ";session=" + session.getId());
        return HttpResponse.ok(result).contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    @Post("/auth/revoke")
    @Transactional
    public HttpResponse<?> revoke(@Body AuthorizeRequest body) {
        try {
            TokenService.ParsedSessionToken parsed = tokens.parseSessionToken(body.token());
            sessions.findById(parsed.sessionId()).ifPresent(session -> {
                if (session.getRevokedAt() == null) {
                    session.setRevokedAt(Instant.now());
                    sessions.update(session);
                }
            });
        } catch (DecisionException ignored) {
            // best effort: an already-invalid token is effectively revoked
        }
        return HttpResponse.ok(Map.of("revoked", true));
    }

    /** Internal: revokes every live session for a principal (password change / disable). */
    @Post("/internal/sessions/revoke-all")
    @Transactional
    public HttpResponse<?> revokeAll(@Body RevokeAllRequest body) {
        Principal principal = principals.findByUsername(body.username() == null ? "" : body.username().trim())
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        for (PrincipalSession session : sessions.findByPrincipalId(principal.getId())) {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(Instant.now());
                sessions.update(session);
            }
        }
        return HttpResponse.ok(Map.of("revoked", true));
    }

    /** Internal: mints a scope token for the vendor core. Only decision knows the signing secret. */
    @Post("/auth/mint-scope-token")
    public HttpResponse<?> mintScopeToken(@Body ScopeTokenMintRequest body) {
        TokenService.ParsedSessionToken parsed = tokens.parseSessionToken(body.token());
        PrincipalSession session = sessions.findById(parsed.sessionId())
                .orElseThrow(() -> DecisionException.unauthorized("D-401: unknown session"));
        if (session.getRevokedAt() != null || session.getExpiresAt().isBefore(Instant.now())) {
            throw DecisionException.unauthorized("D-401: session not valid");
        }
        return mintScopeTokenForPrincipal(parsed.principalId(), body.purpose(), body.courseId(), body.ttlSeconds());
    }

    /** Internal variant for batch jobs: presents the internal secret plus a username. */
    @Post("/internal/mint-scope-token")
    public HttpResponse<?> mintScopeTokenInternal(@Body InternalScopeTokenMintRequest body) {
        Principal principal = principals.findByUsername(body.username() == null ? "" : body.username().trim())
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        return mintScopeTokenForPrincipal(principal.getId(), body.purpose(), body.courseId(), body.ttlSeconds());
    }

    private HttpResponse<?> mintScopeTokenForPrincipal(Long principalId, String purpose, Long courseId, long ttlSeconds) {
        if (!SCOPE_TOKEN_PURPOSES.contains(purpose)) {
            throw DecisionException.badRequest("D-400: unknown scope-token purpose");
        }
        // The scope must be derived from the principal's own scopes, never from the request.
        boolean hasCourseScope = scopes.findByPrincipalId(principalId).stream()
                .anyMatch(s -> s.getCourseId().equals(courseId));
        if (!hasCourseScope) {
            throw DecisionException.forbidden("D-403: course outside principal scope");
        }
        long ttl = Math.min(Math.max(ttlSeconds, 30), 3600);
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        String scopeToken = tokens.mintScopeToken(Map.of(
                "v", 1,
                "purpose", purpose,
                "courseId", courseId,
                "principalId", principalId,
                "exp", expiresAt.toString()));
        return HttpResponse.ok(Map.of("scopeToken", scopeToken, "purpose", purpose,
                "courseId", courseId, "expiresAt", expiresAt.toString()));
    }

    // ---- internal user/scope/limit sync (channel is the only caller) -----------

    @Post("/internal/users/sync")
    @Transactional
    public HttpResponse<?> syncUser(@Body UserSyncRequest body) {
        String username = normalizeUsername(body.username());
        String role = body.role() == null ? "" : body.role().trim();
        if (!ROLES.contains(role)) {
            throw DecisionException.badRequest("D-400: unknown role");
        }
        Principal principal = principals.findByUsername(username).orElseGet(() -> {
            Principal created = new Principal();
            created.setUsername(username);
            return created;
        });
        principal.setEmail(body.email());
        principal.setDisplayName(body.displayName());
        if (body.externalId() != null) {
            principal.setExternalId(body.externalId());
        }
        principal.setRole(role);
        principal.setEnabled(body.enabled());
        principals.save(principal);
        return HttpResponse.ok(Map.of("username", principal.getUsername(), "role", principal.getRole()));
    }

    @Delete("/internal/users/{username}")
    @Transactional
    public HttpResponse<?> removeUser(String username) {
        Principal principal = principals.findByUsername(username)
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        sessions.findByPrincipalId(principal.getId()).forEach(sessions::delete);
        principals.deleteById(principal.getId());
        return HttpResponse.ok(Map.of("removed", username));
    }

    @Post("/internal/scopes")
    @Transactional
    public HttpResponse<?> syncScope(@Body ScopeSyncRequest body) {
        Principal principal = principals.findByUsername(body.username() == null ? "" : body.username().trim())
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        if (body.courseId() == null || body.courseId() <= 0 || !SCOPE_ROLES.contains(body.scopeRole())) {
            throw DecisionException.badRequest("D-400: invalid scope");
        }
        boolean add = "add".equalsIgnoreCase(body.action());
        boolean remove = "remove".equalsIgnoreCase(body.action());
        if (!add && !remove) {
            throw DecisionException.badRequest("D-400: action must be add or remove");
        }
        var existing = scopes.findByPrincipalIdAndCourseIdAndScopeRole(principal.getId(), body.courseId(), body.scopeRole());
        if (add && existing.isEmpty()) {
            PrincipalScope scope = new PrincipalScope();
            scope.setPrincipalId(principal.getId());
            scope.setCourseId(body.courseId());
            scope.setScopeRole(body.scopeRole());
            scopes.save(scope);
        }
        if (remove) {
            existing.ifPresent(scopes::delete);
        }
        return HttpResponse.ok(Map.of("scopes", scopes.findByPrincipalId(principal.getId()).size()));
    }

    @Post("/internal/limits")
    @Transactional
    public HttpResponse<?> syncLimit(@Body LimitSyncRequest body) {
        Principal principal = principals.findByUsername(body.username() == null ? "" : body.username().trim())
                .orElseThrow(() -> DecisionException.notFound("D-404: unknown principal"));
        if (body.key() == null || body.key().isBlank() || body.value() == null || body.value().isBlank()) {
            throw DecisionException.badRequest("D-400: limit key and value required");
        }
        var existing = limits.findByPrincipalIdAndLimitKey(principal.getId(), body.key());
        if (existing.isPresent()) {
            PrincipalLimit limit = existing.get();
            limit.setLimitValue(body.value());
            limits.update(limit);
        } else {
            PrincipalLimit limit = new PrincipalLimit();
            limit.setPrincipalId(principal.getId());
            limit.setLimitKey(body.key());
            limit.setLimitValue(body.value());
            limits.save(limit);
        }
        return HttpResponse.ok(Map.of("key", body.key()));
    }

    // /health is provided by micronaut-management; no duplicate route here.

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw DecisionException.badRequest("D-400: username required");
        }
        return username.trim();
    }

    @Error(global = true)
    public HttpResponse<?> onError(HttpRequest<?> request, Throwable exception) {
        if (exception instanceof DecisionException decisionException) {
            return HttpResponse.status(HttpStatus.valueOf(decisionException.getStatus()))
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .body("{\"code\":\"" + decisionException.getMessage().split(":")[0] + "\",\"message\":\""
                            + decisionException.getMessage() + "\"}");
        }
        return HttpResponse.serverError()
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body("{\"code\":\"D-5000\",\"message\":\"internal error\"}");
    }

    @Serdeable
@Introspected

    public record SessionMintRequest(String username) {
    }

    @Serdeable
@Introspected

    public record AuthorizeRequest(String token) {
    }

    @Serdeable
@Introspected

    public record RevokeAllRequest(String username) {
    }

    @Serdeable
@Introspected

    public record ScopeTokenMintRequest(String token, String purpose, Long courseId, long ttlSeconds) {
    }

    @Serdeable
@Introspected

    public record InternalScopeTokenMintRequest(String username, String purpose, Long courseId, long ttlSeconds) {
    }

    @Serdeable
@Introspected

    public record UserSyncRequest(String username, Long externalId, String email, String displayName, String role, boolean enabled) {
    }

    @Serdeable
@Introspected

    public record ScopeSyncRequest(String username, Long courseId, String scopeRole, String action) {
    }

    @Serdeable
@Introspected

    public record LimitSyncRequest(String username, String key, String value) {
    }
}
