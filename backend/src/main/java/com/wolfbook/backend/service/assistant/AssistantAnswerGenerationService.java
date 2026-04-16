package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 助手答案生成服务。
 *
 * <p>严格主体问题优先走模板直答：把同一主体的 BASE / SKILL / FAQ / GLOSSARY chunk 聚合成答案。
 * 只有宽泛问题才调用模型改写，且模型只能看到已重排后的结构化 chunk。</p>
 */
@Service
class AssistantAnswerGenerationService {

    private final ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider;

    AssistantAnswerGenerationService(ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider) {
        this.miniMaxChatModelProvider = miniMaxChatModelProvider;
    }

    AssistantGeneratedAnswer generateKnowledge(
            AssistantDtos.AdminAiConfig config,
            AssistantQueryPlan queryPlan,
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            List<String> recentMessages
    ) {
        String templateAnswer = templateKnowledgeAnswer(queryPlan, hits);
        if (shouldUseTemplate(queryPlan, hits, templateAnswer)) {
            return new AssistantGeneratedAnswer(templateAnswer, "TEMPLATE_DIRECT", 0L);
        }

        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return new AssistantGeneratedAnswer(templateAnswer, "NO_MODEL", 0L);
        }

        Prompt prompt = buildKnowledgePrompt(config, queryPlan, hits, recentMessages);
        long modelStartAt = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            long modelMs = System.currentTimeMillis() - modelStartAt;
            if (content == null || content.isBlank()) {
                return new AssistantGeneratedAnswer(templateAnswer, "MODEL_EMPTY", modelMs);
            }
            return new AssistantGeneratedAnswer(normalizeMarkdown(content), "NONE", modelMs);
        } catch (Exception exception) {
            return new AssistantGeneratedAnswer(templateAnswer, "MODEL_EXCEPTION", System.currentTimeMillis() - modelStartAt);
        }
    }

    String templateKnowledgeAnswer(AssistantQueryPlan queryPlan, List<AssistantKnowledgeService.KnowledgeHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return """
                    ## 结论

                    - 现有知识库暂时没有找到能直接回答这个问题的资料。
                    """.trim();
        }
        String subjectName = queryPlan != null && queryPlan.subject() != null
                ? queryPlan.subject().name()
                : firstNonBlank(hits.getFirst().subjectName(), hits.getFirst().title(), "这个问题");
        boolean skillOnly = asksForSkill(queryPlan == null ? "" : queryPlan.effectiveQuery());
        Map<String, List<String>> sections = new LinkedHashMap<>();
        Set<String> fingerprints = new LinkedHashSet<>();

        List<AssistantKnowledgeService.KnowledgeHit> orderedHits = hits.stream()
                .sorted((left, right) -> Integer.compare(kindPriority(left.chunkKind(), skillOnly), kindPriority(right.chunkKind(), skillOnly)))
                .toList();
        for (AssistantKnowledgeService.KnowledgeHit hit : orderedHits) {
            for (String line : linesForHit(hit, queryPlan, skillOnly)) {
                String normalized = fingerprint(line);
                if (normalized.isBlank() || fingerprints.contains(normalized)) {
                    continue;
                }
                String sectionName = sectionName(hit, queryPlan, skillOnly);
                List<String> sectionLines = sections.computeIfAbsent(sectionName, key -> new ArrayList<>());
                if (sectionLines.size() >= sectionLimit(sectionName)) {
                    continue;
                }
                sectionLines.add(line);
                fingerprints.add(normalized);
            }
        }
        if (sections.isEmpty()) {
            sections.put("知识库补充", List.of("- " + clean(hits.getFirst().contextText(), 520)));
        }
        StringBuilder builder = new StringBuilder("## 结论\n\n");
        if (queryPlan != null && queryPlan.hasSubject()) {
            builder.append("- **主体**：").append(subjectName).append('\n');
        }
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (section.getValue().isEmpty()) {
                continue;
            }
            builder.append("\n### ").append(section.getKey()).append('\n');
            section.getValue().forEach(line -> builder.append(line).append('\n'));
        }
        return normalizeMarkdown(builder.toString());
    }

    private boolean shouldUseTemplate(AssistantQueryPlan queryPlan, List<AssistantKnowledgeService.KnowledgeHit> hits, String templateAnswer) {
        if (templateAnswer == null || templateAnswer.isBlank() || queryPlan == null) {
            return false;
        }
        if (queryPlan.strictSubject() && !queryPlan.timeSensitive()) {
            return true;
        }
        return hits != null && !hits.isEmpty() && hits.stream().allMatch(hit -> AssistantConstants.SOURCE_STRUCTURED.equals(hit.sourceType()));
    }

    private Prompt buildKnowledgePrompt(
            AssistantDtos.AdminAiConfig config,
            AssistantQueryPlan queryPlan,
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            List<String> recentMessages
    ) {
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (AssistantKnowledgeService.KnowledgeHit hit : hits) {
            context.append("Chunk ").append(index++).append('\n')
                    .append("chunkUid: ").append(hit.chunkUid() == null ? "" : hit.chunkUid()).append('\n')
                    .append("subjectKey: ").append(hit.subjectKey() == null ? "" : hit.subjectKey()).append('\n')
                    .append("subjectName: ").append(hit.subjectName() == null ? "" : hit.subjectName()).append('\n')
                    .append("title: ").append(hit.title()).append('\n')
                    .append("content: ").append(clean(hit.contextText(), 1000)).append("\n\n");
        }
        String history = recentMessages == null || recentMessages.isEmpty() ? "无" : String.join("\n", recentMessages);
        double temperature = config == null || config.base() == null || config.base().temperature() == null
                ? 0.1d
                : Math.min(config.base().temperature(), 0.1d);
        String model = config == null || config.base() == null ? null : config.base().chatModel();
        return new Prompt(
                List.of(
                        new SystemMessage("""
                                你是 Wolfbook 的狼人杀知识助手。你必须只依据给定 chunk 回答。
                                要求：
                                1. 输出简洁 Markdown，先写 `## 结论`。
                                2. 每个要点独占一行，不要把多个 `-` 挤在一行。
                                3. 如果用户问的是具体角色、板子或术语，只回答该主体，不得混入相邻主体。
                                4. 如果 chunk 没有直接说明，就回答“现有资料未直接说明”，不要猜测。
                                5. 不要输出依据、来源、chunkUid、参考来源或继续追问。
                                """),
                        new UserMessage("""
                                当前问题：
                                %s

                                最近上下文：
                                %s

                                可用 chunk：
                                %s
                                """.formatted(queryPlan == null ? "" : queryPlan.effectiveQuery(), history, context))
                ),
                MiniMaxChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build()
        );
    }

    private List<String> linesForHit(AssistantKnowledgeService.KnowledgeHit hit, AssistantQueryPlan queryPlan, boolean skillOnly) {
        String content = hit.contextText() == null || hit.contextText().isBlank() ? hit.snippet() : hit.contextText();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if ("SKILL".equals(hit.chunkKind())) {
            return List.of("- **技能**：" + cleanField(content, "技能", 520));
        }
        if (skillOnly && !"FAQ".equals(hit.chunkKind()) && !"SKILL".equals(hit.chunkKind())) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        String subjectName = queryPlan == null || queryPlan.subject() == null ? "" : queryPlan.subject().name();
        for (String rawLine : splitAnswerLines(content)) {
            String line = clean(rawLine, 520);
            if (line.isBlank() || looksLikeSourceNoise(line)) {
                continue;
            }
            int separatorIndex = firstSeparator(line);
            if (separatorIndex > 0) {
                String key = clean(line.substring(0, separatorIndex), 80);
                String value = clean(line.substring(separatorIndex + 1), 520);
                if (!value.isBlank() && !Set.of("角色名称", "板子名称").contains(key)) {
                    lines.add("- **" + key + "**：" + value);
                } else if (!value.isBlank() && !subjectName.isBlank()) {
                    lines.add("- **" + subjectName + "**：" + value);
                }
            } else {
                lines.add("- " + line);
            }
        }
        return lines;
    }

    private List<String> splitAnswerLines(String content) {
        String normalized = content.replace("\r\n", "\n")
                .replaceAll("\\h+-\\h+(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,16}[（(：:])", "\n- ");
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() > 260 && trimmed.contains("；")) {
                for (String part : trimmed.split("[；;]")) {
                    if (!part.isBlank()) {
                        lines.add(part);
                    }
                }
            } else {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private int kindPriority(String kind, boolean skillOnly) {
        if (skillOnly && "SKILL".equals(kind)) {
            return 0;
        }
        if ("BASE".equals(kind)) {
            return 1;
        }
        if ("SKILL".equals(kind)) {
            return 2;
        }
        if ("GLOSSARY".equals(kind)) {
            return 2;
        }
        if ("RULE".equals(kind) || "TIP".equals(kind) || "CATALOG_ROW".equals(kind)) {
            return 3;
        }
        if ("FAQ".equals(kind)) {
            return 4;
        }
        return 8;
    }

    private String sectionName(AssistantKnowledgeService.KnowledgeHit hit, AssistantQueryPlan queryPlan, boolean skillOnly) {
        String kind = hit.chunkKind() == null ? "" : hit.chunkKind();
        boolean termQuery = queryPlan != null && queryPlan.subject() != null && queryPlan.subject().isTerm();
        if ("BASE".equals(kind)) {
            return "基础信息";
        }
        if ("SKILL".equals(kind)) {
            return "技能与限制";
        }
        if ("GLOSSARY".equals(kind) || termQuery) {
            return "术语解释";
        }
        if ("RULE".equals(kind) || "TIP".equals(kind) || "CATALOG_ROW".equals(kind)) {
            return "规则补充";
        }
        if ("FAQ".equals(kind)) {
            return "常见问题";
        }
        if ("BACKGROUND".equals(kind)) {
            return "背景补充";
        }
        return skillOnly ? "相关补充" : "知识库补充";
    }

    private int sectionLimit(String sectionName) {
        if ("基础信息".equals(sectionName)) {
            return 8;
        }
        if ("技能与限制".equals(sectionName)) {
            return 8;
        }
        if ("术语解释".equals(sectionName)) {
            return 12;
        }
        if ("常见问题".equals(sectionName)) {
            return 8;
        }
        return 10;
    }

    private boolean asksForSkill(String query) {
        return query != null && (query.contains("技能")
                || query.contains("能力")
                || query.contains("作用")
                || query.contains("怎么用")
                || query.contains("发动"));
    }

    private int firstSeparator(String line) {
        int chinese = line.indexOf('：');
        int english = line.indexOf(':');
        if (chinese < 0) {
            return english;
        }
        if (english < 0) {
            return chinese;
        }
        return Math.min(chinese, english);
    }

    private boolean looksLikeSourceNoise(String line) {
        return line.startsWith("来源")
                || line.startsWith("依据")
                || line.startsWith("参考来源")
                || line.startsWith("chunkUid");
    }

    private String cleanField(String value, String fieldName, int maxLength) {
        String cleaned = clean(value, maxLength);
        String chinesePrefix = fieldName + "：";
        String englishPrefix = fieldName + ":";
        if (cleaned.startsWith(chinesePrefix)) {
            return cleaned.substring(chinesePrefix.length()).trim();
        }
        if (cleaned.startsWith(englishPrefix)) {
            return cleaned.substring(englishPrefix.length()).trim();
        }
        return cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").trim();
        normalized = normalized.replaceAll("\\h+-\\h+(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,16}[（(：:])", "\n- ");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        if (normalized.startsWith("#") || normalized.startsWith("-")) {
            return normalized;
        }
        return "## 结论\n\n" + normalized;
    }

    private String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^[-*]\\s+", "")
                .replaceAll("\\*\\*|`", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLength));
    }

    private String fingerprint(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\s]+", "")
                .trim();
    }
}
