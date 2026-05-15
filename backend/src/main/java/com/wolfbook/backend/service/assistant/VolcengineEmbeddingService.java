package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VolcengineEmbeddingService {

    private final AssistantProperties assistantProperties;
    private final AssistantConfigService assistantConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public VolcengineEmbeddingService(
            AssistantProperties assistantProperties,
            AssistantConfigService assistantConfigService,
            ObjectMapper objectMapper
    ) {
        this.assistantProperties = assistantProperties;
        this.assistantConfigService = assistantConfigService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return StringUtils.hasText(currentConfig().embeddingApiKey()) && StringUtils.hasText(currentConfig().embeddingModel());
    }

    public int defaultDimensions() {
        return assistantProperties.getVolcengine().getEmbeddingDimensions();
    }

    public List<Double> embed(String input) {
        List<List<Double>> results = embedBatch(List.of(input));
        return results.isEmpty() ? List.of() : results.getFirst();
    }

    public List<List<Double>> embedBatch(List<String> inputs) {
        if (inputs == null || inputs.isEmpty() || !isAvailable()) {
            return List.of();
        }
        try {
            AssistantDtos.VolcengineSection config = currentConfig();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.embeddingModel());
            payload.put("input", inputs);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveBaseUrl(config.baseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.embeddingApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            return parseEmbeddings(response.body());
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String resolveBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return AssistantProperties.VOLCENGINE_DEFAULT_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private AssistantDtos.VolcengineSection currentConfig() {
        return assistantConfigService.getRuntimeConfig().volcengine();
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new IllegalStateException("Volcengine embedding request failed with status " + statusCode + ": " + body);
    }

    private List<List<Double>> parseEmbeddings(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        List<List<Double>> results = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : item.path("embedding")) {
                vector.add(value.asDouble());
            }
            results.add(vector);
        }
        return results;
    }
}
