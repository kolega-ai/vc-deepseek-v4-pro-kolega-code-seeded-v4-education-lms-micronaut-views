package com.brightpath.channel.security;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * HTTP client for the decision tier. The channel authenticates users; the
 * decision tier resolves the opaque session token against ITS OWN database and
 * returns the authoritative role, scopes and limits on every request.
 */
@Singleton
public class DecisionClient {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionClient.class);

    private final HttpClient httpClient;
    private final BlockingHttpClient client;
    private final String internalSecret;

    public DecisionClient(@Client("${brightpath.decision.base-url}") HttpClient httpClient,
                          @Value("${brightpath.decision.internal-secret}") String internalSecret) {
        this.httpClient = httpClient;
        this.client = httpClient.toBlocking();
        this.internalSecret = internalSecret;
    }

    /** True when the decision tier answers its health endpoint. */
    public boolean ping() {
        try {
            HttpResponse<?> response = client.exchange(HttpRequest.GET("/health"));
            return response.getStatus().getCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolves a session token into the authoritative authorization context (reactive; for use in filters). */
    public org.reactivestreams.Publisher<DecisionAuthContext> authorizeReactive(String token) {
        return reactor.core.publisher.Mono.from(httpClient.exchange(
                HttpRequest.POST("/auth/authorize", new AuthorizeRequest(token)),
                Argument.of(DecisionAuthContext.class)))
                .map(response -> {
                    DecisionAuthContext ctx = response.body();
                    if (ctx == null) {
                        throw new DecisionUnauthorizedException("empty authorization response");
                    }
                    return ctx;
                })
                .onErrorMap(e -> {
                    if (e instanceof DecisionUnauthorizedException) {
                        return e;
                    }
                    if (e instanceof io.micronaut.http.client.exceptions.HttpClientResponseException hre
                            && (hre.getStatus() == HttpStatus.UNAUTHORIZED || hre.getStatus() == HttpStatus.FORBIDDEN)) {
                        return new DecisionUnauthorizedException("decision rejected session token");
                    }
                    return new DecisionUnavailableException("decision authorize failed", e);
                });
    }

    /** Resolves a session token into the authoritative authorization context. */
    public DecisionAuthContext authorize(String token) {
        try {
            HttpResponse<DecisionAuthContext> response = client.exchange(
                    HttpRequest.POST("/auth/authorize", new AuthorizeRequest(token)),
                    Argument.of(DecisionAuthContext.class));
            DecisionAuthContext ctx = response.body();
            if (ctx == null) {
                throw new DecisionUnauthorizedException("empty authorization response");
            }
            return ctx;
        } catch (HttpClientResponseException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED || e.getStatus() == HttpStatus.FORBIDDEN) {
                throw new DecisionUnauthorizedException("decision rejected session token");
            }
            throw new DecisionUnavailableException("decision authorize failed: " + e.getStatus(), e);
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision authorize failed", e);
        }
    }

    /** Mints a new decision session for a username the channel just authenticated (password verified locally). */
    public String mintSession(String username) {
        try {
            HttpResponse<MintSessionResponse> response = client.exchange(
                    HttpRequest.POST("/auth/session", new MintSessionRequest(username))
                            .header("X-Internal-Secret", internalSecret),
                    Argument.of(MintSessionResponse.class));
            return Optional.ofNullable(response.body()).orElseThrow().token();
        } catch (HttpClientResponseException e) {
            LOG.warn("decision mint session failed with status {} for username {}", e.getStatus(), username);
            throw new DecisionUnavailableException("decision session mint failed: " + e.getStatus(), e);
        } catch (Exception e) {
            LOG.warn("decision mint session generic failure for username {}: {} ({})",
                    username, e.getMessage(), e.getClass().getName());
            throw new DecisionUnavailableException("decision session mint failed", e);
        }
    }

    /** Revokes a decision session (logout). Best effort: a down decision tier must not trap a user in a session. */
    public void revoke(String token) {
        try {
            client.exchange(HttpRequest.POST("/auth/revoke", new AuthorizeRequest(token)));
        } catch (Exception e) {
            LOG.warn("decision revoke failed (ignoring): {}", e.getMessage());
        }
    }

    /** Invalidates every active decision session for a principal (password change / admin disable). */
    public void revokeAllSessions(String username) {
        try {
            client.exchange(HttpRequest.POST("/internal/sessions/revoke-all", new RevokeAllRequest(username))
                    .header("X-Internal-Secret", internalSecret));
        } catch (Exception e) {
            LOG.warn("decision revoke-all failed (ignoring): {}", e.getMessage());
        }
    }

    /** Pushes a user/role/enabled change into the decision database (internal admin operation). */
    public void syncUser(String username, Long userId, String email, String displayName, String role, boolean enabled) {
        try {
            client.exchange(HttpRequest.POST("/internal/users/sync",
                            new UserSyncRequest(username, userId, email, displayName, role, enabled))
                    .header("X-Internal-Secret", internalSecret));
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision user sync failed", e);
        }
    }

    public void removeUser(String username) {
        try {
            client.exchange(HttpRequest.DELETE("/internal/users/" + username)
                    .header("X-Internal-Secret", internalSecret));
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision user remove failed", e);
        }
    }

    /** Adds or removes a course scope for a principal in the decision database. */
    public void syncScope(String username, Long courseId, String scopeRole, boolean add) {
        try {
            client.exchange(HttpRequest.POST("/internal/scopes",
                            new ScopeSyncRequest(username, courseId, scopeRole, add ? "add" : "remove"))
                    .header("X-Internal-Secret", internalSecret));
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision scope sync failed", e);
        }
    }

    /** Sets a named limit for a principal (seed and admin support). */
    public void syncLimit(String username, String key, String value) {
        try {
            client.exchange(HttpRequest.POST("/internal/limits",
                            new LimitSyncRequest(username, key, value))
                    .header("X-Internal-Secret", internalSecret));
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision limit sync failed", e);
        }
    }

    /** Asks decision to mint a scope token for the vendor core mock. Only decision knows the signing secret. */
    public String mintScopeToken(String sessionToken, String purpose, Long courseId, long ttlSeconds) {
        try {
            HttpResponse<MintScopeTokenResponse> response = client.exchange(
                    HttpRequest.POST("/auth/mint-scope-token",
                                    new MintScopeTokenRequest(sessionToken, purpose, courseId, ttlSeconds))
                            .header("X-Internal-Secret", internalSecret),
                    Argument.of(MintScopeTokenResponse.class));
            return Optional.ofNullable(response.body()).orElseThrow().scopeToken();
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision scope-token mint failed", e);
        }
    }

    /** Internal variant for system actors (batch/seed): presents the internal secret plus a username. */
    public String mintScopeTokenInternal(String username, String purpose, Long courseId, long ttlSeconds) {
        try {
            HttpResponse<MintScopeTokenResponse> response = client.exchange(
                    HttpRequest.POST("/internal/mint-scope-token",
                                    new InternalScopeTokenMintRequest(username, purpose, courseId, ttlSeconds))
                            .header("X-Internal-Secret", internalSecret),
                    Argument.of(MintScopeTokenResponse.class));
            return Optional.ofNullable(response.body()).orElseThrow().scopeToken();
        } catch (Exception e) {
            throw new DecisionUnavailableException("decision scope-token mint failed", e);
        }
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

    public record MintSessionRequest(String username) {
    }

    @Serdeable
@Introspected

    public record MintSessionResponse(String token, String expiresAt) {
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

    @Serdeable
@Introspected

    public record MintScopeTokenRequest(String token, String purpose, Long courseId, long ttlSeconds) {
    }

    @Serdeable
@Introspected

    public record InternalScopeTokenMintRequest(String username, String purpose, Long courseId, long ttlSeconds) {
    }

    @Serdeable
@Introspected

    public record MintScopeTokenResponse(String scopeToken, String purpose, Long courseId, String expiresAt) {
    }
}
