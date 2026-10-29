package com.wolfbook.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantAiConfigurationTests {

    @Test
    void skipsTheIndexWhenEmbeddingsAreWiderThanPgvectorCanIndex() {
        assertThat(AssistantAiConfiguration.indexTypeFor(2048)).isEqualTo(PgVectorStore.PgIndexType.NONE);
        assertThat(AssistantAiConfiguration.indexTypeFor(2000)).isEqualTo(PgVectorStore.PgIndexType.HNSW);
        assertThat(AssistantAiConfiguration.indexTypeFor(1024)).isEqualTo(PgVectorStore.PgIndexType.HNSW);
    }
}
