package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.dto.AiDtos;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class AiRuntimeSettings {
    private static final String LEGACY_CHAT_MODEL = "abab6.5s-chat";

    public static final String ENABLED_KEY = "enabled";
    public static final String KNOWLEDGE_BASE_ENABLED_KEY = "knowledgeBaseEnabled";
    public static final String WEB_SEARCH_ENABLED_KEY = "webSearchEnabled";
    public static final String TOP_K_KEY = "topK";
    public static final String MIN_SCORE_KEY = "minScore";
    public static final String MAX_EVIDENCE_CHARS_KEY = "maxEvidenceChars";
    public static final String CHAT_MODEL_KEY = "chatModel";
    public static final String EMBEDDING_MODEL_KEY = "embeddingModel";
    public static final String CHAT_API_KEY = "chatApiKey";
    public static final String EMBEDDING_API_KEY = "embeddingApiKey";

    private boolean enabled;
    private boolean knowledgeBaseEnabled;
    private boolean webSearchEnabled;
    private int topK;
    private double minScore;
    private int maxEvidenceChars;
    private String chatModel;
    private String embeddingModel;
    private String chatApiKey;
    private String embeddingApiKey;
    private boolean storeHydrated;

    public AiRuntimeSettings(AiProperties properties) {
        this.enabled = properties.isEnabled();
        this.knowledgeBaseEnabled = properties.isKnowledgeBaseEnabled();
        this.webSearchEnabled = properties.getWebSearch().isEnabled();
        this.topK = properties.getRetrieval().getTopK();
        this.minScore = properties.getRetrieval().getMinScore();
        this.maxEvidenceChars = properties.getRetrieval().getMaxEvidenceChars();
        this.chatModel = properties.getChat().getModel();
        this.embeddingModel = properties.getEmbedding().getModel();
        this.chatApiKey = properties.getChat().getApiKey();
        this.embeddingApiKey = properties.getEmbedding().getApiKey();
        this.storeHydrated = false;
    }

    public synchronized void update(AiDtos.AdminAiConfigRequest request) {
        if (request.enabled() != null) {
            enabled = request.enabled();
        }
        if (request.knowledgeBaseEnabled() != null) {
            knowledgeBaseEnabled = request.knowledgeBaseEnabled();
        }
        if (request.webSearchEnabled() != null) {
            webSearchEnabled = request.webSearchEnabled();
        }
        if (request.topK() != null && request.topK() > 0) {
            topK = request.topK();
        }
        if (request.minScore() != null && request.minScore() >= 0) {
            minScore = request.minScore();
        }
        if (request.maxEvidenceChars() != null && request.maxEvidenceChars() > 500) {
            maxEvidenceChars = request.maxEvidenceChars();
        }
        if (request.chatModel() != null && !request.chatModel().isBlank()) {
            chatModel = normalizeModel(request.chatModel().trim());
        }
        if (request.embeddingModel() != null && !request.embeddingModel().isBlank()) {
            embeddingModel = request.embeddingModel().trim();
        }
        if (StringUtils.hasText(request.chatApiKey())) {
            chatApiKey = request.chatApiKey().trim();
        }
        if (StringUtils.hasText(request.embeddingApiKey())) {
            embeddingApiKey = request.embeddingApiKey().trim();
        }
    }

    public synchronized void applyPersistedConfig(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            storeHydrated = true;
            return;
        }
        enabled = parseBoolean(values.get(ENABLED_KEY), enabled);
        knowledgeBaseEnabled = parseBoolean(values.get(KNOWLEDGE_BASE_ENABLED_KEY), knowledgeBaseEnabled);
        webSearchEnabled = parseBoolean(values.get(WEB_SEARCH_ENABLED_KEY), webSearchEnabled);
        topK = parseInt(values.get(TOP_K_KEY), topK);
        minScore = parseDouble(values.get(MIN_SCORE_KEY), minScore);
        maxEvidenceChars = parseInt(values.get(MAX_EVIDENCE_CHARS_KEY), maxEvidenceChars);
        chatModel = parseString(values.get(CHAT_MODEL_KEY), chatModel);
        embeddingModel = parseString(values.get(EMBEDDING_MODEL_KEY), embeddingModel);
        chatApiKey = parseString(values.get(CHAT_API_KEY), chatApiKey);
        embeddingApiKey = parseString(values.get(EMBEDDING_API_KEY), embeddingApiKey);
        storeHydrated = true;
    }

    public synchronized boolean enabled() {
        return enabled;
    }

    public synchronized boolean knowledgeBaseEnabled() {
        return knowledgeBaseEnabled;
    }

    public synchronized boolean webSearchEnabled() {
        return webSearchEnabled;
    }

    public synchronized int topK() {
        return topK;
    }

    public synchronized double minScore() {
        return minScore;
    }

    public synchronized int maxEvidenceChars() {
        return maxEvidenceChars;
    }

    public synchronized String chatModel() {
        return chatModel;
    }

    public synchronized String embeddingModel() {
        return embeddingModel;
    }

    public synchronized String chatApiKey() {
        return chatApiKey;
    }

    public synchronized String embeddingApiKey() {
        return embeddingApiKey;
    }

    public synchronized String chatApiKeyMasked() {
        return maskSecret(chatApiKey);
    }

    public synchronized String embeddingApiKeyMasked() {
        return maskSecret(embeddingApiKey);
    }

    public synchronized boolean storeHydrated() {
        return storeHydrated;
    }

    private boolean parseBoolean(String value, boolean fallback) {
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String parseString(String value, String fallback) {
        return StringUtils.hasText(value) ? normalizeModel(value.trim()) : normalizeModel(fallback);
    }

    private String normalizeModel(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (LEGACY_CHAT_MODEL.equalsIgnoreCase(value.trim())) {
            return AiProperties.Chat.DEFAULT_MODEL;
        }
        return value.trim();
    }

    private String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        if (trimmed.length() <= 8) {
            return trimmed.substring(0, 1) + "****" + trimmed.substring(trimmed.length() - 1);
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
