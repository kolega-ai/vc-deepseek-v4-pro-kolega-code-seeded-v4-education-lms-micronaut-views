package com.brightpath.channel.service;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Client for the vendor core mock (the external student-information system).
 * Every call carries a scope token minted by the decision tier; the vendor
 * verifies the signature and enforces the scope claim itself.
 */
@Singleton
public class CoreMockClient {

    private static final Logger LOG = LoggerFactory.getLogger(CoreMockClient.class);

    private final BlockingHttpClient client;
    private final String vendorApiKey;

    public CoreMockClient(@Client("${brightpath.coremock.base-url}") HttpClient httpClient,
                          @Value("${brightpath.coremock.vendor-api-key}") String vendorApiKey) {
        this.client = httpClient.toBlocking();
        this.vendorApiKey = vendorApiKey;
    }

    public SyncResult syncGrades(String scopeToken, Long courseId, List<GradeRecord> grades) {
        try {
            HttpResponse<SyncResult> response = client.exchange(
                    HttpRequest.POST("/v1/gradebooks/sync",
                                    new SyncRequest(scopeToken, courseId, grades))
                            .header("X-Vendor-Api-Key", vendorApiKey),
                    Argument.of(SyncResult.class));
            return response.body();
        } catch (HttpClientResponseException e) {
            LOG.warn("vendor core sync rejected: status={}, reason={}", e.getStatus(), e.getMessage());
            throw new VendorCoreException("vendor core rejected the sync: " + e.getStatus(), e);
        } catch (Exception e) {
            LOG.warn("vendor core sync generic failure: {} ({})", e.getMessage(), e.getClass().getName(), e);
            throw new VendorCoreException("vendor core unreachable", e);
        }
    }

    @Serdeable
    @Introspected
    public record GradeRecord(Long studentId, Long assignmentId, String points, String maxPoints, Instant releasedAt) {
    }

    @Serdeable
    @Introspected
    public record SyncRequest(String scopeToken, Long courseId, List<GradeRecord> grades) {
    }

    @Serdeable
    @Introspected
    public record SyncResult(String syncId, int accepted, List<RejectedRow> rejected) {
    }

    @Serdeable
    @Introspected
    public record RejectedRow(int index, String code) {
    }

    public static final class VendorCoreException extends RuntimeException {
        public VendorCoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
