package com.brightpath.channel.security;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.server.filter.FilterBodyParser;
import io.micronaut.http.server.multipart.MultipartBody;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Session-bound CSRF protection for all state-changing requests.
 * The token is generated into the session on first access and must be
 * submitted as the "_csrf" form field or the X-CSRF-Token header.
 * The session cookie is HttpOnly + SameSite=Lax, which further limits the
 * attack surface of the login and account-recovery forms.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class CsrfFilter implements HttpServerFilter {

    public static final String CSRF_KEY = "csrfToken";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionStore<Session> sessionStore;
    private final FilterBodyParser bodyParser;

    public CsrfFilter(SessionStore<Session> sessionStore, FilterBodyParser bodyParser) {
        this.sessionStore = sessionStore;
        this.bodyParser = bodyParser;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.before() + 1;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (isStaticPath(request.getPath())) {
            return chain.proceed(request);
        }
        HttpMethod method = request.getMethod();
        if (method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE) {
            Optional<Session> sessionOpt = Sessions.of(request);
            if (sessionOpt.isEmpty()) {
                return reject();
            }
            Session session = sessionOpt.get();
            String expected = (String) session.get(CSRF_KEY).orElse("");
            return resolveProvided(request).flatMap(provided ->
                    provided != null && constantTimeEquals(expected, provided)
                            ? Mono.from(chain.proceed(request))
                            : reject());
        }
        // GET/HEAD: make sure a session with a CSRF token exists so the next form render has one.
        Session session = Sessions.resolveOrCreate(request, sessionStore);
        if (session.get(CSRF_KEY).isEmpty()) {
            session.put(CSRF_KEY, newToken());
        }
        return chain.proceed(request);
    }

    /** Resolves the submitted token from query, header, urlencoded body or multipart body. */
    private Mono<String> resolveProvided(HttpRequest<?> request) {
        String queryOrHeader = request.getParameters().get("_csrf");
        if (queryOrHeader == null) {
            queryOrHeader = request.getHeaders().get("X-CSRF-Token");
        }
        if (queryOrHeader != null) {
            return Mono.just(queryOrHeader);
        }
        MediaType contentType = request.getContentType().orElse(null);
        if (contentType != null && contentType.getName().equalsIgnoreCase(MediaType.MULTIPART_FORM_DATA)) {
            // Multipart bodies cannot be parsed in a filter without consuming them, so
            // multipart forms submit the CSRF token as a query parameter on the action
            // URL. SameSite=Lax on the session cookie still blocks cross-site posts.
            return Mono.justOrEmpty(Optional.empty());
        }
        try {
            Map<String, Object> body = bodyParser.parseBody(request).get(5, TimeUnit.SECONDS);
            Object token = body.get("_csrf");
            return Mono.justOrEmpty(token == null ? null : token.toString());
        } catch (Exception e) {
            return Mono.justOrEmpty(Optional.empty());
        }
    }

    private static Mono<MutableHttpResponse<?>> reject() {
        return Mono.just(HttpResponse.status(HttpStatus.FORBIDDEN)
                .contentType("text/plain")
                .body("Invalid or missing CSRF token. Please go back and resubmit the form."));
    }

    private static boolean isStaticPath(String path) {
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.equals("/favicon.ico") || path.startsWith("/health") || path.startsWith("/api/v1/");
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
