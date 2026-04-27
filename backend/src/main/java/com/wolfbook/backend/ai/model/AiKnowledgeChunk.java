package com.wolfbook.backend.ai.model;

public record AiKnowledgeChunk(
        String chunkUid,
        String domain,
        String title,
        String sectionPath,
        String subject,
        String content,
        int ordinal
) {
}
