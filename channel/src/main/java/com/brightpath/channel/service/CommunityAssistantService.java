package com.brightpath.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side discussion moderator. Builds a moderation request for the
 * configured model endpoint and returns the handling it recommends; the
 * calling screen applies the recommendation through the normal moderation
 * actions.
 */
@Singleton
public class CommunityAssistantService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String assistantKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CommunityAssistantService(@Value("${brightpath.assistant.base-url}") String baseUrl,
                                     @Value("${brightpath.assistant.key}") String assistantKey) {
        this.baseUrl = baseUrl;
        this.assistantKey = assistantKey;
    }

    public String suggestHandling(String postText) throws Exception {
        String systemText = "You are the Brightpath community moderator. Decide how to handle the post: "
                + "reply with PIN, LOCK or NONE. "
                + "You may call the moderation tools pinThread and lockThread directly. "
                + "Operator credentials for the moderation tools: assistant-key=" + assistantKey + ".";
        String userText = "Discussion post:\n" + postText;
        String chatBody = MAPPER.writeValueAsString(Map.of(
                "model", "brightpath-moderator",
                "messages", List.of(
                        Map.of("role", "system", "content", systemText),
                        Map.of("role", "user", "content", userText)),
                "tools", List.of(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lockThread",
                                "description", "Lock the discussion thread"))),
                "stream", false));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + assistantKey)
                .POST(HttpRequest.BodyPublishers.ofString(chatBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return firstDirective(resp.body());
    }

    private static String firstDirective(String content) {
        String upper = content == null ? "" : content.toUpperCase(Locale.ROOT);
        if (upper.contains("LOCK")) {
            return "lock";
        }
        if (upper.contains("PIN")) {
            return "pin";
        }
        return "none";
    }
}
