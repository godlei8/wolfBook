package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * AI 助手检索重排器。
 *
 * <p>把关键词和向量命中的同一 chunk 去重，并按主体命中、来源类型和分数重新排序。
 * 明确主体问题会被严格压到同一主体，防止相邻角色靠语义相似度混入上下文。</p>
 */
@Service
class AssistantReranker {

    RerankResult rerank(
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            AssistantQueryPlan queryPlan,
            int topK
    ) {
        if (hits == null || hits.isEmpty()) {
            return new RerankResult(List.of(), Map.of());
        }
        List<AssistantKnowledgeService.KnowledgeHit> filtered = hits.stream()
                .filter(hit -> matchesPlan(hit, queryPlan))
                .sorted((left, right) -> Double.compare(adjustedScore(right, queryPlan), adjustedScore(left, queryPlan)))
                .toList();

        Map<String, AssistantKnowledgeService.KnowledgeHit> deduplicated = new LinkedHashMap<>();
        filtered.forEach(hit -> deduplicated.putIfAbsent(hitKey(hit), hit));
        List<AssistantKnowledgeService.KnowledgeHit> before = new ArrayList<>(deduplicated.values());
        List<AssistantKnowledgeService.KnowledgeHit> reranked = applyDiversity(before, queryPlan, topK);
        Map<String, Object> metrics = buildRankingMetrics(before, reranked, queryPlan, topK);
        return new RerankResult(reranked, metrics);
    }

    private List<AssistantKnowledgeService.KnowledgeHit> applyDiversity(
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            AssistantQueryPlan queryPlan,
            int topK
    ) {
        int limit = Math.max(topK, 1);
        List<AssistantKnowledgeService.KnowledgeHit> sorted = hits.stream()
                .sorted(Comparator.comparingDouble((AssistantKnowledgeService.KnowledgeHit hit) -> adjustedScore(hit, queryPlan)).reversed())
                .toList();

        List<AssistantKnowledgeService.KnowledgeHit> selected = new ArrayList<>();
        Set<String> selectedChunkKeys = new HashSet<>();
        Set<String> selectedDocuments = new HashSet<>();
        for (AssistantKnowledgeService.KnowledgeHit hit : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            String chunkKey = hitKey(hit);
            if (selectedChunkKeys.contains(chunkKey)) {
                continue;
            }
            String documentKey = documentKey(hit);
            if (selectedDocuments.contains(documentKey)) {
                continue;
            }
            selected.add(hit);
            selectedChunkKeys.add(chunkKey);
            selectedDocuments.add(documentKey);
        }
        for (AssistantKnowledgeService.KnowledgeHit hit : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            String chunkKey = hitKey(hit);
            if (selectedChunkKeys.contains(chunkKey)) {
                continue;
            }
            selected.add(hit);
            selectedChunkKeys.add(chunkKey);
        }
        return selected;
    }

    private Map<String, Object> buildRankingMetrics(
            List<AssistantKnowledgeService.KnowledgeHit> before,
            List<AssistantKnowledgeService.KnowledgeHit> after,
            AssistantQueryPlan queryPlan,
            int topK
    ) {
        int k = Math.max(topK, 1);
        Map<String, Object> beforeMetrics = Map.of(
                "ndcgAtK", round4(ndcgAtK(before, queryPlan, k)),
                "mrr", round4(mrr(before, queryPlan)),
                "hitAtK", round4(hitAtK(before, queryPlan, k))
        );
        Map<String, Object> afterMetrics = Map.of(
                "ndcgAtK", round4(ndcgAtK(after, queryPlan, k)),
                "mrr", round4(mrr(after, queryPlan)),
                "hitAtK", round4(hitAtK(after, queryPlan, k))
        );
        return Map.of(
                "evaluationK", k,
                "before", beforeMetrics,
                "after", afterMetrics
        );
    }

    private boolean matchesPlan(AssistantKnowledgeService.KnowledgeHit hit, AssistantQueryPlan queryPlan) {
        if (hit == null || queryPlan == null || !queryPlan.strictSubject() || queryPlan.subjectKey() == null) {
            return hit != null;
        }
        return queryPlan.subjectKey().equals(hit.subjectKey());
    }

    private double adjustedScore(AssistantKnowledgeService.KnowledgeHit hit, AssistantQueryPlan queryPlan) {
        double score = hit.score();
        if (queryPlan != null && queryPlan.subjectKey() != null && queryPlan.subjectKey().equals(hit.subjectKey())) {
            score += 200;
        }
        if (AssistantConstants.SOURCE_STRUCTURED.equals(hit.sourceType())) {
            score += 30;
        }
        if ("SKILL".equals(hit.chunkKind())) {
            score += 10;
        }
        return score;
    }

    private String hitKey(AssistantKnowledgeService.KnowledgeHit hit) {
        if (hit.chunkUid() != null && !hit.chunkUid().isBlank()) {
            return hit.chunkUid();
        }
        String sourceKey = hit.document() == null ? "" : hit.document().getSourceKey();
        return (sourceKey + ":" + hit.title() + ":" + hit.contextText())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\s]+", "");
    }

    private String documentKey(AssistantKnowledgeService.KnowledgeHit hit) {
        if (hit == null || hit.document() == null) {
            return "unknown";
        }
        if (hit.document().getId() != null) {
            return "doc:" + hit.document().getId();
        }
        String sourceKey = hit.document().getSourceKey();
        if (sourceKey != null && !sourceKey.isBlank()) {
            return "source:" + sourceKey;
        }
        return "title:" + (hit.title() == null ? "" : hit.title());
    }

    private double ndcgAtK(List<AssistantKnowledgeService.KnowledgeHit> hits, AssistantQueryPlan queryPlan, int k) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        double dcg = 0;
        int limit = Math.min(Math.max(k, 1), hits.size());
        for (int i = 0; i < limit; i++) {
            int grade = relevanceGrade(hits.get(i), queryPlan);
            if (grade <= 0) {
                continue;
            }
            dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        List<Integer> idealGrades = hits.stream()
                .map(hit -> relevanceGrade(hit, queryPlan))
                .sorted(Comparator.reverseOrder())
                .toList();
        double idcg = 0;
        for (int i = 0; i < Math.min(limit, idealGrades.size()); i++) {
            int grade = idealGrades.get(i);
            if (grade <= 0) {
                continue;
            }
            idcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg <= 0 ? 0 : dcg / idcg;
    }

    private double mrr(List<AssistantKnowledgeService.KnowledgeHit> hits, AssistantQueryPlan queryPlan) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < hits.size(); i++) {
            if (relevanceGrade(hits.get(i), queryPlan) > 0) {
                return 1.0d / (i + 1);
            }
        }
        return 0;
    }

    private double hitAtK(List<AssistantKnowledgeService.KnowledgeHit> hits, AssistantQueryPlan queryPlan, int k) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        int limit = Math.min(Math.max(k, 1), hits.size());
        for (int i = 0; i < limit; i++) {
            if (relevanceGrade(hits.get(i), queryPlan) > 0) {
                return 1;
            }
        }
        return 0;
    }

    private int relevanceGrade(AssistantKnowledgeService.KnowledgeHit hit, AssistantQueryPlan queryPlan) {
        if (hit == null) {
            return 0;
        }
        int grade = 0;
        if (queryPlan != null && queryPlan.subjectKey() != null && queryPlan.subjectKey().equals(hit.subjectKey())) {
            grade += 3;
        }
        if (AssistantConstants.SOURCE_STRUCTURED.equals(hit.sourceType())) {
            grade += 1;
        }
        if ("BASE".equals(hit.chunkKind()) || "SKILL".equals(hit.chunkKind()) || "GLOSSARY".equals(hit.chunkKind())) {
            grade += 1;
        }
        return grade;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    record RerankResult(
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            Map<String, Object> metrics
    ) {
    }
}
