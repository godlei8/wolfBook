package com.wolfbook.backend.ai.model;

import java.util.Set;

public record AiRetrievalContext(String confirmedEntity, Set<String> recentEntities) {

    public static AiRetrievalContext empty() {
        return new AiRetrievalContext(null, Set.of());
    }
}
