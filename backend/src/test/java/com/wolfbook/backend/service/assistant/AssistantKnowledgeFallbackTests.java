package com.wolfbook.backend.service.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 没有向量检索时的关键词切分：中文问题要切成相邻两字，才能和资料对上。 */
class AssistantKnowledgeFallbackTests {

    @Test
    void chineseQuestionShouldSplitIntoCharacterPairs() {
        var tokens = AssistantKnowledgeService.fallbackTokens("夜镇的银钟是什么来历");
        assertTrue(tokens.contains("银钟"));
        assertTrue(tokens.contains("夜镇"));
        assertFalse(tokens.contains("什么"), "常见问句词不算关键词");
    }

    @Test
    void latinWordsShouldStayWhole() {
        var tokens = AssistantKnowledgeService.fallbackTokens("deepseek 12人 板子");
        assertTrue(tokens.contains("deepseek"));
        assertTrue(tokens.contains("板子"));
    }

    @Test
    void chunkIdsShouldBeStableUuidsForPgVector() {
        String id = AssistantKnowledgeService.buildChunkId("BOARD:1", 0);
        assertTrue(java.util.UUID.fromString(id).toString().equals(id), "pgvector 的 id 列是 UUID");
        assertTrue(id.equals(AssistantKnowledgeService.buildChunkId("BOARD:1", 0)), "同一段每次算出同一个 id，重建时才能删掉旧的");
        assertFalse(id.equals(AssistantKnowledgeService.buildChunkId("BOARD:1", 1)));
    }
}
