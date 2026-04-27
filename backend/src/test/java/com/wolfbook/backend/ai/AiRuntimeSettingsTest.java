package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.service.AiRuntimeSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiRuntimeSettingsTest {

    @Test
    void persistedConfigAndManualUpdatesShouldOverrideEnvironmentDefaults() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeBaseEnabled(true);
        properties.getWebSearch().setEnabled(true);
        properties.getRetrieval().setTopK(8);
        properties.getRetrieval().setMinScore(0.12);
        properties.getRetrieval().setMaxEvidenceChars(6000);
        properties.getChat().setApiKey("env-chat-key");
        properties.getChat().setModel("env-chat-model");
        properties.getEmbedding().setApiKey("env-embedding-key");
        properties.getEmbedding().setModel("env-embedding-model");

        AiRuntimeSettings settings = new AiRuntimeSettings(properties);

        settings.applyPersistedConfig(Map.of(
                "enabled", "false",
                "knowledgeBaseEnabled", "false",
                "webSearchEnabled", "false",
                "topK", "5",
                "minScore", "0.2",
                "maxEvidenceChars", "3200",
                "chatModel", "db-chat-model",
                "embeddingModel", "db-embedding-model",
                "chatApiKey", "db-chat-key",
                "embeddingApiKey", "db-embedding-key"
        ));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.knowledgeBaseEnabled()).isFalse();
        assertThat(settings.webSearchEnabled()).isFalse();
        assertThat(settings.topK()).isEqualTo(5);
        assertThat(settings.minScore()).isEqualTo(0.2);
        assertThat(settings.maxEvidenceChars()).isEqualTo(3200);
        assertThat(settings.chatModel()).isEqualTo("db-chat-model");
        assertThat(settings.embeddingModel()).isEqualTo("db-embedding-model");
        assertThat(settings.chatApiKey()).isEqualTo("db-chat-key");
        assertThat(settings.embeddingApiKey()).isEqualTo("db-embedding-key");

        settings.update(new AiDtos.AdminAiConfigRequest(
                true,
                true,
                true,
                9,
                0.15,
                6800,
                "runtime-chat-model",
                "runtime-embedding-model",
                "runtime-chat-key",
                "runtime-embedding-key"
        ));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.knowledgeBaseEnabled()).isTrue();
        assertThat(settings.webSearchEnabled()).isTrue();
        assertThat(settings.topK()).isEqualTo(9);
        assertThat(settings.minScore()).isEqualTo(0.15);
        assertThat(settings.maxEvidenceChars()).isEqualTo(6800);
        assertThat(settings.chatModel()).isEqualTo("runtime-chat-model");
        assertThat(settings.embeddingModel()).isEqualTo("runtime-embedding-model");
        assertThat(settings.chatApiKey()).isEqualTo("runtime-chat-key");
        assertThat(settings.embeddingApiKey()).isEqualTo("runtime-embedding-key");
    }

    @Test
    void legacyDefaultChatModelShouldBeNormalizedToMiniMaxM27() {
        AiProperties properties = new AiProperties();

        AiRuntimeSettings settings = new AiRuntimeSettings(properties);
        settings.applyPersistedConfig(Map.of("chatModel", "abab6.5s-chat"));

        assertThat(settings.chatModel()).isEqualTo("MiniMax-M2.7");
    }
}
