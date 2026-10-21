package com.brightpath.channel.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** Baseline hardening headers on every response. */
@Filter(Filter.MATCH_ALL_PATTERN)
public class SecurityHeadersFilter implements HttpServerFilter {

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Mono.from(chain.proceed(request))
                .doOnNext(response -> {
                    response.getHeaders().add("X-Content-Type-Options", "nosniff");
                    response.getHeaders().add("X-Frame-Options", "DENY");
                    response.getHeaders().add("Referrer-Policy", "same-origin");
                    response.getHeaders().add("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                    response.getHeaders().add("Content-Security-Policy",
                            "default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
                                    + "font-src 'self' https://fonts.gstatic.com; script-src 'self'; "
                                    + "object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
                });
    }
}
