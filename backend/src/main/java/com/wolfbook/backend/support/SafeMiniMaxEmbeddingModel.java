package com.wolfbook.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.minimax.MiniMaxEmbeddingOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SafeMiniMaxEmbeddingModel extends AbstractEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(SafeMiniMaxEmbeddingModel.class);
    private static final String DEFAULT_EMBEDDING_TYPE = "db";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MetadataMode metadataMode;
    private final MiniMaxEmbeddingOptions defaultOptions;
    private final RetryTemplate retryTemplate;

    public SafeMiniMaxEmbeddingModel(
            String baseUrl,
            String apiKey,
            MetadataMode metadataMode,
            MiniMaxEmbeddingOptions defaultOptions,
            RestClient.Builder restClientBuilder
    ) {
        this(baseUrl, apiKey, metadataMode, defaultOptions, restClientBuilder, RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    public SafeMiniMaxEmbeddingModel(
            String baseUrl,
            String apiKey,
            MetadataMode metadataMode,
            MiniMaxEmbeddingOptions defaultOptions,
            RestClient.Builder restClientBuilder,
            RetryTemplate retryTemplate
    ) {
        Assert.hasText(baseUrl, "MiniMax baseUrl must not be empty");
        Assert.hasText(apiKey, "MiniMax apiKey must not be empty");
        Assert.notNull(metadataMode, "MetadataMode must not be null");
        Assert.notNull(defaultOptions, "MiniMaxEmbeddingOptions must not be null");
        Assert.notNull(restClientBuilder, "RestClient.Builder must not be null");
        Assert.notNull(retryTemplate, "RetryTemplate must not be null");
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = new ObjectMapper();
        this.metadataMode = metadataMode;
        this.defaultOptions = defaultOptions;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public float[] embed(Document document) {
        Assert.notNull(document, "Document must not be null");
        return embed(document.getFormattedContent(metadataMode));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingRequest mergedRequest = buildEmbeddingRequest(request);
        String model = mergedRequest.getOptions().getModel();
        String rawResponse = retryTemplate.execute(context -> requestEmbeddings(mergedRequest.getInstructions(), model));
        return parseEmbeddingResponse(rawResponse, model, mergedRequest.getInstructions().size());
    }

    private String requestEmbeddings(List<String> texts, String model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("texts", texts);
        payload.put("model", model);
        payload.put("type", DEFAULT_EMBEDDING_TYPE);
        return restClient.post()
                .uri("/v1/embeddings")
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    private EmbeddingResponse parseEmbeddingResponse(String rawResponse, String model, int textCount) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode vectorsNode = root.path("vectors");
            if (vectorsNode.isArray() && !vectorsNode.isEmpty()) {
                List<Embedding> embeddings = new ArrayList<>();
                for (int index = 0; index < vectorsNode.size(); index++) {
                    JsonNode vectorNode = vectorsNode.get(index);
                    if (!vectorNode.isArray() || vectorNode.isEmpty()) {
                        throw invalidVectorPayload(model, index);
                    }
                    embeddings.add(new Embedding(toFloatArray(vectorNode), index));
                }
                int totalTokens = root.path("total_tokens").asInt(0);
                EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(
                        model,
                        new DefaultUsage(0, 0, totalTokens)
                );
                return new EmbeddingResponse(embeddings, metadata);
            }

            throw buildMiniMaxError(root, model, textCount);
        }
        catch (ApiException exception) {
            throw exception;
        }
        catch (Exception exception) {
            log.warn("Failed to parse MiniMax embedding response, model={}, raw={}", model, rawResponse, exception);
            throw new ApiException(5001, "MiniMax embedding response is invalid, please check the endpoint configuration");
        }
    }

    private ApiException buildMiniMaxError(JsonNode root, String model, int textCount) {
        JsonNode baseRespNode = root.path("base_resp");
        String statusCode = baseRespNode.path("status_code").asText("");
        String statusMessage = baseRespNode.path("status_msg").asText("");
        log.warn(
                "MiniMax embedding failed, model={}, type={}, textCount={}, statusCode={}, statusMessage={}",
                model,
                DEFAULT_EMBEDDING_TYPE,
                textCount,
                statusCode,
                statusMessage
        );

        String message;
        if ("1008".equals(statusCode) || statusMessage.toLowerCase().contains("insufficient balance")) {
            message = "MiniMax embedding 余额不足，请充值后再发布知识库或重建索引";
        }
        else if (StringUtils.hasText(statusMessage)) {
            message = "MiniMax embedding 调用失败：" + statusMessage;
        }
        else {
            message = "MiniMax embedding returned empty vectors, please check the MiniMax embedding model or endpoint configuration";
        }
        return new ApiException(5001, message);
    }

    private ApiException invalidVectorPayload(String model, int index) {
        log.warn("MiniMax embedding returned an invalid vector payload, model={}, index={}", model, index);
        return new ApiException(5001, "MiniMax embedding returned an invalid vector payload");
    }

    private float[] toFloatArray(JsonNode vectorNode) {
        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vectorNode.size(); i++) {
            vector[i] = (float) vectorNode.get(i).asDouble();
        }
        return vector;
    }

    private EmbeddingRequest buildEmbeddingRequest(EmbeddingRequest request) {
        MiniMaxEmbeddingOptions runtimeOptions = null;
        if (request.getOptions() != null) {
            runtimeOptions = ModelOptionsUtils.copyToTarget(
                    request.getOptions(),
                    EmbeddingOptions.class,
                    MiniMaxEmbeddingOptions.class
            );
        }
        MiniMaxEmbeddingOptions mergedOptions = ModelOptionsUtils.merge(runtimeOptions, defaultOptions, MiniMaxEmbeddingOptions.class);
        if (!StringUtils.hasText(mergedOptions.getModel())) {
            throw new IllegalArgumentException("MiniMax embedding model can not be empty");
        }
        return new EmbeddingRequest(request.getInstructions(), mergedOptions);
    }
}
