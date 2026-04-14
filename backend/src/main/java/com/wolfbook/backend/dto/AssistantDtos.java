package com.wolfbook.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record AssistantBootstrapResponse(
            boolean enabled,
            String welcomeMessage,
            List<String> quickQuestions,
            String latestSessionId,
            Appearance appearance,
            FeatureFlags featureFlags
    ) {
    }

    public record Appearance(String mascot, String accentColor, String dockLabel) {
    }

    public record FeatureFlags(boolean webSearchEnabled, boolean historyEnabled, boolean streamEnabled) {
    }

    public record AssistantAskRequest(
            String sessionId,
            @NotBlank(message = "message is required") @Size(max = 500, message = "message is too long") String message,
            String scene,
            Map<String, Object> pageContext,
            String clientTimestamp
    ) {
    }

    public record AssistantAskResponse(
            String sessionId,
            Long messageId,
            String answer,
            String contentFormat,
            String answerType,
            List<AssistantCitation> citations,
            List<RecommendedBoardCard> recommendedBoards,
            List<String> suggestedQuestions,
            boolean usedWebSearch,
            String traceId
    ) {
    }

    public record AssistantStreamStarted(
            String sessionId,
            String traceId,
            String contentFormat
    ) {
    }

    public record AssistantStreamDelta(
            String delta
    ) {
    }

    public record AssistantStreamError(
            String message,
            String traceId
    ) {
    }

    public record AssistantCitation(
            String sourceType,
            String title,
            String snippet,
            String url,
            String sourceId
    ) {
    }

    public record RecommendedBoardCard(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String reason
    ) {
    }

    public record AssistantSessionView(
            String sessionId,
            String title,
            String scene,
            Map<String, Object> pageContext,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    public record AssistantMessageView(
            Long id,
            String role,
            String content,
            String contentFormat,
            String answerType,
            List<AssistantCitation> citations,
            List<RecommendedBoardCard> recommendedBoards,
            List<String> suggestedQuestions,
            boolean usedWebSearch,
            String traceId,
            LocalDateTime createTime
    ) {
    }

    public record AdminAiConfig(
            BaseSection base,
            PromptSection prompt,
            RetrievalSection retrieval,
            SearchSection search,
            SafetySection safety,
            UiSection ui
    ) {
    }

    public record BaseSection(
            boolean enabled,
            String welcomeMessage,
            List<String> quickQuestions,
            String chatModel,
            String embeddingModel,
            Double temperature,
            Integer maxSuggestions
    ) {
    }

    public record PromptSection(
            String systemPrompt,
            String recommendationPrompt,
            String refusalPrompt
    ) {
    }

    public record RetrievalSection(
            Integer topK,
            Double similarityThreshold,
            Integer historyWindow
    ) {
    }

    public record SearchSection(
            boolean webSearchEnabled,
            Integer timeoutSeconds,
            String provider
    ) {
    }

    public record SafetySection(
            String unsupportedMessage,
            List<String> blockedKeywords
    ) {
    }

    public record UiSection(
            String mascot,
            String dockLabel,
            String accentColor
    ) {
    }

    public record AdminDocumentUpdateRequest(
            @NotBlank(message = "reviewStatus is required") String reviewStatus
    ) {
    }

    public record AdminPublishRequest(String notes) {
    }

    public record AdminRollbackRequest(@NotNull(message = "versionId is required") Integer versionId) {
    }

    public record AdminDocumentView(
            Integer id,
            String name,
            String fileName,
            String sourceType,
            String sourceKey,
            String sourceId,
            String summary,
            Integer chunkCount,
            String processingStatus,
            String reviewStatus,
            Integer publishVersionId,
            String lastError,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    public record AdminPublishVersionView(
            Integer id,
            String versionName,
            String notes,
            List<Integer> documentIds,
            boolean current,
            String publishedBy,
            LocalDateTime createTime
    ) {
    }

    public record AdminQueryLogView(
            Long id,
            String openid,
            String sessionId,
            String userMessage,
            String answerType,
            List<String> hitSources,
            boolean usedWebSearch,
            long latencyMs,
            long firstTokenMs,
            long embeddingMs,
            long retrievalMs,
            long modelMs,
            long webSearchMs,
            boolean cacheHit,
            String fallbackMode,
            String streamMode,
            boolean success,
            String failureType,
            String traceId,
            LocalDateTime createTime
    ) {
    }

    public record AdminAiPerformanceView(
            long queryCount24h,
            long avgFirstTokenMs,
            long avgTotalLatencyMs,
            long avgRetrievalMs,
            long avgModelMs,
            long p95LatencyMs,
            double ragHitRate,
            double structuredHitRate,
            double webSearchRate,
            double cacheHitRate,
            double failureRate
    ) {
    }
}
