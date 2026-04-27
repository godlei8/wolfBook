package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.service.AiRuntimeSettings;
import com.wolfbook.backend.ai.service.MiniMaxFailureClassifier;
import com.wolfbook.backend.ai.service.MiniMaxGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.minimax.api.MiniMaxApi;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MiniMaxGatewayStreamTest {

    @Test
    void streamAnswerAggregatesDeltaChunks() {
        AiProperties properties = new AiProperties();
        AiRuntimeSettings settings = new AiRuntimeSettings(properties);
        settings.update(new AiDtos.AdminAiConfigRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "chat-key",
                null
        ));
        MiniMaxFailureClassifier failureClassifier = new MiniMaxFailureClassifier();

        MiniMaxGateway gateway = new MiniMaxGateway(
                properties,
                settings,
                failureClassifier,
                (baseUrl, apiKey) -> new MiniMaxGateway.MiniMaxClient() {
                    @Override
                    public MiniMaxApi.ChatCompletion complete(MiniMaxApi.ChatCompletionRequest request) {
                        throw new UnsupportedOperationException("not needed");
                    }

                    @Override
                    public Flux<MiniMaxApi.ChatCompletionChunk> stream(MiniMaxApi.ChatCompletionRequest request) {
                        return Flux.just(
                                chunk("first "),
                                chunk("second "),
                                chunk("third")
                        );
                    }

                    @Override
                    public MiniMaxApi.EmbeddingList embed(MiniMaxApi.EmbeddingRequest request) {
                        throw new UnsupportedOperationException("not needed");
                    }
                }
        );

        List<String> deltas = new ArrayList<>();
        String answer = gateway.streamAnswer("question", false, deltas::add);

        assertThat(deltas).containsExactly("first ", "second ", "third");
        assertThat(answer).isEqualTo("first second third");
    }

    @Test
    void runtimeConfiguredKeysShouldDriveGatewayReadiness() {
        AiProperties properties = new AiProperties();
        AiRuntimeSettings settings = new AiRuntimeSettings(properties);
        settings.update(new AiDtos.AdminAiConfigRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "runtime-chat-key",
                "runtime-embedding-key"
        ));

        MiniMaxGateway gateway = new MiniMaxGateway(
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
        );

        assertThat(gateway.chatReady()).isTrue();
        assertThat(gateway.embeddingReady()).isTrue();
    }

    private MiniMaxApi.ChatCompletionChunk chunk(String content) {
        return new MiniMaxApi.ChatCompletionChunk(
                "chatcmpl-1",
                List.of(new MiniMaxApi.ChatCompletionChunk.ChunkChoice(
                        null,
                        0,
                        new MiniMaxApi.ChatCompletionMessage(content, MiniMaxApi.ChatCompletionMessage.Role.ASSISTANT),
                        null
                )),
                1L,
                "abab6.5s-chat",
                null,
                "chat.completion.chunk"
        );
    }
}
