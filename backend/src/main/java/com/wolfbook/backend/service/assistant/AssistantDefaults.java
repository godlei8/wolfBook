package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.dto.AssistantDtos;

import java.util.List;

final class AssistantDefaults {

    private AssistantDefaults() {
    }

    static AssistantDtos.AdminAiConfig adminConfig(AssistantProperties properties) {
        return new AssistantDtos.AdminAiConfig(
                new AssistantDtos.BaseSection(
                        true,
                        "欢迎来到狼人杀 AI 助手，想问规则、角色，还是让我们一起挑个合适的板子？",
                        List.of("12人进阶推荐什么板子", "女巫能不能自救", "守卫和女巫会不会冲突"),
                        properties.getTemperature(),
                        properties.getMaxSuggestions()
                ),
                new AssistantDtos.ProviderSection(
                        AssistantProperties.DEEPSEEK_PLATFORM,
                        properties.getDeepSeek().getModel(),
                        properties.getDeepSeek().getBaseUrl(),
                        properties.getDeepSeek().getDefaultApiKey()
                ),
                new AssistantDtos.VolcengineSection(
                        properties.getVolcengine().getBaseUrl(),
                        properties.getVolcengine().getEmbeddingModel(),
                        properties.getVolcengine().getEmbeddingApiKey(),
                        properties.getVolcengine().getSearchModel(),
                        properties.getVolcengine().getSearchApiKey()
                ),
                new AssistantDtos.PromptSection(
                        "你是 Wolfbook 的狼人杀知识助手。优先依据站内结构化数据和已发布知识库回答，回答简洁、准确、可执行。",
                        "根据候选板子列表，用中文为用户解释每个推荐板子的适配场景、人数和难度，不要编造站内不存在的信息。",
                        "当前问题超出了狼人杀知识助手的能力边界。你可以继续问我规则、角色、板子推荐或站内资料相关的问题。"
                ),
                new AssistantDtos.RetrievalSection(
                        properties.getTopK(),
                        properties.getSimilarityThreshold(),
                        properties.getHistoryWindow()
                ),
                new AssistantDtos.SearchSection(properties.isWebSearchEnabled()),
                new AssistantDtos.SafetySection(
                        "我不能替你做局中站边、判狼或实时裁判，但可以帮你解释规则、角色机制和推荐合适的板子。",
                        List.of("站边", "投谁", "谁是狼", "今晚刀谁", "谁更像狼", "实时判")
                ),
                new AssistantDtos.UiSection(
                        "wolf-head",
                        "AI狼顾问",
                        "#FFC000"
                )
        );
    }
}
