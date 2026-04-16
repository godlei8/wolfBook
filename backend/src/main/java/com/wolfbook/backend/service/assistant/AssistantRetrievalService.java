package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        int selectedLimit = selectedLimit(queryPlan, topK);
        AssistantKnowledgeService.KnowledgeSearchResult searchResult = assistantKnowledgeService.searchPublishedKnowledgeResult(
                query,
                queryPlan,
                Math.max(selectedLimit * 3, selectedLimit + 6),
                similarityThreshold
        );
        List<AssistantKnowledgeService.KnowledgeHit> reranked = assistantReranker.rerank(searchResult.hits(), queryPlan, selectedLimit);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("subjectKey", queryPlan == null ? null : queryPlan.subjectKey());
        meta.put("queryType", queryPlan == null || queryPlan.queryType() == null ? AssistantQueryType.OPEN_QA.name() : queryPlan.queryType().name());
        meta.put("strictSubject", queryPlan != null && queryPlan.strictSubject());
        meta.put("boardCatalogQuery", queryPlan != null && queryPlan.boardCatalogQuery());
        meta.put("vectorSearchUsed", searchResult.vectorSearchUsed());
        meta.put("candidateCount", searchResult.hits().size());
        if (searchResult.diagnostics() != null && !searchResult.diagnostics().isEmpty()) {
            meta.put("retrievalDiagnostics", searchResult.diagnostics());
        }
        meta.put("selectedChunks", reranked.stream().map(hit -> Map.of(
                "chunkUid", hit.chunkUid() == null ? "" : hit.chunkUid(),
                "title", hit.title() == null ? "" : hit.title(),
                "subjectKey", hit.subjectKey() == null ? "" : hit.subjectKey(),
                "score", hit.score(),
                "retrievalChannel", hit.retrievalChannel() == null ? "HYBRID" : hit.retrievalChannel()
        )).toList());
        return new AssistantRetrievalResult(
                reranked,
                searchResult.retrievalMs(),
                searchResult.embeddingMs(),
                searchResult.vectorSearchUsed(),
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
