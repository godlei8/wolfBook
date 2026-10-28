package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantMessageEntity;
import com.wolfbook.backend.entity.AssistantQueryLogEntity;
import com.wolfbook.backend.entity.AssistantSessionEntity;
import com.wolfbook.backend.mapper.AssistantQueryLogMapper;
import com.wolfbook.backend.service.UserService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 小程序问答入口：鉴权、会话、落库、写日志。怎么回答在 {@link AssistantAnswerPipeline}。
 * <p>
 * 流式接口的 SSE 事件：started → stage（进度，可能多次）→ delta（文字，多次）→ done；出错时 error。
 */
@Service
public class AssistantAnswerService {

    private static final Logger log = LoggerFactory.getLogger(AssistantAnswerService.class);

    static final String FAILED_ANSWER = "这次没答上来，换个问法或者稍后再问一次吧。";

    private final UserService userService;
    private final AssistantConfigService assistantConfigService;
    private final AssistantConversationService assistantConversationService;
    private final AssistantAnswerPipeline pipeline;
    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AssistantAnswerService(
            UserService userService,
            AssistantConfigService assistantConfigService,
            AssistantConversationService assistantConversationService,
            AssistantAnswerPipeline pipeline,
            AssistantQueryLogMapper assistantQueryLogMapper,
            AssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.assistantConfigService = assistantConfigService;
        this.assistantConversationService = assistantConversationService;
        this.pipeline = pipeline;
        this.assistantQueryLogMapper = assistantQueryLogMapper;
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
        return answer(prepare(authorization, request), request, AssistantAnswerPipeline.AnswerSink.NONE);
    }

    public SseEmitter askStream(String authorization, AssistantDtos.AssistantAskRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            try {
                Prepared prepared = prepare(authorization, request);
                traceId = prepared.traceId();
                SseSink sink = new SseSink(emitter);
                sink.send("started", new AssistantDtos.AssistantStreamStarted(
                        prepared.session().getSessionId(),
                        prepared.traceId(),
                        AssistantConstants.CONTENT_MARKDOWN
                ));
                AssistantDtos.AssistantAskResponse response = answer(prepared, request, sink);
                sink.send("done", response);
                emitter.complete();
            } catch (Exception exception) {
                emitStreamError(emitter, exception, traceId);
            }
        });
        return emitter;
    }

    /** 后台调试台：用当前生效的配置和模型答一题，不建会话、不落库、不写日志。 */
    public AssistantDtos.AdminPlaygroundResponse playground(AssistantDtos.AdminPlaygroundRequest request) {
        AssistantDtos.AdminAiConfig config = assistantConfigService.getRuntimeConfig();
        long startAt = System.currentTimeMillis();
        AssistantAnswerPipeline.Outcome outcome = pipeline.run(
                config,
                request.message(),
                request.pageContext(),
                List.of(),
                AssistantAnswerPipeline.AnswerSink.NONE
        );
        return new AssistantDtos.AdminPlaygroundResponse(
                outcome.answer(),
                outcome.answerType(),
                outcome.intent(),
                outcome.reason(),
                outcome.hits(),
                outcome.boards(),
                outcome.usedWebSearch(),
                config.provider() == null ? null : config.provider().model(),
                outcome.failureType(),
                outcome.timings(),
                System.currentTimeMillis() - startAt
        );
    }

    private Prepared prepare(String authorization, AssistantDtos.AssistantAskRequest request) {
        String openid = userService.requireUser(authorization).openid();
        AssistantDtos.AdminAiConfig config = assistantConfigService.getRuntimeConfig();
        if (!config.base().enabled()) {
            throw new ApiException(4000, "AI 助手暂时关闭了");
        }
        AssistantSessionEntity session = assistantConversationService.resolveSession(
                openid,
                request.sessionId(),
                request.scene(),
                request.pageContext(),
                request.message()
        );
        assistantConversationService.saveUserMessage(session.getSessionId(), request.message());
        return new Prepared(openid, session, config, UUID.randomUUID().toString().replace("-", ""), System.currentTimeMillis());
    }

    /** 跑一遍回答流程，结果存进会话、写一条日志；流程里出了意外也给用户一句话，并记成失败。 */
    private AssistantDtos.AssistantAskResponse answer(
            Prepared prepared,
            AssistantDtos.AssistantAskRequest request,
            AssistantAnswerPipeline.AnswerSink sink
    ) {
        AssistantAnswerPipeline.Outcome outcome;
        try {
            List<AssistantConversationService.Turn> history = assistantConversationService.recentTurns(prepared.openid(), prepared.session().getSessionId());
            outcome = pipeline.run(prepared.config(), request.message(), request.pageContext(), history, sink);
        } catch (RuntimeException exception) {
            log.error("assistant answer failed, traceId={}", prepared.traceId(), exception);
            sink.delta(FAILED_ANSWER);
            outcome = new AssistantAnswerPipeline.Outcome(
                    FAILED_ANSWER, AssistantConstants.ANSWER_REFUSAL, List.of(), List.of(), false,
                    exception.getClass().getSimpleName(), null, null, List.of(), Map.of()
            );
        }

        List<String> suggestedQuestions = prepared.config().base().quickQuestions().stream()
                .filter(item -> !item.equalsIgnoreCase(request.message()))
                .limit(assistantProperties.getMaxSuggestions())
                .toList();
        AssistantMessageEntity saved = assistantConversationService.saveAssistantMessage(
                prepared.session().getSessionId(),
                outcome.answer(),
                outcome.answerType(),
                outcome.citations(),
                outcome.boards(),
                suggestedQuestions,
                outcome.usedWebSearch(),
                prepared.traceId()
        );
        writeLog(prepared, request.message(), outcome);
        return new AssistantDtos.AssistantAskResponse(
                prepared.session().getSessionId(),
                saved.getId(),
                outcome.answer(),
                AssistantConstants.CONTENT_MARKDOWN,
                outcome.answerType(),
                prepared.traceId()
        );
    }

    private void writeLog(Prepared prepared, String userMessage, AssistantAnswerPipeline.Outcome outcome) {
        try {
            AssistantQueryLogEntity entity = new AssistantQueryLogEntity();
            entity.setOpenid(prepared.openid());
            entity.setSessionId(prepared.session().getSessionId());
            entity.setUserMessage(userMessage);
            entity.setAnswerType(outcome.answerType());
            entity.setHitSources(objectMapper.writeValueAsString(outcome.hits().stream().map(AssistantDtos.AdminPlaygroundHit::title).toList()));
            entity.setUsedWebSearch(outcome.usedWebSearch() ? 1 : 0);
            entity.setLatencyMs(System.currentTimeMillis() - prepared.startAtMs());
            entity.setSuccess(outcome.failureType() == null ? 1 : 0);
            entity.setFailureType(outcome.failureType());
            entity.setTraceId(prepared.traceId());
            entity.setCreateTime(LocalDateTime.now());
            assistantQueryLogMapper.insert(entity);
        } catch (Exception exception) {
            // 日志写不进去不影响回答
            log.warn("assistant query log failed, traceId={}: {}", prepared.traceId(), exception.toString());
        }
    }

    private void emitStreamError(SseEmitter emitter, Exception exception, String traceId) {
        try {
            emitter.send(SseEmitter.event().name("error").data(toStreamError(exception, traceId), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ignored) {
            emitter.completeWithError(exception);
        }
    }

    /** 业务异常原样带 code 下发（小程序靠 4001 清登录态）；其他异常只给通用文案，不泄露内部信息。 */
    private AssistantDtos.AssistantStreamError toStreamError(Exception exception, String traceId) {
        if (exception instanceof ApiException apiException) {
            return new AssistantDtos.AssistantStreamError(apiException.getCode(), apiException.getMessage(), traceId);
        }
        log.error("assistant stream failed, traceId={}", traceId, exception);
        return new AssistantDtos.AssistantStreamError(5000, "AI 回答失败，请稍后重试", traceId);
    }

    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    private record Prepared(
            String openid,
            AssistantSessionEntity session,
            AssistantDtos.AdminAiConfig config,
            String traceId,
            long startAtMs
    ) {
    }

    /**
     * 往 SSE 写进度和文字。用户中途关掉页面后发送会失败，之后的内容不再发，但回答照常生成完、存进会话，
     * 下次打开还能看到。
     */
    private static final class SseSink implements AssistantAnswerPipeline.AnswerSink {
        private final SseEmitter emitter;
        private volatile boolean closed;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void stage(String key, String label) {
            send("stage", new AssistantDtos.AssistantStreamStage(key, label));
        }

        @Override
        public void delta(String text) {
            if (text != null && !text.isEmpty()) {
                send("delta", new AssistantDtos.AssistantStreamDelta(text));
            }
        }

        void send(String event, Object payload) {
            if (closed) return;
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                closed = true;
            }
        }
    }
}
