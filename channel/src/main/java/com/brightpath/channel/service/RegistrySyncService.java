package com.brightpath.channel.service;

import com.brightpath.channel.domain.Course;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes the active course catalog to the state training registry (the
 * licensing body that keeps accredited courses on file). The registry key is
 * issued to the school and passed through with each publish.
 */
@Singleton
public class RegistrySyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public String publish(String registryKey, List<Course> courses) {
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Course course : courses) {
                entries.add(Map.of("code", course.getCode(), "title", course.getTitle()));
            }
            String body = MAPPER.writeValueAsString(Map.of("catalog", entries));
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://catalog-registry.internal/v2/catalog?registryKey=" + registryKey))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("registry publish failed", e);
        }
    }
}
