package com.brightpath.channel.service;

import com.brightpath.channel.domain.GradingScale;
import com.brightpath.channel.repository.GradingScaleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** Reference data used by filters and grading displays. */
@Singleton
public class ReferenceDataService {

    public static final List<String> COURSE_CATEGORIES = List.of(
            "Computer Science", "Data Science", "Business", "Design", "Mathematics",
            "Communications", "Engineering", "Language");

    public static final List<String> COURSE_LEVELS = List.of("Beginner", "Intermediate", "Advanced");

    public static final List<String> ASSIGNMENT_STATUSES =
            List.of("draft", "published", "closed", "archived");

    public static final List<String> SUBMISSION_STATUSES = List.of(
            "submitted", "late", "needs_resubmission", "graded", "released");

    public static final List<String> COHORT_STATUSES =
            List.of("upcoming", "active", "completed", "cancelled");

    public static final List<String> ENROLLMENT_STATUSES =
            List.of("pending", "active", "completed", "dropped");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GradingScaleRepository gradingScales;

    public ReferenceDataService(GradingScaleRepository gradingScales) {
        this.gradingScales = gradingScales;
    }

    public List<GradingScale> gradingScales() {
        return gradingScales.findAllOrderByMinPercentDesc();
    }

    /**
     * Reference payload with a strong-consistency tag. Non-cryptographic use:
     * the MD5 digest is used only as an HTTP ETag for this reference-data
     * response (cache validation), never for security purposes.
     */
    public TaggedReference referenceJson() {
        try {
            Map<String, Object> payload = Map.of(
                    "courseCategories", COURSE_CATEGORIES,
                    "courseLevels", COURSE_LEVELS,
                    "assignmentStatuses", ASSIGNMENT_STATUSES,
                    "submissionStatuses", SUBMISSION_STATUSES,
                    "cohortStatuses", COHORT_STATUSES,
                    "enrollmentStatuses", ENROLLMENT_STATUSES,
                    "gradingScale", gradingScales.findAllOrderByMinPercentDesc().stream()
                            .map(s -> Map.of("letter", s.getLetter(), "minPercent", s.getMinPercent(), "gpa", s.getGpa()))
                            .toList());
            String json = MAPPER.writeValueAsString(payload);
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            String etag = "\"" + java.util.HexFormat.of().formatHex(md5.digest(bytes)) + "\"";
            return new TaggedReference(json, etag);
        } catch (Exception e) {
            throw new IllegalStateException("could not build reference payload", e);
        }
    }

    public record TaggedReference(String json, String etag) {
    }
}
