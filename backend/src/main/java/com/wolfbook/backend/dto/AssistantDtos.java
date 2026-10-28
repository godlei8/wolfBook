package com.wolfbook.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
            List<String> quickQuestions,
            String latestSessionId
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
            int code,
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
            String traceId,
            LocalDateTime createTime
    ) {
    }

    /** 模型管理列表里的一项，apiKey 已打码。 */
    public record AdminModelView(
            Integer id,
            String name,
            String baseUrl,
            String model,
            String apiKey,
            boolean enabled,
            boolean active,
            boolean disableThinking,
            String remark,
            LocalDateTime updateTime
    ) {
    }

    /** 新建 / 修改模型。apiKey 原样传回打码值或留空表示不改（新建时留空就是没有 Key）。 */
    public record AdminModelRequest(
            @NotBlank(message = "模型名称不能为空") @Size(max = 60, message = "模型名称不能超过 60") String name,
            @NotBlank(message = "接口地址不能为空") @Size(max = 300, message = "接口地址不能超过 300") String baseUrl,
            @NotBlank(message = "模型编码不能为空") @Size(max = 120, message = "模型编码不能超过 120") String model,
            @Size(max = 500, message = "Key 太长了") String apiKey,
            Boolean enabled,
            Boolean disableThinking,
            @Size(max = 200, message = "备注不能超过 200") String remark
    ) {
    }

    /** 测试连接：id 有值时，没改的 Key 用库里存的。 */
    public record ModelTestRequest(Integer id, String baseUrl, String model, String apiKey, Boolean disableThinking) {
    }

    public record ModelTestResult(boolean ok, long latencyMs, String reply, String error) {
    }

    public record AdminAiConfig(
            BaseSection base,
            ProviderSection provider,
            VolcengineSection volcengine,
            PromptSection prompt,
            RetrievalSection retrieval,
            SearchSection search,
            SafetySection safety,
            UiSection ui
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BaseSection(
            boolean enabled,
            String welcomeMessage,
            List<String> quickQuestions,
            Double temperature,
            Integer maxSuggestions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderSection(
            String platform,
            String model,
            String baseUrl,
            String apiKey,
            // 推理模型（比如学校的 DeepSeek V4）不先长篇思考再回答，见 DeepSeekChatService.buildRequest
            Boolean disableThinking
    ) {
        public ProviderSection(String platform, String model, String baseUrl, String apiKey) {
            this(platform, model, baseUrl, apiKey, false);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VolcengineSection(
            String baseUrl,
            String embeddingModel,
            String embeddingApiKey,
            String searchModel,
            String searchApiKey
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptSection(
            String systemPrompt,
            String recommendationPrompt,
            String refusalPrompt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrievalSection(
            Integer topK,
            Double similarityThreshold,
            Integer historyWindow
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchSection(
            boolean webSearchEnabled
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SafetySection(
            String unsupportedMessage,
            List<String> blockedKeywords
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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
            boolean success,
            String failureType,
            String traceId,
            LocalDateTime createTime,
            // 这次的回答全文（按 traceId 从会话消息里找），后台日志里点开看答成了什么
            String answer
    ) {
    }

    /** 流式回答进行到哪一步了：小程序在还没出字的时候显示「正在查站内资料」之类的提示。 */
    public record AssistantStreamStage(
            String key,
            String label
    ) {
    }

    /** 后台调试台：用当前配置答一题，不落库、不写日志，返回判断过程。 */
    public record AdminPlaygroundRequest(
            @NotBlank(message = "message is required") @Size(max = 500, message = "message is too long") String message,
            String scene,
            Map<String, Object> pageContext
    ) {
    }

    public record AdminPlaygroundHit(
            String title,
            String sourceType,
            double score
    ) {
    }

    public record AdminPlaygroundResponse(
            String answer,
            String answerType,
            String intent,
            String reason,
            List<AdminPlaygroundHit> hits,
            List<RecommendedBoardCard> boards,
            boolean usedWebSearch,
            String model,
            String failureType,
            // 各步耗时（毫秒）：route 判断问题、retrieve 查资料、generate 模型生成
            Map<String, Long> timings,
            long totalMs
    ) {
    }
}
