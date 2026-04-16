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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

                    - 现有知识库暂时没有找到能直接回答这个问题的资料，当前信息不足。

                    ## 证据引用

                    - 暂无可引用证据。

                    ## 置信度

                    - 低（0.20）

                    ## 后续建议

                    - 请补充更具体的问题主体（角色/板子/术语），或允许我联网检索最新资料。
                    """.trim();
        }
        String subjectName = queryPlan != null && queryPlan.subject() != null
                ? queryPlan.subject().name()
                : firstNonBlank(hits.getFirst().subjectName(), hits.getFirst().title(), "这个问题");
        boolean skillOnly = asksForSkill(queryPlan == null ? "" : queryPlan.effectiveQuery());
        Map<String, List<String>> sections = new LinkedHashMap<>();
        Map<String, String> evidenceMap = buildEvidenceMap(hits);
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
            String fallbackEvidenceId = firstEvidenceId(evidenceMap);
            sections.put("知识库补充", List.of("- " + clean(hits.getFirst().contextText(), 520) + " " + fallbackEvidenceId));
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
        builder.append("\n## 证据引用\n\n");
        evidenceMap.forEach((key, value) -> builder.append("- ").append(key).append(" ").append(value).append('\n'));
        builder.append("\n## 置信度\n\n")
                .append("- 中（0.78）\n")
                .append("\n## 后续建议\n\n")
                .append("- 若你需要更高置信度，我可以继续检索同一主体的更多条目或补充联网检索。\n");
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
                                1. 硬约束：仅可基于给定 chunk 的检索证据回答；若证据不足，必须明确写“证据不足/资料不足”。
                                2. 输出必须严格按四段格式：
                                   `## 结论`、`## 证据引用`、`## 置信度`、`## 后续建议`。
                                3. `## 结论`中的每个要点必须在行末追加证据标记（如 `[E1]`、`[E1][E2]`）。
                                4. `## 证据引用`必须列出所有证据标记及对应摘要，不得出现未在 chunk 中出现的信息。
                                5. `## 置信度`仅能输出“高/中/低 + 0~1 小数”，当证据缺口明显时必须输出低置信度。
                                6. 若低置信度，在`## 后续建议`中优先给出“追问补充信息”或“建议联网检索”。
                                7. 如果用户问的是具体角色、板子或术语，只回答该主体，不得混入相邻主体。
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
        String evidenceId = evidenceId(hit);
        if ("SKILL".equals(hit.chunkKind())) {
            return List.of("- **技能**：" + cleanField(content, "技能", 520) + " " + evidenceId);
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
        return lines.stream()
                .map(line -> line + " " + evidenceId)
                .toList();
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

    private Map<String, String> buildEvidenceMap(List<AssistantKnowledgeService.KnowledgeHit> hits) {
        Map<String, String> evidence = new LinkedHashMap<>();
        if (hits == null) {
            return evidence;
        }
        hits.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::evidenceId))
                .forEach(hit -> evidence.put(
                        evidenceId(hit),
                        "%s：%s".formatted(
                                firstNonBlank(hit.title(), hit.subjectName(), "检索片段"),
                                clean(hit.contextText(), 180)
                        )
                ));
        return evidence;
    }

    private String firstEvidenceId(Map<String, String> evidenceMap) {
        return evidenceMap.keySet().stream().findFirst().orElse("[E1]");
    }

    private String evidenceId(AssistantKnowledgeService.KnowledgeHit hit) {
        String uid = hit.chunkUid();
        if (uid == null || uid.isBlank()) {
            return "[E1]";
        }
        int number = Math.abs(uid.hashCode() % 900) + 100;
        return "[E" + number + "]";
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
