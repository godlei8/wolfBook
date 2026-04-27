package com.wolfbook.backend.ai.model;

import java.time.OffsetDateTime;

public record AiQaLogRecord(
        String traceId,
        String sessionId,
        String question,
        String answerType,
        String subject,
        String retrievalMode,
        int hitCount,
        boolean webUsed,
        String failureReason,
        Long latencyMs,
        OffsetDateTime createdAt
) {
}
