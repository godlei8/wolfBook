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
import com.wolfbook.backend.entity.AssistantKnowledgeChunkEntity;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.AssistantDocumentMapper;
import com.wolfbook.backend.mapper.AssistantKnowledgeChunkMapper;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 助手知识库服务。
 *
 * <p>负责上传文档解析、结构化板子/角色资料同步、向量索引、关键词兜底检索和命中结果裁剪。
 * 为了避免“问舞者答假面”这类串题，检索阶段会先提取用户问题里的主体 token，
 * 再把命中文档裁成只包含该主体的片段。</p>
 */
@Service
public class AssistantKnowledgeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<FaqItem>> FAQ_LIST_TYPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(AssistantKnowledgeService.class);
    private static final int KEYWORD_SEGMENT_MAX_LENGTH = 900;
    private static final double MIN_KEYWORD_SCORE = 8.0d;
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|\\d{1,3}[.、．]\\s*.+|第[一二三四五六七八九十百0-9]{1,4}[章节].*)$");

    private final AssistantDocumentMapper assistantDocumentMapper;
    private final AssistantKnowledgeChunkMapper assistantKnowledgeChunkMapper;
    private final AssistantPublishService assistantPublishService;
    private final BoardService boardService;
    private final RoleMapper roleMapper;
    private final ObjectMapper objectMapper;
    private final AssistantProperties assistantProperties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AssistantKnowledgeIndexer assistantKnowledgeIndexer;
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
            AssistantKnowledgeChunkMapper assistantKnowledgeChunkMapper,
            AssistantPublishService assistantPublishService,
            BoardService boardService,
            RoleMapper roleMapper,
            ObjectMapper objectMapper,
            AssistantProperties assistantProperties,
            ObjectProvider<VectorStore> vectorStoreProvider,
            AssistantKnowledgeIndexer assistantKnowledgeIndexer,
            UploadProvider uploadProvider
    ) {
        this.assistantDocumentMapper = assistantDocumentMapper;
        this.assistantKnowledgeChunkMapper = assistantKnowledgeChunkMapper;
        this.assistantPublishService = assistantPublishService;
        this.boardService = boardService;
        this.roleMapper = roleMapper;
        this.objectMapper = objectMapper;
        this.assistantProperties = assistantProperties;
        this.vectorStoreProvider = vectorStoreProvider;
        this.assistantKnowledgeIndexer = assistantKnowledgeIndexer;
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
        return searchPublishedKnowledgeResult(query).hits();
    }

    public KnowledgeSearchResult searchPublishedKnowledgeResult(String query) {
        return searchPublishedKnowledgeResult(query, assistantProperties.getTopK(), assistantProperties.getSimilarityThreshold());
    }

    public KnowledgeSearchResult searchPublishedKnowledgeResult(String query, int topK, double similarityThreshold) {
        AssistantQueryPlan queryPlan = new AssistantQueryPlan(
                query,
                query,
                null,
                false,
                false,
                false,
                false,
                AssistantKeywordMatcher.extractTokens(query)
        );
        return searchPublishedKnowledgeResult(query, queryPlan, topK, similarityThreshold);
    }

    KnowledgeSearchResult searchPublishedKnowledgeResult(
            String query,
            AssistantQueryPlan queryPlan,
            int topK,
            double similarityThreshold
    ) {
        long startAt = System.currentTimeMillis();
        ensureStructuredDocuments();
        Integer currentVersionId = assistantPublishService.getCurrentVersionId();
        List<AssistantDocumentEntity> searchableDocuments = loadSearchableDocuments(currentVersionId);
        if (searchableDocuments.isEmpty()) {
            return new KnowledgeSearchResult(List.of(), System.currentTimeMillis() - startAt, 0L, false);
        }
        ensureChunkIndex(searchableDocuments);

        Map<String, AssistantDocumentEntity> bySourceKey = searchableDocuments.stream()
                .collect(Collectors.toMap(AssistantDocumentEntity::getSourceKey, item -> item, (left, right) -> left));
        Map<Integer, AssistantDocumentEntity> byDocumentId = searchableDocuments.stream()
                .filter(document -> document.getId() != null)
                .collect(Collectors.toMap(AssistantDocumentEntity::getId, item -> item, (left, right) -> left));
        List<AssistantKnowledgeChunkEntity> searchableChunks = loadSearchableChunks(currentVersionId, queryPlan, byDocumentId, true);
        boolean unscopedTermFallback = false;
        if (searchableChunks.isEmpty() && isStrictTermQuery(queryPlan)) {
            // 老资料可能还没有被识别成 TERM:金水 这类主体。这里只放宽 chunk 的 subjectKey 过滤，
            // 后面的关键词检索仍必须命中术语本身，避免退回到“狼人杀”里的“狼人”泛检索。
            searchableChunks = loadSearchableChunks(currentVersionId, queryPlan, byDocumentId, false);
            unscopedTermFallback = true;
        }
        Map<String, AssistantKnowledgeChunkEntity> chunksByUid = searchableChunks.stream()
                .collect(Collectors.toMap(AssistantKnowledgeChunkEntity::getChunkUid, item -> item, (left, right) -> left, LinkedHashMap::new));

        if (!chunksByUid.isEmpty()) {
            List<KnowledgeHit> vectorHits = unscopedTermFallback
                    ? List.of()
                    : searchChunksViaVectorStore(query, queryPlan, currentVersionId, chunksByUid, byDocumentId, topK, similarityThreshold);
            List<KnowledgeHit> keywordHits = searchChunksViaKeyword(query, queryPlan, new ArrayList<>(chunksByUid.values()), byDocumentId, unscopedTermFallback);
            List<KnowledgeHit> mergedHits = new ArrayList<>(keywordHits);
            mergedHits.addAll(vectorHits);
            return new KnowledgeSearchResult(pruneHits(mergedHits, topK), System.currentTimeMillis() - startAt, 0L, !vectorHits.isEmpty());
        }

        // 没有 chunk 表时才退回旧检索，但仍优先沿用查询计划里的主体，不能重新把术语问题解析成角色。
        QueryFocus queryFocus = queryFocusFromPlan(queryPlan).orElseGet(() -> resolveQueryFocus(query));
        List<KnowledgeHit> vectorHits = searchViaVectorStore(query, queryFocus, currentVersionId, bySourceKey, topK, similarityThreshold);
        List<KnowledgeHit> keywordHits = searchViaKeywordFallback(query, queryFocus, searchableDocuments);
        List<KnowledgeHit> mergedHits = new ArrayList<>(keywordHits);
        mergedHits.addAll(vectorHits);
        return new KnowledgeSearchResult(pruneHits(mergedHits, topK), System.currentTimeMillis() - startAt, 0L, !vectorHits.isEmpty());
    }

    private void ensureChunkIndex(List<AssistantDocumentEntity> documents) {
        for (AssistantDocumentEntity document : documents) {
            if (document.getId() == null || document.getContentText() == null || document.getContentText().isBlank()) {
                continue;
            }
            Long chunkCount = assistantKnowledgeChunkMapper.selectCount(
                    new LambdaQueryWrapper<AssistantKnowledgeChunkEntity>()
                            .eq(AssistantKnowledgeChunkEntity::getDocumentId, document.getId())
            );
            if (chunkCount != null && chunkCount > 0 && hasCurrentChunkSchema(document)) {
                continue;
            }
            int indexedCount = assistantKnowledgeIndexer.indexDocument(document);
            document.setChunkCount(indexedCount);
            markCurrentChunkSchema(document);
            document.setUpdateTime(LocalDateTime.now());
            assistantDocumentMapper.updateById(document);
        }
    }

    private List<AssistantKnowledgeChunkEntity> loadSearchableChunks(
            Integer currentVersionId,
            AssistantQueryPlan queryPlan,
            Map<Integer, AssistantDocumentEntity> byDocumentId,
            boolean enforceSubjectFilter
    ) {
        if (byDocumentId.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<AssistantKnowledgeChunkEntity> wrapper = new LambdaQueryWrapper<AssistantKnowledgeChunkEntity>()
                .in(AssistantKnowledgeChunkEntity::getDocumentId, byDocumentId.keySet())
                .orderByAsc(AssistantKnowledgeChunkEntity::getDocumentId)
                .orderByAsc(AssistantKnowledgeChunkEntity::getOrdinal);
        if (currentVersionId != null) {
            wrapper.and(nested -> nested
                    .eq(AssistantKnowledgeChunkEntity::getPublishVersionId, currentVersionId)
                    .or()
                    .isNull(AssistantKnowledgeChunkEntity::getPublishVersionId));
        }
        if (enforceSubjectFilter && queryPlan != null && queryPlan.strictSubject() && queryPlan.subjectKey() != null) {
            wrapper.eq(AssistantKnowledgeChunkEntity::getSubjectKey, queryPlan.subjectKey());
        }
        return assistantKnowledgeChunkMapper.selectList(wrapper);
    }

    private List<KnowledgeHit> searchChunksViaVectorStore(
            String query,
            AssistantQueryPlan queryPlan,
            Integer currentVersionId,
            Map<String, AssistantKnowledgeChunkEntity> chunksByUid,
            Map<Integer, AssistantDocumentEntity> documentsById,
            int topK,
            double similarityThreshold
    ) {
        if (currentVersionId == null || chunksByUid.isEmpty()) {
            return List.of();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return List.of();
        }
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(queryPlan == null ? query : queryPlan.effectiveQuery())
                .topK(Math.max(topK * 6, topK + 6))
                .similarityThreshold(similarityThreshold)
                .filterExpression("publishedVersion == " + currentVersionId)
                .build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<KnowledgeHit> hits = new ArrayList<>();
        for (Document vectorDocument : documents) {
            Map<String, Object> metadata = vectorDocument.getMetadata();
            String chunkUid = String.valueOf(metadata.getOrDefault("chunkUid", ""));
            AssistantKnowledgeChunkEntity chunk = chunksByUid.get(chunkUid);
            if (chunk == null || !matchesQueryPlan(chunk, queryPlan)) {
                continue;
            }
            AssistantDocumentEntity source = documentsById.get(chunk.getDocumentId());
            if (source == null) {
                continue;
            }
            double score = (vectorDocument.getScore() == null ? 0.7d : vectorDocument.getScore()) * 100;
            if (queryPlan != null && queryPlan.subjectKey() != null && queryPlan.subjectKey().equals(chunk.getSubjectKey())) {
                score += 150;
            }
            hits.add(toKnowledgeHit(source, chunk, score));
        }
        return hits;
    }

    private List<KnowledgeHit> searchChunksViaKeyword(
            String query,
            AssistantQueryPlan queryPlan,
            List<AssistantKnowledgeChunkEntity> chunks,
            Map<Integer, AssistantDocumentEntity> documentsById,
            boolean allowUnscopedTermFallback
    ) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        String effectiveQuery = queryPlan == null ? query : queryPlan.effectiveQuery();
        List<KnowledgeHit> hits = new ArrayList<>();
        for (AssistantKnowledgeChunkEntity chunk : chunks) {
            if (!matchesQueryPlan(chunk, queryPlan)) {
                if (!allowUnscopedTermFallback || !matchesStrictTermFallback(chunk, queryPlan)) {
                    continue;
                }
            }
            AssistantDocumentEntity source = documentsById.get(chunk.getDocumentId());
            if (source == null) {
                continue;
            }
            String title = chunkTitle(source, chunk);
            AssistantKeywordMatcher.KeywordMatch match = AssistantKeywordMatcher.match(
                    effectiveQuery,
                    title,
                    source.getFileName(),
                    source.getSummary(),
                    chunk.getEmbeddingText() == null ? chunk.getContentText() : chunk.getEmbeddingText(),
                    source.getSourceType()
            );
            if (match.score() < MIN_KEYWORD_SCORE) {
                continue;
            }
            double score = match.score();
            if (queryPlan != null && queryPlan.subjectKey() != null && queryPlan.subjectKey().equals(chunk.getSubjectKey())) {
                score += 150;
            }
            hits.add(toKnowledgeHit(source, chunk, score));
        }
        return hits.stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    private boolean matchesQueryPlan(AssistantKnowledgeChunkEntity chunk, AssistantQueryPlan queryPlan) {
        if (chunk == null || queryPlan == null || !queryPlan.strictSubject() || queryPlan.subjectKey() == null) {
            return true;
        }
        return queryPlan.subjectKey().equals(chunk.getSubjectKey());
    }

    private boolean matchesStrictTermFallback(AssistantKnowledgeChunkEntity chunk, AssistantQueryPlan queryPlan) {
        if (chunk == null || !isStrictTermQuery(queryPlan)) {
            return false;
        }
        List<String> termTokens = termTokens(queryPlan);
        if (termTokens.isEmpty()) {
            return false;
        }
        String text = String.join("\n",
                chunk.getSubjectName() == null ? "" : chunk.getSubjectName(),
                chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle(),
                chunk.getEmbeddingText() == null ? "" : chunk.getEmbeddingText(),
                chunk.getContentText() == null ? "" : chunk.getContentText()
        );
        return AssistantKeywordMatcher.containsAnyToken(text, termTokens);
    }

    private boolean isStrictTermQuery(AssistantQueryPlan queryPlan) {
        return queryPlan != null
                && queryPlan.strictSubject()
                && queryPlan.subject() != null
                && queryPlan.subject().isTerm();
    }

    private List<String> termTokens(AssistantQueryPlan queryPlan) {
        if (queryPlan == null || queryPlan.subject() == null) {
            return List.of();
        }
        if (queryPlan.subject().terms() != null && !queryPlan.subject().terms().isEmpty()) {
            return queryPlan.subject().terms();
        }
        return queryPlan.tokens() == null ? List.of() : queryPlan.tokens();
    }

    private KnowledgeHit toKnowledgeHit(AssistantDocumentEntity source, AssistantKnowledgeChunkEntity chunk, double score) {
        String content = chunk.getContentText() == null ? "" : chunk.getContentText();
        return new KnowledgeHit(
                source,
                chunkTitle(source, chunk),
                chunk.getSourceType() == null ? source.getSourceType() : chunk.getSourceType(),
                trimSnippet(content),
                compactContext(content),
                score,
                source.getSourceId(),
                chunk.getChunkUid(),
                chunk.getSubjectKey(),
                chunk.getSubjectName(),
                chunk.getChunkKind()
        );
    }

    private String chunkTitle(AssistantDocumentEntity source, AssistantKnowledgeChunkEntity chunk) {
        List<String> parts = new ArrayList<>();
        if (chunk.getSubjectName() != null && !chunk.getSubjectName().isBlank()) {
            parts.add(chunk.getSubjectName());
        } else {
            parts.add(source.getName());
        }
        if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isBlank()
                && parts.stream().noneMatch(part -> part.equalsIgnoreCase(chunk.getSectionTitle()))) {
            parts.add(chunk.getSectionTitle());
        }
        if (chunk.getFieldName() != null && !chunk.getFieldName().isBlank()) {
            parts.add(chunk.getFieldName());
        }
        return String.join(" / ", parts);
    }

    public String buildKnowledgeCacheVersion() {
        ensureStructuredDocuments();
        Integer currentVersionId = assistantPublishService.getCurrentVersionId();
        List<AssistantDocumentEntity> searchableDocuments = loadSearchableDocuments(currentVersionId);
        String latestUpdate = searchableDocuments.stream()
                .map(AssistantDocumentEntity::getUpdateTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(LocalDateTime::toString)
                .orElse("none");
        return "knowledge-v5:%s:%s:%d:%s".formatted(
                AssistantKnowledgeIndexer.INDEX_SCHEMA_VERSION,
                currentVersionId == null ? "draft" : currentVersionId,
                searchableDocuments.size(),
                latestUpdate
        );
    }

    private List<AssistantDocumentEntity> loadSearchableDocuments(Integer currentVersionId) {
        List<AssistantDocumentEntity> approvedDocuments = assistantDocumentMapper.selectList(
                new LambdaQueryWrapper<AssistantDocumentEntity>()
                        .eq(AssistantDocumentEntity::getReviewStatus, AssistantConstants.REVIEW_APPROVED)
                        .eq(AssistantDocumentEntity::getProcessingStatus, AssistantConstants.STATUS_READY)
        );
        if (currentVersionId == null) {
            return approvedDocuments;
        }
        return approvedDocuments.stream()
                .filter(document -> currentVersionId.equals(document.getPublishVersionId())
                        || AssistantConstants.SOURCE_STRUCTURED.equals(document.getSourceType()) && document.getPublishVersionId() == null)
                .toList();
    }

    private QueryFocus resolveQueryFocus(String query) {
        List<String> fallbackTokens = AssistantKeywordMatcher.extractTokens(query);
        String normalizedQuery = normalize(query).replace(" ", "");
        if (normalizedQuery.isBlank()) {
            return QueryFocus.fromTokens(fallbackTokens);
        }

        List<QueryFocusCandidate> candidates = new ArrayList<>();
        int roleContextBoost = containsAny(query, List.of("技能", "能力", "身份", "角色", "信息", "介绍", "发动", "怎么用")) ? 30 : 0;
        int boardContextBoost = containsAny(query, List.of("板子", "局", "配置", "人数", "阵容", "规则", "流程")) ? 30 : 0;

        roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId))
                .forEach(role -> {
                    addFocusCandidate(candidates, normalizedQuery, "ROLE", "ROLE:" + role.getId(), role.getName(), role.getName(), roleContextBoost);
                    for (String alias : splitAliases(role.getAlias())) {
                        addFocusCandidate(candidates, normalizedQuery, "ROLE", "ROLE:" + role.getId(), role.getName(), alias, roleContextBoost - 5);
                    }
                });

        boardService.listAllBoards().forEach(board ->
                addFocusCandidate(candidates, normalizedQuery, "BOARD", "BOARD:" + board.id(), board.name(), board.name(), boardContextBoost)
        );

        return candidates.stream()
                .max(Comparator.comparingInt(QueryFocusCandidate::score)
                        .thenComparingInt(candidate -> normalize(candidate.term()).replace(" ", "").length()))
                .map(candidate -> new QueryFocus(
                        candidate.type(),
                        candidate.sourceKey(),
                        candidate.displayName(),
                        focusTokens(candidate, fallbackTokens)
                ))
                .orElseGet(() -> QueryFocus.fromTokens(fallbackTokens));
    }

    private Optional<QueryFocus> queryFocusFromPlan(AssistantQueryPlan queryPlan) {
        if (queryPlan == null || queryPlan.subject() == null) {
            return Optional.empty();
        }
        AssistantSubject subject = queryPlan.subject();
        List<String> tokens = subject.terms() == null || subject.terms().isEmpty()
                ? queryPlan.tokens()
                : subject.terms();
        return Optional.of(new QueryFocus(
                subject.type(),
                subject.sourceKey(),
                subject.name(),
                tokens == null ? List.of() : tokens
        ));
    }

    private void addFocusCandidate(
            List<QueryFocusCandidate> candidates,
            String normalizedQuery,
            String type,
            String sourceKey,
            String displayName,
            String term,
            int contextBoost
    ) {
        String normalizedTerm = normalize(term).replace(" ", "");
        if (normalizedTerm.length() < 2 || !normalizedQuery.contains(normalizedTerm)) {
            return;
        }
        int score = normalizedTerm.length() * 10 + Math.max(contextBoost, 0);
        candidates.add(new QueryFocusCandidate(type, sourceKey, displayName, term, score));
    }

    private List<String> focusTokens(QueryFocusCandidate candidate, List<String> fallbackTokens) {
        List<String> tokens = new ArrayList<>();
        if (candidate.term() != null && !candidate.term().isBlank()) {
            tokens.add(candidate.term());
        }
        if (candidate.displayName() != null
                && !candidate.displayName().isBlank()
                && !candidate.displayName().equals(candidate.term())) {
            tokens.add(candidate.displayName());
        }
        return tokens.isEmpty() ? fallbackTokens : tokens;
    }

    private List<String> splitAliases(String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(alias.split("[,，、/|\\s]+"))
                .map(String::trim)
                .filter(item -> item.length() >= 2)
                .toList();
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkipForFocus(AssistantDocumentEntity document, QueryFocus queryFocus) {
        if (document == null || queryFocus == null || !queryFocus.hasSourceKey()) {
            return false;
        }
        String sourceKey = document.getSourceKey() == null ? "" : document.getSourceKey();
        if ("ROLE".equals(queryFocus.type()) && sourceKey.startsWith("ROLE:")) {
            return !queryFocus.matchesSource(document);
        }
        if ("BOARD".equals(queryFocus.type()) && sourceKey.startsWith("BOARD:")) {
            return !queryFocus.matchesSource(document);
        }
        return false;
    }

    private List<KnowledgeHit> searchViaVectorStore(
            String query,
            QueryFocus queryFocus,
            Integer currentVersionId,
            Map<String, AssistantDocumentEntity> bySourceKey,
            int topK,
            double similarityThreshold
    ) {
        if (currentVersionId == null) {
            return List.of();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return List.of();
        }
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK + 2, topK))
                .similarityThreshold(similarityThreshold)
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
            if (shouldSkipForFocus(source, queryFocus)) {
                continue;
            }
            String title = String.valueOf(metadata.getOrDefault("title", source.getName()));
            String sourceType = String.valueOf(metadata.getOrDefault("sourceType", source.getSourceType()));
            // 向量命中可能是整段大文本；如果用户问了具体主体，只保留包含主体的行/块。
            String focusedText = AssistantKeywordMatcher.focusContent(document.getText(), queryFocus.tokens());
            if (queryFocus.hasTokens() && focusedText.isBlank()) {
                continue;
            }
            String hitText = focusedText.isBlank() ? document.getText() : focusedText;
            String hitTitle = AssistantKeywordMatcher.focusTitle(title, hitText, queryFocus.tokens());
            double score = (document.getScore() == null ? 0.7d : document.getScore()) * 100;
            if (queryFocus.matchesSource(source)) {
                score += 120;
            }
            hits.add(new KnowledgeHit(
                    source,
                    hitTitle,
                    sourceType,
                    trimSnippet(hitText),
                    compactContext(hitText),
                    score,
                    source.getSourceId()
            ));
        }
        return hits;
    }

    List<KnowledgeHit> pruneHits(List<KnowledgeHit> hits, int topK) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeHit> deduplicated = new LinkedHashMap<>();
        // 关键词命中和向量命中会合并到一起，这里按分数排序后用稳定 key 去重。
        hits.stream()
                .sorted((left, right) -> {
                    int scoreCompare = Double.compare(right.score(), left.score());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    int sourceCompare = Integer.compare(sourcePriority(left.sourceType()), sourcePriority(right.sourceType()));
                    if (sourceCompare != 0) {
                        return sourceCompare;
                    }
                    return left.title().compareToIgnoreCase(right.title());
                })
                .forEach(hit -> deduplicated.putIfAbsent(hitDeduplicationKey(hit), hit));
        return deduplicated.values().stream()
                .limit(Math.max(topK, 1))
                .toList();
    }

    private String hitDeduplicationKey(KnowledgeHit hit) {
        if (hit == null || hit.document() == null) {
            return "unknown";
        }
        if (hit.chunkUid() != null && !hit.chunkUid().isBlank()) {
            return hit.chunkUid();
        }
        if (AssistantConstants.SOURCE_DOCUMENT.equals(hit.sourceType())) {
            return hit.document().getSourceKey() + ":" + normalize(hit.title());
        }
        return hit.document().getSourceKey();
    }

    private boolean hasCurrentChunkSchema(AssistantDocumentEntity document) {
        Map<String, Object> metadata = readMetadata(document == null ? null : document.getMetadataJson());
        return AssistantKnowledgeIndexer.INDEX_SCHEMA_VERSION.equals(String.valueOf(metadata.getOrDefault("chunkSchema", "")));
    }

    private void markCurrentChunkSchema(AssistantDocumentEntity document) {
        if (document == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(readMetadata(document.getMetadataJson()));
        metadata.put("sourceType", document.getSourceType());
        if (document.getSourceKey() != null && !document.getSourceKey().isBlank()) {
            metadata.put("sourceKey", document.getSourceKey());
        }
        metadata.put("chunkSchema", AssistantKnowledgeIndexer.INDEX_SCHEMA_VERSION);
        document.setMetadataJson(write(metadata));
    }

    private Map<String, Object> readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private int sourcePriority(String sourceType) {
        if (AssistantConstants.SOURCE_DOCUMENT.equals(sourceType)) {
            return 0;
        }
        if (AssistantConstants.SOURCE_STRUCTURED.equals(sourceType)) {
            return 1;
        }
        return 2;
    }

    private List<KnowledgeHit> searchViaKeywordFallback(String query, QueryFocus queryFocus, List<AssistantDocumentEntity> documents) {
        List<KnowledgeHit> hits = new ArrayList<>();
        for (AssistantDocumentEntity document : documents) {
            if (shouldSkipForFocus(document, queryFocus)) {
                continue;
            }
            for (KnowledgeSegment segment : keywordSegments(document)) {
                // 关键词检索先裁剪再打分，防止同章节里相邻角色因为共同标题被一起送进上下文。
                String focusedContent = AssistantKeywordMatcher.focusContent(segment.content(), queryFocus.tokens());
                String matchContent = focusedContent.isBlank() ? segment.content() : focusedContent;
                AssistantKeywordMatcher.KeywordMatch match = AssistantKeywordMatcher.match(
                        query,
                        segment.title(),
                        document.getFileName(),
                        document.getSummary(),
                        matchContent,
                        document.getSourceType()
                );
                if (match.score() < MIN_KEYWORD_SCORE) {
                    continue;
                }
                if (queryFocus.hasTokens()
                        && focusedContent.isBlank()
                        && !AssistantKeywordMatcher.containsAnyToken(segment.title(), queryFocus.tokens())) {
                    continue;
                }
                String hitTitle = AssistantKeywordMatcher.focusTitle(segment.title(), matchContent, queryFocus.tokens());
                double score = match.score();
                if (queryFocus.matchesSource(document)) {
                    score += 120;
                }
                hits.add(new KnowledgeHit(
                        document,
                        hitTitle,
                        document.getSourceType(),
                        match.snippet().isBlank() ? trimSnippet(matchContent) : match.snippet(),
                        compactContext(matchContent),
                        score,
                        document.getSourceId()
                ));
            }
        }
        return hits.stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    private List<KnowledgeSegment> keywordSegments(AssistantDocumentEntity document) {
        String content = document.getContentText() == null ? "" : document.getContentText().replace("\r\n", "\n").trim();
        if (content.isBlank()) {
            String summary = document.getSummary() == null ? "" : document.getSummary();
            return summary.isBlank() ? List.of() : List.of(new KnowledgeSegment(document.getName(), summary));
        }
        if (!AssistantConstants.SOURCE_DOCUMENT.equals(document.getSourceType())) {
            return List.of(new KnowledgeSegment(document.getName(), content));
        }

        // 上传文档按 Markdown/章节标题切成粗粒度段落；后续还会按用户主体做二次裁剪。
        List<KnowledgeSegment> segments = new ArrayList<>();
        String currentTitle = document.getName();
        StringBuilder current = new StringBuilder();
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            boolean heading = isSectionHeading(line);
            if (heading && !current.isEmpty()) {
                addKnowledgeSegment(segments, currentTitle, current.toString());
                current.setLength(0);
            }
            if (heading) {
                currentTitle = document.getName() + " / " + cleanHeading(line);
            }
            current.append(rawLine).append('\n');
            if (current.length() >= KEYWORD_SEGMENT_MAX_LENGTH) {
                addKnowledgeSegment(segments, currentTitle, current.toString());
                current.setLength(0);
            }
        }
        addKnowledgeSegment(segments, currentTitle, current.toString());
        return segments.isEmpty() ? List.of(new KnowledgeSegment(document.getName(), trimSnippet(content))) : segments;
    }

    private boolean isSectionHeading(String line) {
        return line != null
                && line.length() <= 120
                && MARKDOWN_HEADING_PATTERN.matcher(line).matches();
    }

    private String cleanHeading(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        return line.replaceFirst("^#{1,6}\\s+", "")
                .replaceFirst("^\\d{1,3}[.、．]\\s*", "")
                .strip();
    }

    private void addKnowledgeSegment(List<KnowledgeSegment> segments, String title, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.length() > KEYWORD_SEGMENT_MAX_LENGTH) {
            normalized = normalized.substring(0, KEYWORD_SEGMENT_MAX_LENGTH) + "...";
        }
        segments.add(new KnowledgeSegment(title, normalized));
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
        boolean versionChanged = publishVersionId != null
                && (existing == null || !Objects.equals(existing.getPublishVersionId(), publishVersionId));
        boolean contentChanged = existing == null
                || !Objects.equals(existing.getName(), name)
                || !Objects.equals(existing.getSourceId(), sourceId)
                || !Objects.equals(existing.getContentText(), content)
                || !Objects.equals(existing.getProcessingStatus(), AssistantConstants.STATUS_READY)
                || !Objects.equals(existing.getReviewStatus(), AssistantConstants.REVIEW_APPROVED);
        if (!contentChanged && !versionChanged && !refreshIndex) {
            return;
        }

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
        if (contentChanged || versionChanged || refreshIndex) {
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
        int indexedCount = assistantKnowledgeIndexer.indexDocument(entity);
        entity.setChunkCount(indexedCount);
        markCurrentChunkSchema(entity);
    }

    private void deleteIndexedChunks(AssistantDocumentEntity entity, Integer chunkCount) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        assistantKnowledgeIndexer.deleteDocumentChunks(entity.getId());
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
        if (board.roles() != null && !board.roles().isEmpty()) {
            builder.append("\n阵容：");
            Map<Integer, RoleEntity> rolesById = roleMapper.selectBatchIds(
                            board.roles().stream().map(role -> role.roleId()).filter(Objects::nonNull).toList()
                    ).stream()
                    .collect(Collectors.toMap(RoleEntity::getId, item -> item, (left, right) -> left));
            board.roles().forEach(role -> {
                RoleEntity roleEntity = rolesById.get(role.roleId());
                String roleName = roleEntity == null ? "角色" + role.roleId() : roleEntity.getName();
                builder.append(roleName).append("x").append(role.count()).append("；");
            });
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
        List<FaqItem> faqs = readFaqs(role.getFaqs());
        if (!faqs.isEmpty()) {
            builder.append("FAQ：");
            for (FaqItem faq : faqs) {
                builder.append(faq.question()).append(" -> ").append(faq.answer()).append("；");
            }
        }
        return builder.toString();
    }

    private List<FaqItem> readFaqs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, FAQ_LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
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

    private String compactContext(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 1200) {
            return normalized;
        }
        return normalized.substring(0, 1200) + "...";
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

    private record QueryFocus(
            String type,
            String sourceKey,
            String displayName,
            List<String> tokens
    ) {
        static QueryFocus fromTokens(List<String> tokens) {
            return new QueryFocus("", "", "", tokens == null ? List.of() : tokens);
        }

        boolean hasTokens() {
            return tokens != null && !tokens.isEmpty();
        }

        boolean hasSourceKey() {
            return sourceKey != null && !sourceKey.isBlank();
        }

        boolean matchesSource(AssistantDocumentEntity document) {
            return document != null
                    && hasSourceKey()
                    && sourceKey.equals(document.getSourceKey());
        }
    }

    private record QueryFocusCandidate(
            String type,
            String sourceKey,
            String displayName,
            String term,
            int score
    ) {
    }

    record KnowledgeHit(
            AssistantDocumentEntity document,
            String title,
            String sourceType,
            String snippet,
            String contextText,
            double score,
            String sourceId,
            String chunkUid,
            String subjectKey,
            String subjectName,
            String chunkKind
    ) {
        KnowledgeHit(
                AssistantDocumentEntity document,
                String title,
                String sourceType,
                String snippet,
                String contextText,
                double score,
                String sourceId
        ) {
            this(document, title, sourceType, snippet, contextText, score, sourceId, null, null, null, null);
        }

        AssistantDtos.AssistantCitation toCitation() {
            return new AssistantDtos.AssistantCitation(sourceType, title, snippet, null, sourceId);
        }
    }

    public record KnowledgeSearchResult(
            List<KnowledgeHit> hits,
            long retrievalMs,
            long embeddingMs,
            boolean vectorSearchUsed
    ) {
    }

    private record KnowledgeSegment(
            String title,
            String content
    ) {
    }
}
