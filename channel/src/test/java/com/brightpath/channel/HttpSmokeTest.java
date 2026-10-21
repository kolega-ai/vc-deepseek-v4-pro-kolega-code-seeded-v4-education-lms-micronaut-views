package com.brightpath.channel;

import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.DecisionUnauthorizedException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level smoke checks: public pages render, protected pages redirect to
 * login when anonymous, static assets are served, and security headers are set.
 * Every test uses a fresh client (no shared cookies), so each request is
 * anonymous and independent of any other test.
 */
@MicronautTest
@io.micronaut.context.annotation.Property(name = "micronaut.server.port", value = "18097")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpSmokeTest extends AbstractDbTest {

    @Inject
    DecisionClient injectedDecisionClient;

    @MockBean(DecisionClient.class)
    DecisionClient decisionClient() {
        DecisionClient mock = Mockito.mock(DecisionClient.class);
        Mockito.when(mock.authorize(Mockito.anyString())).thenThrow(new DecisionUnauthorizedException("no session"));
        return mock;
    }

    private BlockingHttpClient freshClient() {
        try {
            var config = new io.micronaut.http.client.DefaultHttpClientConfiguration();
            config.setFollowRedirects(false);
            return HttpClient.create(new URL("http://localhost:18097"), config).toBlocking();
        } catch (java.net.MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void mockDecisionClientReplacesRealBean() {
        assertTrue(Mockito.mockingDetails(injectedDecisionClient).isMock(),
                "DecisionClient should be replaced by the mock in tests");
    }

    @Test
    void loginPageRenders() {
        var response = freshClient().exchange("/login", String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.body().contains("Sign in"));
    }

    @Test
    void protectedPageRedirectsAnonymousToLogin() {
        var response = freshClient().exchange(HttpRequest.GET("/dashboard"));
        assertEquals(HttpStatus.SEE_OTHER, response.getStatus());
        assertTrue(response.header("Location").contains("/login"));
    }

    @Test
    void staticAssetsAreServed() {
        var response = freshClient().exchange("/css/app.css", String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.body().contains("--primary"));
    }

    @Test
    void securityHeadersArePresent() {
        var response = freshClient().exchange("/login", String.class);
        assertEquals("nosniff", response.header("X-Content-Type-Options"));
        assertEquals("DENY", response.header("X-Frame-Options"));
        assertTrue(response.header("Content-Security-Policy").contains("default-src 'self'"));
    }

    @Test
    void postWithoutCsrfTokenIsRejected() {
        BlockingHttpClient client = freshClient();
        try {
            client.exchange(HttpRequest.POST("/login", "username=x&password=y")
                    .contentType("application/x-www-form-urlencoded"));
            org.junit.jupiter.api.Assertions.fail("expected 403");
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        }
    }
}
