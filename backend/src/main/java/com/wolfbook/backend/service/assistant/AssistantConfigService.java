package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantConfigEntity;
import com.wolfbook.backend.entity.AssistantSessionEntity;
import com.wolfbook.backend.mapper.AssistantConfigMapper;
import com.wolfbook.backend.mapper.AssistantSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AssistantConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SECRET_MASK = "********";
    private static final int SECRET_SUFFIX_LENGTH = 4;

    private final AssistantConfigMapper assistantConfigMapper;
    private final AssistantSessionMapper assistantSessionMapper;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;

    public AssistantConfigService(
            AssistantConfigMapper assistantConfigMapper,
            AssistantSessionMapper assistantSessionMapper,
            AssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.assistantConfigMapper = assistantConfigMapper;
        this.assistantSessionMapper = assistantSessionMapper;
        this.assistantProperties = assistantProperties;
        this.objectMapper = objectMapper;
    }

    public AssistantDtos.AdminAiConfig getAdminConfig() {
        return maskSecrets(getRuntimeConfig());
    }

    public AssistantDtos.AdminAiConfig getRuntimeConfig() {
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        AssistantDtos.AdminAiConfig defaults = AssistantDefaults.adminConfig(assistantProperties);
        return new AssistantDtos.AdminAiConfig(
                sanitizeBaseSection(readSection(entity.getBaseConfig(), AssistantDtos.BaseSection.class, defaults.base())),
                sanitizeProviderSection(
                        readSection(entity.getProviderConfig(), AssistantDtos.ProviderSection.class, defaults.provider()),
                        defaults.provider()
                ),
                sanitizeVolcengineSection(
                        readSection(entity.getVolcengineConfig(), AssistantDtos.VolcengineSection.class, defaults.volcengine()),
                        defaults.volcengine()
                ),
                readSection(entity.getPromptConfig(), AssistantDtos.PromptSection.class, defaults.prompt()),
                readSection(entity.getRetrievalConfig(), AssistantDtos.RetrievalSection.class, defaults.retrieval()),
                sanitizeSearchSection(readSection(entity.getSearchConfig(), AssistantDtos.SearchSection.class, defaults.search())),
                readSection(entity.getSafetyConfig(), AssistantDtos.SafetySection.class, defaults.safety()),
                readSection(entity.getUiConfig(), AssistantDtos.UiSection.class, defaults.ui())
        );
    }

    public AssistantDtos.AdminAiConfig saveAdminConfig(AssistantDtos.AdminAiConfig config) {
        if (config == null) {
            throw new ApiException(4000, "AI config can not be empty");
        }
        AssistantDtos.AdminAiConfig existing = getRuntimeConfig();
        AssistantDtos.AdminAiConfig normalizedConfig = new AssistantDtos.AdminAiConfig(
                sanitizeBaseSection(config.base()),
                sanitizeProviderSection(config.provider(), existing.provider()),
                sanitizeVolcengineSection(config.volcengine(), existing.volcengine()),
                config.prompt(),
                config.retrieval(),
                sanitizeSearchSection(config.search()),
                config.safety(),
                config.ui()
        );
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        entity.setBaseConfig(write(normalizedConfig.base()));
        entity.setProviderConfig(write(normalizedConfig.provider()));
        entity.setVolcengineConfig(write(normalizedConfig.volcengine()));
        entity.setPromptConfig(write(normalizedConfig.prompt()));
        entity.setRetrievalConfig(write(normalizedConfig.retrieval()));
        entity.setSearchConfig(write(normalizedConfig.search()));
        entity.setSafetyConfig(write(normalizedConfig.safety()));
        entity.setUiConfig(write(normalizedConfig.ui()));
        entity.setUpdateTime(LocalDateTime.now());
        if (entity.getId() == null) {
            entity.setCreateTime(LocalDateTime.now());
            assistantConfigMapper.insert(entity);
        } else {
            assistantConfigMapper.updateById(entity);
        }
        return getAdminConfig();
    }

    public AssistantDtos.AssistantBootstrapResponse getBootstrap(String openid) {
        AssistantDtos.AdminAiConfig config = getRuntimeConfig();
        AssistantSessionEntity latestSession = assistantSessionMapper.selectOne(
                new LambdaQueryWrapper<AssistantSessionEntity>()
                        .eq(AssistantSessionEntity::getOpenid, openid)
                        .orderByDesc(AssistantSessionEntity::getUpdateTime)
                        .last("LIMIT 1")
        );
        return new AssistantDtos.AssistantBootstrapResponse(
                config.base().enabled(),
                config.base().welcomeMessage(),
                config.base().quickQuestions(),
                latestSession == null ? null : latestSession.getSessionId(),
                new AssistantDtos.Appearance(
                        config.ui().mascot(),
                        config.ui().accentColor(),
                        config.ui().dockLabel()
                ),
                new AssistantDtos.FeatureFlags(
                        config.search().webSearchEnabled() && assistantProperties.isWebSearchEnabled(),
                        true,
                        true
                )
        );
    }

    public Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new ApiException(5000, "Failed to read assistant json");
        }
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(5000, "Failed to write assistant json");
        }
    }

    private AssistantConfigEntity getOrCreateConfigEntity() {
        AssistantConfigEntity entity = assistantConfigMapper.selectById(1);
        if (entity != null) {
            return entity;
        }
        AssistantDtos.AdminAiConfig defaults = AssistantDefaults.adminConfig(assistantProperties);
        AssistantConfigEntity created = new AssistantConfigEntity();
        created.setId(1);
        created.setBaseConfig(write(defaults.base()));
        created.setProviderConfig(write(defaults.provider()));
        created.setVolcengineConfig(write(defaults.volcengine()));
        created.setPromptConfig(write(defaults.prompt()));
        created.setRetrievalConfig(write(defaults.retrieval()));
        created.setSearchConfig(write(defaults.search()));
        created.setSafetyConfig(write(defaults.safety()));
        created.setUiConfig(write(defaults.ui()));
        created.setCreateTime(LocalDateTime.now());
        created.setUpdateTime(LocalDateTime.now());
        assistantConfigMapper.insert(created);
        return created;
    }

    private <T> T readSection(String value, Class<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private AssistantDtos.AdminAiConfig maskSecrets(AssistantDtos.AdminAiConfig config) {
        return new AssistantDtos.AdminAiConfig(
                config.base(),
                new AssistantDtos.ProviderSection(
                        config.provider().platform(),
                        config.provider().model(),
                        config.provider().baseUrl(),
                        maskSecret(config.provider().apiKey())
                ),
                new AssistantDtos.VolcengineSection(
                        config.volcengine().baseUrl(),
                        config.volcengine().embeddingModel(),
                        maskSecret(config.volcengine().embeddingApiKey()),
                        config.volcengine().searchModel(),
                        maskSecret(config.volcengine().searchApiKey())
                ),
                config.prompt(),
                config.retrieval(),
                config.search(),
                config.safety(),
                config.ui()
        );
    }

    private AssistantDtos.BaseSection sanitizeBaseSection(AssistantDtos.BaseSection base) {
        if (base == null) {
            return AssistantDefaults.adminConfig(assistantProperties).base();
        }
        return new AssistantDtos.BaseSection(
                base.enabled(),
                base.welcomeMessage(),
                base.quickQuestions(),
                base.temperature(),
                base.maxSuggestions()
        );
    }

    private AssistantDtos.ProviderSection sanitizeProviderSection(
            AssistantDtos.ProviderSection provider,
            AssistantDtos.ProviderSection existing
    ) {
        AssistantDtos.ProviderSection defaults = AssistantDefaults.adminConfig(assistantProperties).provider();
        AssistantDtos.ProviderSection current = existing == null ? defaults : existing;
        if (provider == null) {
            return current;
        }
        String baseUrl = StringUtils.hasText(provider.baseUrl()) ? provider.baseUrl().trim() : defaults.baseUrl();
        String apiKey = resolveSecret(provider.apiKey(), current.apiKey(), defaults.apiKey());
        return new AssistantDtos.ProviderSection(
                AssistantProperties.DEEPSEEK_PLATFORM,
                assistantProperties.getDeepSeek().getModel(),
                baseUrl,
                apiKey
        );
    }

    private AssistantDtos.SearchSection sanitizeSearchSection(AssistantDtos.SearchSection search) {
        if (!assistantProperties.isWebSearchEnabled()) {
            return new AssistantDtos.SearchSection(false);
        }
        return search == null ? new AssistantDtos.SearchSection(true) : new AssistantDtos.SearchSection(search.webSearchEnabled());
    }

    private AssistantDtos.VolcengineSection sanitizeVolcengineSection(
            AssistantDtos.VolcengineSection volcengine,
            AssistantDtos.VolcengineSection existing
    ) {
        AssistantDtos.VolcengineSection defaults = AssistantDefaults.adminConfig(assistantProperties).volcengine();
        AssistantDtos.VolcengineSection current = existing == null ? defaults : existing;
        if (volcengine == null) {
            return current;
        }
        String embeddingApiKey = resolveSecret(
                volcengine.embeddingApiKey(),
                current.embeddingApiKey(),
                defaults.embeddingApiKey()
        );
        String searchApiKey = resolveSecret(
                volcengine.searchApiKey(),
                current.searchApiKey(),
                defaults.searchApiKey()
        );
        return new AssistantDtos.VolcengineSection(
                assistantProperties.getVolcengine().getBaseUrl(),
                assistantProperties.getVolcengine().getEmbeddingModel(),
                embeddingApiKey,
                assistantProperties.getVolcengine().getSearchModel(),
                searchApiKey
        );
    }

    private String resolveSecret(String submitted, String existing, String fallback) {
        String current = existing == null ? fallback : existing;
        if (submitted == null) {
            return current;
        }
        String normalized = submitted.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (StringUtils.hasText(current) && normalized.equals(maskSecret(current))) {
            return current;
        }
        return normalized;
    }

    private String maskSecret(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.length() <= SECRET_SUFFIX_LENGTH) {
            return SECRET_MASK;
        }
        return SECRET_MASK + normalized.substring(normalized.length() - SECRET_SUFFIX_LENGTH);
    }
}
