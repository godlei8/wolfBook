package com.wolfbook.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.ai.controller.ApiAiController;
import com.wolfbook.backend.ai.dto.AiDtos;
import com.wolfbook.backend.ai.model.AiAnswerType;
import com.wolfbook.backend.ai.service.AiAssistantService;
import com.wolfbook.backend.support.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiAiControllerStreamTest {

    private final AiAssistantService assistantService = Mockito.mock(AiAssistantService.class);
    private final TokenService tokenService = Mockito.mock(TokenService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiAiController(assistantService, tokenService)).build();
        when(tokenService.requireUser("Bearer token")).thenReturn("wx-stream");
    }

    @Test
    void askStreamReturnsServerSentEvents() throws Exception {
        AiDtos.AiStreamStartEvent startEvent = new AiDtos.AiStreamStartEvent(
                "as-1",
                "msg-a-1",
                "trace-1",
                AiAnswerType.RAG_ANSWER,
                List.of(),
                List.of("舞者技能怎么用？"),
                Map.of("mode", "RAG")
        );
        AiDtos.AiAskResponse finalResponse = new AiDtos.AiAskResponse(
                "as-1",
                "msg-a-1",
                "trace-1",
                "舞者可以在夜间交换目标。",
                AiAnswerType.RAG_ANSWER,
                List.of(),
                List.of("舞者技能怎么用？"),
                Map.of("mode", "RAG")
        );

        doAnswer(invocation -> {
            AiAssistantService.AiStreamObserver observer = invocation.getArgument(2);
            observer.onStart(startEvent);
            observer.onDelta("舞者可以");
            observer.onDelta("在夜间交换目标。");
            observer.onComplete(finalResponse);
            return null;
        }).when(assistantService).askStream(eq("wx-stream"), any(AiDtos.AiAskRequest.class), any());

        MvcResult mvcResult = mockMvc.perform(post("/api/ai/ask/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", "舞者技能"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("\"delta\":")))
                .andExpect(content().string(containsString("trace-1")));
    }
}
