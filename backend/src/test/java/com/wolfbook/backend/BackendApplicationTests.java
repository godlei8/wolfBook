package com.wolfbook.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void boardsEndpointShouldReturnPagedPayload() throws Exception {
        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void loginShouldReturnTokenAndAllowUserInfoFetch() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/user/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nickname").isNotEmpty());
    }

    @Test
    void assistantEndpointsShouldSupportConversationLifecycle() throws Exception {
        String token = loginAndGetToken();
        String authorization = "Bearer " + token;

        mockMvc.perform(get("/api/assistant/bootstrap").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.quickQuestions").isArray())
                .andExpect(jsonPath("$.data.featureFlags.historyEnabled").value(true));

        String askBody = """
                {
                  "message": "12人进阶推荐什么板子",
                  "scene": "boards_list",
                  "pageContext": {
                    "page": "boards/list"
                  },
                  "clientTimestamp": "2026-04-09T01:00:00"
                }
                """;

        String askResponse = mockMvc.perform(post("/api/assistant/ask")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(askBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answerType").value("STRUCTURED_RECOMMENDATION"))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.recommendedBoards").isArray())
                .andExpect(jsonPath("$.data.recommendedBoards[0].id").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode askRoot = objectMapper.readTree(askResponse);
        String sessionId = askRoot.path("data").path("sessionId").asText();

        String sessionsResponse = mockMvc.perform(get("/api/assistant/sessions").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sessions = objectMapper.readTree(sessionsResponse).path("data");
        boolean sessionFound = false;
        for (JsonNode session : sessions) {
            if (sessionId.equals(session.path("sessionId").asText())) {
                sessionFound = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(sessionFound, "assistant session should be listed after ask");

        mockMvc.perform(get("/api/assistant/sessions/{id}/messages", sessionId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[1].answerType").value("STRUCTURED_RECOMMENDATION"));

        mockMvc.perform(post("/api/assistant/sessions/{id}/reset", sessionId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String sessionsAfterReset = mockMvc.perform(get("/api/assistant/sessions").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sessionsAfterResetNode = objectMapper.readTree(sessionsAfterReset).path("data");
        boolean sessionStillExists = false;
        for (JsonNode session : sessionsAfterResetNode) {
            if (sessionId.equals(session.path("sessionId").asText())) {
                sessionStillExists = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(sessionStillExists, "assistant session should be removed after reset");
    }

    @Test
    void assistantStreamShouldReturnSseErrorInsteadOfHttp500WhenTokenIsInvalid() throws Exception {
        String askBody = """
                {
                  "message": "test",
                  "scene": "assistant_chat",
                  "pageContext": {
                    "page": "assistant/chat"
                  }
                }
                """;

        var result = mockMvc.perform(post("/api/assistant/ask/stream")
                        .header("Authorization", "Bearer invalid")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(askBody))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));
    }

    @Test
    void favoriteEndpointsShouldSupportAddAndRemoveLifecycle() throws Exception {
        String token = loginAndGetToken();
        String authorization = "Bearer " + token;

        mockMvc.perform(get("/api/user/favorites").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.boardIds").isArray())
                .andExpect(jsonPath("$.data.boards").isArray());

        mockMvc.perform(post("/api/user/favorites/{boardId}", 1).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.boardIds[0]").value(1))
                .andExpect(jsonPath("$.data.boards[0].id").value(1));

        mockMvc.perform(delete("/api/user/favorites/{boardId}", 1).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.boardIds").isArray())
                .andExpect(jsonPath("$.data.boardIds").isEmpty())
                .andExpect(jsonPath("$.data.boards").isArray())
                .andExpect(jsonPath("$.data.boards").isEmpty());
    }

    private String loginAndGetToken() throws Exception {
        String loginBody = """
                {"code":"mock-code"}
                """;
        String loginResponse = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(loginResponse);
        return root.path("data").path("token").asText();
    }
}
