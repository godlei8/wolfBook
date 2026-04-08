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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssistantAnswerService {

    private static final Pattern PLAYER_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*人");

    private final UserService userService;
    private final AssistantConfigService assistantConfigService;
    private final AssistantConversationService assistantConversationService;
    private final AssistantKnowledgeService assistantKnowledgeService;
    private final AssistantSearchService assistantSearchService;
    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final BoardService boardService;
    private final AssistantProperties assistantProperties;
    private final ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider;
    private final ObjectMapper objectMapper;

    public AssistantAnswerService(
            UserService userService,
            AssistantConfigService assistantConfigService,
            AssistantConversationService assistantConversationService,
            AssistantKnowledgeService assistantKnowledgeService,
            AssistantSearchService assistantSearchService,
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
        long start = System.currentTimeMillis();
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
        AssistantDtos.AssistantAskResponse response;
        String failureType = null;
        try {
            response = switch (intent) {
                case BOARD_RECOMMENDATION -> answerBoardRecommendation(session, request, config, traceId);
                case UNSUPPORTED -> refusal(session, config, traceId, config.safety().unsupportedMessage());
                case KNOWLEDGE -> answerKnowledge(session, request, config, traceId);
            };
        } catch (Exception exception) {
            failureType = exception.getClass().getSimpleName();
            response = refusal(session, config, traceId, "当前助手暂时没有拿稳这个问题的答案，你可以换个问法，或问我规则和板子推荐。");
        }

        writeLog(openid, session.getSessionId(), request.message(), response, System.currentTimeMillis() - start, failureType);
        return response;
    }

    private AssistantDtos.AssistantAskResponse answerBoardRecommendation(
            AssistantSessionEntity session,
            AssistantDtos.AssistantAskRequest request,
            AssistantDtos.AdminAiConfig config,
            String traceId
    ) {
        List<AssistantDtos.RecommendedBoardCard> boards = recommendBoards(request.message());
        String answer = synthesizeBoardRecommendation(request.message(), boards, config);
        List<AssistantDtos.AssistantCitation> citations = List.of();
        List<String> suggestedQuestions = nextQuestions(config.base().quickQuestions(), request.message());
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                session.getSessionId(),
                answer,
                AssistantConstants.ANSWER_STRUCTURED,
                citations,
                boards,
                suggestedQuestions,
                false,
                traceId
        );
        return new AssistantDtos.AssistantAskResponse(
                session.getSessionId(),
                saved.getId(),
                answer,
                AssistantConstants.ANSWER_STRUCTURED,
                citations,
                boards,
                suggestedQuestions,
                false,
                traceId
        );
    }

    private AssistantDtos.AssistantAskResponse answerKnowledge(
            AssistantSessionEntity session,
            AssistantDtos.AssistantAskRequest request,
            AssistantDtos.AdminAiConfig config,
            String traceId
    ) {
        List<AssistantKnowledgeService.KnowledgeHit> hits = assistantKnowledgeService.searchPublishedKnowledge(request.message());
        boolean usedWebSearch = false;
        String answerType = AssistantConstants.ANSWER_RAG;
        String answer;
        List<AssistantDtos.AssistantCitation> citations;
        List<AssistantDtos.RecommendedBoardCard> boards = List.of();
        List<String> suggestedQuestions;

        if (!hits.isEmpty()) {
            citations = hits.stream().limit(assistantProperties.getTopK()).map(AssistantKnowledgeService.KnowledgeHit::toCitation).toList();
            answer = synthesizeKnowledgeAnswer(request.message(), hits, config);
            suggestedQuestions = nextQuestions(config.base().quickQuestions(), request.message());
        } else if (config.search().webSearchEnabled() && assistantProperties.isWebSearchEnabled()) {
            AssistantSearchService.SearchResult searchResult = assistantSearchService.searchWeb(
                    request.message(),
                    config.base().chatModel(),
                    config.base().temperature()
            );
            if (searchResult.answer() != null && !searchResult.answer().isBlank()) {
                usedWebSearch = true;
                answerType = AssistantConstants.ANSWER_WEB;
                answer = searchResult.answer();
                citations = searchResult.citations();
                suggestedQuestions = searchResult.suggestedQuestions().isEmpty()
                        ? nextQuestions(config.base().quickQuestions(), request.message())
                        : searchResult.suggestedQuestions().stream().limit(assistantProperties.getMaxSuggestions()).toList();
            } else {
                return refusal(session, config, traceId, config.prompt().refusalPrompt());
            }
        } else {
            return refusal(session, config, traceId, config.prompt().refusalPrompt());
        }

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
                answerType,
                citations,
                boards,
                suggestedQuestions,
                usedWebSearch,
                traceId
        );
    }

    private AssistantDtos.AssistantAskResponse refusal(
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            String message
    ) {
        List<String> suggestedQuestions = config.base().quickQuestions().stream().limit(assistantProperties.getMaxSuggestions()).toList();
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                session.getSessionId(),
                message,
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
                message,
                AssistantConstants.ANSWER_REFUSAL,
                List.of(),
                List.of(),
                suggestedQuestions,
                false,
                traceId
        );
    }

    private QueryIntent classifyIntent(String message, List<String> blockedKeywords) {
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean blocked = blockedKeywords.stream().anyMatch(normalized::contains);
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
                .filter(candidate -> candidate.score > 0 || playerCount == null && difficulty == null && tagHints.isEmpty())
                .sorted(Comparator.comparingInt(BoardCandidate::score).reversed().thenComparing(candidate -> candidate.board.name()))
                .limit(3)
                .map(candidate -> new AssistantDtos.RecommendedBoardCard(
                        candidate.board.id(),
                        candidate.board.name(),
                        candidate.board.playerCount(),
                        candidate.board.difficulty(),
                        candidate.board.tags(),
                        candidate.board.coverImage(),
                        candidate.reason
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

    private String synthesizeBoardRecommendation(String query, List<AssistantDtos.RecommendedBoardCard> boards, AssistantDtos.AdminAiConfig config) {
        if (boards.isEmpty()) {
            return "我暂时没有在站内找到特别贴合这个条件的板子，你可以换成人数、难度或玩法风格再问我一次。";
        }
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackBoardAnswer(boards);
        }
        StringBuilder context = new StringBuilder();
        for (AssistantDtos.RecommendedBoardCard board : boards) {
            context.append(board.name()).append(" / ")
                    .append(board.playerCount()).append("人 / ")
                    .append(board.difficulty()).append(" / ")
                    .append(board.reason()).append('\n');
        }
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(config.prompt().recommendationPrompt()),
                        new UserMessage("用户问题：" + query + "\n候选板子：\n" + context + "\n请生成简洁推荐。")
                ),
                MiniMaxChatOptions.builder()
                        .model(config.base().chatModel())
                        .temperature(config.base().temperature())
                        .build()
        );
        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            return content == null || content.isBlank() ? fallbackBoardAnswer(boards) : content;
        } catch (Exception exception) {
            return fallbackBoardAnswer(boards);
        }
    }

    private String synthesizeKnowledgeAnswer(String query, List<AssistantKnowledgeService.KnowledgeHit> hits, AssistantDtos.AdminAiConfig config) {
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        StringBuilder context = new StringBuilder();
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            context.append("来源：").append(hit.title()).append('\n')
                    .append(hit.snippet()).append("\n\n");
        }
        if (chatModel == null) {
            return fallbackKnowledgeAnswer(hits);
        }
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(config.prompt().systemPrompt()),
                        new UserMessage("问题：" + query + "\n站内资料：\n" + context + "请仅依据资料回答，不能编造。")
                ),
                MiniMaxChatOptions.builder()
                        .model(config.base().chatModel())
                        .temperature(config.base().temperature())
                        .build()
        );
        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            return content == null || content.isBlank() ? fallbackKnowledgeAnswer(hits) : content;
        } catch (Exception exception) {
            return fallbackKnowledgeAnswer(hits);
        }
    }

    private String fallbackBoardAnswer(List<AssistantDtos.RecommendedBoardCard> boards) {
        StringBuilder builder = new StringBuilder("我优先从站内板子里帮你挑了这几种：");
        for (AssistantDtos.RecommendedBoardCard board : boards) {
            builder.append('\n')
                    .append(board.name())
                    .append("（")
                    .append(board.playerCount())
                    .append("人 / ")
                    .append(board.difficulty())
                    .append("）：")
                    .append(board.reason());
        }
        return builder.toString();
    }

    private String fallbackKnowledgeAnswer(List<AssistantKnowledgeService.KnowledgeHit> hits) {
        AssistantKnowledgeService.KnowledgeHit first = hits.getFirst();
        return "根据站内已发布资料，" + first.snippet();
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

    private void writeLog(String openid, String sessionId, String userMessage, AssistantDtos.AssistantAskResponse response, long latencyMs, String failureType) {
        AssistantQueryLogEntity entity = new AssistantQueryLogEntity();
        entity.setOpenid(openid);
        entity.setSessionId(sessionId);
        entity.setUserMessage(userMessage);
        entity.setAnswerType(response.answerType());
        entity.setHitSources(write(response.citations().stream().map(citation -> citation.sourceType() + ":" + citation.title()).toList()));
        entity.setUsedWebSearch(response.usedWebSearch() ? 1 : 0);
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

    private enum QueryIntent {
        BOARD_RECOMMENDATION,
        KNOWLEDGE,
        UNSUPPORTED
    }

    private record BoardCandidate(Board board, int score, String reason) {
    }
}
