package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.BoardService;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 回答一个问题的完整流程：判断问题类型 → 查资料（板子库 / 站内知识 / 联网）→ 让模型生成。
 * <p>
 * 普通接口、流式接口、后台调试台都走这一条；以前普通和流式各写了一套，改一边忘一边。
 * 过程通过 {@link AnswerSink} 往外报：stage 是「正在查站内资料」这类进度，delta 是生成的文字。
 */
@Service
public class AssistantAnswerPipeline {

    /** 回答过程的输出口。流式接口往 SSE 写，普通接口和调试台什么都不做。 */
    public interface AnswerSink {
        void stage(String key, String label);

        void delta(String text);

        AnswerSink NONE = new AnswerSink() {
            @Override
            public void stage(String key, String label) {
            }

            @Override
            public void delta(String text) {
            }
        };
    }

    /**
     * @param intent  RECOMMEND / KNOWLEDGE / REFUSE
     * @param reason  为什么这么判断，后台调试台展示
     * @param hits    检索命中的资料，写进日志的 hit_sources
     * @param timings 各步耗时：route、retrieve、generate
     */
    public record Outcome(
            String answer,
            String answerType,
            List<AssistantDtos.AssistantCitation> citations,
            List<AssistantDtos.RecommendedBoardCard> boards,
            boolean usedWebSearch,
            String failureType,
            String intent,
            String reason,
            List<AssistantDtos.AdminPlaygroundHit> hits,
            Map<String, Long> timings
    ) {
    }

    static final String NO_MODEL_FAILURE = "NoModelKey";

    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern FOLLOW_UP = Pattern.compile("第[一二三1-3]个|它|这个|那个|上面|刚才|具体|详细|展开");
    private static final int MAX_MATERIALS = 6;

    private final AssistantKnowledgeService knowledgeService;
    private final DeepSeekChatService chatService;
    private final VolcengineWebSearchService webSearchService;
    private final BoardService boardService;
    private final RoleMapper roleMapper;
    private final AssistantProperties assistantProperties;

    public AssistantAnswerPipeline(
            AssistantKnowledgeService knowledgeService,
            DeepSeekChatService chatService,
            VolcengineWebSearchService webSearchService,
            BoardService boardService,
            RoleMapper roleMapper,
            AssistantProperties assistantProperties
    ) {
        this.knowledgeService = knowledgeService;
        this.chatService = chatService;
        this.webSearchService = webSearchService;
        this.boardService = boardService;
        this.roleMapper = roleMapper;
        this.assistantProperties = assistantProperties;
    }

    public Outcome run(
            AssistantDtos.AdminAiConfig config,
            String message,
            Map<String, Object> pageContext,
            List<AssistantConversationService.Turn> history,
            AnswerSink sink
    ) {
        Run run = new Run(config, message, AssistantPageContext.from(pageContext), history, sink);
        sink.stage("understand", "正在理解问题");
        run.boards = boardService.listAllBoards();
        run.roleNames = roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId)).stream()
                .collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName, (left, right) -> left, LinkedHashMap::new));
        AssistantIntentRouter.Decision decision = AssistantIntentRouter.route(
                message,
                config.safety().blockedKeywords(),
                run.boards.stream().map(Board::name).toList(),
                run.roleNames.values(),
                run.page.boardId(),
                run.page.roleId()
        );
        run.decision = decision;
        run.mark("route");

        return switch (decision.intent()) {
            case REFUSE -> refuse(run);
            case RECOMMEND -> recommend(run);
            case KNOWLEDGE -> knowledge(run);
        };
    }

    private Outcome refuse(Run run) {
        String text = StringUtils.hasText(run.config.safety().unsupportedMessage())
                ? run.config.safety().unsupportedMessage().trim()
                : "这个我不替你判断，不过规则、角色和板子的问题都可以问我。";
        String answer = text + "\n\n比如可以问：「女巫第一晚能自救吗？」";
        emitStatic(answer, run.sink);
        return run.outcome(answer, AssistantConstants.ANSWER_REFUSAL, List.of(), List.of(), false, null);
    }

    private Outcome recommend(Run run) {
        run.sink.stage("boards", "正在翻板子库");
        List<BoardRecommender.Candidate> candidates = BoardRecommender.recommend(run.message, run.boards, run.roleNames);
        List<AssistantDtos.RecommendedBoardCard> cards = candidates.stream()
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
        run.hits = candidates.stream()
                .map(candidate -> new AssistantDtos.AdminPlaygroundHit(candidate.board().name(), "BOARD", candidate.score()))
                .toList();
        run.mark("retrieve");

        if (candidates.isEmpty()) {
            String answer = "板子库里暂时没有上架的板子，等管理员上架后再来问我吧。";
            emitStatic(answer, run.sink);
            return run.outcome(answer, AssistantConstants.ANSWER_STRUCTURED, List.of(), List.of(), false, null);
        }

        List<AssistantPromptFactory.Material> materials = candidates.stream()
                .map(candidate -> new AssistantPromptFactory.Material(
                        candidate.board().name() + "（匹配：" + candidate.reason() + "）",
                        boardText(candidate.board())
                ))
                .toList();
        Prompt prompt = AssistantPromptFactory.recommendation(run.config, run.pageText(knowledgeService::structuredText), run.history, run.message, materials);
        Generated generated = generate(run, prompt, fallbackBoardAnswer(candidates));
        return run.outcome(generated.answer(), AssistantConstants.ANSWER_STRUCTURED, List.of(), cards, false, generated.failureType());
    }

    private Outcome knowledge(Run run) {
        run.sink.stage("search", "正在查站内资料");
        String pageText = run.pageText(knowledgeService::structuredText);
        Map<String, AssistantPromptFactory.Material> materials = new LinkedHashMap<>();

        // 1. 问题里点了名的板子 / 角色：直接拿全文，比检索可靠
        List<String> mentioned = mentionedKeys(run);
        // 2. 追问（「第一个具体怎么玩」）：问题里没点名，就用上一轮回答里提到的板子
        if (mentioned.isEmpty() && FOLLOW_UP.matcher(run.message).find()) {
            mentioned = boardsInLastAnswer(run);
        }
        for (String key : mentioned) {
            if (key.equals(run.page.pageKey())) continue;
            String text = knowledgeService.structuredText(key);
            if (StringUtils.hasText(text)) {
                materials.putIfAbsent(key, new AssistantPromptFactory.Material(titleOf(key, run), text));
            }
        }
        // 3. 站内知识检索（向量，失败时关键词）；在详情页里问的，带上板子 / 角色名一起搜
        String query = (run.page.searchHint() + " " + run.message).trim();
        List<AssistantKnowledgeService.KnowledgeHit> hits = knowledgeService.searchPublishedKnowledge(query);
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            String key = hit.document() == null ? hit.title() : hit.document().getSourceKey();
            if (key != null && key.equals(run.page.pageKey())) continue;
            materials.putIfAbsent(key, new AssistantPromptFactory.Material(hit.title(), hit.context()));
        }
        List<AssistantDtos.AdminPlaygroundHit> hitViews = new ArrayList<>();
        materials.forEach((key, material) -> hitViews.add(new AssistantDtos.AdminPlaygroundHit(
                material.title(),
                key.contains(":") ? key.substring(0, key.indexOf(':')) : "DOCUMENT",
                hits.stream().filter(hit -> hit.document() != null && key.equals(hit.document().getSourceKey()))
                        .mapToDouble(AssistantKnowledgeService.KnowledgeHit::score).findFirst().orElse(1)
        )));
        run.hits = hitViews;
        List<AssistantDtos.AssistantCitation> citations = hits.stream()
                .limit(assistantProperties.getTopK())
                .map(AssistantKnowledgeService.KnowledgeHit::toCitation)
                .toList();

        // 4. 站内什么都没有、又开了联网搜索：用联网结果
        if (materials.isEmpty() && pageText == null && webSearchEnabled(run.config)) {
            run.sink.stage("web", "站内没找到，正在联网搜索");
            VolcengineWebSearchService.SearchResult searchResult = webSearchService.search(run.message);
            run.mark("retrieve");
            if (searchResult.hasAnswer()) {
                String answer = normalizeMarkdown(searchResult.answer());
                emitStatic(answer, run.sink);
                return run.outcome(answer, AssistantConstants.ANSWER_WEB, searchResult.citations(), List.of(), true, searchResult.failureType());
            }
        } else {
            run.mark("retrieve");
        }

        List<AssistantPromptFactory.Material> materialList = materials.values().stream().limit(MAX_MATERIALS).toList();
        Prompt prompt = AssistantPromptFactory.knowledge(run.config, pageText, run.history, run.message, materialList);
        Generated generated = generate(run, prompt, fallbackKnowledgeAnswer(materialList));
        return run.outcome(generated.answer(), AssistantConstants.ANSWER_RAG, citations, List.of(), false, generated.failureType());
    }

    /** 问题里提到的板子和角色（"BOARD:1"、"ROLE:7"）。名字互相包含时只取长的：「白狼王」不再算上「狼王」。 */
    private List<String> mentionedKeys(Run run) {
        List<Map.Entry<String, String>> found = new ArrayList<>();
        for (Board board : run.boards) {
            if (StringUtils.hasText(board.name()) && run.message.contains(board.name())) {
                found.add(Map.entry(board.name(), "BOARD:" + board.id()));
            }
        }
        run.roleNames.forEach((id, name) -> {
            if (StringUtils.hasText(name) && name.length() >= 2 && run.message.contains(name)) {
                found.add(Map.entry(name, "ROLE:" + id));
            }
        });
        found.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : found) {
            if (names.stream().anyMatch(longer -> longer.contains(entry.getKey()))) continue;
            names.add(entry.getKey());
            keys.add(entry.getValue());
        }
        return keys.stream().limit(4).toList();
    }

    private List<String> boardsInLastAnswer(Run run) {
        String lastAnswer = null;
        for (AssistantConversationService.Turn turn : run.history) {
            if ("ASSISTANT".equals(turn.role())) lastAnswer = turn.content();
        }
        if (lastAnswer == null) {
            return List.of();
        }
        String answer = lastAnswer;
        return run.boards.stream()
                .filter(board -> StringUtils.hasText(board.name()) && answer.contains(board.name()))
                .sorted(Comparator.comparingInt(board -> answer.indexOf(board.name())))
                .limit(2)
                .map(board -> "BOARD:" + board.id())
                .toList();
    }

    private String titleOf(String key, Run run) {
        Integer id = Integer.valueOf(key.substring(key.indexOf(':') + 1));
        if (key.startsWith("BOARD:")) {
            return run.boards.stream().filter(board -> id.equals(board.id())).map(Board::name).findFirst().orElse(key);
        }
        return run.roleNames.getOrDefault(id, key);
    }

    private String boardText(Board board) {
        String text = knowledgeService.structuredText("BOARD:" + board.id());
        if (StringUtils.hasText(text)) {
            return text;
        }
        return "人数：" + board.playerCount() + "\n难度：" + board.difficulty()
                + "\n标签：" + (board.tags() == null ? "" : String.join("、", board.tags()))
                + "\n简介：" + (board.cardDescription() == null ? "" : board.cardDescription());
    }

    private Generated generate(Run run, Prompt prompt, String fallback) {
        run.sink.stage("compose", "正在组织回答");
        if (run.config.provider() == null || !StringUtils.hasText(run.config.provider().apiKey())) {
            emitStatic(fallback, run.sink);
            run.mark("generate");
            return new Generated(fallback, NO_MODEL_FAILURE);
        }
        DeepSeekChatService.StreamResult result = chatService.stream(prompt, run.config.provider(), run.config.base().temperature(), run.sink::delta);
        run.mark("generate");
        if (!StringUtils.hasText(result.answer())) {
            emitStatic(fallback, run.sink);
            return new Generated(fallback, result.failureType() == null ? "EmptyAnswer" : result.failureType());
        }
        if (result.failureType() != null) {
            String tail = "\n\n> 回答到一半断开了，上面是已经生成的部分，可以再问一次。";
            run.sink.delta(tail);
            return new Generated(normalizeMarkdown(result.answer() + tail), result.failureType());
        }
        return new Generated(normalizeMarkdown(result.answer()), null);
    }

    private String fallbackBoardAnswer(List<BoardRecommender.Candidate> candidates) {
        StringBuilder builder = new StringBuilder("AI 模型暂时没连上，先按条件从板子库里挑了这几个：\n\n");
        for (BoardRecommender.Candidate candidate : candidates) {
            Board board = candidate.board();
            builder.append("### ").append(board.name()).append('\n')
                    .append("- ").append(board.playerCount()).append(" 人 · ").append(board.difficulty()).append('\n')
                    .append("- ").append(candidate.reason()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String fallbackKnowledgeAnswer(List<AssistantPromptFactory.Material> materials) {
        if (materials.isEmpty()) {
            return "AI 模型暂时没连上，这题先答不了，稍后再问一次吧。";
        }
        AssistantPromptFactory.Material first = materials.getFirst();
        String text = first.text().strip();
        if (text.length() > 400) {
            text = text.substring(0, 400) + "…";
        }
        return "AI 模型暂时没连上，先把站内资料原文给你：\n\n**" + first.title() + "**\n\n" + text;
    }

    private boolean webSearchEnabled(AssistantDtos.AdminAiConfig config) {
        return config.search() != null
                && config.search().webSearchEnabled()
                && assistantProperties.isWebSearchEnabled()
                && webSearchService.isAvailable();
    }

    /** 不经过模型的回答（婉拒、兜底）也按段落分几次发，前端和模型回答走同一套显示。 */
    static void emitStatic(String markdown, AnswerSink sink) {
        StringBuilder current = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            current.append(line).append('\n');
            if (current.length() >= 48) {
                sink.delta(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            String rest = current.toString();
            sink.delta(rest.endsWith("\n") ? rest.substring(0, rest.length() - 1) : rest);
        }
    }

    static String normalizeMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return MULTI_BLANK_LINES.matcher(content.replace("\r\n", "\n").trim()).replaceAll("\n\n");
    }

    private record Generated(String answer, String failureType) {
    }

    /** 一次回答过程里的状态。 */
    private static final class Run {
        final AssistantDtos.AdminAiConfig config;
        final String message;
        final AssistantPageContext page;
        final List<AssistantConversationService.Turn> history;
        final AnswerSink sink;
        final Map<String, Long> timings = new LinkedHashMap<>();
        long lastMark = System.currentTimeMillis();
        List<Board> boards = List.of();
        Map<Integer, String> roleNames = Map.of();
        AssistantIntentRouter.Decision decision;
        List<AssistantDtos.AdminPlaygroundHit> hits = List.of();
        private String pageText;
        private boolean pageTextLoaded;

        Run(AssistantDtos.AdminAiConfig config, String message, AssistantPageContext page, List<AssistantConversationService.Turn> history, AnswerSink sink) {
            this.config = config;
            this.message = message == null ? "" : message.trim();
            this.page = page;
            this.history = history == null ? List.of() : history;
            this.sink = sink;
        }

        void mark(String step) {
            long now = System.currentTimeMillis();
            timings.merge(step, now - lastMark, Long::sum);
            lastMark = now;
        }

        String pageText(Function<String, String> structuredText) {
            if (!pageTextLoaded) {
                pageText = page.describe(structuredText);
                pageTextLoaded = true;
            }
            return pageText;
        }

        Outcome outcome(
                String answer,
                String answerType,
                List<AssistantDtos.AssistantCitation> citations,
                List<AssistantDtos.RecommendedBoardCard> boards,
                boolean usedWebSearch,
                String failureType
        ) {
            return new Outcome(answer, answerType, citations, boards, usedWebSearch, failureType,
                    decision == null ? null : decision.intent().name(),
                    decision == null ? null : decision.reason(),
                    hits, timings);
        }
    }
}
