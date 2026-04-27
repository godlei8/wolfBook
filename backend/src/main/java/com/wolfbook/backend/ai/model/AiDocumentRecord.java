package com.wolfbook.backend.ai.model;

import java.time.OffsetDateTime;

public record AiDocumentRecord(
        Long id,
        String documentUid,
        String domain,
        String title,
        String sourceType,
        String sourceRef,
        String reviewStatus,
        String parseStatus,
        String content,
        String contentHash,
        String summary,
        int chunkCount,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
