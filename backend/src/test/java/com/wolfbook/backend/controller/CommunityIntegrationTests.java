package com.wolfbook.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void offlinePostIsHiddenFromDetailAndCannotBeCommented() throws Exception {
        String token = login("community-a");
        int postId = createPost(token, "offline test post");
        jdbcTemplate.update("UPDATE posts SET status = 0 WHERE id = ?", postId);

        mockMvc.perform(get("/api/posts/{id}", postId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(4004));
        mockMvc.perform(post("/api/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":" + postId + ",\"content\":\"hi\"}"))
                .andExpect(jsonPath("$.code").value(4004));
    }

    @Test
    void commentCountTracksComments() throws Exception {
        String token = login("community-b");
        int postId = createPost(token, "count test post");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/comments")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"postId\":" + postId + ",\"content\":\"c" + i + "\"}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        mockMvc.perform(get("/api/posts/{id}", postId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.commentCount").value(3));
    }

    @Test
    void postListPageSizeIsCapped() throws Exception {
        mockMvc.perform(get("/api/posts").param("page", "1").param("size", "1000"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    void postListPaginatesOnTheServer() throws Exception {
        String token = login("community-c");
        createPost(token, "page test 1");
        createPost(token, "page test 2");

        String body = mockMvc.perform(get("/api/posts").param("page", "1").param("size", "1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        Assertions.assertEquals(1, data.path("list").size());
        Assertions.assertTrue(data.path("total").asInt() >= 2);
        Assertions.assertFalse(data.path("list").get(0).has("openid"), "作者 openid 不应下发到小程序");
    }

    private int createPost(String token, String content) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\",\"images\":[]}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        Assertions.assertEquals(0, root.path("code").asInt(), body);
        return root.path("data").path("id").asInt();
    }

    private String login(String code) throws Exception {
        String body = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
