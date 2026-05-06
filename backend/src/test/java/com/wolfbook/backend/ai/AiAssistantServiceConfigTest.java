package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.model.AiAnswerType;
import com.wolfbook.backend.ai.model.AiQueryPlan;
import com.wolfbook.backend.ai.service.AiAssistantService;
import com.wolfbook.backend.ai.service.AiQueryPlanner;
import com.wolfbook.backend.ai.service.AiRrfFusion;
import com.wolfbook.backend.ai.service.AiRuntimeSettings;
import com.wolfbook.backend.ai.service.MiniMaxFailureClassifier;
import com.wolfbook.backend.ai.service.MiniMaxGateway;
import com.wolfbook.backend.ai.store.AiKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.minimax.api.MiniMaxApi;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAssistantServiceConfigTest {

    @Test
    void updateConfigShouldReturnConfiguredStatusAndMaskedKeys() {
        AiProperties properties = new AiProperties();
        properties.getChat().setApiKey("");
        properties.getEmbedding().setApiKey("");

        AiRuntimeSettings settings = new AiRuntimeSettings(properties);
        AiKnowledgeStore store = Mockito.mock(AiKnowledgeStore.class);
        when(store.ready()).thenReturn(true);
        when(store.loadConfig()).thenReturn(Map.of());

        AiAssistantService service = new AiAssistantService(
                properties,
                settings,
                store,
                Mockito.mock(AiQueryPlanner.class),
                Mockito.mock(AiRrfFusion.class),
                new MiniMaxGateway(
                        properties,
                        settings,
                        new MiniMaxFailureClassifier(),
                        (baseUrl, apiKey) -> new MiniMaxGateway.MiniMaxClient() {
                            @Override
                            public MiniMaxApi.ChatCompletion complete(MiniMaxApi.ChatCompletionRequest request) {
                                throw new UnsupportedOperationException("not needed");
                            }

                            @Override
                            public Flux<MiniMaxApi.ChatCompletionChunk> stream(MiniMaxApi.ChatCompletionRequest request) {
                                return Flux.empty();
                            }

                            @Override
                            public MiniMaxApi.EmbeddingList embed(MiniMaxApi.EmbeddingRequest request) {
                                throw new UnsupportedOperationException("not needed");
                            }
                        }
                )
        );

        AiDtos.AdminAiConfigView view = service.updateAdminConfig(new AiDtos.AdminAiConfigRequest(
                true,
                true,
                true,
                8,
                0.12,
                6000,
                "MiniMax-M2.7",
                "embo-01",
                "sk-chat-1234567890",
                "sk-embed-1234567890"
        ));

        assertThat(view.chatReady()).isTrue();
        assertThat(view.embeddingReady()).isTrue();
        assertThat(view.chatApiKeyMasked()).isEqualTo("sk-c****7890");
        assertThat(view.embeddingApiKeyMasked()).isEqualTo("sk-e****7890");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(store).saveConfig(captor.capture());
        assertThat(captor.getValue()).containsEntry("chatApiKey", "sk-chat-1234567890");
        assertThat(captor.getValue()).containsEntry("embeddingApiKey", "sk-embed-1234567890");
    }

    @Test
    void chatKeyShouldEnableWebOnlyAnswerWhenMcpKeyIsNotExplicitlyConfigured() {
        AiProperties properties = new AiProperties();
        properties.setKnowledgeBaseEnabled(false);
        properties.getWebSearch().setEnabled(true);
        properties.getWebSearch().setMcpApiKey("");

        AiRuntimeSettings settings = new AiRuntimeSettings(properties);
        settings.update(new AiDtos.AdminAiConfigRequest(
                true,
                false,
                true,
                8,
                0.12,
                6000,
                "MiniMax-M2.7",
                "embo-01",
                "sk-chat-1234567890",
                null
        ));

        AiKnowledgeStore store = Mockito.mock(AiKnowledgeStore.class);
        when(store.ready()).thenReturn(false);

        AiQueryPlanner queryPlanner = Mockito.mock(AiQueryPlanner.class);
        when(queryPlanner.plan(Mockito.anyString(), Mockito.anySet(), Mockito.any())).thenReturn(
                new AiQueryPlan(
                        "女巫能不能自救？",
                        "女巫能不能自救",
                        "女巫",
                        "RULE",
                        false,
                        false,
                        List.of()
                )
        );

        AiAssistantService service = new AiAssistantService(
                properties,
                settings,
                store,
                queryPlanner,
                Mockito.mock(AiRrfFusion.class),
                new MiniMaxGateway(
                        properties,
                        settings,
                        new MiniMaxFailureClassifier(),
                        (baseUrl, apiKey) -> new MiniMaxGateway.MiniMaxClient() {
                            @Override
                            public MiniMaxApi.ChatCompletion complete(MiniMaxApi.ChatCompletionRequest request) {
                                return new MiniMaxApi.ChatCompletion(
                                        "chatcmpl-1",
                                        List.of(new MiniMaxApi.ChatCompletion.Choice(
                                                null,
                                                0,
                                                new MiniMaxApi.ChatCompletionMessage("可以，常规规则下女巫首夜可自救。", MiniMaxApi.ChatCompletionMessage.Role.ASSISTANT),
                                                null,
                                                null
                                        )),
                                        1L,
                                        "MiniMax-M2.7",
                                        null,
                                        "chat.completion",
                                        null,
                                        null
                                );
                            }

                            @Override
                            public Flux<MiniMaxApi.ChatCompletionChunk> stream(MiniMaxApi.ChatCompletionRequest request) {
                                return Flux.empty();
                            }

                            @Override
                            public MiniMaxApi.EmbeddingList embed(MiniMaxApi.EmbeddingRequest request) {
                                throw new UnsupportedOperationException("not needed");
                            }
                        }
                )
        );

        AiDtos.AiAskResponse response = service.ask("openid-1", new AiDtos.AiAskRequest(null, "女巫能不能自救？"));

        assertThat(response.answerType()).isEqualTo(AiAnswerType.WEB_ONLY_ANSWER);
        assertThat(response.answer()).contains("女巫");
    }
}
