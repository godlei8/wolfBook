package com.wolfbook.backend.ai.dto;

import com.wolfbook.backend.ai.model.AiAnswerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AiDtos {

    private AiDtos() {
    }

    public record AiBootstrapResponse(
            boolean enabled,
            boolean knowledgeBaseEnabled,
            boolean webSearchEnabled,
            boolean postgresReady,
            boolean chatReady,
            String currentMode,
            String unavailableReason,
            List<String> quickQuestions
    ) {
    }

    public record AiAskRequest(
            String sessionId,
            @NotBlank(message = "question is required") @Size(max = 600, message = "question is too long") String question
    ) {
    }

    public record AiAskResponse(
            String sessionId,
            String messageId,
            String traceId,
            String answer,
            AiAnswerType answerType,
            List<AiSourceView> sources,
            List<String> suggestedQuestions,
            Map<String, Object> retrievalMeta
    ) {
    }

    public record AiStreamStartEvent(
            String sessionId,
            String messageId,
            String traceId,
            AiAnswerType answerType,
            List<AiSourceView> sources,
            List<String> suggestedQuestions,
            Map<String, Object> retrievalMeta
    ) {
    }

    public record AiStreamDeltaEvent(String delta) {
    }

    public record AiStreamErrorEvent(String message, String reason, boolean retryable) {
    }

    public record AiSourceView(
            String chunkUid,
            String title,
            String sectionPath,
            String subject,
            String content,
            double score,
            String sourceType,
            String url
    ) {
    }

    public record AiSessionView(
            String sessionId,
            String title,
            String confirmedEntity,
            OffsetDateTime createTime,
            OffsetDateTime updateTime
    ) {
    }

    public record AiMessageView(
            String messageId,
            String role,
            String content,
            AiAnswerType answerType,
            String traceId,
            List<AiSourceView> sources,
            OffsetDateTime createTime
    ) {
    }

    public record AiFeedbackRequest(String value, String note) {
    }

    public record AdminAiConfigView(
            boolean enabled,
            boolean knowledgeBaseEnabled,
            boolean webSearchEnabled,
            boolean postgresReady,
            boolean chatReady,
            boolean embeddingReady,
            int topK,
            double minScore,
            int maxEvidenceChars,
            String chatModel,
            String embeddingModel,
            String chatApiKeyMasked,
            String embeddingApiKeyMasked
    ) {
    }

    public record AdminAiConfigRequest(
            Boolean enabled,
            Boolean knowledgeBaseEnabled,
            Boolean webSearchEnabled,
            Integer topK,
            Double minScore,
            Integer maxEvidenceChars,
            String chatModel,
            String embeddingModel,
            String chatApiKey,
            String embeddingApiKey
    ) {
    }

    public record AdminAiDocumentView(
            Long id,
            String documentUid,
            String domain,
            String title,
            String sourceType,
            String reviewStatus,
            String parseStatus,
            String summary,
            int chunkCount,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record AdminAiUploadRequest(
            @NotBlank(message = "domain is required") String domain,
            String title
    ) {
    }

    public record AdminAiPublishRequest(String description) {
    }

    public record AdminAiPublishView(
            String versionKey,
            String status,
            boolean active,
            int documentCount,
            int chunkCount,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt
    ) {
    }

    public record AdminAiDebugRequest(
            @NotBlank(message = "query is required") String query,
            String sessionId,
            List<AiMessageView> recentMessages,
            String confirmedEntity
    ) {
    }

    public record AdminAiDebugResponse(
            String query,
            String subject,
            String intent,
            boolean outOfScope,
            List<AiSourceView> hits,
            Map<String, Object> meta
    ) {
    }

    public record AdminAiLogView(
            String traceId,
            String sessionId,
            String question,
            String answerType,
            String subject,
            String retrievalMode,
            int hitCount,
            boolean webUsed,
            String failureReason,
            Long latencyMs,
            OffsetDateTime createdAt
    ) {
    }

    public record AdminAiEvalCaseRequest(
            @NotBlank(message = "question is required") String question,
            String expectedSubject,
            String expectedKeywords,
            String category
    ) {
    }

    public record AdminAiEvalCaseView(
            Long id,
            String question,
            String expectedSubject,
            String expectedKeywords,
            String category,
            boolean enabled,
            OffsetDateTime createdAt
    ) {
    }
}
