package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AI 助手 RAG 检索编排服务。
 *
 * <p>统一入口负责调用知识库检索、重排和记录可审计的检索元信息。
 * 回答服务不再关心向量/关键词细节，只消费已经收敛好的 chunk 命中。</p>
 */
@Service
class AssistantRetrievalService {

    private final AssistantKnowledgeService assistantKnowledgeService;
    private final AssistantReranker assistantReranker;

    AssistantRetrievalService(AssistantKnowledgeService assistantKnowledgeService, AssistantReranker assistantReranker) {
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.assistantReranker = assistantReranker;
    }

    AssistantRetrievalResult retrieve(String query, AssistantQueryPlan queryPlan, int topK, double similarityThreshold) {
        return retrieveMultiRound(query, queryPlan, null, topK, similarityThreshold);
    }

    AssistantRetrievalResult retrieveMultiRound(
            String query,
            AssistantQueryPlan queryPlan,
            String clarificationHint,
            int topK,
            double similarityThreshold
    ) {
        int selectedLimit = selectedLimit(queryPlan, topK);
        AssistantKnowledgeService.KnowledgeSearchResult firstRound = assistantKnowledgeService.searchPublishedKnowledgeResult(
                queryPlan == null ? query : queryPlan.effectiveQuery(),
                queryPlan,
                Math.max(selectedLimit * 3, selectedLimit + 6),
                similarityThreshold
        );
        AssistantKnowledgeService.KnowledgeSearchResult secondRound = null;
        if (clarificationHint != null && !clarificationHint.isBlank()) {
            String secondQuery = (queryPlan == null ? query : queryPlan.effectiveQuery()) + " " + clarificationHint.trim();
            secondRound = assistantKnowledgeService.searchPublishedKnowledgeResult(
                    secondQuery,
                    queryPlan,
                    Math.max(selectedLimit * 2, selectedLimit + 4),
                    similarityThreshold
            );
        }
        List<AssistantKnowledgeService.KnowledgeHit> mergedCandidates = Stream.concat(
                        firstRound.hits().stream(),
                        secondRound == null ? Stream.empty() : secondRound.hits().stream()
                )
                .distinct()
                .toList();
        List<AssistantKnowledgeService.KnowledgeHit> reranked = assistantReranker.rerank(mergedCandidates, queryPlan, selectedLimit);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("subjectKey", queryPlan == null ? null : queryPlan.subjectKey());
        meta.put("strictSubject", queryPlan != null && queryPlan.strictSubject());
        meta.put("boardCatalogQuery", queryPlan != null && queryPlan.boardCatalogQuery());
        meta.put("vectorSearchUsed", firstRound.vectorSearchUsed() || (secondRound != null && secondRound.vectorSearchUsed()));
        meta.put("candidateCount", mergedCandidates.size());
        meta.put("multiRound", secondRound != null);
        meta.put("firstRoundCount", firstRound.hits().size());
        meta.put("secondRoundCount", secondRound == null ? 0 : secondRound.hits().size());
        meta.put("selectedChunks", reranked.stream().map(hit -> Map.of(
                "chunkUid", hit.chunkUid() == null ? "" : hit.chunkUid(),
                "title", hit.title() == null ? "" : hit.title(),
                "subjectKey", hit.subjectKey() == null ? "" : hit.subjectKey(),
                "score", hit.score()
        )).toList());
        return new AssistantRetrievalResult(
                reranked,
                firstRound.retrievalMs() + (secondRound == null ? 0 : secondRound.retrievalMs()),
                firstRound.embeddingMs() + (secondRound == null ? 0 : secondRound.embeddingMs()),
                firstRound.vectorSearchUsed() || (secondRound != null && secondRound.vectorSearchUsed()),
                meta
        );
    }

    private int selectedLimit(AssistantQueryPlan queryPlan, int configuredTopK) {
        int safeTopK = Math.max(configuredTopK, 1);
        if (queryPlan != null && queryPlan.strictSubject()) {
            return Math.max(safeTopK, 12);
        }
        if (queryPlan != null && queryPlan.boardCatalogQuery()) {
            return Math.max(safeTopK, 10);
        }
        return safeTopK;
    }
}
