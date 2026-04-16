package com.wolfbook.backend.service.assistant;

import java.util.List;

record AssistantQueryPlan(
        String originalQuery,
        String effectiveQuery,
        AssistantSubject subject,
        AssistantQueryType queryType,
        boolean strictSubject,
        boolean boardCatalogQuery,
        boolean timeSensitive,
        List<String> tokens
) {
    boolean hasSubject() {
        return subject != null;
    }

    String subjectKey() {
        return subject == null ? null : subject.sourceKey();
    }

    String cacheToken() {
        String subjectPart = subject == null ? "NO_SUBJECT" : subject.sourceKey();
        return String.join(":",
                strictSubject ? "STRICT" : "BROAD",
                queryType == null ? "OPEN_QA" : queryType.name(),
                boardCatalogQuery ? "BOARD_CATALOG" : "DEFAULT",
                timeSensitive ? "TIME" : "STATIC",
                subjectPart
        );
    }
}
