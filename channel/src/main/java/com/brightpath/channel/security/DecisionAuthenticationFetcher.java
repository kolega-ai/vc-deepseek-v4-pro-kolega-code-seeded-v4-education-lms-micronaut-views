package com.brightpath.channel.security;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.AuthenticationFetcher;
import io.micronaut.session.Session;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * The authentication fetcher used by Micronaut Security. On every protected
 * request it resolves the opaque decision session token from the server-side
 * session, asks the decision tier to authorize it, and turns the response into
 * the principal. Invalid/expired tokens fall back to anonymous; a decision
 * outage fails closed (the request errors rather than degrading to anonymous).
 */
@Singleton
public class DecisionAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>>, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionAuthenticationFetcher.class);

    /** Session attribute holding the opaque decision session token. */
    public static final String TOKEN_KEY = "decisionToken";

    private final DecisionClient decisionClient;

    public DecisionAuthenticationFetcher(DecisionClient decisionClient) {
        this.decisionClient = decisionClient;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // must win over the session/token/anonymous fetchers
    }

    @Override
    public Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
        Optional<Session> sessionOpt = Sessions.of(request);
        if (sessionOpt.isEmpty()) {
            return Mono.empty();
        }
        Object raw = sessionOpt.get().get(TOKEN_KEY).orElse(null);
        if (raw == null) {
            return Mono.empty();
        }
        String token = (String) raw;
        return Mono.from(decisionClient.authorizeReactive(token))
                .map(ctx -> {
                    if (!ctx.enabled()) {
                        sessionOpt.get().clear();
                        return null;
                    }
                    return (Authentication) new UserAuthentication(AuthSupport.fromContext(ctx, token));
                })
                .filter(java.util.Objects::nonNull)
                .onErrorResume(DecisionUnauthorizedException.class, e -> {
                    sessionOpt.get().clear();
                    LOG.info("Dropping invalid decision session token for request {}", request.getPath());
                    return Mono.empty();
                })
                .onErrorResume(DecisionUnavailableException.class, e -> {
                    LOG.error("Decision tier unavailable for {}: {}", request.getPath(), e.getMessage());
                    return Mono.error(e); // fail closed
                });
    }
}
