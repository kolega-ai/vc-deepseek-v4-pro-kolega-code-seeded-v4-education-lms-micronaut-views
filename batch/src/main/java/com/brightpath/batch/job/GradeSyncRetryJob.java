package com.brightpath.batch.job;

import com.brightpath.batch.domain.AuditEvent;
import com.brightpath.batch.domain.GradeSyncEvent;
import com.brightpath.batch.domain.Submission;
import com.brightpath.batch.repository.AuditEventRepository;
import com.brightpath.batch.repository.CohortRepository;
import com.brightpath.batch.repository.CohortStaffRepository;
import com.brightpath.batch.repository.GradeSyncEventRepository;
import com.brightpath.batch.repository.SubmissionRepository;
import com.brightpath.batch.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Retries failed vendor-core grade syncs. The batch asks decision for a fresh
 * scope token (decision derives the scope from its own database), then calls
 * the vendor mock, which verifies the token itself.
 */
@Singleton
public class GradeSyncRetryJob {

    private static final Logger LOG = LoggerFactory.getLogger(GradeSyncRetryJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GradeSyncEventRepository syncEvents;
    private final SubmissionRepository submissions;
    private final CohortRepository cohorts;
    private final CohortStaffRepository cohortStaff;
    private final UserRepository users;
    private final AuditEventRepository audit;
    private final BlockingHttpClient decisionClient;
    private final BlockingHttpClient coreMockClient;
    private final String decisionSecret;
    private final String vendorApiKey;

    public GradeSyncRetryJob(GradeSyncEventRepository syncEvents, SubmissionRepository submissions,
                             CohortRepository cohorts, CohortStaffRepository cohortStaff,
                             UserRepository users, AuditEventRepository audit,
                             @Client("${brightpath.batch.decision.base-url}") HttpClient decisionHttpClient,
                             @Client("${brightpath.batch.coremock.base-url}") HttpClient coreMockHttpClient,
                             @Value("${brightpath.batch.decision.internal-secret}") String decisionSecret,
                             @Value("${brightpath.batch.vendor-api-key}") String vendorApiKey) {
        this.syncEvents = syncEvents;
        this.submissions = submissions;
        this.cohorts = cohorts;
        this.cohortStaff = cohortStaff;
        this.users = users;
        this.audit = audit;
        this.decisionClient = decisionHttpClient.toBlocking();
        this.coreMockClient = coreMockHttpClient.toBlocking();
        this.decisionSecret = decisionSecret;
        this.vendorApiKey = vendorApiKey;
    }

    @Scheduled(fixedDelay = "5m", initialDelay = "2m")
    @Transactional
    public void run() {
        for (GradeSyncEvent event : syncEvents.findByStatusIn(Set.of(GradeSyncEvent.STATUS_FAILED, GradeSyncEvent.STATUS_PARTIAL))) {
            retry(event);
        }
    }

    private void retry(GradeSyncEvent event) {
        try {
            Optional<String> instructor = findInstructorUsername(event.getCourseId());
            if (instructor.isEmpty()) {
                event.setStatus(GradeSyncEvent.STATUS_FAILED);
                event.setResponseText("retry skipped: no instructor scope found");
                syncEvents.update(event);
                return;
            }
            String scopeToken = mintScopeToken(instructor.get(), event.getCourseId());
            List<Map<String, Object>> grades = new ArrayList<>();
            for (Long submissionId : parseIds(event.getSubmissionIds())) {
                Submission submission = submissions.findById(submissionId).orElse(null);
                if (submission == null || submission.getReleasedGrade() == null) {
                    continue;
                }
                String maxPoints = submissions.maxPointsOfSubmission(submissionId)
                        .map(java.math.BigDecimal::toPlainString).orElse("100");
                grades.add(Map.of(
                        "studentId", submission.getStudentId(),
                        "assignmentId", submission.getAssignmentId(),
                        "points", submission.getReleasedGrade().toPlainString(),
                        "maxPoints", maxPoints,
                        "releasedAt", submission.getReleasedAt() == null
                                ? java.time.Instant.now().toString() : submission.getReleasedAt().toString()));
            }
            String body = MAPPER.writeValueAsString(Map.of("scopeToken", scopeToken, "courseId", event.getCourseId(), "grades", grades));
            HttpResponse<String> response = coreMockClient.exchange(
                    HttpRequest.POST("/v1/gradebooks/sync", body)
                            .contentType("application/json")
                            .header("X-Vendor-Api-Key", vendorApiKey),
                    Argument.of(String.class));
            event.setStatus(response.getStatus().getCode() == 201 ? GradeSyncEvent.STATUS_SUCCESS : GradeSyncEvent.STATUS_FAILED);
            event.setResponseText("retry: " + response.getStatus().getCode());
            syncEvents.update(event);
            audit.record("grade_sync.retried", "grade_sync", event.getSyncId(), null,
                    "status=" + event.getStatus());
        } catch (Exception e) {
            event.setStatus(GradeSyncEvent.STATUS_FAILED);
            event.setResponseText("retry error: " + e.getMessage());
            syncEvents.update(event);
            LOG.warn("grade sync retry failed for event {}: {}", event.getId(), e.getMessage());
        }
    }

    private Optional<String> findInstructorUsername(Long courseId) {
        for (var cohort : cohorts.findByCourseId(courseId)) {
            for (var staff : cohortStaff.findByCohortId(cohort.getId())) {
                if ("instructor".equals(staff.getStaffRole())) {
                    return users.findById(staff.getUserId()).map(u -> u.getUsername() == null ? "" : u.getUsername());
                }
            }
        }
        return Optional.empty();
    }

    private String mintScopeToken(String username, Long courseId) {
        HttpResponse<Map<String, Object>> response = decisionClient.exchange(
                HttpRequest.POST("/internal/mint-scope-token",
                                Map.of("username", username, "purpose", "grade-sync", "courseId", courseId, "ttlSeconds", 600))
                        .header("X-Internal-Secret", decisionSecret),
                Argument.mapOf(String.class, Object.class));
        return String.valueOf(response.body().get("scopeToken"));
    }

    private static List<Long> parseIds(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String part : csv.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }
}
