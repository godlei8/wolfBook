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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void boardsEndpointShouldReturnPagedPayload() throws Exception {
        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void boardDetailReportsHowMuchTheElectronicJudgeSupports() throws Exception {
        // 守卫九人局：狼人、预言家、女巫、守卫、平民都登记了 → 可以开无法官
        mockMvc.perform(get("/api/boards/3"))
                .andExpect(jsonPath("$.data.judgeSupportLevel").value("full"));
        // 黑狼王织梦人：黑狼王、织梦人没登记 → 只能有法官
        mockMvc.perform(get("/api/boards/6"))
                .andExpect(jsonPath("$.data.judgeSupportLevel").value("partial"));
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
                .andExpect(jsonPath("$.data.latestSessionId").doesNotExist())
                .andExpect(jsonPath("$.data.welcomeMessage").doesNotExist())
                .andExpect(jsonPath("$.data.appearance").doesNotExist())
                .andExpect(jsonPath("$.data.featureFlags").doesNotExist());

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
                .andExpect(jsonPath("$.data.citations").doesNotExist())
                .andExpect(jsonPath("$.data.recommendedBoards").doesNotExist())
                .andExpect(jsonPath("$.data.suggestedQuestions").doesNotExist())
                .andExpect(jsonPath("$.data.usedWebSearch").doesNotExist())
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
                .andExpect(jsonPath("$.data[1].answerType").isNotEmpty())
                .andExpect(jsonPath("$.data[1].citations").doesNotExist())
                .andExpect(jsonPath("$.data[1].recommendedBoards").doesNotExist())
                .andExpect(jsonPath("$.data[1].suggestedQuestions").doesNotExist())
                .andExpect(jsonPath("$.data[1].usedWebSearch").doesNotExist());

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
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("\"code\":4001")));
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
                .andExpect(content().string(containsString("event:stage")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")));
    }

    @Test
    void adminPlaygroundShouldExplainHowAQuestionWasAnswered() throws Exception {
        String authorization = "Bearer " + loginAndGetAdminToken();

        mockMvc.perform(post("/admin/ai/playground")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "9个人新手玩什么好？" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.intent").value("RECOMMEND"))
                .andExpect(jsonPath("$.data.reason").isNotEmpty())
                .andExpect(jsonPath("$.data.boards[0].name").value("守卫九人局"))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.timings.route").isNumber());

        // 拦截词以当前配置为准（别的用例可能把它改成了 ["blocked"]），两个都带上
        mockMvc.perform(post("/admin/ai/playground")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "今晚刀谁比较好？blocked" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").value("REFUSE"))
                .andExpect(jsonPath("$.data.answerType").value("REFUSAL"));

        // 小程序用户的 token 不能用调试台
        mockMvc.perform(post("/admin/ai/playground")
                        .header("Authorization", "Bearer " + loginAndGetToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "女巫能自救吗" }
                                """))
                .andExpect(jsonPath("$.code").value(4001));
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
                .andExpect(jsonPath("$.data.quickQuestions[0]").value("can witch save herself?"))
                .andExpect(jsonPath("$.data.featureFlags").doesNotExist());
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

    @Test
    void adminCommentsEndpointShouldSupportStringStatusValues() throws Exception {
        String adminToken = loginAndGetAdminToken();
        jdbcTemplate.update("UPDATE comments SET status = ?", "VISIBLE");

        mockMvc.perform(get("/admin/comments").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].status").value("VISIBLE"));
    }

    @Test
    void seededBoardDetailShouldExposeResolvedRuleDetailAndThemeColors() throws Exception {
        mockMvc.perform(get("/api/boards/2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleDetail.overview").value(containsString("12 人标准局")))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[0].order").value(1))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[0].roleName").value("狼人"))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[0].themeColor").value("#C0262D"))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[0].faction").value("狼人"))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[1].roleName").value("女巫"))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder[1].portrait").isNotEmpty())
                .andExpect(jsonPath("$.data.ruleDetail.roleRules[0].roleName").value("女巫"))
                .andExpect(jsonPath("$.data.ruleDetail.roleRules[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.ruleDetail.sections[0].title").value("警长竞选"))
                .andExpect(jsonPath("$.data.roles[0].themeColor").isNotEmpty());

        mockMvc.perform(get("/api/roles"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].themeColor").value("#7B4FD6"))
                // 图鉴用立绘做卡牌墙
                .andExpect(jsonPath("$.data[0].fullIllustration").value("https://picsum.photos/seed/seerfull/700/900"));

        // 列表卡片带胜利条件；角色详情的板子列表带「第几个醒来」和另外几个角色
        mockMvc.perform(get("/api/boards").param("keyword", "预女猎白"))
                .andExpect(jsonPath("$.data.list[0].winCondition").isNotEmpty());
        mockMvc.perform(get("/api/roles/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.boards[?(@.id == 2)].wakeOrder").value(contains(3)))
                .andExpect(jsonPath("$.data.boards[?(@.id == 2)].lineup.length()").value(contains(3)))
                .andExpect(jsonPath("$.data.boards[?(@.id == 1)].wakeOrder").value(contains((Object) null)));
    }

    @Test
    void boardWithoutRuleDetailShouldReturnEmptyRuleLists() throws Exception {
        mockMvc.perform(get("/api/boards/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleDetail.nightOrder.length()").value(0))
                .andExpect(jsonPath("$.data.ruleDetail.roleRules.length()").value(0))
                .andExpect(jsonPath("$.data.ruleDetail.sections.length()").value(0));
    }

    @Test
    void adminBoardSaveShouldPersistBackgroundImageAndCleanedRuleDetail() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String body = """
                {
                  "name": "规则测试板",
                  "playerCount": 6,
                  "difficulty": "入门",
                  "coverImage": "https://example.com/cover.png",
                  "backgroundImage": "  https://example.com/bg.png  ",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 1, "count": 1}, {"roleId": 9, "count": 3}],
                  "ruleDetail": {
                    "overview": "  测试概述  ",
                    "nightOrder": [
                      {"roleId": 6, "action": "  狼人刀人  "},
                      {"roleId": 1, "action": "预言家查验"}
                    ],
                    "roleRules": [
                      {"roleId": 1, "items": ["", "  首夜必须查验  "]},
                      {"roleId": 9, "items": ["   "]}
                    ],
                    "sections": [
                      {"title": "遗言", "items": ["首夜有遗言", ""]},
                      {"title": "空章节", "items": [" "]}
                    ]
                  }
                }
                """;
        String created = mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.backgroundImage").value("https://example.com/bg.png"))
                .andExpect(jsonPath("$.data.ruleDetail.overview").value("测试概述"))
                .andReturn().getResponse().getContentAsString();
        int boardId = objectMapper.readTree(created).path("data").path("id").asInt();

        try {
            mockMvc.perform(get("/api/boards/{id}", boardId))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.backgroundImage").value("https://example.com/bg.png"))
                    .andExpect(jsonPath("$.data.ruleDetail.nightOrder.length()").value(2))
                    .andExpect(jsonPath("$.data.ruleDetail.nightOrder[0].action").value("狼人刀人"))
                    .andExpect(jsonPath("$.data.ruleDetail.nightOrder[1].order").value(2))
                    .andExpect(jsonPath("$.data.ruleDetail.nightOrder[1].roleName").value("预言家"))
                    .andExpect(jsonPath("$.data.ruleDetail.roleRules.length()").value(1))
                    .andExpect(jsonPath("$.data.ruleDetail.roleRules[0].items.length()").value(1))
                    .andExpect(jsonPath("$.data.ruleDetail.roleRules[0].items[0]").value("首夜必须查验"))
                    .andExpect(jsonPath("$.data.ruleDetail.sections.length()").value(1))
                    .andExpect(jsonPath("$.data.ruleDetail.sections[0].items.length()").value(1));

            // 列表卡片也要带背景图和阵容立绘，小程序列表顶部靠这两个字段
            mockMvc.perform(get("/api/boards").param("keyword", "规则测试板"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.list.length()").value(1))
                    .andExpect(jsonPath("$.data.list[0].backgroundImage").value("https://example.com/bg.png"))
                    .andExpect(jsonPath("$.data.list[0].roles[?(@.roleId == 6)].fullIllustration").value(contains("https://picsum.photos/seed/wolffull/700/900")))
                    .andExpect(jsonPath("$.data.list[0].roles[?(@.roleId == 1)].fullIllustration").value(contains("https://picsum.photos/seed/seerfull/700/900")))
                    .andExpect(jsonPath("$.data.list[0].featuredRoles[?(@.roleId == 6)].fullIllustration").value(contains("https://picsum.photos/seed/wolffull/700/900")));
        } finally {
            mockMvc.perform(delete("/admin/boards/{id}", boardId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void adminBoardSaveRejectsAWitchWakingBeforeTheWolves() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String witchFirst = """
                {
                  "name": "女巫先醒板",
                  "playerCount": 6,
                  "difficulty": "入门",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 2, "count": 1}, {"roleId": 9, "count": 3}],
                  "ruleDetail": {"nightOrder": [{"roleId": 2, "action": "女巫用药"}, {"roleId": 6, "action": "狼人刀人"}]}
                }
                """;
        mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witchFirst))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("女巫须在狼人后")));
    }

    @Test
    void adminBoardSaveShouldRejectRuleDetailThatReferencesRolesOutsideLineup() throws Exception {
        String adminToken = loginAndGetAdminToken();
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM boards", Integer.class);
        String outsideLineup = """
                {
                  "name": "非法规则板",
                  "playerCount": 6,
                  "difficulty": "入门",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 9, "count": 4}],
                  "ruleDetail": {"nightOrder": [{"roleId": 2, "action": "女巫用药"}]}
                }
                """;
        mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outsideLineup))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("阵容")));

        String duplicateNotes = """
                {
                  "name": "重复细则板",
                  "playerCount": 6,
                  "difficulty": "入门",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 9, "count": 4}],
                  "ruleDetail": {"roleRules": [
                    {"roleId": 6, "items": ["可以空刀"]},
                    {"roleId": 6, "items": ["可以自刀"]}
                  ]}
                }
                """;
        mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateNotes))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("只能写一组")));

        String blankAction = """
                {
                  "name": "空行动板",
                  "playerCount": 6,
                  "difficulty": "入门",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 9, "count": 4}],
                  "ruleDetail": {"nightOrder": [{"roleId": 6, "action": "  "}]}
                }
                """;
        mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankAction))
                .andExpect(jsonPath("$.code").value(4000));

        Assertions.assertEquals(before, jdbcTemplate.queryForObject("SELECT COUNT(1) FROM boards", Integer.class));
    }

    @Test
    void adminBoardSaveShouldRejectLineupThatDoesNotMatchPlayerCount() throws Exception {
        String adminToken = loginAndGetAdminToken();
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM boards", Integer.class);
        String body = """
                {
                  "name": "人数不符板",
                  "playerCount": 9,
                  "difficulty": "入门",
                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 9, "count": 4}]
                }
                """;
        mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("6 人")));
        Assertions.assertEquals(before, jdbcTemplate.queryForObject("SELECT COUNT(1) FROM boards", Integer.class));
    }

    @Test
    void offlineBoardShouldDisappearFromMiniProgramUntilBackOnline() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String created = mockMvc.perform(post("/admin/boards")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "下架测试板",
                                  "playerCount": 6,
                                  "difficulty": "入门",
                                  "roles": [{"roleId": 6, "count": 2}, {"roleId": 9, "count": 4}]
                                }
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1))
                .andReturn().getResponse().getContentAsString();
        int boardId = objectMapper.readTree(created).path("data").path("id").asInt();

        try {
            mockMvc.perform(patch("/admin/boards/{id}/status", boardId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"0\"}"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value(0));
            mockMvc.perform(get("/api/boards").param("keyword", "下架测试板"))
                    .andExpect(jsonPath("$.data.list.length()").value(0));
            mockMvc.perform(get("/api/boards/{id}", boardId))
                    .andExpect(jsonPath("$.code").value(4004));
            // 后台列表仍然能看到下架的板子
            mockMvc.perform(get("/admin/boards").header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.data[?(@.id == " + boardId + ")].status").value(contains(0)));

            mockMvc.perform(patch("/admin/boards/{id}/status", boardId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"maybe\"}"))
                    .andExpect(jsonPath("$.code").value(4000));

            mockMvc.perform(patch("/admin/boards/{id}/status", boardId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"1\"}"))
                    .andExpect(jsonPath("$.data.status").value(1));
            mockMvc.perform(get("/api/boards/{id}", boardId))
                    .andExpect(jsonPath("$.code").value(0));
        } finally {
            mockMvc.perform(delete("/admin/boards/{id}", boardId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void adminSummaryShouldReportContentGaps() throws Exception {
        String adminToken = loginAndGetAdminToken();
        Integer boards = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM boards", Integer.class);
        Integer noBackground = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM boards WHERE background_image IS NULL OR background_image = ''", Integer.class);
        Integer noIllustration = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM roles WHERE full_illustration IS NULL OR full_illustration = ''", Integer.class);
        mockMvc.perform(get("/admin/summary").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.boardCount").value(boards))
                .andExpect(jsonPath("$.data.boardsWithoutBackground").value(noBackground))
                .andExpect(jsonPath("$.data.rolesWithoutIllustration").value(noIllustration))
                .andExpect(jsonPath("$.data.assistantQuestionsToday").isNumber())
                .andExpect(jsonPath("$.data.recentBoards").isArray());
    }

    @Test
    void roleRelationsShouldBeValidatedAndExpandedInRoleDetail() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String created = mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "关系测试角色", "alias": " 测试真名 ", "title": "试验品", "faction": "好人", "roleType": "神职",
                                 "skill": "测试", "relations": [{"roleId": 2, "label": " 老朋友 "}, {"roleId": 6, "label": "宿敌"}]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.alias").value("测试真名"))
                .andExpect(jsonPath("$.data.relations.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        int roleId = objectMapper.readTree(created).path("data").path("id").asInt();

        try {
            mockMvc.perform(get("/api/roles/{id}", roleId))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.title").value("试验品"))
                    .andExpect(jsonPath("$.data.relations[0].name").value("女巫"))
                    .andExpect(jsonPath("$.data.relations[0].label").value("老朋友"))
                    .andExpect(jsonPath("$.data.relations[1].roleId").value(6));

            String self = """
                    {"name": "关系测试角色", "faction": "好人", "roleType": "神职", "skill": "测试",
                     "relations": [{"roleId": %d, "label": "自己"}]}
                    """.formatted(roleId);
            mockMvc.perform(put("/admin/roles/{id}", roleId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(self))
                    .andExpect(jsonPath("$.code").value(4000));

            mockMvc.perform(put("/admin/roles/{id}", roleId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "关系测试角色", "faction": "好人", "roleType": "神职", "skill": "测试",
                                     "relations": [{"roleId": 2, "label": "a"}, {"roleId": 2, "label": "b"}]}
                                    """))
                    .andExpect(jsonPath("$.code").value(4000));

            // 清空关系和称号
            mockMvc.perform(put("/admin/roles/{id}", roleId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "关系测试角色", "faction": "好人", "roleType": "神职", "skill": "测试", "relations": []}
                                    """))
                    .andExpect(jsonPath("$.code").value(0));
            mockMvc.perform(get("/api/roles/{id}", roleId))
                    .andExpect(jsonPath("$.data.title").doesNotExist())
                    .andExpect(jsonPath("$.data.relations.length()").value(0));
        } finally {
            mockMvc.perform(delete("/admin/roles/{id}", roleId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void assistantModelsShouldBeManagedAndSwitchedFromAdmin() throws Exception {
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(get("/admin/ai/models"))
                .andExpect(jsonPath("$.code").value(4001));
        // 第一次打开时把原来的 DeepSeek 配置搬进来
        mockMvc.perform(get("/admin/ai/models").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("DeepSeek 官方"));

        mockMvc.perform(post("/admin/ai/models")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "坏地址", "baseUrl": "ftp://example.com", "model": "m"}
                                """))
                .andExpect(jsonPath("$.code").value(4000));

        String created = mockMvc.perform(post("/admin/ai/models")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "测试模型", "baseUrl": "http://127.0.0.1:1/v1/chat/completions", "model": "test-model", "apiKey": "sk-test-model-9876"}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.baseUrl").value("http://127.0.0.1:1/v1"))
                .andExpect(jsonPath("$.data.apiKey").value("********9876"))
                .andExpect(jsonPath("$.data.active").value(false))
                .andReturn().getResponse().getContentAsString();
        int modelId = objectMapper.readTree(created).path("data").path("id").asInt();

        try {
            // 打码值原样传回不会覆盖真实 Key
            mockMvc.perform(put("/admin/ai/models/{id}", modelId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "测试模型改名", "baseUrl": "http://127.0.0.1:1/v1", "model": "test-model", "apiKey": "********9876"}
                                    """))
                    .andExpect(jsonPath("$.data.name").value("测试模型改名"))
                    .andExpect(jsonPath("$.data.apiKey").value("********9876"));
            Assertions.assertEquals("sk-test-model-9876",
                    jdbcTemplate.queryForObject("SELECT api_key FROM assistant_models WHERE id = ?", String.class, modelId));

            mockMvc.perform(post("/admin/ai/models/{id}/activate", modelId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.active").value(true));
            Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(1) FROM assistant_models WHERE active = 1", Integer.class));

            // 当前在用的不能停用、不能删
            mockMvc.perform(put("/admin/ai/models/{id}", modelId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "测试模型改名", "baseUrl": "http://127.0.0.1:1/v1", "model": "test-model", "enabled": false}
                                    """))
                    .andExpect(jsonPath("$.code").value(4000));
            mockMvc.perform(delete("/admin/ai/models/{id}", modelId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(4000));

            // 测试连接：地址不通时给出原因，不抛异常
            mockMvc.perform(post("/admin/ai/models/test")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\": " + modelId + "}"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.error").isNotEmpty());

            // 后台的 AI 配置里还是原来的 DeepSeek 那一份，不会被当前模型覆盖
            mockMvc.perform(get("/admin/ai/config").header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.data.provider.baseUrl").value(org.hamcrest.Matchers.not(containsString("127.0.0.1:1"))));
        } finally {
            // 别让其他用例拿着这个不通的模型去调用
            jdbcTemplate.update("DELETE FROM assistant_models WHERE id = ?", modelId);
        }
    }

    @Test
    void roleDraftShouldRequireAdminAndConfiguredDeepSeekKey() throws Exception {
        String body = """
                {"name": "钟楼信使", "faction": "好人", "roleType": "神职", "skill": "每晚可以送一封信"}
                """;
        mockMvc.perform(post("/admin/roles/draft").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(4001));

        // test profile 没有配 DeepSeek Key：直接提示去配置，不去请求外部接口
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(post("/admin/roles/draft")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("模型")));

        mockMvc.perform(post("/admin/roles/draft")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  \"}"))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void worldLoreShouldSaveFromAdminAndExpandEventRoles() throws Exception {
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(get("/admin/world"))
                .andExpect(jsonPath("$.code").value(4001));

        String body = """
                {"townName": " 灰铃镇 ", "tagline": "银钟响过，不出家门", "intro": "山谷小镇",
                 "factions": [{"name": "守钟人", "camp": "good", "description": "镇民"}, {"name": "", "description": ""}],
                 "events": [{"era": "三百年前", "title": "银钟之约", "content": "立约", "roleIds": [1, 2, 1]},
                            {"era": "", "title": "", "content": ""}]}
                """;
        mockMvc.perform(put("/admin/world")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.townName").value("灰铃镇"))
                .andExpect(jsonPath("$.data.factions.length()").value(1))
                .andExpect(jsonPath("$.data.events.length()").value(1))
                .andExpect(jsonPath("$.data.events[0].roleIds.length()").value(2));

        mockMvc.perform(get("/api/world"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tagline").value("银钟响过，不出家门"))
                .andExpect(jsonPath("$.data.events[0].roles[0].name").value("预言家"))
                .andExpect(jsonPath("$.data.events[0].roles[1].name").value("女巫"));

        mockMvc.perform(put("/admin/world")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factions": [{"name": "外星人", "camp": "alien"}]}
                                """))
                .andExpect(jsonPath("$.code").value(4000));
        mockMvc.perform(put("/admin/world")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events": [{"title": "幽灵", "roleIds": [99999]}]}
                                """))
                .andExpect(jsonPath("$.code").value(4000));
        // 失败的保存不应覆盖已有内容
        mockMvc.perform(get("/api/world"))
                .andExpect(jsonPath("$.data.townName").value("灰铃镇"));
    }

    @Test
    void adminRoleSaveShouldValidateAndNormalizeThemeColor() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String invalid = """
                {"name": "颜色测试A", "faction": "好人", "roleType": "神职", "skill": "测试", "themeColor": "red"}
                """;
        mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("主题色")));

        String valid = """
                {"name": "颜色测试B", "faction": "好人", "roleType": "神职", "skill": "测试", "themeColor": "#a1b2c3"}
                """;
        String created = mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.themeColor").value("#A1B2C3"))
                .andReturn().getResponse().getContentAsString();
        int roleId = objectMapper.readTree(created).path("data").path("id").asInt();
        try {
            mockMvc.perform(get("/api/roles/{id}", roleId))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.themeColor").value("#A1B2C3"));
        } finally {
            mockMvc.perform(delete("/admin/roles/{id}", roleId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void adminRoleSaveCanClearArtAndThemeColor() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String withArt = """
                {"name": "清图测试", "faction": "好人", "roleType": "神职", "skill": "测试",
                 "portrait": "https://example.com/p.webp", "fullIllustration": "https://example.com/f.webp", "themeColor": "#112233"}
                """;
        String created = mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withArt))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        int roleId = objectMapper.readTree(created).path("data").path("id").asInt();
        try {
            // 退役旧画风时要能把头像、立绘和主题色清空（MyBatis-Plus 默认会跳过 null 字段）
            String cleared = """
                    {"name": "清图测试", "faction": "好人", "roleType": "神职", "skill": "测试",
                     "portrait": null, "fullIllustration": null, "themeColor": null}
                    """;
            mockMvc.perform(put("/admin/roles/{id}", roleId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cleared))
                    .andExpect(jsonPath("$.code").value(0));
            java.util.Map<String, Object> row = jdbcTemplate.queryForMap("SELECT portrait, full_illustration, theme_color FROM roles WHERE id = ?", roleId);
            Assertions.assertNull(row.get("PORTRAIT"));
            Assertions.assertNull(row.get("FULL_ILLUSTRATION"));
            Assertions.assertNull(row.get("THEME_COLOR"));
        } finally {
            mockMvc.perform(delete("/admin/roles/{id}", roleId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void adminCanSetAndClearARoleMechanic() throws Exception {
        String adminToken = loginAndGetAdminToken();
        String created = mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "看门人", "faction": "好人", "roleType": "神职", "skill": "测试", "mechanic": "guard"}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mechanic").value("guard"))
                .andReturn().getResponse().getContentAsString();
        int roleId = objectMapper.readTree(created).path("data").path("id").asInt();
        try {
            mockMvc.perform(put("/admin/roles/{id}", roleId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "看门人", "faction": "好人", "roleType": "神职", "skill": "测试", "mechanic": null}
                                    """))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.mechanic").doesNotExist());
            Assertions.assertNull(jdbcTemplate.queryForObject("SELECT mechanic FROM roles WHERE id = ?", String.class, roleId));
        } finally {
            mockMvc.perform(delete("/admin/roles/{id}", roleId).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void adminRejectsAnUnknownMechanic() throws Exception {
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(post("/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "猎魔人", "faction": "好人", "roleType": "神职", "skill": "测试", "mechanic": "demon_hunter"}
                                """))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value(containsString("技能机制")));
    }

    @Test
    void mechanicsEndpointListsTheSevenMechanics() throws Exception {
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(get("/admin/roles/mechanics").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].code").value("villager"))
                .andExpect(jsonPath("$.data[?(@.code == 'guard')].nightPriority").value(1))
                .andExpect(jsonPath("$.data[?(@.code == 'wolf')].wolfTeam").value(true));
    }

    @Test
    void seededRolesCarryTheirMechanic() throws Exception {
        String adminToken = loginAndGetAdminToken();
        mockMvc.perform(get("/admin/roles").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.name == '守卫')].mechanic").value("guard"))
                .andExpect(jsonPath("$.data[?(@.name == '白痴')].mechanic").value("idiot"))
                .andExpect(jsonPath("$.data[?(@.name == '骑士')].mechanic").value(org.hamcrest.Matchers.contains((Object) null)));
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
