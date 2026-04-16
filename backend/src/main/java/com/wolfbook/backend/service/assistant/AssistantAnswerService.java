package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.AssistantMessageEntity;
import com.wolfbook.backend.entity.AssistantQueryLogEntity;
import com.wolfbook.backend.entity.AssistantSessionEntity;
import com.wolfbook.backend.mapper.AssistantQueryLogMapper;
import com.wolfbook.backend.service.BoardService;
import com.wolfbook.backend.service.UserService;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 助手回答编排服务。
 *
 * <p>这是 AI 助手的主入口，负责鉴权、会话落库、意图分类、缓存命中、知识库检索、
 * 板子推荐、模型调用、流式输出、回答持久化和查询日志。读 AI 助手后端时建议先看
 * {@link #prepareAsk(String, AssistantDtos.AssistantAskRequest)}、{@link #answerKnowledge(PreparedAsk, AssistantDtos.AssistantAskRequest, ExecutionMetrics)}
 * 和 {@link #streamPromptAnswer(Prompt, String, SseEmitter, ExecutionMetrics)}。</p>
 */
@Service
public class AssistantAnswerService {

    private static final Pattern PLAYER_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*人");
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final String ANSWER_PIPELINE_VERSION = "rag-v5-entity-first-v4";

    private final UserService userService;
    private final AssistantConfigService assistantConfigService;
    private final AssistantConversationService assistantConversationService;
    private final AssistantKnowledgeService assistantKnowledgeService;
    private final AssistantQueryPlanner assistantQueryPlanner;
    private final AssistantRetrievalService assistantRetrievalService;
    private final AssistantAnswerGenerationService assistantAnswerGenerationService;
    private final AssistantAnswerValidator assistantAnswerValidator;
    private final AssistantSearchService assistantSearchService;
    private final AssistantCacheService assistantCacheService;
    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final BoardService boardService;
    private final AssistantProperties assistantProperties;
    private final ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AssistantAnswerService(
            UserService userService,
            AssistantConfigService assistantConfigService,
            AssistantConversationService assistantConversationService,
            AssistantKnowledgeService assistantKnowledgeService,
            AssistantQueryPlanner assistantQueryPlanner,
            AssistantRetrievalService assistantRetrievalService,
            AssistantAnswerGenerationService assistantAnswerGenerationService,
            AssistantAnswerValidator assistantAnswerValidator,
            AssistantSearchService assistantSearchService,
            AssistantCacheService assistantCacheService,
            AssistantQueryLogMapper assistantQueryLogMapper,
            BoardService boardService,
            AssistantProperties assistantProperties,
            ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider,
            ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.assistantConfigService = assistantConfigService;
        this.assistantConversationService = assistantConversationService;
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.assistantQueryPlanner = assistantQueryPlanner;
        this.assistantRetrievalService = assistantRetrievalService;
        this.assistantAnswerGenerationService = assistantAnswerGenerationService;
        this.assistantAnswerValidator = assistantAnswerValidator;
        this.assistantSearchService = assistantSearchService;
        this.assistantCacheService = assistantCacheService;
        this.assistantQueryLogMapper = assistantQueryLogMapper;
        this.boardService = boardService;
        this.assistantProperties = assistantProperties;
        this.miniMaxChatModelProvider = miniMaxChatModelProvider;
        this.objectMapper = objectMapper;
    }

    public AssistantDtos.AssistantBootstrapResponse bootstrap(String authorization) {
        String openid = userService.requireUser(authorization).openid();
        return assistantConfigService.getBootstrap(openid);
    }

    public List<AssistantDtos.AssistantSessionView> listSessions(String authorization) {
        String openid = userService.requireUser(authorization).openid();
        return assistantConversationService.listSessions(openid);
    }

    public List<AssistantDtos.AssistantMessageView> listMessages(String authorization, String sessionId) {
        String openid = userService.requireUser(authorization).openid();
        return assistantConversationService.listMessages(openid, sessionId);
    }

    public void resetSession(String authorization, String sessionId) {
        String openid = userService.requireUser(authorization).openid();
        assistantConversationService.resetSession(openid, sessionId);
    }

    public AssistantDtos.AssistantAskResponse ask(String authorization, AssistantDtos.AssistantAskRequest request) {
        PreparedAsk prepared = prepareAsk(authorization, request);
        return executeAsk(prepared, request).response();
    }

    public SseEmitter askStream(String authorization, AssistantDtos.AssistantAskRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                PreparedAsk prepared = prepareAsk(authorization, request);
                streamAsk(prepared, request, emitter);
            } catch (Exception exception) {
                emitStreamError(emitter, exception, UUID.randomUUID().toString().replace("-", ""));
            }
        });
        return emitter;
    }

    private PreparedAsk prepareAsk(String authorization, AssistantDtos.AssistantAskRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String openid = userService.requireUser(authorization).openid();
        AssistantDtos.AdminAiConfig config = assistantConfigService.getAdminConfig();
        if (!config.base().enabled()) {
            throw new ApiException(4000, "Assistant is disabled");
        }

        AssistantSessionEntity session = assistantConversationService.resolveSession(
                openid,
                request.sessionId(),
                request.scene(),
                request.pageContext(),
                request.message()
        );
        assistantConversationService.saveUserMessage(session.getSessionId(), request.message());
        AssistantQueryPlan queryPlan = assistantQueryPlanner.plan(
                request.message(),
                assistantConversationService.recentContextMessages(openid, session.getSessionId())
        );

        // 缓存版本同时包含后台配置、知识库版本和回答管线版本，避免改了检索/提示词后继续命中旧答案。
        QueryIntent intent = classifyIntent(request.message(), config.safety().blockedKeywords());
        return new PreparedAsk(
                openid,
                session,
                config,
                traceId,
                intent,
                assistantCacheService.buildConfigVersion(config)
                        + ":" + assistantKnowledgeService.buildKnowledgeCacheVersion()
                        + ":" + queryPlan.cacheToken()
                        + ":" + ANSWER_PIPELINE_VERSION,
                queryPlan,
                System.currentTimeMillis()
        );
    }

    private AskExecution executeAsk(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        String failureType = null;
        ExecutionMetrics metrics = ExecutionMetrics.sync(prepared.startAtMs());
        AssistantDtos.AssistantAskResponse response;
        try {
            // 同步接口和流式接口共享同一套编排：先查缓存，再按意图进入板子推荐/知识问答/拒答分支。
            AssistantCacheService.CachedAnswer cachedAnswer = findCachedAnswer(prepared, request, metrics);
            if (cachedAnswer != null) {
                response = persistCachedAnswer(prepared, cachedAnswer);
            } else {
                response = switch (prepared.intent()) {
                    case BOARD_RECOMMENDATION -> answerBoardRecommendation(prepared, request, metrics);
                    case UNSUPPORTED -> refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().safety().unsupportedMessage());
                    case KNOWLEDGE -> answerKnowledge(prepared, request, metrics);
                };
                cacheAnswerIfEligible(prepared, request, response);
            }
        } catch (Exception exception) {
            failureType = exception.getClass().getSimpleName();
            metrics.setFallbackModeIfBlank("ERROR_REFUSAL");
            response = refusal(
                    prepared.session(),
                    prepared.config(),
                    prepared.traceId(),
                    "当前助手暂时没法稳定回答这个问题，你可以换个问法，或继续问我规则、角色和板子推荐。"
            );
        }

        long latencyMs = System.currentTimeMillis() - prepared.startAtMs();
        metrics.ensureFirstToken(latencyMs);
        writeLog(prepared.openid(), prepared.session().getSessionId(), request.message(), response, latencyMs, failureType, metrics);
        return new AskExecution(response, latencyMs, failureType);
    }

    private void streamAsk(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request, SseEmitter emitter) {
        ExecutionMetrics metrics = ExecutionMetrics.stream(prepared.startAtMs());
        try {
            emit(emitter, "started", new AssistantDtos.AssistantStreamStarted(
                    prepared.session().getSessionId(),
                    prepared.traceId(),
                    AssistantConstants.CONTENT_MARKDOWN
            ));

            StreamExecution execution;
            AssistantCacheService.CachedAnswer cachedAnswer = findCachedAnswer(prepared, request, metrics);
            if (cachedAnswer != null) {
                // 缓存答案也按 SSE delta 分片吐给前端，前端无需区分真实模型流和兜底流。
                emitStaticAnswer(cachedAnswer.answer(), emitter, metrics);
                execution = new StreamExecution(persistCachedAnswer(prepared, cachedAnswer), null);
            } else {
                execution = switch (prepared.intent()) {
                    case BOARD_RECOMMENDATION -> streamBoardRecommendation(prepared, request, emitter, metrics);
                    case UNSUPPORTED -> streamUnsupported(prepared, emitter, metrics);
                    case KNOWLEDGE -> streamKnowledge(prepared, request, emitter, metrics);
                };
                cacheAnswerIfEligible(prepared, request, execution.response());
            }
            long latencyMs = System.currentTimeMillis() - prepared.startAtMs();
            metrics.ensureFirstToken(latencyMs);
            writeLog(
                    prepared.openid(),
                    prepared.session().getSessionId(),
                    request.message(),
                    execution.response(),
                    latencyMs,
                    execution.failureType(),
                    metrics
            );
            emit(emitter, "done", execution.response());
            emitter.complete();
        } catch (Exception exception) {
            try {
                emit(emitter, "error", new AssistantDtos.AssistantStreamError(
                        exception.getMessage() == null ? "AI 流式返回失败" : exception.getMessage(),
                        prepared.traceId()
                ));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        }
    }

    private StreamExecution streamBoardRecommendation(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            SseEmitter emitter,
            ExecutionMetrics metrics
    ) throws IOException {
        List<AssistantDtos.RecommendedBoardCard> boards = recommendBoards(request.message());
        List<String> suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
        String fallbackAnswer = boards.isEmpty()
                ? """
                ## 暂时没有完全匹配的板子

                - 你可以补充 **人数**、**难度** 或 **玩法偏好**
                - 例如：`12人 进阶 娱乐板子推荐`
                - 如果你愿意，我也可以继续按“入门 / 进阶 / 烧脑”重新给你筛一轮
                """
                : fallbackBoardAnswer(boards);

        StreamedAnswer streamedAnswer = streamPromptAnswer(
                buildBoardRecommendationPrompt(request.message(), boards, prepared.config()),
                fallbackAnswer,
                emitter,
                metrics
        );

        return new StreamExecution(
                persistAssistantResponse(
                        prepared.session(),
                        streamedAnswer.answer(),
                        AssistantConstants.ANSWER_STRUCTURED,
                        List.of(),
                        boards,
                        suggestedQuestions,
                        false,
                        prepared.traceId()
                ),
                streamedAnswer.failureType()
        );
    }

    private StreamExecution streamUnsupported(PreparedAsk prepared, SseEmitter emitter, ExecutionMetrics metrics) throws IOException {
        String markdown = buildRefusalMarkdown(prepared.config().safety().unsupportedMessage());
        List<String> suggestedQuestions = prepared.config().base().quickQuestions().stream()
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
        metrics.setFallbackModeIfBlank("UNSUPPORTED");
        StreamedAnswer streamedAnswer = emitStaticAnswer(markdown, emitter, metrics);
        return new StreamExecution(
                persistAssistantResponse(
                        prepared.session(),
                        streamedAnswer.answer(),
                        AssistantConstants.ANSWER_REFUSAL,
                        List.of(),
                        List.of(),
                        suggestedQuestions,
                        false,
                        prepared.traceId()
                ),
                streamedAnswer.failureType()
        );
    }

    private StreamExecution streamKnowledge(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            SseEmitter emitter,
            ExecutionMetrics metrics
    ) throws IOException {
        // 站内知识优先：先检索和裁剪知识库，再按需要补联网搜索。
        AssistantRetrievalResult retrievalResult = assistantRetrievalService.retrieve(
                request.message(),
                prepared.queryPlan(),
                resolveTopK(prepared.config()),
                resolveSimilarityThreshold(prepared.config())
        );
        List<AssistantKnowledgeService.KnowledgeHit> hits = distinctKnowledgeHits(retrievalResult.hits());
        metrics.setRetrievalMs(retrievalResult.retrievalMs());
        metrics.setEmbeddingMs(retrievalResult.embeddingMs());
        metrics.setRetrievalMeta(retrievalResult.meta());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        List<AssistantDtos.AssistantCitation> citations;
        List<String> suggestedQuestions;
        StreamedAnswer streamedAnswer;
        AssistantSearchService.SearchResult webSearchResult = searchWebIfEligible(prepared, request.message(), hits, metrics);

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(resolveTopK(prepared.config()))
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            suggestedQuestions = List.of();
            if (hasWebAnswer(webSearchResult)) {
                // 有站内资料且问题又需要时效性时，把站内结论和联网补充合并；来源去重在 mergeCitations。
                AssistantGeneratedAnswer knowledgeAnswer = assistantAnswerGenerationService.generateKnowledge(
                        prepared.config(),
                        prepared.queryPlan(),
                        hits,
                        recentContextMessages(prepared, request.message())
                );
                metrics.setModelMs(knowledgeAnswer.modelMs());
                metrics.setFallbackModeIfBlank(knowledgeAnswer.fallbackMode());
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                citations = mergeCitations(citations, webSearchResult.citations());
                suggestedQuestions = mergeSuggestedQuestions(
                        webSearchResult.suggestedQuestions(),
                        suggestedQuestions
                );
                metrics.setStreamMode("FALLBACK");
                String mergedAnswer = sanitizeSubjectDrift(
                        mergeKnowledgeAndWebAnswer(knowledgeAnswer.answer(), webSearchResult),
                        request.message()
                );
                streamedAnswer = emitStaticAnswer(
                        mergedAnswer,
                        emitter,
                        metrics
                );
            } else {
                // 常规 RAG 路径先生成并校验完整答案，再按静态分片输出，避免 SSE 先吐出未校验的错误内容。
                AssistantGeneratedAnswer generatedAnswer = assistantAnswerGenerationService.generateKnowledge(
                        prepared.config(),
                        prepared.queryPlan(),
                        hits,
                        recentContextMessages(prepared, request.message())
                );
                metrics.setModelMs(generatedAnswer.modelMs());
                metrics.setFallbackModeIfBlank(generatedAnswer.fallbackMode());
                String checkedAnswer = sanitizeSubjectDrift(generatedAnswer.answer(), request.message());
                AssistantAnswerValidation validation = assistantAnswerValidator.validate(checkedAnswer, prepared.queryPlan(), hits);
                if (!validation.valid()) {
                    metrics.setFallbackMode("VALIDATION_" + validation.reason());
                    checkedAnswer = sanitizeSubjectDrift(
                            assistantAnswerGenerationService.templateKnowledgeAnswer(prepared.queryPlan(), hits),
                            request.message()
                    );
                    validation = assistantAnswerValidator.validate(checkedAnswer, prepared.queryPlan(), hits);
                }
                if (!validation.valid()) {
                    metrics.setFallbackMode("VALIDATION_REFUSAL_" + validation.reason());
                    checkedAnswer = buildRefusalMarkdown("现有资料未直接说明这个问题，我先不根据相邻角色或相邻条目猜测。");
                    answerType = AssistantConstants.ANSWER_REFUSAL;
                    citations = List.of();
                }
                streamedAnswer = emitStaticAnswer(checkedAnswer, emitter, metrics);
            }
        } else if (shouldAugmentWithWebSearch(prepared, request.message(), hits)) {
            if (hasWebAnswer(webSearchResult)) {
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                citations = webSearchResult.citations();
                suggestedQuestions = webSearchResult.suggestedQuestions().isEmpty()
                        ? nextQuestions(prepared.config().base().quickQuestions(), request.message())
                        : webSearchResult.suggestedQuestions().stream()
                        .limit(assistantProperties.getMaxSuggestions())
                        .toList();
                metrics.setStreamMode("FALLBACK");
                streamedAnswer = emitStaticAnswer(
                        sanitizeSubjectDrift(buildWebMarkdownAnswer(webSearchResult.answer(), citations), request.message()),
                        emitter,
                        metrics
                );
            } else {
                citations = List.of();
                suggestedQuestions = prepared.config().base().quickQuestions().stream()
                        .limit(assistantProperties.getMaxSuggestions())
                        .toList();
                answerType = AssistantConstants.ANSWER_REFUSAL;
                metrics.setFallbackModeIfBlank("WEB_EMPTY");
                streamedAnswer = emitStaticAnswer(buildRefusalMarkdown(prepared.config().prompt().refusalPrompt()), emitter, metrics);
            }
        } else {
            citations = List.of();
            suggestedQuestions = prepared.config().base().quickQuestions().stream()
                    .limit(assistantProperties.getMaxSuggestions())
                    .toList();
            answerType = AssistantConstants.ANSWER_REFUSAL;
            metrics.setFallbackModeIfBlank("NO_KNOWLEDGE");
            streamedAnswer = emitStaticAnswer(buildRefusalMarkdown(prepared.config().prompt().refusalPrompt()), emitter, metrics);
        }

        return new StreamExecution(
                persistAssistantResponse(
                        prepared.session(),
                        streamedAnswer.answer(),
                        answerType,
                        citations,
                        List.of(),
                        suggestedQuestions,
                        usedWebSearch,
                        prepared.traceId()
                ),
                streamedAnswer.failureType()
        );
    }

    private AssistantDtos.AssistantAskResponse answerBoardRecommendation(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            ExecutionMetrics metrics
    ) {
        List<AssistantDtos.RecommendedBoardCard> boards = recommendBoards(request.message());
        GeneratedAnswer generatedAnswer = synthesizeBoardRecommendationMarkdown(request.message(), boards, prepared.config());
        metrics.setModelMs(generatedAnswer.modelMs());
        metrics.setFallbackModeIfBlank(generatedAnswer.fallbackMode());
        String answer = generatedAnswer.answer();
        List<AssistantDtos.AssistantCitation> citations = List.of();
        List<String> suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
        return persistAssistantResponse(
                prepared.session(),
                answer,
                AssistantConstants.ANSWER_STRUCTURED,
                citations,
                boards,
                suggestedQuestions,
                false,
                prepared.traceId()
        );
    }

    private AssistantDtos.AssistantAskResponse answerKnowledge(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            ExecutionMetrics metrics
    ) {
        AssistantRetrievalResult retrievalResult = assistantRetrievalService.retrieve(
                request.message(),
                prepared.queryPlan(),
                resolveTopK(prepared.config()),
                resolveSimilarityThreshold(prepared.config())
        );
        List<AssistantKnowledgeService.KnowledgeHit> hits = distinctKnowledgeHits(retrievalResult.hits());
        metrics.setRetrievalMs(retrievalResult.retrievalMs());
        metrics.setEmbeddingMs(retrievalResult.embeddingMs());
        metrics.setRetrievalMeta(retrievalResult.meta());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        String answer;
        List<AssistantDtos.AssistantCitation> citations;
        List<AssistantDtos.RecommendedBoardCard> boards = List.of();
        List<String> suggestedQuestions;
        AssistantSearchService.SearchResult webSearchResult = searchWebIfEligible(prepared, request.message(), hits, metrics);

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(resolveTopK(prepared.config()))
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            AssistantGeneratedAnswer generatedAnswer = assistantAnswerGenerationService.generateKnowledge(
                    prepared.config(),
                    prepared.queryPlan(),
                    hits,
                    recentContextMessages(prepared, request.message())
            );
            metrics.setModelMs(generatedAnswer.modelMs());
            metrics.setFallbackModeIfBlank(generatedAnswer.fallbackMode());
            answer = sanitizeSubjectDrift(generatedAnswer.answer(), request.message());
            AssistantAnswerValidation validation = assistantAnswerValidator.validate(answer, prepared.queryPlan(), hits);
            if (!validation.valid()) {
                metrics.setFallbackMode("VALIDATION_" + validation.reason());
                answer = sanitizeSubjectDrift(
                        assistantAnswerGenerationService.templateKnowledgeAnswer(prepared.queryPlan(), hits),
                        request.message()
                );
                validation = assistantAnswerValidator.validate(answer, prepared.queryPlan(), hits);
                if (!validation.valid()) {
                    metrics.setFallbackMode("VALIDATION_REFUSAL_" + validation.reason());
                    return refusal(prepared.session(), prepared.config(), prepared.traceId(), "现有资料未直接说明这个问题，我先不根据相邻角色或相邻条目猜测。");
                }
            }
            suggestedQuestions = List.of();
            if (hasWebAnswer(webSearchResult)) {
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                citations = mergeCitations(citations, webSearchResult.citations());
                answer = sanitizeSubjectDrift(mergeKnowledgeAndWebAnswer(answer, webSearchResult), request.message());
                suggestedQuestions = mergeSuggestedQuestions(
                        webSearchResult.suggestedQuestions(),
                        suggestedQuestions
                );
            }
        } else if (shouldAugmentWithWebSearch(prepared, request.message(), hits)) {
            if (hasWebAnswer(webSearchResult)) {
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                citations = webSearchResult.citations();
                answer = buildWebMarkdownAnswer(webSearchResult.answer(), citations);
                suggestedQuestions = webSearchResult.suggestedQuestions().isEmpty()
                        ? nextQuestions(prepared.config().base().quickQuestions(), request.message())
                        : webSearchResult.suggestedQuestions().stream()
                        .limit(assistantProperties.getMaxSuggestions())
                        .toList();
                answer = sanitizeSubjectDrift(answer, request.message());
            } else {
                metrics.setFallbackModeIfBlank("WEB_EMPTY");
                return refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().prompt().refusalPrompt());
            }
        } else {
            metrics.setFallbackModeIfBlank("NO_KNOWLEDGE");
            return refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().prompt().refusalPrompt());
        }

        return persistAssistantResponse(
                prepared.session(),
                answer,
                answerType,
                citations,
                boards,
                suggestedQuestions,
                usedWebSearch,
                prepared.traceId()
        );
    }

    private AssistantDtos.AssistantAskResponse refusal(
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            String message
    ) {
        String markdown = buildRefusalMarkdown(message);
        List<String> suggestedQuestions = config.base().quickQuestions().stream()
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                session.getSessionId(),
                markdown,
                AssistantConstants.ANSWER_REFUSAL,
                List.of(),
                List.of(),
                suggestedQuestions,
                false,
                traceId
        );
        return new AssistantDtos.AssistantAskResponse(
                session.getSessionId(),
                saved.getId(),
                markdown,
                AssistantConstants.CONTENT_MARKDOWN,
                AssistantConstants.ANSWER_REFUSAL,
                List.of(),
                List.of(),
                suggestedQuestions,
                false,
                traceId
        );
    }

    private AssistantDtos.AssistantAskResponse persistAssistantResponse(
            AssistantSessionEntity session,
            String answer,
            String answerType,
            List<AssistantDtos.AssistantCitation> citations,
            List<AssistantDtos.RecommendedBoardCard> boards,
            List<String> suggestedQuestions,
            boolean usedWebSearch,
            String traceId
    ) {
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                session.getSessionId(),
                answer,
                answerType,
                citations,
                boards,
                suggestedQuestions,
                usedWebSearch,
                traceId
        );
        return new AssistantDtos.AssistantAskResponse(
                session.getSessionId(),
                saved.getId(),
                answer,
                AssistantConstants.CONTENT_MARKDOWN,
                answerType,
                citations,
                boards,
                suggestedQuestions,
                usedWebSearch,
                traceId
        );
    }

    private AssistantDtos.AssistantAskResponse persistCachedAnswer(PreparedAsk prepared, AssistantCacheService.CachedAnswer cachedAnswer) {
        return persistAssistantResponse(
                prepared.session(),
                cachedAnswer.answer(),
                cachedAnswer.answerType(),
                cachedAnswer.citations(),
                cachedAnswer.recommendedBoards(),
                cachedAnswer.suggestedQuestions(),
                cachedAnswer.usedWebSearch(),
                prepared.traceId()
        );
    }

    private AssistantCacheService.CachedAnswer findCachedAnswer(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            ExecutionMetrics metrics
    ) {
        if (!isAnswerCacheEligible(prepared, request)) {
            return null;
        }
        String cacheKey = assistantCacheService.buildAnswerCacheKey(request.message(), request.scene(), prepared.cacheVersion());
        AssistantCacheService.CachedAnswer cachedAnswer = assistantCacheService.getAnswer(cacheKey);
        if (cachedAnswer != null) {
            metrics.setCacheHit(true);
            metrics.setFallbackModeIfBlank("CACHE_HIT");
            if (!"SYNC".equals(metrics.streamMode())) {
                metrics.setStreamMode("FALLBACK");
            }
        }
        return cachedAnswer;
    }

    private void cacheAnswerIfEligible(
            PreparedAsk prepared,
            AssistantDtos.AssistantAskRequest request,
            AssistantDtos.AssistantAskResponse response
    ) {
        if (response == null || !isAnswerCacheEligible(prepared, request) || response.usedWebSearch()
                || AssistantConstants.ANSWER_REFUSAL.equals(response.answerType())) {
            return;
        }
        String cacheKey = assistantCacheService.buildAnswerCacheKey(request.message(), request.scene(), prepared.cacheVersion());
        assistantCacheService.cacheAnswer(cacheKey, AssistantCacheService.CachedAnswer.fromResponse(response));
    }

    private boolean isAnswerCacheEligible(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        String message = request.message();
        return prepared.intent() != QueryIntent.UNSUPPORTED
                && (request.sessionId() == null || request.sessionId().isBlank())
                && message != null
                && message.length() <= 120;
    }

    private int resolveTopK(AssistantDtos.AdminAiConfig config) {
        Integer configured = config == null || config.retrieval() == null ? null : config.retrieval().topK();
        if (configured == null || configured <= 0) {
            return Math.max(assistantProperties.getTopK(), 1);
        }
        return Math.min(Math.max(configured, 1), 8);
    }

    private double resolveSimilarityThreshold(AssistantDtos.AdminAiConfig config) {
        Double configured = config == null || config.retrieval() == null ? null : config.retrieval().similarityThreshold();
        if (configured == null || configured <= 0 || configured >= 1) {
            return assistantProperties.getSimilarityThreshold();
        }
        return configured;
    }

    private boolean shouldUseWebSearch(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        if (!hits.isEmpty() || !prepared.config().search().webSearchEnabled() || !assistantProperties.isWebSearchEnabled()) {
            return false;
        }
        return prepared.queryPlan() != null ? prepared.queryPlan().timeSensitive() : isTimeSensitiveQuery(query);
    }

    private boolean shouldAugmentWithWebSearch(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        if (!prepared.config().search().webSearchEnabled() || !assistantSearchService.isAvailable()) {
            return false;
        }
        if (hits == null || hits.isEmpty()) {
            return prepared.queryPlan() != null && prepared.queryPlan().timeSensitive();
        }
        return prepared.queryPlan() != null ? prepared.queryPlan().timeSensitive() : isTimeSensitiveQuery(query);
    }

    private boolean isTimeSensitiveQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String keyword : List.of(
                "联网", "网上", "搜索", "查一下", "查一查",
                "最新", "今天", "今日", "刚刚", "近期", "最近",
                "新闻", "赛事", "版本", "更新", "公告", "实时",
                "latest", "today", "recent", "news", "version", "update"
        )) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private AssistantSearchService.SearchResult searchWebIfEligible(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            ExecutionMetrics metrics
    ) {
        if (!shouldAugmentWithWebSearch(prepared, query, hits)) {
            return AssistantSearchService.SearchResult.empty();
        }
        long webSearchStartAt = System.currentTimeMillis();
        try {
            AssistantSearchService.SearchResult searchResult = assistantSearchService.searchWeb(
                    query,
                    prepared.config().base().chatModel(),
                    prepared.config().base().temperature()
            );
            metrics.setWebSearchMs(System.currentTimeMillis() - webSearchStartAt);
            return searchResult;
        } catch (Exception exception) {
            metrics.setWebSearchMs(System.currentTimeMillis() - webSearchStartAt);
            metrics.setFallbackModeIfBlank("WEB_EXCEPTION");
            return AssistantSearchService.SearchResult.empty();
        }
    }

    private boolean hasWebAnswer(AssistantSearchService.SearchResult searchResult) {
        return searchResult != null
                && searchResult.answer() != null
                && !searchResult.answer().isBlank();
    }

    private List<AssistantDtos.AssistantCitation> mergeCitations(
            List<AssistantDtos.AssistantCitation> primary,
            List<AssistantDtos.AssistantCitation> secondary
    ) {
        Map<String, AssistantDtos.AssistantCitation> merged = new LinkedHashMap<>();
        for (AssistantDtos.AssistantCitation citation : primary) {
            merged.putIfAbsent(citationKey(citation), citation);
        }
        for (AssistantDtos.AssistantCitation citation : secondary) {
            merged.putIfAbsent(citationKey(citation), citation);
        }
        return new ArrayList<>(merged.values());
    }

    private String citationKey(AssistantDtos.AssistantCitation citation) {
        if (citation == null) {
            return "null";
        }
        return String.join("|",
                citation.sourceType() == null ? "" : citation.sourceType(),
                citation.title() == null ? "" : citation.title(),
                citation.url() == null ? "" : citation.url(),
                citation.sourceId() == null ? "" : String.valueOf(citation.sourceId()));
    }

    private List<String> mergeSuggestedQuestions(List<String> primary, List<String> secondary) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String question : primary) {
            if (question != null && !question.isBlank()) {
                merged.putIfAbsent(question, question);
            }
        }
        for (String question : secondary) {
            if (question != null && !question.isBlank()) {
                merged.putIfAbsent(question, question);
            }
        }
        return merged.values().stream()
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
    }

    private String mergeKnowledgeAndWebAnswer(String knowledgeAnswer, AssistantSearchService.SearchResult searchResult) {
        if (!hasWebAnswer(searchResult)) {
            return normalizeMarkdown(knowledgeAnswer);
        }
        String knowledge = normalizeMarkdown(knowledgeAnswer);
        String web = buildWebMarkdownAnswer(searchResult.answer(), searchResult.citations());
        if (knowledge == null || knowledge.isBlank()) {
            return web;
        }
        return normalizeMarkdown(knowledge + "\n\n" + web);
    }

    private QueryIntent classifyIntent(String message, List<String> blockedKeywords) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<String> blockedWords = blockedKeywords == null ? List.of() : blockedKeywords;
        boolean blocked = blockedWords.stream().filter(keyword -> keyword != null && !keyword.isBlank()).anyMatch(normalized::contains);
        if (blocked) {
            return QueryIntent.UNSUPPORTED;
        }
        boolean hasPlayerCount = PLAYER_COUNT_PATTERN.matcher(normalized).find();
        boolean hasBoardContext = normalized.contains("板子")
                || normalized.contains("局")
                || normalized.contains("配置")
                || hasPlayerCount;
        boolean asksForRecommendation = normalized.contains("推荐")
                || normalized.contains("适合")
                || normalized.contains("选")
                || normalized.contains("找")
                || normalized.contains("玩什么")
                || normalized.contains("什么板")
                || normalized.contains("哪张")
                || normalized.contains("来一套");
        boolean onlyBoardProfile = hasPlayerCount
                && (parseDifficulty(normalized) != null || !extractTagHints(normalized).isEmpty() || normalized.contains("板"));
        if (hasBoardContext && (asksForRecommendation || onlyBoardProfile)) {
            return QueryIntent.BOARD_RECOMMENDATION;
        }
        return QueryIntent.KNOWLEDGE;
    }

    private List<AssistantDtos.RecommendedBoardCard> recommendBoards(String query) {
        Integer playerCount = parsePlayerCount(query);
        String difficulty = parseDifficulty(query);
        List<String> tagHints = extractTagHints(query);

        return boardService.listAllBoards().stream()
                .filter(board -> board.status() != null && board.status() == 1)
                .map(board -> scoreBoard(board, playerCount, difficulty, tagHints))
                .filter(candidate -> candidate.score() > 0 || playerCount == null && difficulty == null && tagHints.isEmpty())
                .sorted(Comparator.comparingInt(BoardCandidate::score).reversed().thenComparing(candidate -> candidate.board().name()))
                .limit(3)
                .map(candidate -> new AssistantDtos.RecommendedBoardCard(
                        candidate.board().id(),
                        candidate.board().name(),
                        candidate.board().playerCount(),
                        candidate.board().difficulty(),
                        candidate.board().tags(),
                        candidate.board().coverImage(),
                        candidate.reason()
                ))
                .toList();
    }

    private BoardCandidate scoreBoard(Board board, Integer playerCount, String difficulty, List<String> tagHints) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (playerCount != null && playerCount.equals(board.playerCount())) {
            score += 4;
            reasons.add("人数匹配 " + playerCount + " 人局");
        }
        if (difficulty != null && difficulty.equals(board.difficulty())) {
            score += 3;
            reasons.add("难度符合 " + difficulty);
        }
        for (String tag : tagHints) {
            if (board.tags() != null && board.tags().contains(tag)) {
                score += 2;
                reasons.add("命中标签 " + tag);
            }
        }
        if (score == 0) {
            if (playerCount == null && difficulty == null && tagHints.isEmpty()) {
                score = 1;
            } else if (playerCount != null && Math.abs(board.playerCount() - playerCount) <= 1) {
                score = 1;
                reasons.add("人数接近");
            }
        }
        String reason = reasons.isEmpty() ? "适合作为兜底候选板子" : String.join("，", reasons);
        return new BoardCandidate(board, score, reason);
    }

    private GeneratedAnswer synthesizeBoardRecommendationMarkdown(
            String query,
            List<AssistantDtos.RecommendedBoardCard> boards,
            AssistantDtos.AdminAiConfig config
    ) {
        if (boards.isEmpty()) {
            return new GeneratedAnswer("""
                    ## 暂时没找到完全匹配的板子

                    - 你可以补充 **人数**、**难度** 或 **玩法偏好**
                    - 例如：`12人 进阶 娱乐板子推荐`
                    - 如果愿意，我也可以直接按“入门 / 进阶 / 烧脑”重新给你筛一轮
                    """, "NO_MATCH", 0L);
        }

        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return new GeneratedAnswer(fallbackBoardAnswer(boards), "NO_MODEL", 0L);
        }

        StringBuilder context = new StringBuilder();
        for (int index = 0; index < boards.size(); index++) {
            AssistantDtos.RecommendedBoardCard board = boards.get(index);
            context.append(index + 1)
                    .append(". ")
                    .append(board.name())
                    .append(" | ")
                    .append(board.playerCount())
                    .append("人 | ")
                    .append(board.difficulty())
                    .append(" | 标签：")
                    .append(board.tags() == null || board.tags().isEmpty() ? "无" : String.join("、", board.tags()))
                    .append(" | 推荐理由：")
                    .append(board.reason())
                    .append('\n');
        }

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(config.prompt().recommendationPrompt() + """

                                请使用简洁 Markdown 输出，要求：
                                1. 先输出 `## 推荐结果`
                                2. 每个板子用 `### 板名` 开头
                                3. 每个板子下面只保留 2-3 条要点
                                4. 不要编造站内不存在的规则或角色
                                5. 不要把候选板子机械地原样抄一遍
                                """),
                        new UserMessage("""
                                用户问题：
                                %s

                                候选板子：
                                %s
                                """.formatted(query, context))
                ),
                MiniMaxChatOptions.builder()
                        .model(config.base().chatModel())
                        .temperature(config.base().temperature())
                        .build()
        );

        long modelStartAt = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            long modelMs = System.currentTimeMillis() - modelStartAt;
            if (content == null || content.isBlank()) {
                return new GeneratedAnswer(fallbackBoardAnswer(boards), "MODEL_EMPTY", modelMs);
            }
            return new GeneratedAnswer(normalizeMarkdown(content), "NONE", modelMs);
        } catch (Exception exception) {
            return new GeneratedAnswer(fallbackBoardAnswer(boards), "MODEL_EXCEPTION", System.currentTimeMillis() - modelStartAt);
        }
    }

    private GeneratedAnswer synthesizeKnowledgeMarkdown(PreparedAsk prepared, String query, List<AssistantKnowledgeService.KnowledgeHit> hits) {
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        List<AssistantKnowledgeService.KnowledgeHit> contextHits = distinctKnowledgeHits(hits);
        StringBuilder context = new StringBuilder();
        int contextIndex = 1;
        for (AssistantKnowledgeService.KnowledgeHit hit : contextHits) {
            context.append("资料 ").append(contextIndex++).append("（相关度 ").append(String.format(Locale.ROOT, "%.1f", hit.score())).append("）\n")
                    .append("来源类型：").append(describeKnowledgeSource(hit.sourceType())).append('\n')
                    .append("来源：").append(hit.title()).append('\n')
                    .append(contextForPrompt(hit)).append("\n\n");
        }

        if (chatModel == null) {
            return new GeneratedAnswer(fallbackKnowledgeAnswer(query, contextHits), "NO_MODEL", 0L);
        }

        // 最近上下文只用于理解追问，不把本轮用户问题重复塞进去，减少模型复读和跑题。
        List<String> recentMessages = recentContextMessages(prepared, query);
        String history = recentMessages.isEmpty() ? "无" : String.join("\n", recentMessages);

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(prepared.config().prompt().systemPrompt() + """

                                请使用简洁 Markdown 回答，并遵守：
                                1. 优先输出 `## 结论`
                                2. 如需拆解规则，再输出 `### 规则拆解`
                                3. 如需给操作建议，再输出 `### 你可以怎么做`
                                4. 只能依据给定资料，不要编造
                                5. 回答必须完整覆盖资料中与问题直接相关的技能、限制、触发时机和结算结果，不要只截取半句
                                6. 优先使用排在最前面的资料；只有当它不能直接回答时，才参考后续资料
                                7. 如果资料没有直接回答用户问题，明确说明“现有资料未直接说明”，不要用相邻角色或相邻 FAQ 猜测
                                8. 不要把原始字段整段照抄成“板子名称/人数/难度/标签”的数据清单，除非用户明确要基础资料卡
                                9. 不要输出文档里的题号、章节号、A:/Q: 前缀或无关 FAQ
                                10. 句子尽量短，优先用项目符号
                                11. 每个要点必须独占一行，严禁在同一行里连续写 `- 技能： - 面具： - 接刀：`
                                12. 小标题用 `### 技能` 这类独立行；小标题后面的内容另起项目符号行
                                13. 如果用户问的是某个具体角色、板子或术语，只回答这个主体；同一资料里的其他角色/板子不得混入答案
                                14. 如果命中资料标题和用户主体不一致，但资料片段内有用户主体，只能使用片段内的用户主体内容
                                """),
                        new UserMessage("""
                                当前问题：
                                %s

                                最近上下文：
                                %s

                                站内资料：
                                %s
                                """.formatted(query, history, context))
                ),
                MiniMaxChatOptions.builder()
                        .model(prepared.config().base().chatModel())
                        .temperature(prepared.config().base().temperature())
                        .build()
        );

        long modelStartAt = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            long modelMs = System.currentTimeMillis() - modelStartAt;
            if (content == null || content.isBlank()) {
                return new GeneratedAnswer(fallbackKnowledgeAnswer(query, contextHits), "MODEL_EMPTY", modelMs);
            }
            return new GeneratedAnswer(normalizeMarkdown(content), "NONE", modelMs);
        } catch (Exception exception) {
            return new GeneratedAnswer(fallbackKnowledgeAnswer(query, contextHits), "MODEL_EXCEPTION", System.currentTimeMillis() - modelStartAt);
        }
    }

    private Prompt buildBoardRecommendationPrompt(
            String query,
            List<AssistantDtos.RecommendedBoardCard> boards,
            AssistantDtos.AdminAiConfig config
    ) {
        if (boards.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        for (int index = 0; index < boards.size(); index++) {
            AssistantDtos.RecommendedBoardCard board = boards.get(index);
            context.append(index + 1)
                    .append(". ")
                    .append(board.name())
                    .append(" | ")
                    .append(board.playerCount())
                    .append("人 | ")
                    .append(board.difficulty())
                    .append(" | 标签: ")
                    .append(board.tags() == null || board.tags().isEmpty() ? "无" : String.join("、", board.tags()))
                    .append(" | 推荐理由: ")
                    .append(board.reason())
                    .append('\n');
        }

        return new Prompt(
                List.of(
                        new SystemMessage(config.prompt().recommendationPrompt() + """

                                请使用简洁 Markdown 输出，要求：
                                1. 先输出 `## 推荐结果`
                                2. 每个板子用 `### 板名`
                                3. 每个板子下只保留 2-3 条要点
                                4. 不要编造站内不存在的规则或角色
                                5. 不要把候选板子机械地原样抄一遍
                                """),
                        new UserMessage("""
                                用户问题：
                                %s

                                候选板子：
                                %s
                                """.formatted(query, context))
                ),
                MiniMaxChatOptions.builder()
                        .model(config.base().chatModel())
                        .temperature(config.base().temperature())
                        .build()
        );
    }

    private Prompt buildKnowledgePrompt(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        List<AssistantKnowledgeService.KnowledgeHit> contextHits = distinctKnowledgeHits(hits);
        StringBuilder context = new StringBuilder();
        int contextIndex = 1;
        for (AssistantKnowledgeService.KnowledgeHit hit : contextHits) {
            context.append("资料 ").append(contextIndex++).append("（相关度 ").append(String.format(Locale.ROOT, "%.1f", hit.score())).append("）\n")
                    .append("来源类型: ").append(describeKnowledgeSource(hit.sourceType())).append('\n')
                    .append("来源: ").append(hit.title()).append('\n')
                    .append(contextForPrompt(hit)).append("\n\n");
        }

        List<String> recentMessages = recentContextMessages(prepared, query);
        String history = recentMessages.isEmpty() ? "无" : String.join("\n", recentMessages);

        return new Prompt(
                List.of(
                        new SystemMessage(prepared.config().prompt().systemPrompt() + """

                                请使用简洁 Markdown 回答，并遵守：
                                1. 优先输出 `## 结论`
                                2. 如需拆解规则，再输出 `### 规则拆解`
                                3. 如需给操作建议，再输出 `### 你可以怎么做`
                                4. 只能依据给定资料，不要编造
                                5. 回答必须完整覆盖资料中与问题直接相关的技能、限制、触发时机和结算结果，不要只截取半句
                                6. 优先使用排在最前面的资料；只有当它不能直接回答时，才参考后续资料
                                7. 如果资料没有直接回答用户问题，明确说明“现有资料未直接说明”，不要用相邻角色或相邻 FAQ 猜测
                                8. 不要把原始字段整段照抄成“板子名称/人数/难度/标签”的数据清单，除非用户明确要基础资料卡
                                9. 不要输出文档里的题号、章节号、A:/Q: 前缀或无关 FAQ
                                10. 句子尽量短，优先用项目符号
                                11. 每个要点必须独占一行，严禁在同一行里连续写 `- 技能： - 面具： - 接刀：`
                                12. 小标题用 `### 技能` 这类独立行；小标题后面的内容另起项目符号行
                                13. 如果用户问的是某个具体角色、板子或术语，只回答这个主体；同一资料里的其他角色/板子不得混入答案
                                14. 如果命中资料标题和用户主体不一致，但资料片段内有用户主体，只能使用片段内的用户主体内容
                                """),
                        new UserMessage("""
                                当前问题：
                                %s

                                最近上下文：
                                %s

                                站内资料：
                                %s
                                """.formatted(query, history, context))
                ),
                MiniMaxChatOptions.builder()
                        .model(prepared.config().base().chatModel())
                        .temperature(prepared.config().base().temperature())
                        .build()
        );
    }

    private StreamedAnswer streamPromptAnswer(
            Prompt prompt,
            String fallbackAnswer,
            SseEmitter emitter,
            ExecutionMetrics metrics
    ) throws IOException {
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (prompt == null || chatModel == null) {
            metrics.setFallbackModeIfBlank("NO_MODEL");
            metrics.setStreamMode("FALLBACK");
            return emitStaticAnswer(fallbackAnswer, emitter, metrics);
        }

        StringBuilder answer = new StringBuilder();
        String failureType = null;
        long modelStartAt = System.currentTimeMillis();

        try {
            for (ChatResponse chunkResponse : chatModel.stream(prompt).toIterable()) {
                String chunkText = extractResponseText(chunkResponse);
                // 不同模型/SDK 可能返回“增量文本”或“累计全文”，这里统一转成真正增量再发给前端。
                String delta = resolveStreamDelta(chunkText, answer.toString());
                if (delta == null || delta.isBlank()) {
                    continue;
                }
                metrics.ensureFirstToken(System.currentTimeMillis() - metrics.startAtMs());
                answer.append(delta);
                emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(delta));
            }
        } catch (Exception exception) {
            failureType = exception.getClass().getSimpleName();
            metrics.setModelMs(System.currentTimeMillis() - modelStartAt);
            if (answer.length() == 0) {
                metrics.setFallbackModeIfBlank("MODEL_EXCEPTION");
                metrics.setStreamMode("FALLBACK");
                return emitStaticAnswer(fallbackAnswer, emitter, metrics, failureType);
            }
            String degradedTail = "\n\n> 当前回答在生成过程中中断，以下是已生成内容。";
            answer.append(degradedTail);
            emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(degradedTail));
            metrics.setFallbackModeIfBlank("PARTIAL_STREAM");
        }

        if (answer.length() == 0) {
            metrics.setFallbackModeIfBlank("MODEL_EMPTY");
            metrics.setStreamMode("FALLBACK");
            return emitStaticAnswer(fallbackAnswer, emitter, metrics, failureType);
        }

        metrics.setModelMs(System.currentTimeMillis() - modelStartAt);
        return new StreamedAnswer(normalizeMarkdown(answer.toString()), failureType);
    }

    private StreamedAnswer emitStaticAnswer(String markdown, SseEmitter emitter, ExecutionMetrics metrics) throws IOException {
        return emitStaticAnswer(markdown, emitter, metrics, null);
    }

    private StreamedAnswer emitStaticAnswer(
            String markdown,
            SseEmitter emitter,
            ExecutionMetrics metrics,
            String failureType
    ) throws IOException {
        String normalized = normalizeMarkdown(markdown);
        for (String chunk : splitMarkdownForStream(normalized)) {
            metrics.ensureFirstToken(System.currentTimeMillis() - metrics.startAtMs());
            emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(chunk));
        }
        return new StreamedAnswer(normalized, failureType);
    }

    private String extractResponseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String resolveStreamDelta(String chunkText, String currentAnswer) {
        if (chunkText == null || chunkText.isBlank()) {
            return "";
        }
        if (currentAnswer == null || currentAnswer.isEmpty()) {
            return chunkText;
        }
        if (chunkText.startsWith(currentAnswer)) {
            return chunkText.substring(currentAnswer.length());
        }
        if (currentAnswer.endsWith(chunkText)) {
            return "";
        }
        int overlap = textOverlapLength(currentAnswer, chunkText);
        if (overlap > 0) {
            return chunkText.substring(overlap);
        }
        return chunkText;
    }

    private int textOverlapLength(String left, String right) {
        int maxLength = Math.min(left.length(), right.length());
        for (int length = maxLength; length > 0; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    private String fallbackBoardAnswer(List<AssistantDtos.RecommendedBoardCard> boards) {
        StringBuilder builder = new StringBuilder("## 推荐结果\n\n");
        builder.append("我先从站内板库里帮你筛了这几张：\n\n");
        for (int index = 0; index < boards.size(); index++) {
            AssistantDtos.RecommendedBoardCard board = boards.get(index);
            builder.append("### ").append(index + 1).append(". ").append(board.name()).append('\n')
                    .append("- 人数：").append(board.playerCount()).append(" 人\n")
                    .append("- 难度：").append(board.difficulty()).append('\n');
            if (board.tags() != null && !board.tags().isEmpty()) {
                builder.append("- 标签：").append(String.join("、", board.tags())).append('\n');
            }
            builder.append("- 推荐理由：").append(board.reason()).append("\n\n");
        }
        builder.append("### 怎么选\n")
                .append("- 先看人数和难度是否贴近你的局配置\n")
                .append("- 如果你愿意，我还能继续帮你比较其中两张板子的节奏差异");
        return builder.toString().trim();
    }

    private String fallbackKnowledgeAnswer(String query, List<AssistantKnowledgeService.KnowledgeHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return buildRefusalMarkdown("现有知识库暂时没有找到能直接回答这个问题的资料。");
        }
        hits = distinctKnowledgeHits(hits);
        if (hits.isEmpty()) {
            return buildRefusalMarkdown("现有知识库暂时没有找到能直接回答这个问题的资料。");
        }
        AssistantKnowledgeService.KnowledgeHit first = hits.getFirst();
        String structuredAnswer = buildStructuredFallbackAnswer(query, first);
        if (structuredAnswer != null && !structuredAnswer.isBlank()) {
            return structuredAnswer;
        }
        String sourceHint = AssistantConstants.SOURCE_DOCUMENT.equals(first.sourceType())
                ? "这条回答优先依据你上传并已发布的知识库文档"
                : "这条回答优先依据站内已发布知识整理";
        StringBuilder builder = new StringBuilder("## 结论\n\n");
        List<String> excerpts = distinctKnowledgeExcerpts(hits);
        if (excerpts.isEmpty()) {
            excerpts = List.of(cleanKnowledgeExcerpt(first));
        }
        excerpts.stream()
                .limit(2)
                .map(excerpt -> "- " + excerpt)
                .forEach(line -> builder.append(line).append('\n'));
        builder.append("\n### 依据\n")
                .append("- 来源：**").append(first.title()).append("**\n")
                .append("- ").append(sourceHint);
        return builder.toString().trim();
    }

    private List<String> distinctKnowledgeExcerpts(List<AssistantKnowledgeService.KnowledgeHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<String> excerpts = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        for (AssistantKnowledgeService.KnowledgeHit hit : distinctKnowledgeHits(hits)) {
            String excerpt = cleanKnowledgeExcerpt(hit);
            String fingerprint = textFingerprint(excerpt);
            if (excerpt.isBlank() || hasSimilarFingerprint(fingerprint, fingerprints)) {
                continue;
            }
            excerpts.add(excerpt);
            rememberFingerprint(fingerprints, fingerprint, 12);
        }
        return excerpts;
    }

    private String sanitizeSubjectDrift(String answer, String query) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        SubjectGuard guard = buildSubjectGuard(query);
        if (!guard.enabled()) {
            return answer;
        }

        List<String> keptLines = new ArrayList<>();
        for (String line : answer.replace("\r\n", "\n").split("\n", -1)) {
            if (shouldDropSubjectDriftLine(line, guard)) {
                continue;
            }
            keptLines.add(line);
        }
        if (keptLines.isEmpty()) {
            return answer;
        }
        return MULTI_BLANK_LINES.matcher(String.join("\n", keptLines).trim()).replaceAll("\n\n");
    }

    private SubjectGuard buildSubjectGuard(String query) {
        String normalizedQuery = textFingerprint(query);
        if (normalizedQuery.isBlank()) {
            return SubjectGuard.disabled();
        }

        List<SubjectCandidate> candidates = new ArrayList<>();
        List<String> allTerms = new ArrayList<>();
        int roleBoost = containsAny(query, List.of("技能", "能力", "身份", "角色", "信息", "介绍", "发动", "怎么用")) ? 30 : 0;
        int boardBoost = containsAny(query, List.of("板子", "局", "配置", "人数", "阵容", "规则", "流程")) ? 30 : 0;

        for (WolfbookDtos.RoleListItemView role : boardService.listRoles(null)) {
            addSubjectCandidate(candidates, allTerms, normalizedQuery, "ROLE", role.name(), role.name(), roleBoost);
            for (String alias : splitAliases(role.alias())) {
                addSubjectCandidate(candidates, allTerms, normalizedQuery, "ROLE", role.name(), alias, roleBoost - 5);
            }
        }
        for (Board board : boardService.listAllBoards()) {
            addSubjectCandidate(candidates, allTerms, normalizedQuery, "BOARD", board.name(), board.name(), boardBoost);
        }

        return candidates.stream()
                .max(Comparator.comparingInt(SubjectCandidate::score)
                        .thenComparingInt(candidate -> textFingerprint(candidate.term()).length()))
                .map(candidate -> {
                    String selected = textFingerprint(candidate.displayName());
                    List<String> otherTerms = allTerms.stream()
                            .filter(term -> !textFingerprint(term).isBlank())
                            .filter(term -> !selected.equals(textFingerprint(term)))
                            .filter(term -> !selected.contains(textFingerprint(term)))
                            .distinct()
                            .toList();
                    return new SubjectGuard(
                            List.of(candidate.displayName(), candidate.term()).stream()
                                    .filter(term -> term != null && !term.isBlank())
                                    .distinct()
                                    .toList(),
                            otherTerms
                    );
                })
                .orElseGet(SubjectGuard::disabled);
    }

    private void addSubjectCandidate(
            List<SubjectCandidate> candidates,
            List<String> allTerms,
            String normalizedQuery,
            String type,
            String displayName,
            String term,
            int boost
    ) {
        if (term == null || term.isBlank()) {
            return;
        }
        allTerms.add(term);
        String normalizedTerm = textFingerprint(term);
        if (normalizedTerm.length() < 2 || !normalizedQuery.contains(normalizedTerm)) {
            return;
        }
        candidates.add(new SubjectCandidate(type, displayName, term, normalizedTerm.length() * 10 + Math.max(boost, 0)));
    }

    private boolean shouldDropSubjectDriftLine(String line, SubjectGuard guard) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if (startsWithOtherSubject(trimmed, guard)) {
            return true;
        }
        int otherCount = countOtherTerms(trimmed, guard);
        if (otherCount >= 3 && isCatalogLikeLine(trimmed)) {
            return true;
        }
        return otherCount >= 2 && !containsSubject(trimmed, guard) && isListLikeLine(trimmed);
    }

    private boolean startsWithOtherSubject(String line, SubjectGuard guard) {
        String stripped = line
                .replaceFirst("^\\s*[-*•>]+\\s*", "")
                .replaceFirst("^\\s*\\d+[.、．]\\s*", "")
                .replaceFirst("^\\s*\\|+\\s*", "")
                .replace("**", "")
                .trim();
        String normalized = textFingerprint(stripped);
        if (normalized.isBlank()) {
            return false;
        }
        for (String term : guard.otherTerms()) {
            String normalizedTerm = textFingerprint(term);
            if (normalizedTerm.length() >= 2 && normalized.startsWith(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private int countOtherTerms(String line, SubjectGuard guard) {
        String normalized = textFingerprint(line);
        int count = 0;
        for (String term : guard.otherTerms()) {
            String normalizedTerm = textFingerprint(term);
            if (normalizedTerm.length() >= 2 && normalized.contains(normalizedTerm)) {
                count++;
            }
        }
        return count;
    }

    private boolean containsSubject(String line, SubjectGuard guard) {
        String normalized = textFingerprint(line);
        for (String term : guard.subjectTerms()) {
            String normalizedTerm = textFingerprint(term);
            if (normalizedTerm.length() >= 2 && normalized.contains(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCatalogLikeLine(String line) {
        long pipeCount = line.chars().filter(character -> character == '|').count();
        return line.contains("||")
                || pipeCount >= 4
                || line.length() > 80 && (line.contains(" | ") || line.contains("、") || line.contains("；"));
    }

    private boolean isListLikeLine(String line) {
        return line.startsWith("-")
                || line.startsWith("*")
                || line.startsWith("|")
                || line.matches("^\\d+[.、．].*");
    }

    private List<String> splitAliases(String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(alias.split("[,，、/|\\s]+"))
                .map(String::trim)
                .filter(item -> item.length() >= 2)
                .toList();
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildStructuredFallbackAnswer(String query, AssistantKnowledgeService.KnowledgeHit hit) {
        if (hit == null || hit.document() == null || !AssistantConstants.SOURCE_STRUCTURED.equals(hit.sourceType())) {
            return null;
        }
        Map<String, String> fields = parseStructuredFields(hit.document().getContentText());
        String sourceKey = hit.document().getSourceKey() == null ? "" : hit.document().getSourceKey();
        if (sourceKey.startsWith("ROLE:")) {
            String name = fields.getOrDefault("角色名称", hit.title());
            StringBuilder builder = new StringBuilder("## 结论\n\n");
            if (asksForSkill(query) && fields.containsKey("技能")) {
                builder.append("- **").append(name).append("的技能**：").append(fields.get("技能")).append('\n');
            } else {
                appendFieldBullet(builder, "角色", name);
                appendFieldBullet(builder, "阵营", fields.get("阵营"));
                appendFieldBullet(builder, "类型", fields.get("类型"));
                appendFieldBullet(builder, "技能", fields.get("技能"));
            }
            appendFieldBullet(builder, "补充", fields.get("FAQ"));
            builder.append("\n### 依据\n")
                    .append("- 来源：**").append(hit.title()).append("**\n")
                    .append("- 站内结构化角色资料");
            return builder.toString().trim();
        }
        if (sourceKey.startsWith("BOARD:")) {
            String name = fields.getOrDefault("板子名称", hit.title());
            StringBuilder builder = new StringBuilder("## 结论\n\n");
            appendFieldBullet(builder, "板子", name);
            appendFieldBullet(builder, "人数", fields.get("人数"));
            appendFieldBullet(builder, "难度", fields.get("难度"));
            appendFieldBullet(builder, "规则类型", fields.get("规则类型"));
            appendFieldBullet(builder, "胜利条件", fields.get("胜利条件"));
            appendFieldBullet(builder, "规则", fields.get("规则"));
            appendFieldBullet(builder, "提示", fields.get("提示"));
            builder.append("\n### 依据\n")
                    .append("- 来源：**").append(hit.title()).append("**\n")
                    .append("- 站内结构化板子资料");
            return builder.toString().trim();
        }
        return null;
    }

    private Map<String, String> parseStructuredFields(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return fields;
        }
        for (String line : content.split("\\R")) {
            int separatorIndex = line.indexOf('：');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if (!key.isBlank() && !value.isBlank()) {
                fields.put(key, value);
            }
        }
        return fields;
    }

    private boolean asksForSkill(String query) {
        String normalized = query == null ? "" : query;
        return normalized.contains("技能")
                || normalized.contains("能力")
                || normalized.contains("作用")
                || normalized.contains("怎么用")
                || normalized.contains("发动")
                || normalized.contains("是什么");
    }

    private void appendFieldBullet(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("- **").append(label).append("**：").append(cleanAnswerSnippet(value)).append('\n');
    }

    private List<AssistantKnowledgeService.KnowledgeHit> distinctKnowledgeHits(List<AssistantKnowledgeService.KnowledgeHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<AssistantKnowledgeService.KnowledgeHit> distinct = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            if (hit == null) {
                continue;
            }
            String fingerprint = textFingerprint(contextForPrompt(hit));
            if (fingerprint.isBlank()) {
                fingerprint = textFingerprint((hit.title() == null ? "" : hit.title()) + " " + (hit.snippet() == null ? "" : hit.snippet()));
            }
            if (hasSimilarFingerprint(fingerprint, fingerprints)) {
                continue;
            }
            distinct.add(hit);
            rememberFingerprint(fingerprints, fingerprint, 18);
        }
        return distinct;
    }

    private String contextForPrompt(AssistantKnowledgeService.KnowledgeHit hit) {
        if (hit == null) {
            return "";
        }
        String context = hit.contextText();
        if (context == null || context.isBlank()) {
            context = hit.snippet();
        }
        return limitText(cleanMarkdownNoise(context), 1200);
    }

    private String cleanKnowledgeExcerpt(AssistantKnowledgeService.KnowledgeHit hit) {
        if (hit == null) {
            return "";
        }
        String context = hit.contextText();
        if (context == null || context.isBlank()) {
            context = hit.snippet();
        }
        return limitText(cleanMarkdownNoise(context), 700);
    }

    private String cleanAnswerSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return limitText(cleanMarkdownNoise(snippet), 220);
    }

    private String cleanMarkdownNoise(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^[-*]\\s+", "")
                .replaceAll("(?m)^\\d+[.、．]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String textFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("https?://\\S+", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^[-*]\\s+", "")
                .replaceAll("(?m)^\\d+[.、．]\\s*", "")
                .replaceAll("\\*\\*|`|_", "")
                .replaceAll("[\\p{P}\\s]+", "")
                .trim();
    }

    private boolean hasSimilarFingerprint(String fingerprint, List<String> fingerprints) {
        if (fingerprint == null || fingerprint.length() < 12) {
            return false;
        }
        for (String existing : fingerprints) {
            if (fingerprint.equals(existing)) {
                return true;
            }
            String shorter = fingerprint.length() <= existing.length() ? fingerprint : existing;
            String longer = fingerprint.length() > existing.length() ? fingerprint : existing;
            if (shorter.length() >= 28 && longer.contains(shorter)) {
                return true;
            }
        }
        return false;
    }

    private void rememberFingerprint(List<String> fingerprints, String fingerprint, int maxSize) {
        if (fingerprint == null || fingerprint.length() < 12) {
            return;
        }
        fingerprints.add(fingerprint);
        while (fingerprints.size() > maxSize) {
            fingerprints.removeFirst();
        }
    }

    private String limitText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLength));
    }

    private String describeKnowledgeSource(String sourceType) {
        if (AssistantConstants.SOURCE_DOCUMENT.equals(sourceType)) {
            return "上传知识库文档";
        }
        if (AssistantConstants.SOURCE_STRUCTURED.equals(sourceType)) {
            return "结构化板子/角色资料";
        }
        return "其他资料";
    }

    private String buildWebMarkdownAnswer(String answer, List<AssistantDtos.AssistantCitation> citations) {
        StringBuilder builder = new StringBuilder("## 联网补充结论\n\n")
                .append(normalizeMarkdown(answer));
        if (!citations.isEmpty()) {
            builder.append("\n\n### 使用说明\n")
                    .append("- 这部分内容来自联网搜索，建议和站内规则交叉确认\n")
                    .append("- 下方已附上可追溯来源卡片，方便继续核对");
        }
        return normalizeMarkdown(builder.toString());
    }

    private String buildRefusalMarkdown(String message) {
        String body = (message == null || message.isBlank())
                ? "当前问题超出了狼人杀知识助手的能力边界。"
                : message.trim();
        return """
                ## 当前这题我先不乱答

                %s

                ### 你可以继续这样问我
                - 板子推荐：`12人进阶推荐什么板子？`
                - 规则拆解：`女巫能不能自救？`
                - 角色理解：`守卫和女巫会不会冲突？`
                """.formatted(body).trim();
    }

    private List<String> nextQuestions(List<String> quickQuestions, String currentQuestion) {
        if (quickQuestions == null || quickQuestions.isEmpty()) {
            return List.of();
        }
        return quickQuestions.stream()
                .filter(item -> item != null && !item.equalsIgnoreCase(currentQuestion))
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
    }

    private List<String> recentContextMessages(PreparedAsk prepared, String currentQuestion) {
        List<String> recentMessages = assistantConversationService.recentContextMessages(
                prepared.openid(),
                prepared.session().getSessionId()
        );
        if (recentMessages.isEmpty() || currentQuestion == null) {
            return recentMessages;
        }
        String currentLine = "USER: " + currentQuestion;
        if (currentLine.equals(recentMessages.getLast())) {
            return recentMessages.subList(0, recentMessages.size() - 1);
        }
        return recentMessages;
    }

    private Integer parsePlayerCount(String query) {
        Matcher matcher = PLAYER_COUNT_PATTERN.matcher(query);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String parseDifficulty(String query) {
        if (query.contains("入门") || query.contains("新手")) {
            return "入门";
        }
        if (query.contains("进阶")) {
            return "进阶";
        }
        if (query.contains("烧脑")) {
            return "烧脑";
        }
        return null;
    }

    private List<String> extractTagHints(String query) {
        List<String> tags = new ArrayList<>();
        for (String tag : List.of("经典", "教学", "娱乐", "高配", "第三方")) {
            if (query.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private List<String> splitMarkdownForStream(String markdown) {
        String normalized = normalizeMarkdown(markdown);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            String nextLine = line + "\n";
            if (current.length() > 0 && current.length() + nextLine.length() > 96) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(nextLine);
            if (line.isBlank() || line.startsWith("## ") || line.startsWith("### ")) {
                if (current.length() >= 24) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private String normalizeMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").trim();
        // 模型偶尔把多个 Markdown 要点挤在同一行，这里先拆行，再做重复行过滤。
        normalized = normalized.replaceAll("\\h+-\\h+(?=(?:#{1,6}\\h*)?[\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]{1,12}[：:])", "\n- ");
        normalized = normalized.replaceAll("\\h+-\\h+(?=(若|如果|当|可|可以|不|在|被|否则|同时|然后|接刀|小贴士|注意))", "\n- ");
        normalized = normalized.replaceAll("(?m)^\\s*[-*]\\s+(#{1,6}\\s+)", "$1");
        normalized = normalized.replaceAll("(?m)^\\s*[-*]\\s+(\\d+[.、．]\\s*)", "$1");
        normalized = normalized.replaceAll("(?m)^\\s*[-*]\\s*(结论|技能|规则拆解|你可以怎么做|小贴士|注意事项|常见问题)[：:]?\\s*$", "### $1");
        normalized = deduplicateMarkdownLines(normalized);
        normalized = MULTI_BLANK_LINES.matcher(normalized).replaceAll("\n\n");
        if (normalized.startsWith("#") || normalized.startsWith("-") || normalized.startsWith(">") || normalized.matches("^\\d+\\..*")) {
            return normalized;
        }
        return "## 回答\n\n" + normalized;
    }

    private String deduplicateMarkdownLines(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        boolean inCodeBlock = false;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                lines.add(line);
                continue;
            }
            if (inCodeBlock || trimmed.isBlank()) {
                lines.add(line);
                continue;
            }
            String fingerprint = textFingerprint(trimmed);
            if (hasSimilarFingerprint(fingerprint, fingerprints)) {
                continue;
            }
            lines.add(line);
            rememberFingerprint(fingerprints, fingerprint, 24);
        }
        return String.join("\n", lines).trim();
    }

    private void emit(SseEmitter emitter, String eventName, Object payload) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(payload, MediaType.APPLICATION_JSON));
    }

    private void emitStreamError(SseEmitter emitter, Exception exception, String traceId) {
        try {
            emit(emitter, "error", new AssistantDtos.AssistantStreamError(
                    exception.getMessage() == null ? "AI stream failed" : exception.getMessage(),
                    traceId
            ));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(exception);
        }
    }

    private void writeLog(
            String openid,
            String sessionId,
            String userMessage,
            AssistantDtos.AssistantAskResponse response,
            long latencyMs,
            String failureType,
            ExecutionMetrics metrics
    ) {
        AssistantQueryLogEntity entity = new AssistantQueryLogEntity();
        entity.setOpenid(openid);
        entity.setSessionId(sessionId);
        entity.setUserMessage(userMessage);
        entity.setAnswerType(response.answerType());
        entity.setHitSources(write(response.citations().stream().map(citation -> citation.sourceType() + ":" + citation.title()).toList()));
        entity.setUsedWebSearch(response.usedWebSearch() ? 1 : 0);
        entity.setLatencyMs(latencyMs);
        entity.setFirstTokenMs(metrics.firstTokenMs());
        entity.setEmbeddingMs(metrics.embeddingMs());
        entity.setRetrievalMs(metrics.retrievalMs());
        entity.setModelMs(metrics.modelMs());
        entity.setWebSearchMs(metrics.webSearchMs());
        entity.setCacheHit(metrics.cacheHit() ? 1 : 0);
        entity.setFallbackMode(metrics.fallbackMode());
        entity.setStreamMode(metrics.streamMode());
        entity.setRetrievalMetaJson(write(metrics.retrievalMeta()));
        entity.setSuccess(failureType == null ? 1 : 0);
        entity.setFailureType(failureType);
        entity.setTraceId(response.traceId());
        entity.setCreateTime(LocalDateTime.now());
        assistantQueryLogMapper.insert(entity);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    private enum QueryIntent {
        BOARD_RECOMMENDATION,
        KNOWLEDGE,
        UNSUPPORTED
    }

    private record SubjectGuard(
            List<String> subjectTerms,
            List<String> otherTerms
    ) {
        static SubjectGuard disabled() {
            return new SubjectGuard(List.of(), List.of());
        }

        boolean enabled() {
            return subjectTerms != null && !subjectTerms.isEmpty();
        }
    }

    private record SubjectCandidate(
            String type,
            String displayName,
            String term,
            int score
    ) {
    }

    private record PreparedAsk(
            String openid,
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            QueryIntent intent,
            String cacheVersion,
            AssistantQueryPlan queryPlan,
            long startAtMs
    ) {
    }

    private record AskExecution(
            AssistantDtos.AssistantAskResponse response,
            long latencyMs,
            String failureType
    ) {
    }

    private record StreamExecution(
            AssistantDtos.AssistantAskResponse response,
            String failureType
    ) {
    }

    private record StreamedAnswer(
            String answer,
            String failureType
    ) {
    }

    private record GeneratedAnswer(
            String answer,
            String fallbackMode,
            long modelMs
    ) {
    }

    private record BoardCandidate(Board board, int score, String reason) {
    }

    private static final class ExecutionMetrics {
        private final long startAtMs;
        private Long firstTokenMs = 0L;
        private Long embeddingMs = 0L;
        private Long retrievalMs = 0L;
        private Long modelMs = 0L;
        private Long webSearchMs = 0L;
        private boolean cacheHit = false;
        private String fallbackMode = "NONE";
        private String streamMode;
        private Map<String, Object> retrievalMeta = Map.of();

        private ExecutionMetrics(long startAtMs, String streamMode) {
            this.startAtMs = startAtMs;
            this.streamMode = streamMode;
        }

        static ExecutionMetrics sync(long startAtMs) {
            return new ExecutionMetrics(startAtMs, "SYNC");
        }

        static ExecutionMetrics stream(long startAtMs) {
            return new ExecutionMetrics(startAtMs, "STREAM");
        }

        long startAtMs() {
            return startAtMs;
        }

        long firstTokenMs() {
            return firstTokenMs == null ? 0L : firstTokenMs;
        }

        void ensureFirstToken(long value) {
            if (firstTokenMs == null || firstTokenMs <= 0L) {
                firstTokenMs = Math.max(value, 0L);
            }
        }

        long embeddingMs() {
            return embeddingMs == null ? 0L : embeddingMs;
        }

        void setEmbeddingMs(long embeddingMs) {
            this.embeddingMs = Math.max(embeddingMs, 0L);
        }

        long retrievalMs() {
            return retrievalMs == null ? 0L : retrievalMs;
        }

        void setRetrievalMs(long retrievalMs) {
            this.retrievalMs = Math.max(retrievalMs, 0L);
        }

        long modelMs() {
            return modelMs == null ? 0L : modelMs;
        }

        void setModelMs(long modelMs) {
            this.modelMs = Math.max(modelMs, 0L);
        }

        long webSearchMs() {
            return webSearchMs == null ? 0L : webSearchMs;
        }

        void setWebSearchMs(long webSearchMs) {
            this.webSearchMs = Math.max(webSearchMs, 0L);
        }

        boolean cacheHit() {
            return cacheHit;
        }

        void setCacheHit(boolean cacheHit) {
            this.cacheHit = cacheHit;
        }

        String fallbackMode() {
            return fallbackMode == null || fallbackMode.isBlank() ? "NONE" : fallbackMode;
        }

        void setFallbackModeIfBlank(String fallbackMode) {
            if ((this.fallbackMode == null || this.fallbackMode.isBlank() || "NONE".equals(this.fallbackMode))
                    && fallbackMode != null && !fallbackMode.isBlank()) {
                this.fallbackMode = fallbackMode;
            }
        }

        void setFallbackMode(String fallbackMode) {
            if (fallbackMode != null && !fallbackMode.isBlank()) {
                this.fallbackMode = fallbackMode;
            }
        }

        String streamMode() {
            return streamMode == null || streamMode.isBlank() ? "STREAM" : streamMode;
        }

        void setStreamMode(String streamMode) {
            this.streamMode = streamMode;
        }

        Map<String, Object> retrievalMeta() {
            return retrievalMeta == null ? Map.of() : retrievalMeta;
        }

        void setRetrievalMeta(Map<String, Object> retrievalMeta) {
            this.retrievalMeta = retrievalMeta == null ? Map.of() : retrievalMeta;
        }
    }
}
