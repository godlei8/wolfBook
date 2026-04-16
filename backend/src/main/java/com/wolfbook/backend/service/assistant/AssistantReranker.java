package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 助手检索重排器。
 *
 * <p>把关键词和向量命中的同一 chunk 去重，并按主体命中、来源类型和分数重新排序。
 * 明确主体问题会被严格压到同一主体，防止相邻角色靠语义相似度混入上下文。</p>
 */
@Service
class AssistantReranker {

    List<AssistantKnowledgeService.KnowledgeHit> rerank(
            List<AssistantKnowledgeService.KnowledgeHit> hits,
            AssistantQueryPlan queryPlan,
            int topK
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, AssistantKnowledgeService.KnowledgeHit> deduplicated = new LinkedHashMap<>();
        hits.stream()
                .filter(hit -> matchesPlan(hit, queryPlan))
                .sorted((left, right) -> Double.compare(adjustedScore(right, queryPlan), adjustedScore(left, queryPlan)))
                .forEach(hit -> deduplicated.putIfAbsent(hitKey(hit), hit));
        return new ArrayList<>(deduplicated.values()).stream()
                .limit(Math.max(topK, 1))
                .toList();
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
}
