package com.brightpath.channel.service;

import com.brightpath.channel.domain.WebhookDelivery;
import com.brightpath.channel.domain.WebhookEndpoint;
import com.brightpath.channel.repository.WebhookDeliveryRepository;
import com.brightpath.channel.repository.WebhookEndpointRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Webhook endpoints and delivery attempts. Payloads are signed with HMAC-SHA256
 * using the endpoint secret; every attempt is recorded with its outcome.
 */
@Singleton
public class WebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookService.class);

    public static final List<String> EVENT_TYPES = List.of(
            "grade.released", "enrollment.activated", "enrollment.dropped", "assignment.published");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final AuditService audit;

    public WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                          AuditService audit) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.audit = audit;
    }

    public List<WebhookEndpoint> list() {
        return endpoints.findAll();
    }

    @Transactional
    public WebhookEndpoint create(SessionUser actor, String name, String url, String secret, String eventTypes) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage webhooks.");
        }
        if (name == null || name.isBlank() || url == null || url.isBlank()
                || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw AppException.badRequest("Provide a name and a valid http(s) URL.");
        }
        String normalizedEvents = normalizeEvents(eventTypes);
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setName(name.trim());
        endpoint.setUrl(url.trim());
        endpoint.setSecret(secret == null || secret.isBlank() ? randomSecret() : secret.trim());
        endpoint.setEventTypes(normalizedEvents);
        endpoint.setEnabled(true);
        endpoint.setCreatedBy(actor.id());
        endpoints.save(endpoint);
        audit.record(actor, "webhook.endpoint_created", "webhook_endpoint", String.valueOf(endpoint.getId()),
                null, "url=" + endpoint.getUrl() + ",events=" + normalizedEvents);
        return endpoint;
    }

    @Transactional
    public void setEnabled(SessionUser actor, Long endpointId, boolean enabled) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage webhooks.");
        }
        WebhookEndpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> AppException.notFound("Webhook endpoint not found."));
        endpoint.setEnabled(enabled);
        endpoints.update(endpoint);
        audit.record(actor, enabled ? "webhook.endpoint_enabled" : "webhook.endpoint_disabled",
                "webhook_endpoint", String.valueOf(endpointId), null, null);
    }

    @Transactional
    public void delete(SessionUser actor, Long endpointId) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can manage webhooks.");
        }
        endpoints.findById(endpointId).orElseThrow(() -> AppException.notFound("Webhook endpoint not found."));
        endpoints.deleteById(endpointId);
        audit.record(actor, "webhook.endpoint_deleted", "webhook_endpoint", String.valueOf(endpointId), null, null);
    }

    /** Delivers an event to every enabled endpoint subscribed to it, recording each attempt. */
    public void dispatch(String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = MAPPER.writeValueAsString(Map.of(
                    "event", eventType,
                    "sentAt", java.time.Instant.now().toString(),
                    "data", payload));
        } catch (JsonProcessingException e) {
            LOG.error("webhook payload serialization failed: {}", e.getMessage());
            return;
        }
        for (WebhookEndpoint endpoint : endpoints.findByEnabled(true)) {
            if (!Arrays.asList(endpoint.getEventTypes().split(",")).contains(eventType)) {
                continue;
            }
            recordAttempt(endpoint, eventType, json);
        }
    }

    private void recordAttempt(WebhookEndpoint endpoint, String eventType, String json) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventType(eventType);
        delivery.setPayloadSha256(UserService.sha256Hex(json));
        // The event type is appended to the path so path-routed receivers (like the
        // bundled vendor-core mock at /v1/hooks/{event}) can subscribe per event.
        String targetUrl = endpoint.getUrl().endsWith("/")
                ? endpoint.getUrl() + eventType : endpoint.getUrl() + "/" + eventType;
        try (BlockingHttpClient client = HttpClient.create(java.net.URI.create(targetUrl).toURL()).toBlocking()) {
            HttpResponse<String> response = client.exchange(
                    HttpRequest.POST(java.net.URI.create(targetUrl).getPath(), json)
                            .contentType("application/json")
                            .header("X-Brightpath-Signature", "sha256=" + hmacSha256(json, endpoint.getSecret())),
                    Argument.of(String.class));
            delivery.setResponseCode(response.getStatus().getCode());
            delivery.setSuccess(response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300);
        } catch (Exception e) {
            delivery.setSuccess(false);
            delivery.setErrorMessage(truncate(e.getMessage(), 500));
        }
        deliveries.save(delivery);
    }

    public String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String normalizeEvents(String eventTypes) {
        StringBuilder sb = new StringBuilder();
        for (String event : eventTypes == null ? new String[0] : eventTypes.split(",")) {
            String trimmed = event.trim();
            if (!EVENT_TYPES.contains(trimmed)) {
                throw AppException.badRequest("Unknown event type: " + trimmed);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(trimmed);
        }
        if (sb.length() == 0) {
            throw AppException.badRequest("Select at least one event type.");
        }
        return sb.toString();
    }

    private static String randomSecret() {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.security.SecureRandom.getSeed(32));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
