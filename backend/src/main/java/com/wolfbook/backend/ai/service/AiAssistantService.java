package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.config.AiProperties;
import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.model.AiAnswerType;
import com.wolfbook.backend.ai.model.AiFailureReason;
import com.wolfbook.backend.ai.model.AiQueryPlan;
import com.wolfbook.backend.ai.model.AiRetrievalCandidate;
import com.wolfbook.backend.ai.model.AiRetrievalContext;
import com.wolfbook.backend.ai.model.AiServiceException;
import com.wolfbook.backend.ai.store.AiKnowledgeStore;
import com.wolfbook.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AiAssistantService {

    private static final List<String> DEFAULT_QUICK_QUESTIONS = List.of(
            "12人进阶有什么板子？",
            "女巫能不能自救？",
            "守卫和女巫会不会冲突？"
    );

    private final AiProperties properties;
    private final AiRuntimeSettings runtimeSettings;
    private final AiKnowledgeStore store;
    private final AiQueryPlanner queryPlanner;
    private final AiRrfFusion rrfFusion;
    private final MiniMaxGateway miniMaxGateway;

    public AiAssistantService(
            AiProperties properties,
            AiRuntimeSettings runtimeSettings,
            AiKnowledgeStore store,
            AiQueryPlanner queryPlanner,
            AiRrfFusion rrfFusion,
            MiniMaxGateway miniMaxGateway
    ) {
        this.properties = properties;
        this.runtimeSettings = runtimeSettings;
        this.store = store;
        this.queryPlanner = queryPlanner;
        this.rrfFusion = rrfFusion;
        this.miniMaxGateway = miniMaxGateway;
    }

    public AiDtos.AiBootstrapResponse bootstrap() {
        hydrateRuntimeSettings();
        boolean postgresReady = store.ready();
        boolean chatReady = miniMaxGateway.chatReady();
        boolean webSearchReady = runtimeSettings.webSearchEnabled()
                && StringUtils.hasText(properties.getWebSearch().getMcpApiKey())
                && chatReady;
        String mode = resolveMode(runtimeSettings.knowledgeBaseEnabled(), webSearchReady);
        String reason = null;
        if (!runtimeSettings.enabled()) {
            reason = "AI 助手已在后台关闭。";
        } else if (!chatReady) {
            reason = "MiniMax 聊天 key 未配置。";
        } else if (runtimeSettings.knowledgeBaseEnabled() && !postgresReady) {
            reason = "知识库已启用，但 PostgreSQL/pgvector 还没有就绪。";
        } else if (!runtimeSettings.knowledgeBaseEnabled() && !webSearchReady) {
            reason = "知识库已关闭，但联网搜索暂时不可用。";
        }
        return new AiDtos.AiBootstrapResponse(
                runtimeSettings.enabled(),
                runtimeSettings.knowledgeBaseEnabled(),
                webSearchReady,
                postgresReady,
                chatReady,
                mode,
                reason,
                DEFAULT_QUICK_QUESTIONS
        );
    }

    public AiDtos.AiAskResponse ask(String openid, AiDtos.AiAskRequest request) {
        AskTurn turn = prepareTurn(openid, request);
        try {
            String answer = generateAnswer(turn);
            return completeTurn(turn, turn.answerType(), answer, null);
        } catch (ApiException exception) {
            return completeTurn(turn, AiAnswerType.ERROR, exception.getMessage(), "CONFIG");
        } catch (AiServiceException exception) {
            return completeTurn(turn, AiAnswerType.ERROR, exception.failure().userMessage(), exception.failure().reason().name());
        } catch (Exception exception) {
            return completeTurn(turn, AiAnswerType.ERROR, "AI 助手暂时不可用，请稍后再试。", AiFailureReason.UNKNOWN.name());
        }
    }

    public void askStream(String openid, AiDtos.AiAskRequest request, AiStreamObserver observer) {
        AskTurn turn = prepareTurn(openid, request);
        observer.onStart(new AiDtos.AiStreamStartEvent(
                turn.sessionId(),
                turn.assistantMessageId(),
                turn.traceId(),
                turn.answerType(),
                turn.sourceViews(),
                turn.suggestedQuestions(),
                turn.retrievalMeta()
        ));
        try {
            if (isImmediateAnswer(turn.answerType())) {
                String answer = generateImmediateAnswer(turn.answerType());
                observer.onDelta(answer);
                observer.onComplete(completeTurn(turn, turn.answerType(), answer, null));
                return;
            }
            String answer = miniMaxGateway.streamAnswer(
                    buildPrompt(turn.question(), turn.queryPlan(), turn.hits(), turn.answerType()),
                    turn.useWebSearch(),
                    observer::onDelta
            );
            observer.onComplete(completeTurn(turn, turn.answerType(), answer, null));
        } catch (ApiException exception) {
            completeStreamError(turn, observer, exception.getMessage(), "CONFIG", false);
        } catch (AiServiceException exception) {
            completeStreamError(
                    turn,
                    observer,
                    exception.failure().userMessage(),
                    exception.failure().reason().name(),
                    exception.failure().retryable()
            );
        } catch (Exception exception) {
            completeStreamError(turn, observer, "AI 助手暂时不可用，请稍后再试。", AiFailureReason.UNKNOWN.name(), true);
        }
    }

    public List<AiDtos.AiSessionView> listSessions(String openid) {
        if (!store.ready()) {
            return List.of();
        }
        return store.listSessions(openid).stream()
                .map(row -> new AiDtos.AiSessionView(
                        String.valueOf(row.get("session_id")),
                        asString(row.get("title")),
                        asString(row.get("confirmed_entity")),
                        asOffsetDateTime(row.get("create_time")),
                        asOffsetDateTime(row.get("update_time"))
                ))
                .toList();
    }

    public List<AiDtos.AiMessageView> listMessages(String sessionId) {
        if (!store.ready()) {
            return List.of();
        }
        return store.listMessages(sessionId).stream()
                .map(row -> new AiDtos.AiMessageView(
                        asString(row.get("message_id")),
                        asString(row.get("role")),
                        asString(row.get("content")),
                        parseAnswerType(asString(row.get("answer_type"))),
                        asString(row.get("trace_id")),
                        mapSources(asString(row.get("sources"))),
                        asOffsetDateTime(row.get("create_time"))
                ))
                .toList();
    }

    public void saveFeedback(String messageId, AiDtos.AiFeedbackRequest request) {
        if (messageId == null || messageId.isBlank()) {
            throw new ApiException(4000, "messageId is required");
        }
    }

    public AiDtos.AdminAiDebugResponse debugRetrieve(AiDtos.AdminAiDebugRequest request) {
        hydrateRuntimeSettings();
        AiQueryPlan plan = queryPlanner.plan(
                request.query(),
                loadKnownSubjects(),
                new AiRetrievalContext(request.confirmedEntity(), Set.of())
        );
        List<AiRetrievalCandidate> hits = retrieve(plan);
        return new AiDtos.AdminAiDebugResponse(
                request.query(),
                plan.subject(),
                plan.intent(),
                plan.outOfScope(),
                hits.stream().map(this::toSourceView).toList(),
                Map.of(
                        "mode", runtimeSettings.knowledgeBaseEnabled() ? "RAG" : "WEB_ONLY",
                        "hitCount", hits.size(),
                        "postgresReady", store.ready(),
                        "embeddingReady", miniMaxGateway.embeddingReady()
                )
        );
    }

    public AiDtos.AdminAiConfigView getAdminConfig() {
        hydrateRuntimeSettings();
        return new AiDtos.AdminAiConfigView(
                runtimeSettings.enabled(),
                runtimeSettings.knowledgeBaseEnabled(),
                runtimeSettings.webSearchEnabled(),
                store.ready(),
                miniMaxGateway.chatReady(),
                miniMaxGateway.embeddingReady(),
                runtimeSettings.topK(),
                runtimeSettings.minScore(),
                runtimeSettings.maxEvidenceChars(),
                runtimeSettings.chatModel(),
                runtimeSettings.embeddingModel(),
                runtimeSettings.chatApiKeyMasked(),
                runtimeSettings.embeddingApiKeyMasked()
        );
    }

    public AiDtos.AdminAiConfigView updateAdminConfig(AiDtos.AdminAiConfigRequest request) {
        hydrateRuntimeSettings();
        runtimeSettings.update(request);
        if (store.ready()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(AiRuntimeSettings.ENABLED_KEY, String.valueOf(runtimeSettings.enabled()));
            values.put(AiRuntimeSettings.KNOWLEDGE_BASE_ENABLED_KEY, String.valueOf(runtimeSettings.knowledgeBaseEnabled()));
            values.put(AiRuntimeSettings.WEB_SEARCH_ENABLED_KEY, String.valueOf(runtimeSettings.webSearchEnabled()));
            values.put(AiRuntimeSettings.TOP_K_KEY, String.valueOf(runtimeSettings.topK()));
            values.put(AiRuntimeSettings.MIN_SCORE_KEY, String.valueOf(runtimeSettings.minScore()));
            values.put(AiRuntimeSettings.MAX_EVIDENCE_CHARS_KEY, String.valueOf(runtimeSettings.maxEvidenceChars()));
            values.put(AiRuntimeSettings.CHAT_MODEL_KEY, runtimeSettings.chatModel());
            values.put(AiRuntimeSettings.EMBEDDING_MODEL_KEY, runtimeSettings.embeddingModel());
            if (StringUtils.hasText(request.chatApiKey())) {
                values.put(AiRuntimeSettings.CHAT_API_KEY, runtimeSettings.chatApiKey());
            }
            if (StringUtils.hasText(request.embeddingApiKey())) {
                values.put(AiRuntimeSettings.EMBEDDING_API_KEY, runtimeSettings.embeddingApiKey());
            }
            store.saveConfig(values);
        }
        return getAdminConfig();
    }

    public List<AiDtos.AdminAiLogView> listAdminLogs() {
        if (!store.ready()) {
            return List.of();
        }
        return store.listLogs(200).stream().map(log -> new AiDtos.AdminAiLogView(
                log.traceId(),
                log.sessionId(),
                log.question(),
                log.answerType(),
                log.subject(),
                log.retrievalMode(),
                log.hitCount(),
                log.webUsed(),
                log.failureReason(),
                log.latencyMs(),
                log.createdAt()
        )).toList();
    }

    public List<AiDtos.AdminAiEvalCaseView> listEvalCases() {
        if (!store.ready()) {
            return List.of();
        }
        return store.listEvalCases().stream().map(row -> new AiDtos.AdminAiEvalCaseView(
                ((Number) row.get("id")).longValue(),
                asString(row.get("question")),
                asString(row.get("expected_subject")),
                asString(row.get("expected_keywords")),
                asString(row.get("category")),
                asBoolean(row.get("enabled")),
                asOffsetDateTime(row.get("created_at"))
        )).toList();
    }

    public void createEvalCase(AiDtos.AdminAiEvalCaseRequest request) {
        if (!store.ready()) {
            throw new ApiException(5001, "AI PostgreSQL/pgvector 未配置");
        }
        store.insertEvalCase(request.question(), request.expectedSubject(), request.expectedKeywords(), request.category());
    }

    public List<AiDtos.AdminAiPublishView> listPublishVersions() {
        if (!store.ready()) {
            return List.of();
        }
        return store.listPublishVersions().stream().map(row -> new AiDtos.AdminAiPublishView(
                asString(row.get("version_key")),
                asString(row.get("status")),
                asBoolean(row.get("active")),
                asInt(row.get("document_count")),
                asInt(row.get("chunk_count")),
                asOffsetDateTime(row.get("created_at")),
                asOffsetDateTime(row.get("activated_at"))
        )).toList();
    }

    private AskTurn prepareTurn(String openid, AiDtos.AiAskRequest request) {
        hydrateRuntimeSettings();
        if (!runtimeSettings.enabled()) {
            throw new ApiException(5002, "AI 助手已关闭，请联系管理员开启。");
        }
        if (!miniMaxGateway.chatReady()) {
            throw new ApiException(5003, "MiniMax 聊天 key 未配置，暂时无法回答。");
        }

        long startTime = System.currentTimeMillis();
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId().trim() : newSessionId();
        String question = request.question().trim();
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "");
        String userMessageId = "msg-u-" + UUID.randomUUID().toString().replace("-", "");
        String assistantMessageId = "msg-a-" + UUID.randomUUID().toString().replace("-", "");

        Set<String> knownSubjects = loadKnownSubjects();
        AiRetrievalContext context = resolveContext(sessionId);
        AiQueryPlan queryPlan = queryPlanner.plan(question, knownSubjects, context);

        AiAnswerType answerType;
        List<AiRetrievalCandidate> hits = List.of();
        boolean useWebSearch = false;
        String retrievalMode = "NONE";

        if (queryPlan.outOfScope()) {
            answerType = AiAnswerType.OUT_OF_SCOPE;
        } else {
            RouteDecision routeDecision = route(queryPlan);
            answerType = routeDecision.answerType();
            hits = routeDecision.hits();
            useWebSearch = routeDecision.useWebSearch();
            retrievalMode = routeDecision.retrievalMode();
        }

        List<AiDtos.AiSourceView> sourceViews = hits.stream().map(this::toSourceView).toList();
        Map<String, Object> retrievalMeta = buildRetrievalMeta(queryPlan, retrievalMode, sourceViews.size());
        List<String> suggestedQuestions = buildSuggestedQuestions(queryPlan.subject());

        return new AskTurn(
                startTime,
                openid,
                sessionId,
                question,
                traceId,
                userMessageId,
                assistantMessageId,
                queryPlan,
                hits,
                answerType,
                useWebSearch,
                retrievalMode,
                sourceViews,
                suggestedQuestions,
                retrievalMeta
        );
    }

    private void completeStreamError(
            AskTurn turn,
            AiStreamObserver observer,
            String message,
            String failureReason,
            boolean retryable
    ) {
        completeTurn(turn, AiAnswerType.ERROR, message, failureReason);
        observer.onError(new AiDtos.AiStreamErrorEvent(message, failureReason, retryable));
    }

    private AiDtos.AiAskResponse completeTurn(AskTurn turn, AiAnswerType answerType, String answer, String failureReason) {
        Map<String, Object> retrievalMeta = new LinkedHashMap<>(turn.retrievalMeta());
        if (failureReason != null) {
            retrievalMeta.put("failureReason", failureReason);
        }
        persistConversation(
                turn.openid(),
                turn.sessionId(),
                turn.question(),
                turn.userMessageId(),
                answer,
                turn.assistantMessageId(),
                turn.traceId(),
                answerType,
                turn.sourceViews(),
                turn.queryPlan().subject()
        );
        persistLog(
                turn.traceId(),
                turn.sessionId(),
                turn.question(),
                answer,
                answerType,
                turn.queryPlan().subject(),
                turn.retrievalMode(),
                turn.sourceViews().size(),
                answerType == AiAnswerType.WEB_AUGMENTED_ANSWER || answerType == AiAnswerType.WEB_ONLY_ANSWER,
                failureReason,
                turn.startTime()
        );
        return new AiDtos.AiAskResponse(
                turn.sessionId(),
                turn.assistantMessageId(),
                turn.traceId(),
                answer,
                answerType,
                turn.sourceViews(),
                turn.suggestedQuestions(),
                retrievalMeta
        );
    }

    private String generateAnswer(AskTurn turn) {
        if (isImmediateAnswer(turn.answerType())) {
            return generateImmediateAnswer(turn.answerType());
        }
        return miniMaxGateway.answer(
                buildPrompt(turn.question(), turn.queryPlan(), turn.hits(), turn.answerType()),
                turn.useWebSearch()
        );
    }

    private boolean isImmediateAnswer(AiAnswerType answerType) {
        return answerType == AiAnswerType.OUT_OF_SCOPE || answerType == AiAnswerType.NO_EVIDENCE;
    }

    private String generateImmediateAnswer(AiAnswerType answerType) {
        if (answerType == AiAnswerType.OUT_OF_SCOPE) {
            return "我只回答狼人杀相关的问题。你可以继续问角色技能、板子规则、术语解释或复盘分析。";
        }
        return "我目前检索到的证据还不够。请补充更具体的主体、板子或规则场景，我会继续精确回答。";
    }

    private Map<String, Object> buildRetrievalMeta(AiQueryPlan queryPlan, String retrievalMode, int hitCount) {
        Map<String, Object> retrievalMeta = new LinkedHashMap<>();
        retrievalMeta.put("mode", retrievalMode);
        retrievalMeta.put("subject", queryPlan.subject());
        retrievalMeta.put("intent", queryPlan.intent());
        retrievalMeta.put("hitCount", hitCount);
        retrievalMeta.put("knowledgeBaseEnabled", runtimeSettings.knowledgeBaseEnabled());
        retrievalMeta.put("webSearchEnabled", runtimeSettings.webSearchEnabled());
        return retrievalMeta;
    }

    private RouteDecision route(AiQueryPlan plan) {
        if (!runtimeSettings.knowledgeBaseEnabled()) {
            ensureWebSearchReady();
            return new RouteDecision(AiAnswerType.WEB_ONLY_ANSWER, List.of(), true, "WEB_ONLY");
        }
        List<AiRetrievalCandidate> hits = retrieve(plan);
        if (!hits.isEmpty()) {
            if (plan.webPreferred() && runtimeSettings.webSearchEnabled()) {
                ensureWebSearchReady();
                return new RouteDecision(AiAnswerType.WEB_AUGMENTED_ANSWER, hits, true, "RAG+WEB");
            }
            return new RouteDecision(AiAnswerType.RAG_ANSWER, hits, false, "RAG");
        }
        if (runtimeSettings.webSearchEnabled() && plan.webPreferred()) {
            ensureWebSearchReady();
            return new RouteDecision(AiAnswerType.WEB_ONLY_ANSWER, List.of(), true, "WEB_ONLY");
        }
        return new RouteDecision(AiAnswerType.NO_EVIDENCE, List.of(), false, "RAG_EMPTY");
    }

    private List<AiRetrievalCandidate> retrieve(AiQueryPlan plan) {
        if (!store.ready()) {
            return List.of();
        }
        List<AiRetrievalCandidate> ftsHits = store.searchText(plan.searchQuery(), plan.subject(), runtimeSettings.topK());
        List<AiRetrievalCandidate> vectorHits = List.of();
        if (miniMaxGateway.embeddingReady()) {
            try {
                float[] queryEmbedding = miniMaxGateway.embedForQuery(plan.searchQuery());
                vectorHits = store.searchVector(queryEmbedding, plan.subject(), runtimeSettings.topK());
            } catch (AiServiceException ignored) {
                vectorHits = List.of();
            }
        }
        return rrfFusion.fuse(runtimeSettings.topK(), vectorHits, ftsHits).stream()
                .filter(hit -> hit.score() >= runtimeSettings.minScore())
                .limit(runtimeSettings.topK())
                .toList();
    }

    private void ensureWebSearchReady() {
        if (!runtimeSettings.webSearchEnabled()) {
            throw new ApiException(5004, "联网搜索已关闭，无法切换到纯联网模式。");
        }
        if (!StringUtils.hasText(properties.getWebSearch().getMcpApiKey())) {
            throw new ApiException(5005, "MINIMAX_MCP_API_KEY 未配置，联网搜索不可用。");
        }
    }

    private Set<String> loadKnownSubjects() {
        Set<String> subjects = new LinkedHashSet<>();
        subjects.addAll(List.of(
                "狼人",
                "女巫",
                "预言家",
                "猎人",
                "守卫",
                "白狼王",
                "舞者",
                "假面",
                "金水",
                "银水",
                "自爆"
        ));
        if (store.ready()) {
            subjects.addAll(store.listSubjects());
        }
        return subjects.stream()
                .filter(StringUtils::hasText)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private AiRetrievalContext resolveContext(String sessionId) {
        if (!store.ready()) {
            return AiRetrievalContext.empty();
        }
        return new AiRetrievalContext(store.confirmedEntity(sessionId).orElse(null), Set.of());
    }

    private String buildPrompt(String question, AiQueryPlan plan, List<AiRetrievalCandidate> hits, AiAnswerType answerType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(question).append('\n');
        prompt.append("识别主体：").append(plan.subject() == null ? "无" : plan.subject()).append('\n');
        prompt.append("意图：").append(plan.intent()).append('\n');
        prompt.append("回答类型：").append(answerType.name()).append('\n');
        if (hits.isEmpty()) {
            prompt.append("证据：暂无本地知识命中。\n");
            prompt.append("如果没有足够信息，请明确告知并引导用户补充。\n");
            return prompt.toString();
        }
        prompt.append("证据（仅可使用以下内容回答）：\n");
        int index = 1;
        int consumed = 0;
        for (AiRetrievalCandidate hit : hits) {
            if (consumed >= runtimeSettings.maxEvidenceChars()) {
                break;
            }
            String snippet = truncate(hit.content(), 700);
            consumed += snippet.length();
            prompt.append(index++)
                    .append(". [subject=").append(hit.subject())
                    .append(",score=").append(String.format("%.3f", hit.score()))
                    .append(",source=").append(hit.source()).append("] ")
                    .append(snippet)
                    .append('\n');
        }
        return prompt.toString();
    }

    private List<String> buildSuggestedQuestions(String subject) {
        if (!StringUtils.hasText(subject)) {
            return DEFAULT_QUICK_QUESTIONS;
        }
        return List.of(
                subject + "的技能触发条件是什么？",
                subject + "在 12 人局里怎么配合队友？",
                subject + "这个角色常见误区有哪些？"
        );
    }

    private void persistConversation(
            String openid,
            String sessionId,
            String question,
            String userMessageId,
            String answer,
            String assistantMessageId,
            String traceId,
            AiAnswerType answerType,
            List<AiDtos.AiSourceView> sources,
            String confirmedEntity
    ) {
        if (!store.ready()) {
            return;
        }
        store.saveMessage(sessionId, openid, "USER", question, userMessageId, null, null, List.of(), confirmedEntity);
        List<Map<String, Object>> sourcePayload = sources.stream().map(source -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chunkUid", nullSafe(source.chunkUid()));
            map.put("title", nullSafe(source.title()));
            map.put("sectionPath", nullSafe(source.sectionPath()));
            map.put("subject", nullSafe(source.subject()));
            map.put("score", source.score());
            map.put("sourceType", nullSafe(source.sourceType()));
            map.put("url", nullSafe(source.url()));
            return map;
        }).toList();
        store.saveMessage(
                sessionId,
                openid,
                "ASSISTANT",
                answer,
                assistantMessageId,
                traceId,
                answerType.name(),
                sourcePayload,
                confirmedEntity
        );
    }

    private void persistLog(
            String traceId,
            String sessionId,
            String question,
            String answer,
            AiAnswerType answerType,
            String subject,
            String retrievalMode,
            int hitCount,
            boolean webUsed,
            String failureReason,
            long startTime
    ) {
        if (!store.ready()) {
            return;
        }
        long latency = System.currentTimeMillis() - startTime;
        store.saveLog(traceId, sessionId, question, answer, answerType.name(), subject, retrievalMode, hitCount, webUsed, failureReason, latency);
    }

    private List<AiDtos.AiSourceView> mapSources(String json) {
        return store.readSourceList(json).stream()
                .map(source -> new AiDtos.AiSourceView(
                        asString(source.get("chunkUid")),
                        asString(source.get("title")),
                        asString(source.get("sectionPath")),
                        asString(source.get("subject")),
                        "",
                        source.get("score") instanceof Number number ? number.doubleValue() : 0,
                        asString(source.get("sourceType")),
                        asString(source.get("url"))
                ))
                .toList();
    }

    private AiDtos.AiSourceView toSourceView(AiRetrievalCandidate hit) {
        return new AiDtos.AiSourceView(
                hit.chunkUid(),
                hit.subject() == null ? "知识切片" : hit.subject(),
                "",
                hit.subject(),
                truncate(hit.content(), 320),
                hit.score(),
                hit.source(),
                null
        );
    }

    private String resolveMode(boolean knowledgeBaseEnabled, boolean webSearchEnabled) {
        if (knowledgeBaseEnabled) {
            return "RAG";
        }
        return webSearchEnabled ? "WEB_ONLY" : "DISABLED";
    }

    private String newSessionId() {
        return "as-" + UUID.randomUUID().toString().replace("-", "");
    }

    private AiAnswerType parseAnswerType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return AiAnswerType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AiAnswerType.ERROR;
        }
    }

    private OffsetDateTime asOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String nullSafe(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private void hydrateRuntimeSettings() {
        if (runtimeSettings.storeHydrated()) {
            return;
        }
        try {
            if (!store.ready()) {
                return;
            }
            runtimeSettings.applyPersistedConfig(store.loadConfig());
        } catch (Exception ignored) {
            // Keep environment-backed defaults when persisted config is unavailable.
        }
    }

    public interface AiStreamObserver {
        void onStart(AiDtos.AiStreamStartEvent event);

        void onDelta(String delta);

        void onComplete(AiDtos.AiAskResponse response);

        default void onError(AiDtos.AiStreamErrorEvent error) {
        }
    }

    private record AskTurn(
            long startTime,
            String openid,
            String sessionId,
            String question,
            String traceId,
            String userMessageId,
            String assistantMessageId,
            AiQueryPlan queryPlan,
            List<AiRetrievalCandidate> hits,
            AiAnswerType answerType,
            boolean useWebSearch,
            String retrievalMode,
            List<AiDtos.AiSourceView> sourceViews,
            List<String> suggestedQuestions,
            Map<String, Object> retrievalMeta
    ) {
    }

    private record RouteDecision(
            AiAnswerType answerType,
            List<AiRetrievalCandidate> hits,
            boolean useWebSearch,
            String retrievalMode
    ) {
    }
}
