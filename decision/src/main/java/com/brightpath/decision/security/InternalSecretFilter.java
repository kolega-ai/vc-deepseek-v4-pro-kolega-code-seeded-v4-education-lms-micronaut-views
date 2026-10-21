package com.brightpath.decision.security;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Protects decision's internal management surface with a shared credential.
 * Channel -> decision calls carry X-Internal-Secret; anything else must
 * present a valid session token (handled by the controller itself).
 */
@Singleton
@Filter("/**")
public class InternalSecretFilter implements HttpServerFilter {

    private static final List<String> PROTECTED = List.of(
            "/auth/session", "/auth/mint-scope-token", "/internal/");

    private final String internalSecret;

    public InternalSecretFilter(@Value("${brightpath.decision.internal-secret}") String internalSecret) {
        this.internalSecret = internalSecret;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.before();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String path = request.getPath();
        boolean needsSecret = PROTECTED.stream().anyMatch(path::startsWith);
        if (!needsSecret) {
            return chain.proceed(request);
        }
        String provided = request.getHeaders().get("X-Internal-Secret");
        if (provided == null || !constantTimeEquals(provided, internalSecret)) {
            return Mono.just(HttpResponse.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json")
                    .body("{\"code\":\"D-4010\",\"message\":\"missing or invalid internal credential\"}"));
        }
        return chain.proceed(request);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
