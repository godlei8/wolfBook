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
import java.util.List;
import java.util.Map;

/**
 * AI 助手后台配置服务。
 *
 * <p>读取和保存管理员配置，包括基础开关、模型参数、检索参数、提示词、安全策略和搜索配置。
 * 配置更新后会清理回答缓存，避免旧提示词或旧检索参数继续影响新回答。</p>
 */
@Service
public class AssistantConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AssistantConfigMapper assistantConfigMapper;
    private final AssistantSessionMapper assistantSessionMapper;
    private final AssistantProperties assistantProperties;
    private final AssistantCacheService assistantCacheService;
    private final AssistantSearchService assistantSearchService;
    private final ObjectMapper objectMapper;

    public AssistantConfigService(
            AssistantConfigMapper assistantConfigMapper,
            AssistantSessionMapper assistantSessionMapper,
            AssistantProperties assistantProperties,
            AssistantCacheService assistantCacheService,
            AssistantSearchService assistantSearchService,
            ObjectMapper objectMapper
    ) {
        this.assistantConfigMapper = assistantConfigMapper;
        this.assistantSessionMapper = assistantSessionMapper;
        this.assistantProperties = assistantProperties;
        this.assistantCacheService = assistantCacheService;
        this.assistantSearchService = assistantSearchService;
        this.objectMapper = objectMapper;
    }

    public AssistantDtos.AdminAiConfig getAdminConfig() {
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        AssistantDtos.AdminAiConfig defaults = AssistantDefaults.adminConfig(assistantProperties);
        return new AssistantDtos.AdminAiConfig(
                sanitizeBaseSection(readSection(entity.getBaseConfig(), AssistantDtos.BaseSection.class, defaults.base())),
                readSection(entity.getPromptConfig(), AssistantDtos.PromptSection.class, defaults.prompt()),
                readSection(entity.getRetrievalConfig(), AssistantDtos.RetrievalSection.class, defaults.retrieval()),
                readSection(entity.getSearchConfig(), AssistantDtos.SearchSection.class, defaults.search()),
                readSection(entity.getSafetyConfig(), AssistantDtos.SafetySection.class, defaults.safety()),
                readSection(entity.getUiConfig(), AssistantDtos.UiSection.class, defaults.ui())
        );
    }

    public AssistantDtos.AdminAiConfig saveAdminConfig(AssistantDtos.AdminAiConfig config) {
        if (config == null) {
            throw new ApiException(4000, "AI config can not be empty");
        }
        AssistantDtos.AdminAiConfig normalizedConfig = new AssistantDtos.AdminAiConfig(
                sanitizeBaseSection(config.base()),
                config.prompt(),
                config.retrieval(),
                config.search(),
                config.safety(),
                config.ui()
        );
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        entity.setBaseConfig(write(normalizedConfig.base()));
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
        AssistantDtos.AdminAiConfig config = getAdminConfig();
        String configVersion = assistantCacheService.buildConfigVersion(config);
        AssistantCacheService.BootstrapShell shell = assistantCacheService.getBootstrapShell(configVersion);
        if (shell == null) {
            shell = new AssistantCacheService.BootstrapShell(
                    config.base().enabled(),
                    config.base().welcomeMessage(),
                    config.base().quickQuestions(),
                    new AssistantDtos.Appearance(
                            config.ui().mascot(),
                            config.ui().accentColor(),
                            config.ui().dockLabel()
                    ),
                    new AssistantDtos.FeatureFlags(
                            config.search().webSearchEnabled() && assistantSearchService.isAvailable(),
                            true,
                            true
                    )
            );
            assistantCacheService.cacheBootstrapShell(configVersion, shell);
        }
        AssistantSessionEntity latestSession = assistantSessionMapper.selectOne(
                new LambdaQueryWrapper<AssistantSessionEntity>()
                        .eq(AssistantSessionEntity::getOpenid, openid)
                        .orderByDesc(AssistantSessionEntity::getUpdateTime)
                        .last("LIMIT 1")
        );
        return new AssistantDtos.AssistantBootstrapResponse(
                shell.enabled(),
                shell.welcomeMessage(),
                shell.quickQuestions(),
                latestSession == null ? null : latestSession.getSessionId(),
                shell.appearance(),
                shell.featureFlags()
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

    private AssistantDtos.BaseSection sanitizeBaseSection(AssistantDtos.BaseSection base) {
        if (base == null) {
            return AssistantDefaults.adminConfig(assistantProperties).base();
        }
        String embeddingModel = normalizeEmbeddingModel(base.embeddingModel());
        return new AssistantDtos.BaseSection(
                base.enabled(),
                base.welcomeMessage(),
                base.quickQuestions(),
                base.chatModel(),
                embeddingModel,
                base.temperature(),
                base.maxSuggestions()
        );
    }

    private String normalizeEmbeddingModel(String configuredEmbeddingModel) {
        String defaultLabel = assistantProperties.getDefaultEmbeddingModelLabel();
        if (!StringUtils.hasText(configuredEmbeddingModel)) {
            return defaultLabel;
        }
        String normalized = configuredEmbeddingModel.trim();
        if (!"minimax".equalsIgnoreCase(assistantProperties.getEmbeddingProvider())
                && ("text-embedding-3-small".equalsIgnoreCase(normalized) || "embo-01".equalsIgnoreCase(normalized))) {
            return defaultLabel;
        }
        if ("minimax".equalsIgnoreCase(assistantProperties.getEmbeddingProvider())
                && AssistantProperties.OLLAMA_DEFAULT_EMBEDDING_MODEL.equalsIgnoreCase(normalized)) {
            return defaultLabel;
        }
        return normalized;
    }
}
