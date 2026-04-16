package com.wolfbook.backend.service.assistant;

import java.util.List;

record AssistantSubject(
        String type,
        String sourceKey,
        String sourceId,
        String name,
        List<String> terms,
        int score
) {
    boolean isRole() {
        return "ROLE".equals(type);
    }

    boolean isBoard() {
        return "BOARD".equals(type);
    }

    boolean isTerm() {
        return "TERM".equals(type);
    }
}
