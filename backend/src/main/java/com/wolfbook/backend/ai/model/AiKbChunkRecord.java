package com.wolfbook.backend.ai.model;

public record AiKbChunkRecord(
        String chunkUid,
        String documentUid,
        String domain,
        String title,
        String sectionPath,
        String subject,
        String content,
        int ordinal,
        double score,
        String source
) {
}
