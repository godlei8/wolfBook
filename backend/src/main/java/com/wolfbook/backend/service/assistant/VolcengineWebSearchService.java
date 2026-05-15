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
public class VolcengineWebSearchService {

    private final AssistantProperties assistantProperties;
    private final AssistantConfigService assistantConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public VolcengineWebSearchService(
            AssistantProperties assistantProperties,
            AssistantConfigService assistantConfigService,
            ObjectMapper objectMapper
    ) {
        this.assistantProperties = assistantProperties;
        this.assistantConfigService = assistantConfigService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        AssistantDtos.VolcengineSection config = currentConfig();
        return assistantProperties.isWebSearchEnabled()
                && StringUtils.hasText(config.searchApiKey())
                && StringUtils.hasText(config.searchModel());
    }

    public SearchResult search(String query) {
        if (!StringUtils.hasText(query) || !isAvailable()) {
            return SearchResult.empty();
        }
        try {
            AssistantDtos.VolcengineSection config = currentConfig();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.searchModel());
            payload.put("input", query);
            payload.put("tools", List.of(Map.of("type", "web_search")));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveBaseUrl(config.baseUrl()) + "/responses"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + config.searchApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            return parseResponse(response.body());
        } catch (Exception exception) {
            return SearchResult.empty(exception.getClass().getSimpleName());
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
        throw new IllegalStateException("Volcengine web search request failed with status " + statusCode + ": " + body);
    }

    private SearchResult parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String answer = root.path("output_text").asText("");
        if (!StringUtils.hasText(answer)) {
            answer = collectOutputText(root.path("output"));
        }
        List<AssistantDtos.AssistantCitation> citations = new ArrayList<>();
        collectCitations(root, citations);
        return new SearchResult(answer == null ? "" : answer.trim(), citations, null);
    }

    private String collectOutputText(JsonNode output) {
        if (output == null || output.isMissingNode()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : output) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode content : item.path("content")) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text);
                    }
                }
            }
        }
        return builder.toString();
    }

    private void collectCitations(JsonNode node, List<AssistantDtos.AssistantCitation> citations) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            JsonNode urlNode = firstNonBlank(node.get("url"), node.get("link"));
            JsonNode titleNode = firstNonBlank(node.get("title"), node.get("site_name"), node.get("name"));
            JsonNode textNode = firstNonBlank(node.get("snippet"), node.get("text"), node.get("content"));
            if (urlNode != null && !urlNode.asText("").isBlank()) {
                citations.add(new AssistantDtos.AssistantCitation(
                        AssistantConstants.SOURCE_WEB,
                        titleNode == null ? "联网搜索来源" : titleNode.asText("联网搜索来源"),
                        textNode == null ? "" : truncate(textNode.asText("")),
                        urlNode.asText(""),
                        null
                ));
            }
            node.fields().forEachRemaining(entry -> collectCitations(entry.getValue(), citations));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectCitations(child, citations);
            }
        }
    }

    private JsonNode firstNonBlank(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && StringUtils.hasText(node.asText())) {
                return node;
            }
        }
        return null;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180) + "...";
    }

    public record SearchResult(
            String answer,
            List<AssistantDtos.AssistantCitation> citations,
            String failureType
    ) {
        static SearchResult empty() {
            return new SearchResult("", List.of(), null);
        }

        static SearchResult empty(String failureType) {
            return new SearchResult("", List.of(), failureType);
        }

        boolean hasAnswer() {
            return answer != null && !answer.isBlank();
        }
    }
}
