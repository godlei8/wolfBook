package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.entity.AssistantDocumentEntity;
import com.wolfbook.backend.entity.AssistantKnowledgeChunkEntity;
import com.wolfbook.backend.mapper.AssistantKnowledgeChunkMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 知识库 v2 索引器。
 *
 * <p>索引器把结构化角色/板子和上传文档拆成带主体 metadata 的小块，同时写入 chunk 表和向量库。
 * 上传资料里的“舞者 - 技能...”和“假面 - 技能...”会拆成不同主体；“金水：...”这类术语也会成为
 * 独立 TERM 主体，后续检索可以先按主体过滤，再做关键词/向量召回。</p>
 */
@Service
class AssistantKnowledgeIndexer {

    static final String INDEX_SCHEMA_VERSION = "rag-v5-chunks-entity-first-v4";

    private static final Pattern INLINE_ENTRY_PATTERN = Pattern.compile("\\h+-\\h+(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,24}[（(])");
    private static final Pattern MIXED_ROW_SEPARATOR_PATTERN = Pattern.compile("\\|{2,}|[；;]\\s*(?=[\\u4e00-\\u9fa5A-Za-z0-9]{1,24}[（(：:])");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|\\d{1,3}[.、．]\\s*.+|第[一二三四五六七八九十百0-9]{1,4}[章节].*)$");
    private static final Pattern LEADING_SUBJECT_PATTERN = Pattern.compile(
            "^([\\u4e00-\\u9fa5A-Za-z0-9]{2,16})(?:[（(][^）)]{1,80}[）)])?\\s*(?:[-–—:：]|\\s+(?:是指|指的是|就是|一般指|在狼人杀))"
    );
    private static final Set<String> BLOCKED_VIRTUAL_SUBJECTS = Set.of(
            "技能", "规则", "如果", "若被", "玩家", "法官", "阵营", "保护", "接刀", "小贴士", "常见问题",
            "回答", "结论", "狼人杀", "狼人杀游戏", "游戏", "信息", "资料", "说明", "角色", "身份"
    );

    private final AssistantKnowledgeChunkMapper chunkMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AssistantSubjectCatalog subjectCatalog;

    AssistantKnowledgeIndexer(
            AssistantKnowledgeChunkMapper chunkMapper,
            ObjectProvider<VectorStore> vectorStoreProvider,
            AssistantSubjectCatalog subjectCatalog
    ) {
        this.chunkMapper = chunkMapper;
        this.vectorStoreProvider = vectorStoreProvider;
        this.subjectCatalog = subjectCatalog;
    }

    int indexDocument(AssistantDocumentEntity entity) {
        if (entity == null || entity.getId() == null || entity.getContentText() == null || entity.getContentText().isBlank()) {
            return 0;
        }

        deleteDocumentChunks(entity.getId());
        List<AssistantKnowledgeChunkEntity> chunks = buildChunks(entity);
        if (chunks.isEmpty()) {
            return 0;
        }
        chunks.forEach(chunkMapper::insert);
        addVectorDocuments(entity, chunks);
        return chunks.size();
    }

    void deleteDocumentChunks(Integer documentId) {
        if (documentId == null) {
            return;
        }
        List<AssistantKnowledgeChunkEntity> oldChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AssistantKnowledgeChunkEntity>()
                        .eq(AssistantKnowledgeChunkEntity::getDocumentId, documentId)
        );
        if (!oldChunks.isEmpty()) {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore != null) {
                try {
                    vectorStore.delete(oldChunks.stream().map(AssistantKnowledgeChunkEntity::getChunkUid).toList());
                } catch (Exception ignored) {
                    // 向量删除失败不影响 MySQL chunk 重建，下次重建会用新的 chunkUid 覆盖检索入口。
                }
            }
        }
        chunkMapper.delete(new LambdaQueryWrapper<AssistantKnowledgeChunkEntity>()
                .eq(AssistantKnowledgeChunkEntity::getDocumentId, documentId));
    }

    List<AssistantKnowledgeChunkEntity> buildChunks(AssistantDocumentEntity entity) {
        if (AssistantConstants.SOURCE_STRUCTURED.equals(entity.getSourceType())) {
            return buildStructuredChunks(entity);
        }
        return buildUploadedDocumentChunks(entity);
    }

    private List<AssistantKnowledgeChunkEntity> buildStructuredChunks(AssistantDocumentEntity entity) {
        Map<String, String> fields = parseFields(entity.getContentText());
        AssistantSubject subject = subjectFromStructuredDocument(entity, fields);
        List<ChunkDraft> drafts = new ArrayList<>();
        if (entity.getSourceKey() != null && entity.getSourceKey().startsWith("ROLE:")) {
            addDraft(drafts, "基础信息", "BASE", "基础信息", subject, joinFields(fields, List.of("角色名称", "别名", "阵营", "类型", "定位")));
            addDraft(drafts, "技能与限制", "SKILL", "技能", subject, fields.get("技能"));
            addDraft(drafts, "背景", "BACKGROUND", "背景", subject, fields.get("背景"));
            splitFaq(fields.get("FAQ")).forEach(faq -> addDraft(drafts, "常见问题", "FAQ", "FAQ", subject, faq));
        } else if (entity.getSourceKey() != null && entity.getSourceKey().startsWith("BOARD:")) {
            addDraft(drafts, "基础信息", "BASE", "基础信息", subject, joinFields(fields, List.of("板子名称", "人数", "难度", "标签", "规则类型", "胜利条件", "简介", "阵容")));
            addDraft(drafts, "规则", "RULE", "规则", subject, fields.get("规则"));
            addDraft(drafts, "提示", "TIP", "提示", subject, fields.get("提示"));
            splitFaq(fields.get("FAQ")).forEach(faq -> addDraft(drafts, "常见问题", "FAQ", "FAQ", subject, faq));
        } else {
            addDraft(drafts, entity.getName(), "TEXT", null, subject, entity.getContentText());
        }
        return toEntities(entity, drafts);
    }

    private List<AssistantKnowledgeChunkEntity> buildUploadedDocumentChunks(AssistantDocumentEntity entity) {
        String prepared = entity.getContentText() == null ? "" : entity.getContentText().replace("\r\n", "\n");
        prepared = MIXED_ROW_SEPARATOR_PATTERN.matcher(prepared).replaceAll("\n- ");
        prepared = INLINE_ENTRY_PATTERN.matcher(prepared).replaceAll("\n- ");

        List<ChunkDraft> drafts = new ArrayList<>();
        String sectionTitle = entity.getName();
        StringBuilder paragraph = new StringBuilder();
        AssistantSubject paragraphSubject = null;
        for (String rawLine : prepared.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                flushParagraph(drafts, sectionTitle, paragraph, paragraphSubject);
                paragraphSubject = null;
                continue;
            }
            if (isHeading(line)) {
                flushParagraph(drafts, sectionTitle, paragraph, paragraphSubject);
                paragraphSubject = null;
                sectionTitle = cleanHeading(line);
                continue;
            }
            List<String> entries = splitListOrTableLine(line);
            if (entries.size() > 1 || isListLike(line) || isTableLike(line)) {
                flushParagraph(drafts, sectionTitle, paragraph, paragraphSubject);
                paragraphSubject = null;
                for (String entry : entries) {
                    addUploadedDraft(drafts, sectionTitle, entry);
                }
                continue;
            }

            AssistantSubject lineSubject = inferSubjectFromEntry(line);
            if (paragraph.length() > 0 && (paragraph.length() + line.length() > 900 || subjectChanged(paragraphSubject, lineSubject))) {
                flushParagraph(drafts, sectionTitle, paragraph, paragraphSubject);
                paragraphSubject = null;
            }
            if (paragraphSubject == null) {
                paragraphSubject = lineSubject;
            }
            paragraph.append(rawLine).append('\n');
        }
        flushParagraph(drafts, sectionTitle, paragraph, paragraphSubject);
        return toEntities(entity, drafts);
    }

    private void addUploadedDraft(List<ChunkDraft> drafts, String sectionTitle, String entry) {
        String cleaned = cleanEntry(entry);
        if (cleaned.isBlank()) {
            return;
        }
        AssistantSubject subject = inferSubjectFromEntry(cleaned);
        if (subject == null) {
            subject = inferSubjectFromEntry(sectionTitle);
        }
        addDraft(drafts, sectionTitle, chunkKindFor(cleaned, subject), null, subject, cleaned);
    }

    private void flushParagraph(List<ChunkDraft> drafts, String sectionTitle, StringBuilder paragraph, AssistantSubject subject) {
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (text.isBlank()) {
            return;
        }
        AssistantSubject resolvedSubject = subject == null ? inferSubjectFromEntry(text) : subject;
        if (resolvedSubject == null) {
            resolvedSubject = inferSubjectFromEntry(sectionTitle);
        }
        addDraft(drafts, sectionTitle, chunkKindFor(text, resolvedSubject), null, resolvedSubject, text);
    }

    private AssistantSubject inferSubjectFromEntry(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = cleanEntry(text);
        String leadingName = leadingSubjectName(cleaned);
        if (!leadingName.isBlank()) {
            AssistantSubject exact = subjectCatalog.resolveExactName(leadingName);
            if (exact != null) {
                return exact;
            }
            return subjectCatalog.virtualSubject(leadingName);
        }
        return subjectCatalog.resolveFromText(cleaned);
    }

    private String leadingSubjectName(String cleaned) {
        Matcher matcher = LEADING_SUBJECT_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return "";
        }
        String name = matcher.group(1).trim();
        if (name.isBlank() || BLOCKED_VIRTUAL_SUBJECTS.contains(name)) {
            return "";
        }
        return name;
    }

    private String chunkKindFor(String text, AssistantSubject subject) {
        if (subject != null && subject.isTerm()) {
            return "GLOSSARY";
        }
        if (isCatalogLike(text)) {
            return "CATALOG_ROW";
        }
        return "TEXT";
    }

    private boolean subjectChanged(AssistantSubject left, AssistantSubject right) {
        if (left == null || right == null) {
            return false;
        }
        return !left.sourceKey().equals(right.sourceKey());
    }

    private AssistantSubject subjectFromStructuredDocument(AssistantDocumentEntity entity, Map<String, String> fields) {
        String name = fields.getOrDefault("角色名称", fields.getOrDefault("板子名称", entity.getName()));
        String type = entity.getSourceKey() != null && entity.getSourceKey().startsWith("BOARD:") ? "BOARD" : "ROLE";
        return new AssistantSubject(type, entity.getSourceKey(), entity.getSourceId(), name, List.of(name), 100);
    }

    private List<AssistantKnowledgeChunkEntity> toEntities(AssistantDocumentEntity document, List<ChunkDraft> drafts) {
        List<AssistantKnowledgeChunkEntity> chunks = new ArrayList<>();
        int ordinal = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ChunkDraft draft : drafts) {
            String content = limit(draft.content(), 1800);
            if (content.isBlank()) {
                continue;
            }
            AssistantKnowledgeChunkEntity chunk = new AssistantKnowledgeChunkEntity();
            chunk.setDocumentId(document.getId());
            chunk.setPublishVersionId(document.getPublishVersionId());
            chunk.setSourceType(document.getSourceType());
            chunk.setSourceKey(document.getSourceKey());
            if (draft.subject() != null) {
                chunk.setSubjectType(draft.subject().type());
                chunk.setSubjectKey(draft.subject().sourceKey());
                chunk.setSubjectName(draft.subject().name());
            }
            chunk.setSectionTitle(limit(draft.sectionTitle(), 200));
            chunk.setChunkKind(draft.chunkKind());
            chunk.setFieldName(draft.fieldName());
            chunk.setContentText(content);
            chunk.setEmbeddingText(buildEmbeddingText(draft, content));
            chunk.setContentHash(sha256(content));
            chunk.setOrdinal(ordinal++);
            chunk.setChunkUid(buildChunkUid(document, chunk));
            chunk.setCreateTime(now);
            chunk.setUpdateTime(now);
            chunks.add(chunk);
        }
        return chunks;
    }

    private void addVectorDocuments(AssistantDocumentEntity entity, List<AssistantKnowledgeChunkEntity> chunks) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || entity.getPublishVersionId() == null || chunks.isEmpty()) {
            return;
        }
        List<Document> vectorDocuments = new ArrayList<>();
        for (AssistantKnowledgeChunkEntity chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            putMetadata(metadata, "chunkUid", chunk.getChunkUid());
            putMetadata(metadata, "sourceType", chunk.getSourceType());
            putMetadata(metadata, "sourceKey", chunk.getSourceKey());
            putMetadata(metadata, "sourceId", entity.getSourceId());
            putMetadata(metadata, "publishedVersion", chunk.getPublishVersionId());
            putMetadata(metadata, "title", entity.getName());
            putMetadata(metadata, "subjectType", chunk.getSubjectType());
            putMetadata(metadata, "subjectKey", chunk.getSubjectKey());
            putMetadata(metadata, "subjectName", chunk.getSubjectName());
            putMetadata(metadata, "chunkKind", chunk.getChunkKind());
            vectorDocuments.add(Document.builder()
                    .id(chunk.getChunkUid())
                    .text(chunk.getEmbeddingText())
                    .metadata(metadata)
                    .build());
        }
        vectorStore.add(vectorDocuments);
    }

    private String buildEmbeddingText(ChunkDraft draft, String content) {
        List<String> parts = new ArrayList<>();
        if (draft.subject() != null) {
            parts.add(draft.subject().name());
        }
        if (draft.sectionTitle() != null && !draft.sectionTitle().isBlank()) {
            parts.add(draft.sectionTitle());
        }
        if (draft.fieldName() != null && !draft.fieldName().isBlank()) {
            parts.add(draft.fieldName());
        }
        parts.add(content);
        return String.join("\n", parts);
    }

    private Map<String, String> parseFields(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return fields;
        }
        for (String line : content.split("\\R")) {
            int index = line.indexOf('：');
            if (index <= 0) {
                index = line.indexOf(':');
            }
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            if (!key.isBlank() && !value.isBlank()) {
                fields.put(key, value);
            }
        }
        return fields;
    }

    private String joinFields(Map<String, String> fields, List<String> names) {
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            String value = fields.get(name);
            if (value != null && !value.isBlank()) {
                parts.add(name + "：" + value);
            }
        }
        return String.join("\n", parts);
    }

    private List<String> splitFaq(String faq) {
        if (faq == null || faq.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(faq.split("[；;]+"))
                .map(String::trim)
                .filter(item -> item.length() >= 3)
                .toList();
    }

    private List<String> splitListOrTableLine(String line) {
        String cleaned = cleanEntry(line);
        if (cleaned.isBlank()) {
            return List.of();
        }
        if (line.contains("||")) {
            return java.util.Arrays.stream(line.split("\\|{2,}"))
                    .map(this::cleanEntry)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of(cleaned);
    }

    private boolean isHeading(String line) {
        return line.length() <= 120 && HEADING_PATTERN.matcher(line).matches();
    }

    private String cleanHeading(String line) {
        return line.replaceFirst("^#{1,6}\\s+", "")
                .replaceFirst("^\\d{1,3}[.、．]\\s*", "")
                .trim();
    }

    private boolean isListLike(String line) {
        return line.startsWith("-") || line.startsWith("*") || line.matches("^\\d{1,3}[.、．]\\s*.+");
    }

    private boolean isTableLike(String line) {
        return line.contains("|");
    }

    private boolean isCatalogLike(String line) {
        long pipeCount = line.chars().filter(character -> character == '|').count();
        return pipeCount >= 2 || line.length() > 140;
    }

    private String cleanEntry(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^\\s*[-*]\\s+", "")
                .replaceFirst("^\\s*\\d{1,3}[.、．]\\s*", "")
                .replaceAll("\\|+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void addDraft(List<ChunkDraft> drafts, String sectionTitle, String chunkKind, String fieldName, AssistantSubject subject, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        drafts.add(new ChunkDraft(sectionTitle, chunkKind, fieldName, subject, content.trim()));
    }

    private String buildChunkUid(AssistantDocumentEntity document, AssistantKnowledgeChunkEntity chunk) {
        String raw = String.join(":",
                "assistant-chunk-v2",
                String.valueOf(document.getId()),
                String.valueOf(document.getPublishVersionId()),
                String.valueOf(chunk.getOrdinal()),
                chunk.getContentHash()
        );
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void putMetadata(Map<String, Object> metadata, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        metadata.put(key, value);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength));
    }

    private record ChunkDraft(
            String sectionTitle,
            String chunkKind,
            String fieldName,
            AssistantSubject subject,
            String content
    ) {
    }
}
