package com.brightpath.coremock.controller;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import com.brightpath.coremock.domain.GradebookRecord;
import com.brightpath.coremock.domain.WebhookReceived;
import com.brightpath.coremock.repository.GradebookRecordRepository;
import com.brightpath.coremock.repository.WebhookReceivedRepository;
import com.brightpath.coremock.security.CoreException;
import com.brightpath.coremock.security.ScopeTokenVerifier;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The vendor core mock: an external student-information system. It verifies
 * decision-minted scope tokens and enforces the scope claim in them.
 */
@Controller("/v1")
public class GradebookController {

    private final GradebookRecordRepository records;
    private final WebhookReceivedRepository webhooks;
    private final ScopeTokenVerifier verifier;
    private final String webhookSecret;

    public GradebookController(GradebookRecordRepository records, WebhookReceivedRepository webhooks,
                               ScopeTokenVerifier verifier,
                               @Value("${brightpath.coremock.webhook-secret}") String webhookSecret) {
        this.records = records;
        this.webhooks = webhooks;
        this.verifier = verifier;
        this.webhookSecret = webhookSecret;
    }

    @Post("/gradebooks/sync")
    @Transactional
    public HttpResponse<?> sync(@Body SyncRequest body) {
        Map<String, Object> claims = verifier.verify(body.scopeToken());
        String purpose = String.valueOf(claims.get("purpose"));
        if (!"grade-sync".equals(purpose)) {
            throw CoreException.forbidden("CM-4032", "scope token purpose is not grade-sync");
        }
        Long scopedCourseId = ((Number) claims.get("courseId")).longValue();
        if (!scopedCourseId.equals(body.courseId())) {
            throw CoreException.forbidden("CM-4031", "courseId outside scope token claim");
        }
        String syncId = UUID.randomUUID().toString();
        int accepted = 0;
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (int i = 0; i < body.grades().size(); i++) {
            GradeRow row = body.grades().get(i);
            try {
                BigDecimal points = new BigDecimal(row.points());
                BigDecimal maxPoints = new BigDecimal(row.maxPoints());
                if (points.signum() < 0 || points.compareTo(maxPoints) > 0) {
                    rejected.add(Map.of("index", i, "code", "CM-4001"));
                    continue;
                }
                if (records.findByCourseIdAndAssignmentIdAndStudentId(
                        body.courseId(), row.assignmentId(), row.studentId()).isPresent()) {
                    rejected.add(Map.of("index", i, "code", "CM-4091"));
                    continue;
                }
                GradebookRecord record = new GradebookRecord();
                record.setSyncId(syncId);
                record.setCourseId(body.courseId());
                record.setAssignmentId(row.assignmentId());
                record.setStudentId(row.studentId());
                record.setPoints(points);
                record.setMaxPoints(maxPoints);
                record.setReleasedAt(row.releasedAt() == null ? Instant.now() : row.releasedAt());
                records.save(record);
                accepted++;
            } catch (NumberFormatException e) {
                rejected.add(Map.of("index", i, "code", "CM-4001"));
            }
        }
        return HttpResponse.created(Map.of("syncId", syncId, "accepted", accepted, "rejected", rejected));
    }

    @Get("/gradebooks/{courseId}")
    public HttpResponse<?> listForCourse(Long courseId, @Header("X-Scope-Token") String scopeToken) {
        Map<String, Object> claims = verifier.verify(scopeToken);
        if (!"grade-sync".equals(String.valueOf(claims.get("purpose")))) {
            throw CoreException.forbidden("CM-4032", "scope token purpose is not grade-sync");
        }
        Long scopedCourseId = ((Number) claims.get("courseId")).longValue();
        if (!scopedCourseId.equals(courseId)) {
            throw CoreException.forbidden("CM-4031", "courseId outside scope token claim");
        }
        return HttpResponse.ok(records.findByCourseIdOrderByReceivedAtDesc(courseId).stream()
                        .map(r -> Map.of(
                                "courseId", r.getCourseId(),
                                "assignmentId", r.getAssignmentId(),
                                "studentId", r.getStudentId(),
                                "points", r.getPoints().toPlainString(),
                                "maxPoints", r.getMaxPoints().toPlainString(),
                                "releasedAt", r.getReleasedAt().toString()))
                        .toList())
                .contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    /** Webhook receiver: the vendor acts as the external system receiving LMS events. */
    @Post("/hooks/{event}")
    @Transactional
    public HttpResponse<?> receiveWebhook(String event, @Header("X-Brightpath-Signature") String signature,
                                          @Body String payload) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw CoreException.forbidden("CM-4033", "missing signature");
        }
        String expected = ScopeTokenVerifier.hmacHex(payload, webhookSecret);
        String provided = signature.substring("sha256=".length());
        if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw CoreException.forbidden("CM-4034", "invalid webhook signature");
        }
        WebhookReceived received = new WebhookReceived();
        received.setEventType(event);
        received.setPayloadSha256(sha256Hex(payload));
        webhooks.save(received);
        return HttpResponse.ok(Map.of("received", event, "id", received.getId()));
    }

    @Get("/hooks")
    public HttpResponse<?> listWebhooks() {
        return HttpResponse.ok(webhooks.findAllOrderByReceivedAtDesc().stream()
                        .map(w -> Map.of("event", w.getEventType(), "receivedAt", w.getReceivedAt().toString(),
                                "sha256", w.getPayloadSha256() == null ? "" : w.getPayloadSha256()))
                        .toList())
                .contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    @Get("/health")
    public HttpResponse<?> health() {
        return HttpResponse.ok(Map.of("status", "UP", "vendor", "AcademicCore SIS (mock)"));
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Error(global = true)
    public HttpResponse<?> onError(HttpRequest<?> request, Throwable exception) {
        if (exception instanceof CoreException coreException) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(coreException.getStatus()))
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .body("{\"code\":\"" + coreException.getCode() + "\",\"message\":\"" + coreException.getMessage() + "\"}");
        }
        return HttpResponse.serverError()
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body("{\"code\":\"CM-5000\",\"message\":\"internal vendor error\"}");
    }

    @Serdeable
@Introspected

    public record SyncRequest(String scopeToken, Long courseId, List<GradeRow> grades) {
    }

    @Serdeable
@Introspected

    public record GradeRow(Long studentId, Long assignmentId, String points, String maxPoints, Instant releasedAt) {
    }
}
