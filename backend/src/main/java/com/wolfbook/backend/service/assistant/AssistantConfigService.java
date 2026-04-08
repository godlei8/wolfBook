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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AssistantConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        return new AssistantDtos.AdminAiConfig(
                readSection(entity.getBaseConfig(), AssistantDtos.BaseSection.class, AssistantDefaults.adminConfig(assistantProperties).base()),
                readSection(entity.getPromptConfig(), AssistantDtos.PromptSection.class, AssistantDefaults.adminConfig(assistantProperties).prompt()),
                readSection(entity.getRetrievalConfig(), AssistantDtos.RetrievalSection.class, AssistantDefaults.adminConfig(assistantProperties).retrieval()),
                readSection(entity.getSearchConfig(), AssistantDtos.SearchSection.class, AssistantDefaults.adminConfig(assistantProperties).search()),
                readSection(entity.getSafetyConfig(), AssistantDtos.SafetySection.class, AssistantDefaults.adminConfig(assistantProperties).safety()),
                readSection(entity.getUiConfig(), AssistantDtos.UiSection.class, AssistantDefaults.adminConfig(assistantProperties).ui())
        );
    }

    public AssistantDtos.AdminAiConfig saveAdminConfig(AssistantDtos.AdminAiConfig config) {
        if (config == null) {
            throw new ApiException(4000, "AI config can not be empty");
        }
        AssistantConfigEntity entity = getOrCreateConfigEntity();
        entity.setBaseConfig(write(config.base()));
        entity.setPromptConfig(write(config.prompt()));
        entity.setRetrievalConfig(write(config.retrieval()));
        entity.setSearchConfig(write(config.search()));
        entity.setSafetyConfig(write(config.safety()));
        entity.setUiConfig(write(config.ui()));
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
                        config.search().webSearchEnabled(),
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
}
