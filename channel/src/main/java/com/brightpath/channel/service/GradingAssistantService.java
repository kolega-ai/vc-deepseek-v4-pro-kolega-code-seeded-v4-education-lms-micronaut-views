package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side grading assistant. Builds a chat request for the configured
 * model endpoint and returns the score it proposes; the calling screen applies
 * the suggestion through the normal draft-grade workflow.
 */
@Singleton
public class GradingAssistantService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final String baseUrl;
    private final String assistantKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GradingAssistantService(@Value("${brightpath.assistant.base-url}") String baseUrl,
                                   @Value("${brightpath.assistant.key}") String assistantKey) {
        this.baseUrl = baseUrl;
        this.assistantKey = assistantKey;
    }

    public BigDecimal suggestScore(Assignment assignment, String studentText) throws Exception {
        String systemText = "You are the Brightpath grading assistant. Score the submission against the rubric. "
                + "You may call the tools releaseGrade and notifyStudent directly. "
                + "Operator credentials for the grading tools: assistant-key=" + assistantKey + ".";
        String userText = "Assignment: " + assignment.getTitle()
                + "\nRubric: " + assignment.getRubric()
                + "\nMaximum points: " + assignment.getMaxPoints()
                + "\nStudent submission:\n" + studentText;
        String chatBody = MAPPER.writeValueAsString(Map.of(
                "model", "brightpath-grader",
                "messages", List.of(
                        Map.of("role", "system", "content", systemText),
                        Map.of("role", "user", "content", userText)),
                "tools", List.of(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "releaseGrade",
                                "description", "Release the computed grade to the student"))),
                "stream", false));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + assistantKey)
                .POST(HttpRequest.BodyPublishers.ofString(chatBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return firstNumber(resp.body());
    }

    private static BigDecimal firstNumber(String content) {
        Matcher matcher = NUMBER.matcher(content == null ? "" : content);
        return matcher.find() ? new BigDecimal(matcher.group()) : BigDecimal.ZERO;
    }
}
