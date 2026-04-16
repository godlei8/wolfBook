package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * AI 助手缓存服务。
 *
 * <p>封装 Redis 读写，缓存启动页配置、结构化推荐和知识库问答。
 * 缓存 key 会带上配置版本、知识库版本和回答管线版本，保证后台改配置或检索逻辑后旧答案自动失效。</p>
 */
@Service
public class AssistantCacheService {

    private static final Duration BOOTSTRAP_TTL = Duration.ofMinutes(10);
    private static final Duration STRUCTURED_TTL = Duration.ofHours(6);
    private static final Duration KNOWLEDGE_TTL = Duration.ofHours(24);
    private static final String ANSWER_CACHE_SCHEMA = "rag-grounded-v3";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;

    public AssistantCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public String buildConfigVersion(AssistantDtos.AdminAiConfig config) {
        return digest(write(config));
    }

    public BootstrapShell getBootstrapShell(String configVersion) {
        return read(key("bootstrap", configVersion), BootstrapShell.class);
    }

    public void cacheBootstrapShell(String configVersion, BootstrapShell shell) {
        write(key("bootstrap", configVersion), shell, BOOTSTRAP_TTL);
    }

    public String buildAnswerCacheKey(String question, String scene, String configVersion) {
        String normalizedQuestion = normalizeQuestion(question);
        String normalizedScene = scene == null ? "general" : scene.trim().toLowerCase(Locale.ROOT);
        return key("answer", ANSWER_CACHE_SCHEMA, configVersion, normalizedScene, digest(normalizedQuestion));
    }

    public CachedAnswer getAnswer(String cacheKey) {
        return read(cacheKey, CachedAnswer.class);
    }

    public void cacheAnswer(String cacheKey, CachedAnswer answer) {
        if (answer == null) {
            return;
        }
        Duration ttl = AssistantConstants.ANSWER_STRUCTURED.equals(answer.answerType()) ? STRUCTURED_TTL : KNOWLEDGE_TTL;
        write(cacheKey, answer, ttl);
    }

    private <T> T read(String key, Class<T> type) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void write(String key, Object value, Duration ttl) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, write(value), ttl);
        } catch (Exception ignored) {
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String key(String... parts) {
        return "assistant:" + String.join(":", parts);
    }

    private String normalizeQuestion(String question) {
        return (question == null ? "" : question)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private String digest(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public record BootstrapShell(
            boolean enabled,
            String welcomeMessage,
            List<String> quickQuestions,
            AssistantDtos.Appearance appearance,
            AssistantDtos.FeatureFlags featureFlags
    ) {
    }

    public record CachedAnswer(
            String answer,
            String answerType,
            List<AssistantDtos.AssistantCitation> citations,
            List<AssistantDtos.RecommendedBoardCard> recommendedBoards,
            List<String> suggestedQuestions,
            boolean usedWebSearch
    ) {
        static CachedAnswer fromResponse(AssistantDtos.AssistantAskResponse response) {
            return new CachedAnswer(
                    response.answer(),
                    response.answerType(),
                    response.citations(),
                    response.recommendedBoards(),
                    response.suggestedQuestions(),
                    response.usedWebSearch()
            );
        }
    }
}
