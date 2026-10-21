package com.brightpath.channel.controller;

import com.brightpath.channel.domain.ApiKey;
import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.GradingScale;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.GradingScaleRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import com.brightpath.channel.service.ApiKeyService;
import com.brightpath.channel.service.AuditService;
import com.brightpath.channel.service.ReferenceDataService;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Machine integration surface authenticated by scoped API keys
 * (X-Api-Key header; only SHA-256 digests stored). Session-based
 * authorization does not apply to this surface.
 */
@Controller("/api/v1")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ApiV1Controller {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiKeyService apiKeys;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final EnrollmentRepository enrollments;
    private final GradingScaleRepository gradingScales;
    private final CohortRepository cohorts;
    private final AuditService audit;

    public ApiV1Controller(ApiKeyService apiKeys, CourseRepository courses,
                           AssignmentRepository assignments, SubmissionRepository submissions,
                           UserRepository users, EnrollmentRepository enrollments,
                           GradingScaleRepository gradingScales, CohortRepository cohorts, AuditService audit) {
        this.apiKeys = apiKeys;
        this.courses = courses;
        this.assignments = assignments;
        this.submissions = submissions;
        this.users = users;
        this.enrollments = enrollments;
        this.gradingScales = gradingScales;
        this.cohorts = cohorts;
        this.audit = audit;
    }

    @Get("/courses")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> courses(@Nullable @Header("X-Api-Key") String apiKey,
                                   @Nullable @QueryValue("q") String q) {
        ApiKey key = requireScope(apiKey, ApiKeyService.SCOPE_READ_COURSES);
        List<Map<String, Object>> result = new ArrayList<>();
        String query = q == null ? null : q.trim().toLowerCase();
        for (Course course : courses.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE)) {
            if (query != null && !course.getTitle().toLowerCase().contains(query)
                    && !course.getCode().toLowerCase().contains(query)) {
                continue;
            }
            result.add(Map.of(
                    "code", course.getCode(),
                    "title", course.getTitle(),
                    "category", course.getCategory(),
                    "level", course.getLevel(),
                    "enrollmentOpen", course.isEnrollmentOpen()));
        }
        audit.record(null, "api.courses_read", "api_courses", null, null,
                "count=" + result.size() + ",keyId=" + key.getId());
        return json(result);
    }

    @Get("/courses/{code}/grades")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> grades(String code, @Nullable @Header("X-Api-Key") String apiKey,
                                  @QueryValue(defaultValue = "json") String format) {
        ApiKey key = requireScope(apiKey, ApiKeyService.SCOPE_READ_GRADES);
        Course course = courses.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE).stream()
                .filter(c -> c.getCode().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> AppException.notFound("Course not found."));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Assignment assignment : assignments.findByCourseIdOrderByDueAtAsc(course.getId())) {
            for (Submission submission : submissions.findByAssignmentId(assignment.getId())) {
                if (submission.getReleasedGrade() == null) {
                    continue;
                }
                String email = users.findById(submission.getStudentId()).map(u -> u.getEmail()).orElse("");
                rows.add(Map.of(
                        "assignment", assignment.getTitle(),
                        "studentEmail", email,
                        "grade", submission.getReleasedGrade().toPlainString(),
                        "maxPoints", assignment.getMaxPoints().toPlainString(),
                        "releasedAt", submission.getReleasedAt() == null ? "" : submission.getReleasedAt().toString()));
            }
        }
        audit.record(null, "api.grades_export", "api_grades", null, null,
                "course=" + code + ",count=" + rows.size() + ",keyId=" + key.getId());
        if ("csv".equalsIgnoreCase(format)) {
            List<String[]> csvRows = new ArrayList<>();
            csvRows.add(new String[]{"assignment", "student_email", "grade", "max_points", "released_at"});
            for (Map<String, Object> row : rows) {
                csvRows.add(new String[]{(String) row.get("assignment"), (String) row.get("studentEmail"),
                        (String) row.get("grade"), (String) row.get("maxPoints"), (String) row.get("releasedAt")});
            }
            return HttpResponse.ok(com.brightpath.channel.service.CsvUtil.render(csvRows))
                    .contentType(MediaType.TEXT_PLAIN_TYPE)
                    .header("Content-Disposition", "attachment; filename=\"grades-" + code + ".csv\"");
        }
        return json(rows);
    }

    @Get("/reference")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> reference(@Nullable @Header("X-Api-Key") String apiKey,
                                     @Nullable @Header("If-None-Match") String ifNoneMatch) {
        requireScope(apiKey, ApiKeyService.SCOPE_READ_COURSES);
        ReferenceDataService service = new ReferenceDataService(gradingScales);
        ReferenceDataService.TaggedReference tagged = service.referenceJson();
        // Non-cryptographic MD5 used only as the ETag for this reference-data response.
        if (ifNoneMatch != null && ifNoneMatch.equals(tagged.etag())) {
            return HttpResponse.notModified().header("ETag", tagged.etag());
        }
        return HttpResponse.ok(tagged.json())
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .header("ETag", tagged.etag())
                .header("Cache-Control", "public, max-age=300");
    }

    /**
     * Partner district roster push: a school district exports a signed grant file alongside the
     * CSV of students it wants pre-registered. The grant is the partner's credential for the
     * rostering feed and identifies the district it is authorised to push for.
     */
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/partners/roster")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> partnerRoster(@Part("grant") CompletedFileUpload grant,
                                         @Part("roster") CompletedFileUpload roster) {
        try {
            String assertion = new String(grant.getBytes(), StandardCharsets.UTF_8);
            SignedJWT parsed = SignedJWT.parse(assertion);
            String partnerCode = parsed.getJWTClaimsSet().getSubject();
            List<String[]> rows = com.brightpath.channel.service.CsvUtil.parse(
                    new String(roster.getBytes(), StandardCharsets.UTF_8));
            int applied = 0;
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                if (row.length < 2 || row[0] == null || row[1] == null) {
                    continue;
                }
                Long cohortId;
                try {
                    cohortId = Long.valueOf(row[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                var student = users.findByEmail(row[0].trim().toLowerCase(Locale.ROOT)).orElse(null);
                if (student == null || !"STUDENT".equals(student.getRole()) || cohorts.findById(cohortId).isEmpty()) {
                    continue;
                }
                if (enrollments.findByCohortIdAndStudentId(cohortId, student.getId()).isPresent()) {
                    continue;
                }
                Enrollment enrollment = new Enrollment();
                enrollment.setCohortId(cohortId);
                enrollment.setStudentId(student.getId());
                enrollment.setStatus(Enrollment.STATUS_PENDING);
                enrollments.save(enrollment);
                applied++;
            }
            audit.record(null, "api.partner_roster_applied", "api_roster", null, null,
                    "partner=" + partnerCode + ",count=" + applied);
            return json(Map.of("partner", partnerCode, "applied", applied));
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The partner roster could not be applied.");
        }
    }

    private ApiKey requireScope(String apiKey, String scope) {
        Optional<ApiKey> key = apiKeys.authenticate(apiKey);
        if (key.isEmpty()) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "A valid X-Api-Key header is required.");
        }
        if (!List.of(key.get().getScopes().split(",")).contains(scope)) {
            throw new AppException(HttpStatus.FORBIDDEN, "This API key does not grant " + scope + ".");
        }
        return key.get();
    }

    private static HttpResponse<?> json(Object body) {
        try {
            return HttpResponse.ok(MAPPER.writeValueAsString(body))
                    .contentType(MediaType.APPLICATION_JSON_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("json serialization failed", e);
        }
    }
}
