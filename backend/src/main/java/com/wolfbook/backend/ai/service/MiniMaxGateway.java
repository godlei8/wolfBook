package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.model.AiFailure;
import com.wolfbook.backend.ai.model.AiFailureReason;
import com.wolfbook.backend.ai.model.AiServiceException;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class MiniMaxGateway {

    private static final String WEREWOLF_SYSTEM_PROMPT = """
            你是 WolfBook 的狼人杀 AI 助手。必须遵守：
            1. 只回答狼人杀规则、角色、板子、术语、复盘、赛事资讯相关内容。
            2. 非狼人杀问题直接说明只回答狼人杀相关内容。
            3. 如果给了【证据】，答案只能基于证据；证据不足时明确说明缺少资料。
            4. 如果启用联网搜索，搜索和总结也必须限定在狼人杀语境，不扩展到舞蹈、影视、生活常识等无关内容。
            5. 用中文回答，结论先行，必要时列出规则来源差异。
            """;

    private final AiProperties properties;
    private final AiRuntimeSettings settings;
    private final MiniMaxFailureClassifier failureClassifier;
    private final MiniMaxClientFactory clientFactory;

    @Autowired
    public MiniMaxGateway(AiProperties properties, AiRuntimeSettings settings, MiniMaxFailureClassifier failureClassifier) {
        this(properties, settings, failureClassifier, DefaultMiniMaxClient::new);
    }

    public MiniMaxGateway(
            AiProperties properties,
            AiRuntimeSettings settings,
            MiniMaxFailureClassifier failureClassifier,
            MiniMaxClientFactory clientFactory
    ) {
        this.properties = properties;
        this.settings = settings;
        this.failureClassifier = failureClassifier;
        this.clientFactory = clientFactory;
    }

    public boolean chatReady() {
        return StringUtils.hasText(settings.chatApiKey());
    }

    public boolean embeddingReady() {
        return StringUtils.hasText(settings.embeddingApiKey());
    }

    public String answer(String userPrompt, boolean useWebSearch) {
        if (!chatReady()) {
            throw new AiServiceException(failureClassifier.missingKey("MINIMAX_CHAT_API_KEY"));
        }
        try {
            MiniMaxClient client = createChatClient();
            MiniMaxApi.ChatCompletion body = client.complete(buildChatRequest(userPrompt, useWebSearch, false));
            return extractAnswer(body);
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyTransportFailure(exception);
        }
    }

    public String streamAnswer(String userPrompt, boolean useWebSearch, Consumer<String> onDelta) {
        if (!chatReady()) {
            throw new AiServiceException(failureClassifier.missingKey("MINIMAX_CHAT_API_KEY"));
        }
        Consumer<String> deltaConsumer = onDelta == null ? ignored -> { } : onDelta;
        try {
            MiniMaxApi.ChatCompletionRequest request = buildChatRequest(userPrompt, useWebSearch, true);
            StringBuilder answer = new StringBuilder();
            createChatClient()
                    .stream(request)
                    .timeout(properties.getChat().getTimeout())
                    .doOnNext(chunk -> appendChunk(answer, deltaConsumer, chunk))
                    .blockLast();
            String finalAnswer = answer.toString().trim();
            if (!StringUtils.hasText(finalAnswer)) {
                throw new AiServiceException(new AiFailure(
                        AiFailureReason.SERVICE_UNAVAILABLE,
                        "MiniMax 返回内容为空，请稍后再试。",
                        true
                ));
            }
            return finalAnswer;
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyTransportFailure(exception);
        }
    }

    public List<float[]> embedForDb(List<String> texts) {
        return embed(texts, MiniMaxApi.EmbeddingType.DB);
    }

    public float[] embedForQuery(String text) {
        List<float[]> embeddings = embed(List.of(text), MiniMaxApi.EmbeddingType.Query);
        return embeddings.isEmpty() ? null : embeddings.getFirst();
    }

    private List<float[]> embed(List<String> texts, MiniMaxApi.EmbeddingType type) {
        if (!embeddingReady()) {
            throw new AiServiceException(failureClassifier.missingKey("MINIMAX_EMBEDDING_API_KEY"));
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            MiniMaxClient client = createEmbeddingClient();
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                MiniMaxApi.EmbeddingRequest request = new MiniMaxApi.EmbeddingRequest(
                        List.of(text),
                        settings.embeddingModel(),
                        type.getValue()
                );
                MiniMaxApi.EmbeddingList body = client.embed(request);
                if (body == null || body.vectors() == null || body.vectors().isEmpty()) {
                    vectors.add(null);
                } else {
                    vectors.add(body.vectors().getFirst());
                }
                sleepTinyBatchDelay();
            }
            return vectors;
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyTransportFailure(exception);
        }
    }

    private MiniMaxClient createChatClient() {
        return clientFactory.create(properties.getChat().getBaseUrl(), settings.chatApiKey());
    }

    private MiniMaxClient createEmbeddingClient() {
        return clientFactory.create(properties.getEmbedding().getBaseUrl(), settings.embeddingApiKey());
    }

    private MiniMaxApi.ChatCompletionRequest buildChatRequest(String userPrompt, boolean useWebSearch, boolean stream) {
        List<MiniMaxApi.FunctionTool> tools = useWebSearch
                ? List.of(MiniMaxApi.FunctionTool.webSearchFunctionTool())
                : null;
        return new MiniMaxApi.ChatCompletionRequest(
                List.of(
                        new MiniMaxApi.ChatCompletionMessage(WEREWOLF_SYSTEM_PROMPT, MiniMaxApi.ChatCompletionMessage.Role.SYSTEM),
                        new MiniMaxApi.ChatCompletionMessage(userPrompt, MiniMaxApi.ChatCompletionMessage.Role.USER)
                ),
                settings.chatModel(),
                null,
                properties.getChat().getMaxTokens(),
                1,
                null,
                null,
                null,
                null,
                stream,
                properties.getChat().getTemperature(),
                null,
                Boolean.FALSE,
                tools,
                tools == null ? MiniMaxApi.ChatCompletionRequest.ToolChoiceBuilder.NONE : MiniMaxApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO
        );
    }

    private String extractAnswer(MiniMaxApi.ChatCompletion body) {
        if (body == null || body.choices() == null || body.choices().isEmpty()) {
            throw new AiServiceException(new AiFailure(
                    AiFailureReason.SERVICE_UNAVAILABLE,
                    "MiniMax 没有返回可用回答，请稍后再试。",
                    true
            ));
        }
        MiniMaxApi.ChatCompletion.BaseResponse baseResponse = body.baseResponse();
        if (baseResponse != null && baseResponse.statusCode() != null && baseResponse.statusCode() != 0) {
            throw new AiServiceException(
                    failureClassifier.classify(baseResponse.statusCode().intValue(), baseResponse.message())
            );
        }
        MiniMaxApi.ChatCompletion.Choice choice = body.choices().getFirst();
        if (choice.message() == null || !StringUtils.hasText(choice.message().content())) {
            throw new AiServiceException(new AiFailure(
                    AiFailureReason.SERVICE_UNAVAILABLE,
                    "MiniMax 返回内容为空，请稍后再试。",
                    true
            ));
        }
        return choice.message().content().trim();
    }

    private void appendChunk(StringBuilder answer, Consumer<String> onDelta, MiniMaxApi.ChatCompletionChunk chunk) {
        if (chunk == null || chunk.choices() == null) {
            return;
        }
        for (MiniMaxApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            if (choice == null || choice.delta() == null || !StringUtils.hasText(choice.delta().content())) {
                continue;
            }
            String delta = choice.delta().content();
            answer.append(delta);
            onDelta.accept(delta);
        }
    }

    private AiServiceException classifyTransportFailure(Exception exception) {
        Throwable root = Exceptions.unwrap(exception);
        if (root instanceof AiServiceException serviceException) {
            return serviceException;
        }
        if (root instanceof RestClientResponseException restClientResponseException) {
            return new AiServiceException(
                    failureClassifier.classify(
                            restClientResponseException.getStatusCode().value(),
                            restClientResponseException.getResponseBodyAsString()
                    )
            );
        }
        if (root instanceof WebClientResponseException webClientResponseException) {
            return new AiServiceException(
                    failureClassifier.classify(
                            webClientResponseException.getStatusCode().value(),
                            webClientResponseException.getResponseBodyAsString()
                    )
            );
        }
        return new AiServiceException(failureClassifier.classify(root));
    }

    private void sleepTinyBatchDelay() throws InterruptedException {
        Duration timeout = properties.getEmbedding().getTimeout();
        if (!timeout.isNegative()) {
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    public interface MiniMaxClientFactory {
        MiniMaxClient create(String baseUrl, String apiKey);
    }

    public interface MiniMaxClient {
        MiniMaxApi.ChatCompletion complete(MiniMaxApi.ChatCompletionRequest request);

        Flux<MiniMaxApi.ChatCompletionChunk> stream(MiniMaxApi.ChatCompletionRequest request);

        MiniMaxApi.EmbeddingList embed(MiniMaxApi.EmbeddingRequest request);
    }

    private static final class DefaultMiniMaxClient implements MiniMaxClient {

        private final MiniMaxApi api;

        private DefaultMiniMaxClient(String baseUrl, String apiKey) {
            this.api = new MiniMaxApi(baseUrl, apiKey);
        }

        @Override
        public MiniMaxApi.ChatCompletion complete(MiniMaxApi.ChatCompletionRequest request) {
            return api.chatCompletionEntity(request).getBody();
        }

        @Override
        public Flux<MiniMaxApi.ChatCompletionChunk> stream(MiniMaxApi.ChatCompletionRequest request) {
            return api.chatCompletionStream(request);
        }

        @Override
        public MiniMaxApi.EmbeddingList embed(MiniMaxApi.EmbeddingRequest request) {
            return api.embeddings(request).getBody();
        }
    }
}
