package com.wolfbook.backend.service.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 知识库关键词匹配器。
 *
 * <p>这是 {@link AssistantKnowledgeService} 的纯工具类：从中文问题里抽主体词、给文档片段打分、
 * 并把命中的大段资料裁剪到用户真正询问的主体附近。它不访问数据库，也不调用模型。</p>
 */
final class AssistantKeywordMatcher {

    private static final Pattern CLEAN_PATTERN = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");
    private static final Pattern CJK_SEQUENCE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern INLINE_ENTRY_PATTERN = Pattern.compile("\\h+-\\h+(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,24}[（(])");
    private static final Pattern MIXED_ROW_SEPARATOR_PATTERN = Pattern.compile("\\|{2,}|[；;]\\s*(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,24}[（(：:])");
    private static final Pattern BOUNDARY_LINE_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|[-*]\\s+.+|\\d{1,3}[.、．]\\s*.+|第[一二三四五六七八九十百0-9]{1,4}[章节].*)$");
    private static final int SNIPPET_RADIUS = 90;

    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "是什么", "怎么", "如何", "一下", "介绍", "信息", "规则", "玩法",
            "能不能", "可以", "请问", "帮我", "告诉我", "这个", "那个",
            "技能", "身份", "角色", "板子", "资料", "说明", "机制", "玩家",
            "发动", "使用", "是否", "能否", "不能", "有没有", "哪些", "如果"
    );
    private static final String CJK_PARTICLES = "的了吗呢么吗能不可以是否怎如何什么和与或在被把";

    private AssistantKeywordMatcher() {
    }

    static KeywordMatch match(
            String query,
            String title,
            String fileName,
            String summary,
            String content,
            String sourceType
    ) {
        List<String> tokens = extractTokens(query);
        if (tokens.isEmpty()) {
            return KeywordMatch.none();
        }

        String normalizedTitle = normalize(title);
        String normalizedFileName = normalize(fileName);
        String normalizedSummary = normalize(summary);
        String normalizedContent = normalize(content);
        double score = 0;

        for (String token : tokens) {
            double weight = Math.min(token.length(), 6);
            if (normalizedTitle.equals(token)) {
                score += 80 + weight * 2;
            } else if (normalizedTitle.startsWith(token) || normalizedTitle.endsWith(token)) {
                score += 32 + weight;
            } else if (normalizedTitle.contains(token)) {
                score += 18 + weight;
            }
            if (normalizedFileName.equals(token)) {
                score += 40 + weight;
            } else if (normalizedFileName.contains(token)) {
                score += 14 + weight;
            }
            if (normalizedSummary.contains(token)) {
                score += 10 + weight;
            }
            if (normalizedContent.contains(token)) {
                score += 6 + weight;
            }
        }

        if (score <= 0) {
            return KeywordMatch.none();
        }

        if (AssistantConstants.SOURCE_DOCUMENT.equals(sourceType)) {
            score += 3;
        }

        return new KeywordMatch(score, buildSnippet(tokens, title, summary, content));
    }

    static List<String> extractTokens(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }

        // 中文问题没有天然空格，所以除了整词，还会生成 2-4 字 gram；停用词过滤避免“技能/什么”喧宾夺主。
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            addToken(tokens, token);
        }

        Matcher matcher = CJK_SEQUENCE_PATTERN.matcher(normalized.replace(" ", ""));
        while (matcher.find()) {
            String sequence = matcher.group();
            if (sequence.length() <= 6) {
                addToken(tokens, sequence);
            }
            int maxGram = Math.min(4, sequence.length());
            for (int size = maxGram; size >= 2; size--) {
                for (int start = 0; start + size <= sequence.length(); start++) {
                    addToken(tokens, sequence.substring(start, start + size));
                }
            }
        }

        return tokens.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    static String focusContent(String content, List<String> tokens) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (tokens == null || tokens.isEmpty()) {
            return content;
        }

        // 有些资料把多个角色写在同一行，用常见分隔符先拆成伪列表，再按主体行选块。
        String prepared = content.replace("\r\n", "\n");
        prepared = MIXED_ROW_SEPARATOR_PATTERN.matcher(prepared).replaceAll("\n- ");
        prepared = INLINE_ENTRY_PATTERN.matcher(prepared).replaceAll("\n- ");
        String[] lines = prepared.split("\n", -1);
        List<String> selected = new ArrayList<>();
        Set<Integer> selectedIndexes = new LinkedHashSet<>();

        for (int index = 0; index < lines.length; index++) {
            if (!containsAnyToken(lines[index], tokens)) {
                continue;
            }
            appendFocusedBlock(lines, index, tokens, selectedIndexes);
        }

        for (Integer index : selectedIndexes) {
            String line = lines[index].trim();
            if (!line.isBlank()) {
                selected.add(line);
            }
        }
        return trim(String.join("\n", selected), 900);
    }

    static String focusTitle(String fallbackTitle, String focusedContent, List<String> tokens) {
        if (focusedContent != null && tokens != null && !tokens.isEmpty()) {
            for (String line : focusedContent.split("\\R")) {
                if (containsAnyToken(line, tokens)) {
                    String title = cleanTitleCandidate(line);
                    if (!title.isBlank()) {
                        return title;
                    }
                }
            }
        }
        return compact(fallbackTitle);
    }

    static boolean containsAnyToken(String text, List<String> tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.isEmpty()) {
            return false;
        }
        String normalized = normalize(text).replace(" ", "");
        for (String token : tokens) {
            if (token != null && !token.isBlank() && normalized.contains(normalize(token).replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    private static void addToken(Set<String> tokens, String token) {
        if (token == null) {
            return;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2 || STOP_WORDS.contains(normalized) || isNoisyCjkToken(normalized)) {
            return;
        }
        tokens.add(normalized);
    }

    private static boolean isNoisyCjkToken(String token) {
        if (!token.matches("[\\u4e00-\\u9fa5]+")) {
            return false;
        }
        for (int index = 0; index < CJK_PARTICLES.length(); index++) {
            if (token.indexOf(CJK_PARTICLES.charAt(index)) >= 0) {
                return true;
            }
        }
        return STOP_WORDS.contains(token);
    }

    private static String buildSnippet(List<String> tokens, String title, String summary, String content) {
        String contentSnippet = bestSnippet(content, tokens);
        if (!contentSnippet.isBlank()) {
            return contentSnippet;
        }

        String summarySnippet = bestSnippet(summary, tokens);
        if (!summarySnippet.isBlank()) {
            return summarySnippet;
        }

        String titleText = compact(title);
        String summaryText = compact(summary);
        if (!titleText.isBlank() && !summaryText.isBlank()) {
            return trim(titleText + "：" + summaryText, SNIPPET_RADIUS * 2);
        }
        if (!summaryText.isBlank()) {
            return trim(summaryText, SNIPPET_RADIUS * 2);
        }
        return trim(compact(content), SNIPPET_RADIUS * 2);
    }

    private static String bestSnippet(String text, List<String> tokens) {
        String focused = focusContent(text, tokens);
        if (!focused.isBlank()) {
            return trim(compact(focused), SNIPPET_RADIUS * 2);
        }

        String compacted = compact(text);
        if (compacted.isBlank()) {
            return "";
        }
        String lower = compacted.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            int index = lower.indexOf(token.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = Math.max(0, index - SNIPPET_RADIUS);
                int end = Math.min(compacted.length(), index + token.length() + SNIPPET_RADIUS);
                String prefix = start > 0 ? "..." : "";
                String suffix = end < compacted.length() ? "..." : "";
                return prefix + compacted.substring(start, end) + suffix;
            }
        }
        return "";
    }

    private static void appendFocusedBlock(String[] lines, int index, List<String> tokens, Set<Integer> selectedIndexes) {
        int start = index;
        if (!isBoundaryLine(lines[index])) {
            // 如果命中的是正文行，只有上方标题本身也包含主体词时才把标题带上，避免父章节标题误导模型。
            for (int cursor = index - 1; cursor >= 0; cursor--) {
                if (isBoundaryLine(lines[cursor])) {
                    if (containsAnyToken(lines[cursor], tokens)) {
                        start = cursor;
                    }
                    break;
                }
            }
        }

        int end = index + 1;
        while (end < lines.length && !isBoundaryLine(lines[end])) {
            end++;
        }

        for (int cursor = start; cursor < end; cursor++) {
            selectedIndexes.add(cursor);
        }
    }

    private static boolean isBoundaryLine(String line) {
        return line != null && BOUNDARY_LINE_PATTERN.matcher(line.trim()).matches();
    }

    private static String cleanTitleCandidate(String line) {
        String cleaned = compact(line)
                .replaceFirst("^#{1,6}\\s+", "")
                .replaceFirst("^[-*]\\s+", "")
                .replaceFirst("^\\d{1,3}[.、．]\\s*", "")
                .replace("**", "")
                .replace("`", "")
                .trim();
        for (String marker : List.of("技能", "阵营", "规则", "简介", "FAQ", "Q：", "Q:", "A：", "A:")) {
            int index = cleaned.indexOf(marker);
            if (index > 1) {
                cleaned = cleaned.substring(0, index);
                break;
            }
        }
        cleaned = cleaned.replaceAll("[-–—:：\\s]+$", "").trim();
        return trim(cleaned, 80);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return CLEAN_PATTERN.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    record KeywordMatch(double score, String snippet) {
        static KeywordMatch none() {
            return new KeywordMatch(0, "");
        }
    }
}
