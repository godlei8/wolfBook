package com.wolfbook.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.service.assistant.AssistantConfigService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssistantConfigService assistantConfigService;

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
                  "message": "12\u4eba\u8fdb\u9636\u63a8\u8350\u4ec0\u4e48\u677f\u5b50\uff1f",
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
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.answerType").isNotEmpty())
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
        Assertions.assertTrue(sessionFound, "assistant session should be listed after ask");

        mockMvc.perform(get("/api/assistant/sessions/{id}/messages", sessionId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[1].answerType").isNotEmpty());

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
        Assertions.assertFalse(sessionStillExists, "assistant session should be removed after reset");
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
    void assistantStreamShouldEmitStartedDeltaAndDoneForAuthorizedRequest() throws Exception {
        String token = loginAndGetToken();
        String askBody = """
                {
                  "message": "12\u4eba\u8fdb\u9636\u63a8\u8350\u4ec0\u4e48\u677f\u5b50\uff1f",
                  "scene": "assistant_chat",
                  "pageContext": {
                    "page": "assistant/chat"
                  }
                }
                """;

        var result = mockMvc.perform(post("/api/assistant/ask/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(askBody))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:started")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")));
    }

    @Test
    void adminAiConfigShouldMaskSecretsInAdminResponseAndKeepRawKeysForRuntime() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String authorization = "Bearer " + adminToken;

        mockMvc.perform(get("/admin/ai/config").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider.platform").value("DEEPSEEK"))
                .andExpect(jsonPath("$.data.provider.model").value("deepseek-v4-flash"));

        String requestBody = """
                {
                  "base": {
                    "enabled": true,
                    "welcomeMessage": "test welcome",
                    "quickQuestions": ["can witch save herself?"],
                    "temperature": 0.42,
                    "maxSuggestions": 4
                  },
                  "provider": {
                    "platform": "LEGACY_PROVIDER",
                    "model": "other-model",
                    "baseUrl": "https://api.deepseek.com",
                    "apiKey": "sk-test-deepseek"
                  },
                  "volcengine": {
                    "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
                    "embeddingModel": "custom-embedding",
                    "embeddingApiKey": "ark-test-embedding",
                    "searchModel": "custom-search",
                    "searchApiKey": "ark-test-search"
                  },
                  "prompt": {
                    "systemPrompt": "system prompt",
                    "recommendationPrompt": "recommendation prompt",
                    "refusalPrompt": "refusal prompt"
                  },
                  "retrieval": {
                    "topK": 5,
                    "similarityThreshold": 0.5,
                    "historyWindow": 10
                  },
                  "search": {
                    "webSearchEnabled": true
                  },
                  "safety": {
                    "unsupportedMessage": "unsupported",
                    "blockedKeywords": ["blocked"]
                  },
                  "ui": {
                    "mascot": "wolf-head",
                    "dockLabel": "AI assistant",
                    "accentColor": "#FFC000"
                  }
                }
                """;

        String saveResponse = mockMvc.perform(put("/admin/ai/config")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.base.welcomeMessage").value("test welcome"))
                .andExpect(jsonPath("$.data.provider.platform").value("DEEPSEEK"))
                .andExpect(jsonPath("$.data.provider.model").value("deepseek-v4-flash"))
                .andExpect(jsonPath("$.data.provider.baseUrl").value("https://api.deepseek.com"))
                .andExpect(jsonPath("$.data.volcengine.baseUrl").value("https://ark.cn-beijing.volces.com/api/v3"))
                .andExpect(jsonPath("$.data.volcengine.embeddingModel").value("doubao-embedding-large-text-250515"))
                .andExpect(jsonPath("$.data.volcengine.searchModel").value("doubao-seed-1-6-thinking-250715"))
                .andExpect(jsonPath("$.data.search.webSearchEnabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode savedConfig = objectMapper.readTree(saveResponse).path("data");
        String maskedDeepSeekKey = savedConfig.path("provider").path("apiKey").asText();
        String maskedEmbeddingKey = savedConfig.path("volcengine").path("embeddingApiKey").asText();
        String maskedSearchKey = savedConfig.path("volcengine").path("searchApiKey").asText();

        Assertions.assertNotEquals("sk-test-deepseek", maskedDeepSeekKey);
        Assertions.assertNotEquals("ark-test-embedding", maskedEmbeddingKey);
        Assertions.assertNotEquals("ark-test-search", maskedSearchKey);
        Assertions.assertTrue(maskedDeepSeekKey.endsWith("seek"));
        Assertions.assertTrue(maskedEmbeddingKey.endsWith("ding"));
        Assertions.assertTrue(maskedSearchKey.endsWith("arch"));

        mockMvc.perform(get("/admin/ai/config").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider.apiKey").value(maskedDeepSeekKey))
                .andExpect(jsonPath("$.data.volcengine.embeddingApiKey").value(maskedEmbeddingKey))
                .andExpect(jsonPath("$.data.volcengine.searchApiKey").value(maskedSearchKey));

        String maskedResaveBody = """
                {
                  "base": {
                    "enabled": true,
                    "welcomeMessage": "saved twice",
                    "quickQuestions": ["can witch save herself?"],
                    "temperature": 0.42,
                    "maxSuggestions": 4
                  },
                  "provider": {
                    "platform": "DEEPSEEK",
                    "model": "deepseek-v4-flash",
                    "baseUrl": "https://api.deepseek.com",
                    "apiKey": "%s"
                  },
                  "volcengine": {
                    "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
                    "embeddingModel": "doubao-embedding-large-text-250515",
                    "embeddingApiKey": "%s",
                    "searchModel": "doubao-seed-1-6-thinking-250715",
                    "searchApiKey": "%s"
                  },
                  "prompt": {
                    "systemPrompt": "system prompt",
                    "recommendationPrompt": "recommendation prompt",
                    "refusalPrompt": "refusal prompt"
                  },
                  "retrieval": {
                    "topK": 5,
                    "similarityThreshold": 0.5,
                    "historyWindow": 10
                  },
                  "search": {
                    "webSearchEnabled": true
                  },
                  "safety": {
                    "unsupportedMessage": "unsupported",
                    "blockedKeywords": ["blocked"]
                  },
                  "ui": {
                    "mascot": "wolf-head",
                    "dockLabel": "AI assistant",
                    "accentColor": "#FFC000"
                  }
                }
                """.formatted(maskedDeepSeekKey, maskedEmbeddingKey, maskedSearchKey);

        mockMvc.perform(put("/admin/ai/config")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maskedResaveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        AssistantDtos.AdminAiConfig runtimeConfig = assistantConfigService.getRuntimeConfig();
        Assertions.assertEquals("sk-test-deepseek", runtimeConfig.provider().apiKey());
        Assertions.assertEquals("ark-test-embedding", runtimeConfig.volcengine().embeddingApiKey());
        Assertions.assertEquals("ark-test-search", runtimeConfig.volcengine().searchApiKey());

        String userToken = loginAndGetToken();
        mockMvc.perform(get("/api/assistant/bootstrap").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.featureFlags.webSearchEnabled").value(true));
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

    private String loginAndGetAdminToken() throws Exception {
        String loginBody = """
                {"username":"admin","password":"wolf123"}
                """;
        String loginResponse = mockMvc.perform(post("/admin/login")
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
