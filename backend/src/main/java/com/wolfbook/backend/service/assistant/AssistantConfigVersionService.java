package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantConfigVersionEntity;
import com.wolfbook.backend.entity.AssistantModelEntity;
import com.wolfbook.backend.mapper.AssistantConfigVersionMapper;
import com.wolfbook.backend.mapper.AssistantModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 助手设置的发布与版本：后台编排页改的是浏览器里的草稿，点「发布」才保存配置、切换线上模型并记一个版本。
 * 版本里存完整配置快照，但 Key 一律清空（Key 不走草稿也不进版本，见 AssistantConfigService.withoutSecrets）。
 */
@Service
public class AssistantConfigVersionService {

    private final AssistantConfigService assistantConfigService;
    private final AssistantModelService assistantModelService;
    private final AssistantModelMapper assistantModelMapper;
    private final AssistantConfigVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public AssistantConfigVersionService(
            AssistantConfigService assistantConfigService,
            AssistantModelService assistantModelService,
            AssistantModelMapper assistantModelMapper,
            AssistantConfigVersionMapper versionMapper,
            ObjectMapper objectMapper
    ) {
        this.assistantConfigService = assistantConfigService;
        this.assistantModelService = assistantModelService;
        this.assistantModelMapper = assistantModelMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存配置 → 按需切换线上模型 → 记版本，一个事务：哪一步失败（比如模型已停用）都整体回滚。
     * 开关和 Key 不走草稿：一律用服务器上现在的值（打码值原样交回去表示不改），
     * 免得开着的旧页面发布时把别处刚关掉的开关、刚换的 Key 又带回去。
     */
    @Transactional
    public AssistantDtos.AdminConfigPublishResult publish(AssistantDtos.AdminConfigPublishRequest request, String publishedBy) {
        AssistantDtos.AdminAiConfig current = assistantConfigService.getAdminConfig();
        AssistantDtos.AdminAiConfig draft = request.config();
        AssistantDtos.BaseSection draftBase = draft.base() == null ? current.base() : draft.base();
        AssistantDtos.AdminAiConfig merged = new AssistantDtos.AdminAiConfig(
                new AssistantDtos.BaseSection(
                        current.base().enabled(),
                        draftBase.welcomeMessage(),
                        draftBase.quickQuestions(),
                        draftBase.temperature(),
                        draftBase.maxSuggestions()
                ),
                current.provider(),
                current.volcengine(),
                draft.prompt() == null ? current.prompt() : draft.prompt(),
                draft.retrieval() == null ? current.retrieval() : draft.retrieval(),
                draft.search() == null ? current.search() : draft.search(),
                draft.safety() == null ? current.safety() : draft.safety(),
                current.ui()
        );
        AssistantDtos.AdminAiConfig saved = assistantConfigService.saveAdminConfig(merged);
        if (request.modelId() != null) {
            AssistantModelEntity target = assistantModelMapper.selectById(request.modelId());
            if (target == null || !Integer.valueOf(1).equals(target.getActive())) {
                assistantModelService.activate(request.modelId());
            }
        }
        AssistantModelEntity active = assistantModelMapper.selectOne(new LambdaQueryWrapper<AssistantModelEntity>()
                .eq(AssistantModelEntity::getActive, 1)
                .last("LIMIT 1"));

        AssistantConfigVersionEntity entity = new AssistantConfigVersionEntity();
        entity.setConfigJson(assistantConfigService.write(AssistantConfigService.withoutSecrets(saved)));
        entity.setModelId(active == null ? null : active.getId());
        entity.setModelName(active == null ? null : active.getName());
        entity.setNotes(StringUtils.hasText(request.notes()) ? request.notes().trim() : null);
        entity.setPublishedBy(publishedBy);
        entity.setCreateTime(LocalDateTime.now().withNano(0));
        versionMapper.insert(entity);
        return new AssistantDtos.AdminConfigPublishResult(toView(entity), saved);
    }

    public List<AssistantDtos.AdminConfigVersionView> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<AssistantConfigVersionEntity>()
                        .orderByDesc(AssistantConfigVersionEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    private AssistantDtos.AdminConfigVersionView toView(AssistantConfigVersionEntity entity) {
        return new AssistantDtos.AdminConfigVersionView(
                entity.getId(),
                entity.getNotes(),
                entity.getPublishedBy(),
                entity.getCreateTime(),
                entity.getModelId(),
                entity.getModelName(),
                readConfig(entity.getConfigJson())
        );
    }

    private AssistantDtos.AdminAiConfig readConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            // 存进去时已经清过 Key，读出来再清一遍，万一库里被手动改过也不往外带
            return AssistantConfigService.withoutSecrets(objectMapper.readValue(json, AssistantDtos.AdminAiConfig.class));
        } catch (Exception exception) {
            return null;
        }
    }
}
