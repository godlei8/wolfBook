package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.dto.AssistantDtos;
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

@Service
public class AssistantAnswerService {

    private static final Pattern PLAYER_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*人");
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final UserService userService;
    private final AssistantConfigService assistantConfigService;
    private final AssistantConversationService assistantConversationService;
    private final AssistantKnowledgeService assistantKnowledgeService;
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

        QueryIntent intent = classifyIntent(request.message(), config.safety().blockedKeywords());
        return new PreparedAsk(
                openid,
                session,
                config,
                traceId,
                intent,
                assistantCacheService.buildConfigVersion(config),
                System.currentTimeMillis()
        );
    }

    private AskExecution executeAsk(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        String failureType = null;
        ExecutionMetrics metrics = ExecutionMetrics.sync(prepared.startAtMs());
        AssistantDtos.AssistantAskResponse response;
        try {
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
        AssistantKnowledgeService.KnowledgeSearchResult searchResult = assistantKnowledgeService.searchPublishedKnowledgeResult(request.message());
        List<AssistantKnowledgeService.KnowledgeHit> hits = searchResult.hits();
        metrics.setRetrievalMs(searchResult.retrievalMs());
        metrics.setEmbeddingMs(searchResult.embeddingMs());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        List<AssistantDtos.AssistantCitation> citations;
        List<String> suggestedQuestions;
        StreamedAnswer streamedAnswer;
        AssistantSearchService.SearchResult webSearchResult = searchWebIfEligible(prepared, request.message(), hits, metrics);

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(assistantProperties.getTopK())
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
            if (hasWebAnswer(webSearchResult)) {
                GeneratedAnswer knowledgeAnswer = synthesizeKnowledgeMarkdown(prepared, request.message(), hits);
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
                streamedAnswer = emitStaticAnswer(
                        mergeKnowledgeAndWebAnswer(knowledgeAnswer.answer(), webSearchResult),
                        emitter,
                        metrics
                );
            } else {
                streamedAnswer = streamPromptAnswer(
                        buildKnowledgePrompt(prepared, request.message(), hits),
                        fallbackKnowledgeAnswer(hits),
                        emitter,
                        metrics
                );
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
                streamedAnswer = emitStaticAnswer(buildWebMarkdownAnswer(webSearchResult.answer(), citations), emitter, metrics);
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
        AssistantKnowledgeService.KnowledgeSearchResult searchResult = assistantKnowledgeService.searchPublishedKnowledgeResult(request.message());
        List<AssistantKnowledgeService.KnowledgeHit> hits = searchResult.hits();
        metrics.setRetrievalMs(searchResult.retrievalMs());
        metrics.setEmbeddingMs(searchResult.embeddingMs());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        String answer;
        List<AssistantDtos.AssistantCitation> citations;
        List<AssistantDtos.RecommendedBoardCard> boards = List.of();
        List<String> suggestedQuestions;
        AssistantSearchService.SearchResult webSearchResult = searchWebIfEligible(prepared, request.message(), hits, metrics);

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(assistantProperties.getTopK())
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            GeneratedAnswer generatedAnswer = synthesizeKnowledgeMarkdown(prepared, request.message(), hits);
            metrics.setModelMs(generatedAnswer.modelMs());
            metrics.setFallbackModeIfBlank(generatedAnswer.fallbackMode());
            answer = generatedAnswer.answer();
            suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
            if (hasWebAnswer(webSearchResult)) {
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                citations = mergeCitations(citations, webSearchResult.citations());
                answer = mergeKnowledgeAndWebAnswer(answer, webSearchResult);
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

    private boolean shouldUseWebSearch(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        if (!hits.isEmpty() || !prepared.config().search().webSearchEnabled() || !assistantProperties.isWebSearchEnabled()) {
            return false;
        }
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return normalized.contains("联网")
                || normalized.contains("最新")
                || normalized.contains("今天")
                || normalized.contains("近期")
                || normalized.contains("版本")
                || normalized.contains("更新")
                || normalized.contains("新闻")
                || normalized.contains("赛事");
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
            return true;
        }
        return isTimeSensitiveQuery(query);
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
        AssistantSearchService.SearchResult searchResult = assistantSearchService.searchWeb(
                query,
                prepared.config().base().chatModel(),
                prepared.config().base().temperature()
        );
        metrics.setWebSearchMs(System.currentTimeMillis() - webSearchStartAt);
        return searchResult;
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
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean blocked = blockedKeywords.stream().filter(keyword -> keyword != null && !keyword.isBlank()).anyMatch(normalized::contains);
        if (blocked) {
            return QueryIntent.UNSUPPORTED;
        }
        if (normalized.contains("推荐") || normalized.contains("板子") || normalized.contains("适合") || PLAYER_COUNT_PATTERN.matcher(normalized).find()) {
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
        StringBuilder context = new StringBuilder();
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            context.append("来源类型：").append(describeKnowledgeSource(hit.sourceType())).append('\n')
                    .append("来源：").append(hit.title()).append('\n')
                    .append(hit.snippet()).append("\n\n");
        }

        if (chatModel == null) {
            return new GeneratedAnswer(fallbackKnowledgeAnswer(hits), "NO_MODEL", 0L);
        }

        List<String> recentMessages = assistantConversationService.recentContextMessages(prepared.openid(), prepared.session().getSessionId());
        String history = recentMessages.isEmpty() ? "无" : String.join("\n", recentMessages);

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(prepared.config().prompt().systemPrompt() + """

                                请使用简洁 Markdown 回答，并遵守：
                                1. 优先输出 `## 结论`
                                2. 如需拆解规则，再输出 `### 规则拆解`
                                3. 如需给操作建议，再输出 `### 你可以怎么做`
                                4. 只能依据给定资料，不要编造
                                5. 如果资料里同时有“上传知识库文档”和“结构化板子/角色资料”，优先采用上传知识库文档的说法，结构化资料只作为补充
                                6. 不要把原始字段整段照抄成“板子名称/人数/难度/标签”的数据清单，除非用户明确要基础资料卡
                                7. 句子尽量短，优先用项目符号
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
                return new GeneratedAnswer(fallbackKnowledgeAnswer(hits), "MODEL_EMPTY", modelMs);
            }
            return new GeneratedAnswer(normalizeMarkdown(content), "NONE", modelMs);
        } catch (Exception exception) {
            return new GeneratedAnswer(fallbackKnowledgeAnswer(hits), "MODEL_EXCEPTION", System.currentTimeMillis() - modelStartAt);
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
        StringBuilder context = new StringBuilder();
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            context.append("来源类型: ").append(describeKnowledgeSource(hit.sourceType())).append('\n')
                    .append("来源: ").append(hit.title()).append('\n')
                    .append(hit.snippet()).append("\n\n");
        }

        List<String> recentMessages = assistantConversationService.recentContextMessages(prepared.openid(), prepared.session().getSessionId());
        String history = recentMessages.isEmpty() ? "无" : String.join("\n", recentMessages);

        return new Prompt(
                List.of(
                        new SystemMessage(prepared.config().prompt().systemPrompt() + """

                                请使用简洁 Markdown 回答，并遵守：
                                1. 优先输出 `## 结论`
                                2. 如需拆解规则，再输出 `### 规则拆解`
                                3. 如需给操作建议，再输出 `### 你可以怎么做`
                                4. 只能依据给定资料，不要编造
                                5. 如果资料里同时有“上传知识库文档”和“结构化板子/角色资料”，优先采用上传知识库文档的说法，结构化资料只作为补充
                                6. 不要把原始字段整段照抄成“板子名称/人数/难度/标签”的数据清单，除非用户明确要基础资料卡
                                7. 句子尽量短，优先用项目符号
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
        if (currentAnswer != null && !currentAnswer.isEmpty() && chunkText.startsWith(currentAnswer)) {
            return chunkText.substring(currentAnswer.length());
        }
        return chunkText;
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

    private String fallbackKnowledgeAnswer(List<AssistantKnowledgeService.KnowledgeHit> hits) {
        AssistantKnowledgeService.KnowledgeHit first = hits.stream()
                .filter(hit -> AssistantConstants.SOURCE_DOCUMENT.equals(hit.sourceType()))
                .findFirst()
                .orElseGet(hits::getFirst);
        String sourceHint = AssistantConstants.SOURCE_DOCUMENT.equals(first.sourceType())
                ? "这条回答优先依据你上传并已发布的知识库文档"
                : "这条回答优先依据站内已发布知识整理";
        return """
                ## 结论

                %s

                ### 依据
                - 来源：**%s**
                - %s
                """.formatted(first.snippet(), first.title(), sourceHint).trim();
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
        return quickQuestions.stream()
                .filter(item -> !item.equalsIgnoreCase(currentQuestion))
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
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
        normalized = MULTI_BLANK_LINES.matcher(normalized).replaceAll("\n\n");
        if (normalized.startsWith("#") || normalized.startsWith("-") || normalized.startsWith(">") || normalized.matches("^\\d+\\..*")) {
            return normalized;
        }
        return "## 回答\n\n" + normalized;
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

    private record PreparedAsk(
            String openid,
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            QueryIntent intent,
            String cacheVersion,
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

        String streamMode() {
            return streamMode == null || streamMode.isBlank() ? "STREAM" : streamMode;
        }

        void setStreamMode(String streamMode) {
            this.streamMode = streamMode;
        }
    }
}
