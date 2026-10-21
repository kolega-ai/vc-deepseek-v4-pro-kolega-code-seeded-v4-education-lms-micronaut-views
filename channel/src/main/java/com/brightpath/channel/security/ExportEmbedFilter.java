package com.brightpath.channel.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Lets partner portals embed the school's export screens: a caller that declares
 * the hosting origin on the embed URL (e.g. ?origin=https://partner.example) is
 * granted cross-origin access to the export response.
 */
@Filter("/admin/export/**")
public class ExportEmbedFilter implements HttpServerFilter {

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String origin = request.getParameters().get("origin");
        return Mono.from(chain.proceed(request))
                .doOnNext(response -> {
                    if (origin != null && !origin.isBlank()) {
                        response.getHeaders().add("Access-Control-Allow-Origin", origin);
                        response.getHeaders().add("Access-Control-Allow-Credentials", "true");
                        response.getHeaders().add("Vary", "Origin");
                    }
                });
    }
}
