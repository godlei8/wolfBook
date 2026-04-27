package com.wolfbook.backend.ai.controller;

import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.service.AiAssistantService;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.support.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class ApiAiController {

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final AiAssistantService assistantService;
    private final TokenService tokenService;

    public ApiAiController(AiAssistantService assistantService, TokenService tokenService) {
        this.assistantService = assistantService;
        this.tokenService = tokenService;
    }

    @GetMapping("/bootstrap")
    public ApiResponse<AiDtos.AiBootstrapResponse> bootstrap() {
        return ApiResponse.success(assistantService.bootstrap());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiDtos.AiSessionView>> sessions(@RequestHeader("Authorization") String authorization) {
        String openid = tokenService.requireUser(authorization);
        return ApiResponse.success(assistantService.listSessions(openid));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<AiDtos.AiMessageView>> messages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String sessionId
    ) {
        tokenService.requireUser(authorization);
        return ApiResponse.success(assistantService.listMessages(sessionId));
    }

    @PostMapping("/ask")
    public ApiResponse<AiDtos.AiAskResponse> ask(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AiDtos.AiAskRequest request
    ) {
        String openid = tokenService.requireUser(authorization);
        return ApiResponse.success(assistantService.ask(openid, request));
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter askStream(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AiDtos.AiAskRequest request
    ) {
        String openid = tokenService.requireUser(authorization);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> { });
        CompletableFuture.runAsync(() -> {
            try {
                assistantService.askStream(openid, request, new AiAssistantService.AiStreamObserver() {
                    @Override
                    public void onStart(AiDtos.AiStreamStartEvent event) {
                        sendEvent(emitter, "start", event);
                    }

                    @Override
                    public void onDelta(String delta) {
                        sendEvent(emitter, "delta", new AiDtos.AiStreamDeltaEvent(delta));
                    }

                    @Override
                    public void onComplete(AiDtos.AiAskResponse response) {
                        sendEvent(emitter, "done", response);
                    }

                    @Override
                    public void onError(AiDtos.AiStreamErrorEvent error) {
                        sendEvent(emitter, "error", error);
                    }
                });
            } catch (ApiException exception) {
                sendErrorEvent(emitter, exception.getMessage(), "CONFIG", false);
            } catch (Exception exception) {
                sendErrorEvent(emitter, "AI 助手暂时不可用，请稍后再试。", "UNKNOWN", true);
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ApiResponse<Void> feedback(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String messageId,
            @RequestBody(required = false) AiDtos.AiFeedbackRequest request
    ) {
        tokenService.requireUser(authorization);
        assistantService.saveFeedback(messageId, request == null ? new AiDtos.AiFeedbackRequest("", "") : request);
        return ApiResponse.success();
    }

    private void sendErrorEvent(SseEmitter emitter, String message, String reason, boolean retryable) {
        sendEvent(emitter, "error", new AiDtos.AiStreamErrorEvent(message, reason, retryable));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, JSON_UTF8));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }
}
