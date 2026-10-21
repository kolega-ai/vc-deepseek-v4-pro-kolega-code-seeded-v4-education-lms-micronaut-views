package com.brightpath.coremock.security;

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

/**
 * The vendor core has its own credential: every gradebook call must present
 * the vendor API key. Webhook receipt is instead verified by HMAC signature.
 */
@Singleton
@Filter("/v1/**")
public class VendorApiKeyFilter implements HttpServerFilter {

    private final String vendorApiKey;

    public VendorApiKeyFilter(@Value("${brightpath.coremock.vendor-api-key}") String vendorApiKey) {
        this.vendorApiKey = vendorApiKey;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.before();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String path = request.getPath();
        if (path.startsWith("/v1/hooks/") || path.equals("/v1/health")) {
            return chain.proceed(request); // webhooks use HMAC signatures; health is public
        }
        String provided = request.getHeaders().get("X-Vendor-Api-Key");
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                vendorApiKey.getBytes(StandardCharsets.UTF_8))) {
            return Mono.just(HttpResponse.status(HttpStatus.UNAUTHORIZED)
                    .contentType("application/json")
                    .body("{\"code\":\"CM-4011\",\"message\":\"missing or invalid vendor api key\"}"));
        }
        return chain.proceed(request);
    }
}
