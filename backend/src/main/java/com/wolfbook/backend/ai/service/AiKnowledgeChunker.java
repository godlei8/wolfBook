package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.model.AiKnowledgeChunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiKnowledgeChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern ROLE_PREFIX = Pattern.compile("^[\\[【]?ROLE-\\d+[\\]】]?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SUBJECT = Pattern.compile("^(?:角色|术语|板子|词条)[:：]\\s*(.+)$");
    private static final List<String> AGGREGATE_TITLES = List.of(
            "狼人杀角色知识库（优化版）", "狼人杀角色知识库", "狼人杀术语大全", "狼人杀知识库", "定义", "条目摘要",
            "FAQ", "FAQ 1", "文档结构规范", "游戏基础术语", "角色知识库", "术语知识库"
    );
    private static final List<String> GENERIC_SECTION_TITLES = List.of(
            "技能说明", "玩法提示", "FAQ", "FAQ 1", "常见问题", "条目摘要", "规则说明", "限制", "适用板子"
    );

    public List<AiKnowledgeChunk> chunk(String domain, String documentTitle, String content) {
        List<AiKnowledgeChunk> chunks = new ArrayList<>();
        String currentSubject = null;
        String currentTitle = cleanTitle(documentTitle);
        List<String> path = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int ordinal = 0;

        for (String rawLine : normalizeNewlines(content).split("\\n")) {
            Matcher matcher = HEADING.matcher(rawLine);
            if (matcher.matches()) {
                ordinal = flush(chunks, domain, currentTitle, path, currentSubject, buffer, ordinal);
                int level = matcher.group(1).length();
                String headingTitle = cleanTitle(matcher.group(2));
                shrinkPath(path, level);
                path.add(headingTitle);

                String explicitSubject = extractSubject(headingTitle, level);
                if (explicitSubject != null) {
                    currentSubject = explicitSubject;
                } else if (level <= 2 && isAggregateTitle(headingTitle)) {
                    currentSubject = null;
                }

                currentTitle = headingTitle;
                buffer.append(renderPath(path)).append('\n');
            } else if (!rawLine.isBlank()) {
                buffer.append(rawLine.trim()).append('\n');
            }
        }
        flush(chunks, domain, currentTitle, path, currentSubject, buffer, ordinal);
        return chunks;
    }

    private int flush(
            List<AiKnowledgeChunk> chunks,
            String domain,
            String title,
            List<String> path,
            String subject,
            StringBuilder buffer,
            int ordinal
    ) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isBlank()) {
            return ordinal;
        }
        chunks.add(new AiKnowledgeChunk(
                stableUid(domain, title, ordinal, text),
                domain,
                title,
                String.join(" > ", path),
                subject,
                text,
                ordinal
        ));
        return ordinal + 1;
    }

    private void shrinkPath(List<String> path, int level) {
        int desiredSize = Math.max(level - 1, 0);
        while (path.size() > desiredSize) {
            path.removeLast();
        }
    }

    private String extractSubject(String title, int level) {
        if (isAggregateTitle(title) || GENERIC_SECTION_TITLES.contains(title)) {
            return null;
        }
        Matcher roleMatcher = ROLE_PREFIX.matcher(title);
        if (roleMatcher.matches()) {
            return stripDecorations(roleMatcher.group(1));
        }
        Matcher explicitMatcher = EXPLICIT_SUBJECT.matcher(title);
        if (explicitMatcher.matches()) {
            return stripDecorations(explicitMatcher.group(1));
        }
        if (level == 2 && title.length() <= 18 && !title.contains("知识库") && !title.contains("大全")) {
            return stripDecorations(title);
        }
        return null;
    }

    private String cleanTitle(String value) {
        return stripDecorations(value)
                .replace("**", "")
                .replace("《", "")
                .replace("》", "")
                .trim();
    }

    private String stripDecorations(String value) {
        String stripped = value == null ? "" : value.trim();
        stripped = stripped.replaceFirst("^[-—、\\d.\\s]+", "");
        stripped = stripped.replaceFirst("[（(].*?[）)]$", "");
        stripped = stripped.replaceFirst("\\s*>.*$", "");
        return stripped.trim();
    }

    private boolean isAggregateTitle(String title) {
        return AGGREGATE_TITLES.stream().anyMatch(item -> item.equalsIgnoreCase(title));
    }

    private String renderPath(List<String> path) {
        return String.join(" > ", path);
    }

    private String normalizeNewlines(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String stableUid(String domain, String title, int ordinal, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((domain + "|" + title + "|" + ordinal + "|" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
