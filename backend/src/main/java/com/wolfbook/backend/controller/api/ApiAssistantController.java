package com.wolfbook.backend.controller.api;

import com.wolfbook.backend.common.ApiResponse;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.service.assistant.AssistantAnswerService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class ApiAssistantController {

    private final AssistantAnswerService assistantAnswerService;

    public ApiAssistantController(AssistantAnswerService assistantAnswerService) {
        this.assistantAnswerService = assistantAnswerService;
    }

    @GetMapping("/bootstrap")
    public ApiResponse<AssistantDtos.AssistantBootstrapResponse> bootstrap(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(assistantAnswerService.bootstrap(authorization));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AssistantDtos.AssistantSessionView>> sessions(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(assistantAnswerService.listSessions(authorization));
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<AssistantDtos.AssistantMessageView>> messages(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("id") String sessionId
    ) {
        return ApiResponse.success(assistantAnswerService.listMessages(authorization, sessionId));
    }

    @PostMapping("/ask")
    public ApiResponse<AssistantDtos.AssistantAskResponse> ask(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AssistantDtos.AssistantAskRequest request
    ) {
        return ApiResponse.success(assistantAnswerService.ask(authorization, request));
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestHeader("Authorization") String authorization,
            @RequestBody @Valid AssistantDtos.AssistantAskRequest request
    ) {
        return assistantAnswerService.askStream(authorization, request);
    }

    @PostMapping("/sessions/{id}/reset")
    public ApiResponse<Void> resetSession(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("id") String sessionId
    ) {
        assistantAnswerService.resetSession(authorization, sessionId);
        return ApiResponse.success();
    }
}
