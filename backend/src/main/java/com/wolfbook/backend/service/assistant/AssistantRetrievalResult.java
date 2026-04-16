package com.wolfbook.backend.service.assistant;

import java.util.List;
import java.util.Map;

record AssistantRetrievalResult(
        List<AssistantKnowledgeService.KnowledgeHit> hits,
        long retrievalMs,
        long embeddingMs,
        boolean vectorSearchUsed,
        Map<String, Object> meta
) {
}
