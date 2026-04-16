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

    private static final int INITIAL_RECALL_CANDIDATES = 50;
    private static final int MIN_EVIDENCE_WINDOW = 5;
    private static final int MAX_EVIDENCE_WINDOW = 10;
    private static final double MIN_EVIDENCE_SCORE = 25.0d;

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
                Math.max(INITIAL_RECALL_CANDIDATES, Math.max(selectedLimit * 5, selectedLimit + 10)),
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
        AssistantReranker.RerankResult rerankResult = assistantReranker.rerank(mergedCandidates, queryPlan, selectedLimit);
        List<AssistantKnowledgeService.KnowledgeHit> reranked = rerankResult.hits();
        double topScore = reranked.isEmpty() ? 0 : reranked.getFirst().score();
        boolean enoughEvidence = !reranked.isEmpty() && topScore >= MIN_EVIDENCE_SCORE;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("subjectKey", queryPlan == null ? null : queryPlan.subjectKey());
        meta.put("queryType", queryPlan == null || queryPlan.queryType() == null ? AssistantQueryType.OPEN_QA.name() : queryPlan.queryType().name());
        meta.put("strictSubject", queryPlan != null && queryPlan.strictSubject());
        meta.put("boardCatalogQuery", queryPlan != null && queryPlan.boardCatalogQuery());
        meta.put("initialRecallTarget", INITIAL_RECALL_CANDIDATES);
        meta.put("vectorSearchUsed", firstRound.vectorSearchUsed() || (secondRound != null && secondRound.vectorSearchUsed()));
        meta.put("candidateCount", mergedCandidates.size());
        meta.put("multiRound", secondRound != null);
        meta.put("firstRoundCount", firstRound.hits().size());
        meta.put("secondRoundCount", secondRound == null ? 0 : secondRound.hits().size());
        meta.put("evidenceWindowSize", selectedLimit);
        meta.put("minimumEvidenceScore", MIN_EVIDENCE_SCORE);
        meta.put("topEvidenceScore", topScore);
        meta.put("minimumEvidenceMet", enoughEvidence);
        meta.put("rankingMetrics", rerankResult.metrics());
        if (firstRound.diagnostics() != null && !firstRound.diagnostics().isEmpty()) {
            meta.put("retrievalDiagnostics", firstRound.diagnostics());
        }
        if (secondRound != null && secondRound.diagnostics() != null && !secondRound.diagnostics().isEmpty()) {
            meta.put("secondRoundDiagnostics", secondRound.diagnostics());
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
                firstRound.retrievalMs() + (secondRound == null ? 0 : secondRound.retrievalMs()),
                firstRound.embeddingMs() + (secondRound == null ? 0 : secondRound.embeddingMs()),
                firstRound.vectorSearchUsed() || (secondRound != null && secondRound.vectorSearchUsed()),
                meta,
                enoughEvidence
        );
    }

    private int selectedLimit(AssistantQueryPlan queryPlan, int configuredTopK) {
        int safeTopK = Math.min(Math.max(configuredTopK, MIN_EVIDENCE_WINDOW), MAX_EVIDENCE_WINDOW);
        if (queryPlan != null && queryPlan.strictSubject()) {
            return Math.min(Math.max(safeTopK, 8), MAX_EVIDENCE_WINDOW);
        }
        if (queryPlan != null && queryPlan.boardCatalogQuery()) {
            return Math.min(Math.max(safeTopK, 7), MAX_EVIDENCE_WINDOW);
        }
        return safeTopK;
    }
}
