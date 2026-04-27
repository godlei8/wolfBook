package com.wolfbook.backend.ai.model;

import java.util.List;

public record AiQueryPlan(
        String originalQuestion,
        String searchQuery,
        String subject,
        String intent,
        boolean outOfScope,
        boolean webPreferred,
        List<String> warnings
) {
}
