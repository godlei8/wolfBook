package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.FaqItem;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantDocumentEntity;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.AssistantDocumentMapper;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.BoardService;
import com.wolfbook.backend.support.UploadProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AssistantKnowledgeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(AssistantKnowledgeService.class);

    private final AssistantDocumentMapper assistantDocumentMapper;
    private final AssistantPublishService assistantPublishService;
    private final BoardService boardService;
    private final RoleMapper roleMapper;
    private final ObjectMapper objectMapper;
    private final AssistantProperties assistantProperties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final UploadProvider uploadProvider;
    private final ExecutorService structuredKnowledgeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "assistant-structured-knowledge");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean structuredKnowledgeRebuildRunning = new AtomicBoolean(false);
    private final AtomicBoolean structuredKnowledgeRebuildPending = new AtomicBoolean(false);

    public AssistantKnowledgeService(
            AssistantDocumentMapper assistantDocumentMapper,
            AssistantPublishService assistantPublishService,
            BoardService boardService,
            RoleMapper roleMapper,
            ObjectMapper objectMapper,
            AssistantProperties assistantProperties,
            ObjectProvider<VectorStore> vectorStoreProvider,
            UploadProvider uploadProvider
    ) {
        this.assistantDocumentMapper = assistantDocumentMapper;
        this.assistantPublishService = assistantPublishService;
        this.boardService = boardService;
        this.roleMapper = roleMapper;
        this.objectMapper = objectMapper;
        this.assistantProperties = assistantProperties;
        this.vectorStoreProvider = vectorStoreProvider;
        this.uploadProvider = uploadProvider;
    }

    public List<AssistantDtos.AdminDocumentView> listDocuments() {
        ensureStructuredDocuments();
        return assistantDocumentMapper.selectList(new LambdaQueryWrapper<AssistantDocumentEntity>().orderByDesc(AssistantDocumentEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public void rebuildStructuredKnowledge() {
        syncStructuredDocuments(assistantPublishService.getCurrentVersionId(), true);
    }

    public void requestStructuredKnowledgeRebuild() {
        structuredKnowledgeRebuildPending.set(true);
        if (structuredKnowledgeRebuildRunning.compareAndSet(false, true)) {
            structuredKnowledgeExecutor.submit(this::drainStructuredKnowledgeRebuildQueue);
        }
    }

    public void syncPublishedKnowledgeVersion(Integer versionId) {
        if (versionId == null) {
            return;
        }
        assistantDocumentMapper.selectList(
                        new LambdaQueryWrapper<AssistantDocumentEntity>()
                                .eq(AssistantDocumentEntity::getPublishVersionId, versionId)
                                .eq(AssistantDocumentEntity::getReviewStatus, AssistantConstants.REVIEW_APPROVED)
                                .eq(AssistantDocumentEntity::getProcessingStatus, AssistantConstants.STATUS_READY)
                                .orderByAsc(AssistantDocumentEntity::getId)
                ).forEach(document -> {
                    indexDocument(document);
                    document.setUpdateTime(LocalDateTime.now());
                    assistantDocumentMapper.updateById(document);
                });
    }

    public AssistantDtos.AdminDocumentView uploadDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(4000, "Knowledge file can not be empty");
        }
        String fileName = file.getOriginalFilename() == null ? "knowledge.txt" : file.getOriginalFilename();
        String extension = extensionOf(fileName);
        if (!Set.of(".pdf", ".md", ".txt").contains(extension.toLowerCase(Locale.ROOT))) {
            throw new ApiException(4000, "Only pdf, md and txt are supported");
        }

        AssistantDocumentEntity entity = new AssistantDocumentEntity();
        entity.setName(stripExtension(fileName));
        entity.setFileName(fileName);
        entity.setSourceType(AssistantConstants.SOURCE_DOCUMENT);
        entity.setSourceKey("DOCUMENT:" + UUID.randomUUID());
        entity.setProcessingStatus(AssistantConstants.STATUS_READY);
        entity.setReviewStatus(AssistantConstants.REVIEW_PENDING);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());

        try {
            byte[] fileBytes = file.getBytes();
            UploadProvider.UploadResult uploaded = uploadProvider.uploadObject(fileName, file.getContentType(), fileBytes, "assistant/knowledge");
            entity.setFilePath(uploaded.url());
            String content = readFileContent(fileBytes, extension);
            entity.setContentText(content);
            entity.setSummary(buildSummary(content));
            entity.setChunkCount(chunkText(content).size());
            entity.setMetadataJson(write(Map.of("sourceType", AssistantConstants.SOURCE_DOCUMENT)));
            assistantDocumentMapper.insert(entity);
            indexDocument(entity);
            assistantDocumentMapper.updateById(entity);
            return toView(entity);
        } catch (Exception exception) {
            entity.setProcessingStatus(AssistantConstants.STATUS_FAILED);
            entity.setLastError(exception.getMessage());
            assistantDocumentMapper.insert(entity);
            throw new ApiException(5001, "Failed to process knowledge file");
        }
    }

    public AssistantDtos.AdminDocumentView updateReviewStatus(Integer id, String reviewStatus) {
        AssistantDocumentEntity entity = requireDocument(id);
        entity.setReviewStatus(reviewStatus);
        entity.setUpdateTime(LocalDateTime.now());
        assistantDocumentMapper.updateById(entity);
        return toView(entity);
    }

    public AssistantDtos.AdminDocumentView reindexDocument(Integer id) {
        AssistantDocumentEntity entity = requireDocument(id);
        if (entity.getContentText() == null || entity.getContentText().isBlank()) {
            throw new ApiException(4000, "Document content is empty");
        }
        indexDocument(entity);
        entity.setUpdateTime(LocalDateTime.now());
        assistantDocumentMapper.updateById(entity);
        return toView(entity);
    }

    public int clearUploadedDocuments() {
        List<AssistantDocumentEntity> uploadedDocuments = assistantDocumentMapper.selectList(
                new LambdaQueryWrapper<AssistantDocumentEntity>()
                        .eq(AssistantDocumentEntity::getSourceType, AssistantConstants.SOURCE_DOCUMENT)
                        .orderByDesc(AssistantDocumentEntity::getId)
        );
        if (uploadedDocuments.isEmpty()) {
            return 0;
        }

        List<Integer> removedIds = uploadedDocuments.stream()
                .map(AssistantDocumentEntity::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        uploadedDocuments.forEach(document -> deleteIndexedChunks(document, document.getChunkCount()));
        assistantDocumentMapper.delete(new LambdaQueryWrapper<AssistantDocumentEntity>()
                .eq(AssistantDocumentEntity::getSourceType, AssistantConstants.SOURCE_DOCUMENT));
        assistantPublishService.removeDocumentReferences(removedIds);
        return removedIds.size();
    }

    public void ensureStructuredDocuments() {
        syncStructuredDocuments(null, false);
    }

    private void syncStructuredDocuments(Integer publishVersionId, boolean refreshIndex) {
        List<Board> boards = boardService.listAllBoards();
        List<RoleEntity> roles = roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId));

        Map<String, AssistantDocumentEntity> existing = assistantDocumentMapper.selectList(
                        new LambdaQueryWrapper<AssistantDocumentEntity>()
                                .eq(AssistantDocumentEntity::getSourceType, AssistantConstants.SOURCE_STRUCTURED)
                ).stream()
                .collect(Collectors.toMap(AssistantDocumentEntity::getSourceKey, item -> item, (left, right) -> left, LinkedHashMap::new));

        Set<String> activeKeys = new java.util.LinkedHashSet<>();

        for (Board board : boards) {
            String sourceKey = "BOARD:" + board.id();
            activeKeys.add(sourceKey);
            upsertStructuredDocument(
                    existing.get(sourceKey),
                    sourceKey,
                    String.valueOf(board.id()),
                    board.name(),
                    buildBoardStructuredText(board),
                    publishVersionId,
                    refreshIndex
            );
        }

        for (RoleEntity role : roles) {
            String sourceKey = "ROLE:" + role.getId();
            activeKeys.add(sourceKey);
            upsertStructuredDocument(
                    existing.get(sourceKey),
                    sourceKey,
                    String.valueOf(role.getId()),
                    role.getName(),
                    buildRoleStructuredText(role),
                    publishVersionId,
                    refreshIndex
            );
        }

        existing.values().stream()
                .filter(item -> !activeKeys.contains(item.getSourceKey()))
                .forEach(item -> {
                    deleteIndexedChunks(item, item.getChunkCount());
                    assistantDocumentMapper.deleteById(item.getId());
                });
    }

    private void drainStructuredKnowledgeRebuildQueue() {
        try {
            while (structuredKnowledgeRebuildPending.compareAndSet(true, false)) {
                try {
                    rebuildStructuredKnowledge();
                } catch (Exception exception) {
                    log.error("Failed to rebuild structured assistant knowledge in background", exception);
                }
            }
        } finally {
            structuredKnowledgeRebuildRunning.set(false);
            if (structuredKnowledgeRebuildPending.get()) {
                requestStructuredKnowledgeRebuild();
            }
        }
    }

    public List<KnowledgeHit> searchPublishedKnowledge(String query) {
        ensureStructuredDocuments();
        Integer currentVersionId = assistantPublishService.getCurrentVersionId();
        if (currentVersionId == null) {
            return List.of();
        }

        List<AssistantDocumentEntity> publishedDocuments = assistantDocumentMapper.selectList(
                new LambdaQueryWrapper<AssistantDocumentEntity>()
                        .eq(AssistantDocumentEntity::getPublishVersionId, currentVersionId)
                        .eq(AssistantDocumentEntity::getReviewStatus, AssistantConstants.REVIEW_APPROVED)
                        .eq(AssistantDocumentEntity::getProcessingStatus, AssistantConstants.STATUS_READY)
        );
        if (publishedDocuments.isEmpty()) {
            return List.of();
        }

        Map<String, AssistantDocumentEntity> bySourceKey = publishedDocuments.stream()
                .collect(Collectors.toMap(AssistantDocumentEntity::getSourceKey, item -> item, (left, right) -> left));

        List<KnowledgeHit> vectorHits = searchViaVectorStore(query, currentVersionId, bySourceKey);
        if (!vectorHits.isEmpty()) {
            return vectorHits;
        }

        return publishedDocuments.stream()
                .map(document -> scoreFallback(query, document))
                .filter(hit -> hit.score() > 0)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(assistantProperties.getTopK())
                .toList();
    }

    private List<KnowledgeHit> searchViaVectorStore(String query, Integer currentVersionId, Map<String, AssistantDocumentEntity> bySourceKey) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return List.of();
        }
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(assistantProperties.getTopK())
                .similarityThreshold(assistantProperties.getSimilarityThreshold())
                .filterExpression("publishedVersion == " + currentVersionId)
                .build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<KnowledgeHit> hits = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            String sourceKey = String.valueOf(metadata.getOrDefault("sourceKey", ""));
            AssistantDocumentEntity source = bySourceKey.get(sourceKey);
            if (source == null) {
                continue;
            }
            String title = String.valueOf(metadata.getOrDefault("title", source.getName()));
            String sourceType = String.valueOf(metadata.getOrDefault("sourceType", source.getSourceType()));
            double score = document.getScore() == null ? 0.7d : document.getScore();
            hits.add(new KnowledgeHit(
                    source,
                    title,
                    sourceType,
                    trimSnippet(document.getText()),
                    score,
                    source.getSourceId()
            ));
        }
        return hits;
    }

    private KnowledgeHit scoreFallback(String query, AssistantDocumentEntity document) {
        String text = document.getContentText() == null ? "" : document.getContentText();
        String normalizedQuery = normalize(query);
        String normalizedText = normalize(text);
        int score = 0;
        for (String token : normalizedQuery.split("\\s+")) {
            if (!token.isBlank() && normalizedText.contains(token)) {
                score += 3;
            }
        }
        if (normalizedText.contains(normalize(document.getName()))) {
            score += 2;
        }
        return new KnowledgeHit(
                document,
                document.getName(),
                document.getSourceType(),
                trimSnippet(text),
                score,
                document.getSourceId()
        );
    }

    private void upsertStructuredDocument(
            AssistantDocumentEntity existing,
            String sourceKey,
            String sourceId,
            String name,
            String content,
            Integer publishVersionId,
            boolean refreshIndex
    ) {
        AssistantDocumentEntity entity = existing == null ? new AssistantDocumentEntity() : existing;
        int previousChunkCount = entity.getChunkCount() == null ? 0 : entity.getChunkCount();
        entity.setName(name);
        entity.setFileName(null);
        entity.setSourceType(AssistantConstants.SOURCE_STRUCTURED);
        entity.setSourceKey(sourceKey);
        entity.setSourceId(sourceId);
        entity.setFilePath(null);
        entity.setSummary(buildSummary(content));
        entity.setContentText(content);
        entity.setMetadataJson(write(Map.of("sourceType", AssistantConstants.SOURCE_STRUCTURED, "sourceKey", sourceKey)));
        entity.setChunkCount(chunkText(content).size());
        entity.setProcessingStatus(AssistantConstants.STATUS_READY);
        entity.setReviewStatus(AssistantConstants.REVIEW_APPROVED);
        if (publishVersionId != null) {
            entity.setPublishVersionId(publishVersionId);
        }
        entity.setUpdateTime(LocalDateTime.now());
        if (entity.getId() == null) {
            entity.setCreateTime(LocalDateTime.now());
            assistantDocumentMapper.insert(entity);
        } else {
            assistantDocumentMapper.updateById(entity);
        }
        if (refreshIndex) {
            indexDocument(entity, previousChunkCount);
            entity.setUpdateTime(LocalDateTime.now());
            assistantDocumentMapper.updateById(entity);
        }
    }

    private void indexDocument(AssistantDocumentEntity entity) {
        int previousChunkCount = entity.getChunkCount() == null ? 0 : entity.getChunkCount();
        indexDocument(entity, previousChunkCount);
    }

    private void indexDocument(AssistantDocumentEntity entity, int previousChunkCount) {
        List<String> chunks = chunkText(entity.getContentText());
        entity.setChunkCount(chunks.size());
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || entity.getPublishVersionId() == null) {
            return;
        }
        deleteIndexedChunks(entity, Math.max(previousChunkCount, chunks.size()));
        List<String> ids = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String chunkId = buildChunkId(entity, index);
            ids.add(chunkId);
            Map<String, Object> metadata = new HashMap<>();
            putMetadata(metadata, "sourceType", entity.getSourceType());
            putMetadata(metadata, "sourceKey", entity.getSourceKey());
            putMetadata(metadata, "sourceId", entity.getSourceId());
            putMetadata(metadata, "publishedVersion", entity.getPublishVersionId());
            putMetadata(metadata, "title", entity.getName());
            documents.add(Document.builder()
                    .id(chunkId)
                    .text(chunks.get(index))
                    .metadata(metadata)
                    .build());
        }
        vectorStore.add(documents);
    }

    private void deleteIndexedChunks(AssistantDocumentEntity entity, Integer chunkCount) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || entity.getId() == null || chunkCount == null || chunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < chunkCount; index++) {
            ids.add(buildChunkId(entity, index));
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception ignored) {
        }
    }

    private String buildChunkId(AssistantDocumentEntity entity, int chunkIndex) {
        String raw = String.join(":",
                "assistant-doc",
                String.valueOf(entity.getId()),
                String.valueOf(entity.getPublishVersionId()),
                entity.getSourceKey() == null ? "unknown" : entity.getSourceKey(),
                String.valueOf(chunkIndex)
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

    private String buildBoardStructuredText(Board board) {
        StringBuilder builder = new StringBuilder();
        builder.append("板子名称：").append(board.name()).append('\n');
        builder.append("人数：").append(board.playerCount()).append('\n');
        builder.append("难度：").append(board.difficulty()).append('\n');
        builder.append("标签：").append(String.join("、", board.tags())).append('\n');
        builder.append("规则类型：").append(board.ruleType()).append('\n');
        builder.append("胜利条件：").append(board.winCondition()).append('\n');
        builder.append("简介：").append(board.cardDescription() == null ? board.briefConfig() : board.cardDescription()).append('\n');
        if (board.specialRules() != null && !board.specialRules().isEmpty()) {
            builder.append("规则：").append(String.join("；", board.specialRules())).append('\n');
        }
        if (board.tips() != null && !board.tips().isEmpty()) {
            builder.append("提示：").append(String.join("；", board.tips())).append('\n');
        }
        if (board.faqs() != null && !board.faqs().isEmpty()) {
            builder.append("FAQ：");
            for (FaqItem faq : board.faqs()) {
                builder.append(faq.question()).append(" -> ").append(faq.answer()).append("；");
            }
        }
        return builder.toString();
    }

    private String buildRoleStructuredText(RoleEntity role) {
        StringBuilder builder = new StringBuilder();
        builder.append("角色名称：").append(role.getName()).append('\n');
        builder.append("别名：").append(role.getAlias() == null ? "" : role.getAlias()).append('\n');
        builder.append("阵营：").append(role.getFaction()).append('\n');
        builder.append("类型：").append(role.getRoleType()).append('\n');
        builder.append("定位：").append(role.getCamp()).append('\n');
        builder.append("技能：").append(role.getSkill()).append('\n');
        if (role.getBackground() != null && !role.getBackground().isBlank()) {
            builder.append("背景：").append(role.getBackground()).append('\n');
        }
        return builder.toString();
    }

    private AssistantDtos.AdminDocumentView toView(AssistantDocumentEntity entity) {
        return new AssistantDtos.AdminDocumentView(
                entity.getId(),
                entity.getName(),
                entity.getFileName(),
                entity.getSourceType(),
                entity.getSourceKey(),
                entity.getSourceId(),
                entity.getSummary(),
                entity.getChunkCount(),
                entity.getProcessingStatus(),
                entity.getReviewStatus(),
                entity.getPublishVersionId(),
                entity.getLastError(),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }

    private AssistantDocumentEntity requireDocument(Integer id) {
        AssistantDocumentEntity entity = assistantDocumentMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "Assistant document not found");
        }
        return entity;
    }

    private String readFileContent(byte[] contentBytes, String extension) throws IOException {
        if (".pdf".equalsIgnoreCase(extension)) {
            try (PDDocument document = Loader.loadPDF(contentBytes)) {
                return new PDFTextStripper().getText(document);
            }
        }
        return new String(contentBytes, StandardCharsets.UTF_8);
    }

    private List<String> chunkText(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        int chunkSize = 600;
        int overlap = 80;
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private String buildSummary(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    private String trimSnippet(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180) + "...";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ").trim();
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex) : ".txt";
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(5000, "Failed to write assistant metadata");
        }
    }

    @PreDestroy
    void shutdownStructuredKnowledgeExecutor() {
        structuredKnowledgeExecutor.shutdownNow();
    }

    record KnowledgeHit(
            AssistantDocumentEntity document,
            String title,
            String sourceType,
            String snippet,
            double score,
            String sourceId
    ) {
        AssistantDtos.AssistantCitation toCitation() {
            return new AssistantDtos.AssistantCitation(sourceType, title, snippet, null, sourceId);
        }
    }
}
