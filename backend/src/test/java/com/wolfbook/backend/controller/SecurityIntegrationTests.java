package com.wolfbook.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTests {

    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void forgedAdminTokenCannotReachAdminEndpoints() throws Exception {
        String forged = Base64.getUrlEncoder().encodeToString("admin:nobody".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/admin/summary").header("Authorization", "Bearer " + forged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void missingAuthorizationHeaderIsReportedAsUnauthenticated() throws Exception {
        mockMvc.perform(get("/admin/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void disabledAdminAccountIsRejectedEvenWithValidToken() throws Exception {
        String token = loginAdmin();
        jdbcTemplate.update("UPDATE admin_users SET status = 0 WHERE username = 'admin'");
        try {
            mockMvc.perform(get("/admin/summary").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(4001));
        } finally {
            jdbcTemplate.update("UPDATE admin_users SET status = 1 WHERE username = 'admin'");
        }
    }

    @Test
    void anonymousUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_HEADER);

        mockMvc.perform(multipart("/api/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void nonImageUploadIsRejected() throws Exception {
        String token = loginUser();
        MockMultipartFile html = new MockMultipartFile("file", "evil.html", "text/html", "<script>1</script>".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile disguised = new MockMultipartFile("file", "evil.png", "image/png", "<script>1</script>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/upload").file(html).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(4000));
        mockMvc.perform(multipart("/api/upload").file(disguised).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void realImageUploadSucceedsForLoggedInUser() throws Exception {
        String token = loginUser();
        MockMultipartFile png = new MockMultipartFile("file", "ok.png", "image/png", PNG_HEADER);

        mockMvc.perform(multipart("/api/upload").file(png).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").isNotEmpty());
    }

    @Test
    void unknownServerErrorsDoNotLeakInternalMessages() throws Exception {
        mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value("请求格式错误"));
    }

    private String loginUser() throws Exception {
        String body = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"security-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private String loginAdmin() throws Exception {
        String body = mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wolf123\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
