package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.function.Consumer;

@Service
public class DeepSeekChatService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public DeepSeekChatService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String complete(Prompt prompt, AssistantDtos.ProviderSection provider, Double temperature) {
        if (prompt == null || !hasApiKey(provider)) {
            return "";
        }
        try {
            HttpRequest request = buildRequest(prompt, provider, temperature, false);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            return extractCompletionText(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek completion failed", exception);
        }
    }

    public StreamResult stream(Prompt prompt, AssistantDtos.ProviderSection provider, Double temperature, Consumer<String> onDelta) {
        if (prompt == null || !hasApiKey(provider)) {
            return new StreamResult("", null);
        }

        StringBuilder answer = new StringBuilder();
        try {
            HttpRequest request = buildRequest(prompt, provider, temperature, true);
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                ensureSuccess(response.statusCode(), body);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    String delta = extractStreamDelta(payload);
                    if (delta.isBlank()) {
                        continue;
                    }
                    answer.append(delta);
                    onDelta.accept(delta);
                }
            }
            return new StreamResult(answer.toString(), null);
        } catch (Exception exception) {
            return new StreamResult(answer.toString(), exception.getClass().getSimpleName());
        }
    }

    private HttpRequest buildRequest(Prompt prompt, AssistantDtos.ProviderSection provider, Double temperature, boolean stream) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", provider.model());
        payload.put("messages", buildMessages(prompt));
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        payload.put("stream", stream);

        return HttpRequest.newBuilder()
                .uri(URI.create(resolveBaseUrl(provider) + "/chat/completions"))
                .timeout(Duration.ofSeconds(stream ? 90 : 60))
                .header("Authorization", "Bearer " + provider.apiKey())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
    }

    private List<Map<String, String>> buildMessages(Prompt prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (!(message instanceof AbstractMessage abstractMessage)) {
                continue;
            }
            messages.add(Map.of(
                    "role", message.getMessageType().getValue(),
                    "content", abstractMessage.getText()
            ));
        }
        return messages;
    }

    private String resolveBaseUrl(AssistantDtos.ProviderSection provider) {
        String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl().trim();
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.deepseek.com";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean hasApiKey(AssistantDtos.ProviderSection provider) {
        return provider != null && StringUtils.hasText(provider.apiKey());
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new IllegalStateException("DeepSeek request failed with status " + statusCode + ": " + body);
    }

    private String extractCompletionText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private String extractStreamDelta(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        return root.path("choices").path(0).path("delta").path("content").asText("");
    }

    public record StreamResult(String answer, String failureType) {
    }
}
