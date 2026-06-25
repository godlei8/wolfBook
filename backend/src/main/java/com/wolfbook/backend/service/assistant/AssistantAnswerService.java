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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final DeepSeekChatService deepSeekChatService;
    private final VolcengineWebSearchService volcengineWebSearchService;
    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final BoardService boardService;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AssistantAnswerService(
            UserService userService,
            AssistantConfigService assistantConfigService,
            AssistantConversationService assistantConversationService,
            AssistantKnowledgeService assistantKnowledgeService,
            DeepSeekChatService deepSeekChatService,
            VolcengineWebSearchService volcengineWebSearchService,
            AssistantQueryLogMapper assistantQueryLogMapper,
            BoardService boardService,
            AssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.assistantConfigService = assistantConfigService;
        this.assistantConversationService = assistantConversationService;
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.deepSeekChatService = deepSeekChatService;
        this.volcengineWebSearchService = volcengineWebSearchService;
        this.assistantQueryLogMapper = assistantQueryLogMapper;
        this.boardService = boardService;
        this.assistantProperties = assistantProperties;
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
        AssistantDtos.AdminAiConfig config = assistantConfigService.getRuntimeConfig();
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
        return new PreparedAsk(openid, session, config, traceId, intent, System.currentTimeMillis());
    }

    private AskExecution executeAsk(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        String failureType = null;
        AssistantDtos.AssistantAskResponse response;
        try {
            response = switch (prepared.intent()) {
                case BOARD_RECOMMENDATION -> answerBoardRecommendation(prepared, request);
                case UNSUPPORTED -> refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().safety().unsupportedMessage());
                case KNOWLEDGE -> answerKnowledge(prepared, request);
            };
        } catch (Exception exception) {
            failureType = exception.getClass().getSimpleName();
            response = refusal(
                    prepared.session(),
                    prepared.config(),
                    prepared.traceId(),
                    "当前助手暂时没法稳定回答这个问题，你可以换个问法，或继续问我规则、角色和板子推荐。"
            );
        }

        long latencyMs = System.currentTimeMillis() - prepared.startAtMs();
        writeLog(prepared.openid(), prepared.session().getSessionId(), request.message(), response, latencyMs, failureType);
        return new AskExecution(response, latencyMs, failureType);
    }

    private void streamAsk(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request, SseEmitter emitter) {
        try {
            emit(emitter, "started", new AssistantDtos.AssistantStreamStarted(
                    prepared.session().getSessionId(),
                    prepared.traceId(),
                    AssistantConstants.CONTENT_MARKDOWN
            ));

            StreamExecution execution = switch (prepared.intent()) {
                case BOARD_RECOMMENDATION -> streamBoardRecommendation(prepared, request, emitter);
                case UNSUPPORTED -> streamUnsupported(prepared, emitter);
                case KNOWLEDGE -> streamKnowledge(prepared, request, emitter);
            };
            long latencyMs = System.currentTimeMillis() - prepared.startAtMs();
            writeLog(
                    prepared.openid(),
                    prepared.session().getSessionId(),
                    request.message(),
                    execution.response(),
                    latencyMs,
                    execution.failureType()
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
            SseEmitter emitter
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
                prepared.config().provider(),
                prepared.config().base().temperature(),
                fallbackAnswer,
                emitter
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

    private StreamExecution streamUnsupported(PreparedAsk prepared, SseEmitter emitter) throws IOException {
        String markdown = buildMinimalRefusalMarkdown(prepared.config().safety().unsupportedMessage());
        List<String> suggestedQuestions = prepared.config().base().quickQuestions().stream()
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
        StreamedAnswer streamedAnswer = emitStaticAnswer(markdown, emitter);
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
            SseEmitter emitter
    ) throws IOException {
        List<AssistantKnowledgeService.KnowledgeHit> hits = assistantKnowledgeService.searchPublishedKnowledge(request.message());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        List<AssistantDtos.AssistantCitation> citations;
        List<String> suggestedQuestions;
        StreamedAnswer streamedAnswer;

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(assistantProperties.getTopK())
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
            streamedAnswer = streamPromptAnswer(
                    buildKnowledgePrompt(prepared, request.message(), hits),
                    prepared.config().provider(),
                    prepared.config().base().temperature(),
                    fallbackKnowledgeAnswer(hits),
                    emitter
            );
        } else if (shouldUseWebSearch(prepared.config())) {
            VolcengineWebSearchService.SearchResult searchResult = volcengineWebSearchService.search(request.message());
            if (searchResult.hasAnswer()) {
                citations = searchResult.citations();
                suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
                answerType = AssistantConstants.ANSWER_WEB;
                usedWebSearch = true;
                streamedAnswer = emitStaticAnswer(buildMinimalWebMarkdownAnswer(searchResult.answer()), emitter, searchResult.failureType());
            } else {
                citations = List.of();
                suggestedQuestions = prepared.config().base().quickQuestions().stream()
                        .limit(assistantProperties.getMaxSuggestions())
                        .toList();
                answerType = AssistantConstants.ANSWER_REFUSAL;
                streamedAnswer = emitStaticAnswer(buildMinimalRefusalMarkdown(prepared.config().prompt().refusalPrompt()), emitter, searchResult.failureType());
            }
        } else {
            citations = List.of();
            suggestedQuestions = prepared.config().base().quickQuestions().stream()
                    .limit(assistantProperties.getMaxSuggestions())
                    .toList();
            answerType = AssistantConstants.ANSWER_REFUSAL;
            streamedAnswer = emitStaticAnswer(buildMinimalRefusalMarkdown(prepared.config().prompt().refusalPrompt()), emitter);
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

    private AssistantDtos.AssistantAskResponse answerBoardRecommendation(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        List<AssistantDtos.RecommendedBoardCard> boards = recommendBoards(request.message());
        String answer = synthesizeBoardRecommendationMarkdown(request.message(), boards, prepared.config());
        List<AssistantDtos.AssistantCitation> citations = List.of();
        List<String> suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                prepared.session().getSessionId(),
                answer,
                AssistantConstants.ANSWER_STRUCTURED,
                citations,
                boards,
                suggestedQuestions,
                false,
                prepared.traceId()
        );
        return new AssistantDtos.AssistantAskResponse(
                prepared.session().getSessionId(),
                saved.getId(),
                answer,
                AssistantConstants.CONTENT_MARKDOWN,
                AssistantConstants.ANSWER_STRUCTURED,
                prepared.traceId()
        );
    }

    private AssistantDtos.AssistantAskResponse answerKnowledge(PreparedAsk prepared, AssistantDtos.AssistantAskRequest request) {
        List<AssistantKnowledgeService.KnowledgeHit> hits = assistantKnowledgeService.searchPublishedKnowledge(request.message());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        String answer;
        List<AssistantDtos.AssistantCitation> citations;
        List<AssistantDtos.RecommendedBoardCard> boards = List.of();
        List<String> suggestedQuestions;

        if (!hits.isEmpty()) {
            citations = hits.stream()
                    .limit(assistantProperties.getTopK())
                    .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                    .toList();
            answer = synthesizeKnowledgeMarkdown(prepared, request.message(), hits);
            suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
        } else if (shouldUseWebSearch(prepared.config())) {
            VolcengineWebSearchService.SearchResult searchResult = volcengineWebSearchService.search(request.message());
            if (!searchResult.hasAnswer()) {
                return refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().prompt().refusalPrompt());
            }
            citations = searchResult.citations();
            answerType = AssistantConstants.ANSWER_WEB;
            usedWebSearch = true;
            answer = buildMinimalWebMarkdownAnswer(searchResult.answer());
            suggestedQuestions = nextQuestions(prepared.config().base().quickQuestions(), request.message());
        } else {
            return refusal(prepared.session(), prepared.config(), prepared.traceId(), prepared.config().prompt().refusalPrompt());
        }

        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                prepared.session().getSessionId(),
                answer,
                answerType,
                citations,
                boards,
                suggestedQuestions,
                usedWebSearch,
                prepared.traceId()
        );
        return new AssistantDtos.AssistantAskResponse(
                prepared.session().getSessionId(),
                saved.getId(),
                answer,
                AssistantConstants.CONTENT_MARKDOWN,
                answerType,
                prepared.traceId()
        );
    }

    private AssistantDtos.AssistantAskResponse refusal(
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            String message
    ) {
        String markdown = buildMinimalRefusalMarkdown(message);
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
                traceId
        );
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

    private String synthesizeBoardRecommendationMarkdown(
            String query,
            List<AssistantDtos.RecommendedBoardCard> boards,
            AssistantDtos.AdminAiConfig config
    ) {
        if (boards.isEmpty()) {
            return """
                    ## 暂时没找到完全匹配的板子

                    - 你可以补充 **人数**、**难度** 或 **玩法偏好**
                    - 例如：`12人 进阶 娱乐板子推荐`
                    - 如果愿意，我也可以直接按“入门 / 进阶 / 烧脑”重新给你筛一轮
                    """;
        }

        String content = deepSeekChatService.complete(
                buildBoardRecommendationPrompt(query, boards, config),
                config.provider(),
                config.base().temperature()
        );
        return content == null || content.isBlank() ? fallbackBoardAnswer(boards) : normalizeMarkdown(content);
    }

    private String synthesizeKnowledgeMarkdown(PreparedAsk prepared, String query, List<AssistantKnowledgeService.KnowledgeHit> hits) {
        String content = deepSeekChatService.complete(
                buildKnowledgePrompt(prepared, query, hits),
                prepared.config().provider(),
                prepared.config().base().temperature()
        );
        return content == null || content.isBlank() ? fallbackKnowledgeAnswer(hits) : normalizeMarkdown(content);
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
                )
        );
    }

    private Prompt buildKnowledgePrompt(
            PreparedAsk prepared,
            String query,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        StringBuilder context = new StringBuilder();
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            context.append("来源: ").append(hit.title()).append('\n')
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
                                5. 句子尽量短，优先用项目符号
                                """),
                        new UserMessage("""
                                当前问题：
                                %s

                                最近上下文：
                                %s

                                站内资料：
                                %s
                                """.formatted(query, history, context))
                )
        );
    }

    private StreamedAnswer streamPromptAnswer(
            Prompt prompt,
            AssistantDtos.ProviderSection provider,
            Double temperature,
            String fallbackAnswer,
            SseEmitter emitter
    ) throws IOException {
        if (prompt == null) {
            return emitStaticAnswer(fallbackAnswer, emitter);
        }

        DeepSeekChatService.StreamResult result = deepSeekChatService.stream(
                prompt,
                provider,
                temperature,
                delta -> {
                    try {
                        emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(delta));
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                }
        );

        if (result.answer() == null || result.answer().isBlank()) {
            return emitStaticAnswer(fallbackAnswer, emitter, result.failureType());
        }

        if (result.failureType() != null) {
            String degradedTail = "\n\n> 当前回答在生成过程中中断，以下是已生成内容。";
            emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(degradedTail));
            return new StreamedAnswer(normalizeMarkdown(result.answer() + degradedTail), result.failureType());
        }

        return new StreamedAnswer(normalizeMarkdown(result.answer()), null);
    }

    private StreamedAnswer emitStaticAnswer(String markdown, SseEmitter emitter) throws IOException {
        return emitStaticAnswer(markdown, emitter, null);
    }

    private StreamedAnswer emitStaticAnswer(String markdown, SseEmitter emitter, String failureType) throws IOException {
        String normalized = normalizeMarkdown(markdown);
        for (String chunk : splitMarkdownForStream(normalized)) {
            emit(emitter, "delta", new AssistantDtos.AssistantStreamDelta(chunk));
        }
        return new StreamedAnswer(normalized, failureType);
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
        AssistantKnowledgeService.KnowledgeHit first = hits.getFirst();
        return """
                ## 结论

                %s

                ### 依据
                - 来源：**%s**
                - 这条回答优先依据站内已发布知识整理
                """.formatted(first.snippet(), first.title()).trim();
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

    private String buildMinimalWebMarkdownAnswer(String answer) {
        return normalizeMarkdown(answer);
    }

    private String buildMinimalRefusalMarkdown(String message) {
        String body = (message == null || message.isBlank())
                ? "褰撳墠闂瓒呭嚭浜嗙嫾浜烘潃鐭ヨ瘑鍔╂墜鐨勮兘鍔涜竟鐣屻€?"
                : message.trim();
        return """
                ## 褰撳墠杩欓鎴戝厛涓嶄贡绛?

                %s
                """.formatted(body).trim();
    }

    private List<String> nextQuestions(List<String> quickQuestions, String currentQuestion) {
        return quickQuestions.stream()
                .filter(item -> !item.equalsIgnoreCase(currentQuestion))
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
    }

    private boolean shouldUseWebSearch(AssistantDtos.AdminAiConfig config) {
        return config.search().webSearchEnabled()
                && assistantProperties.isWebSearchEnabled()
                && volcengineWebSearchService.isAvailable();
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

    private void writeLog(String openid, String sessionId, String userMessage, AssistantDtos.AssistantAskResponse response, long latencyMs, String failureType) {
        AssistantQueryLogEntity entity = new AssistantQueryLogEntity();
        entity.setOpenid(openid);
        entity.setSessionId(sessionId);
        entity.setUserMessage(userMessage);
        entity.setAnswerType(response.answerType());
        entity.setHitSources("[]");
        entity.setUsedWebSearch(AssistantConstants.ANSWER_WEB.equals(response.answerType()) ? 1 : 0);
        entity.setLatencyMs(latencyMs);
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

    private record BoardCandidate(Board board, int score, String reason) {
    }
}
