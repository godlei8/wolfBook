package com.wolfbook.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class AssistantAiConfiguration {

    private static final int MAX_INDEXED_DIMENSIONS = 2000;

    @Bean(name = "appJdbcTemplate")
    @Primary
    public JdbcTemplate appJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "assistantPgVectorJdbcTemplate")
    @ConditionalOnProperty(prefix = "wolfbook.assistant.pg-vector", name = "enabled", havingValue = "true")
    public JdbcTemplate assistantPgVectorJdbcTemplate(AssistantProperties properties) {
        if (!StringUtils.hasText(properties.getPgVector().getUrl())) {
            throw new IllegalStateException("assistant pgvector datasource url must be configured");
        }
        DataSource dataSource = DataSourceBuilder.create()
                .url(properties.getPgVector().getUrl())
                .username(properties.getPgVector().getUsername())
                .password(properties.getPgVector().getPassword())
                .driverClassName("org.postgresql.Driver")
                .build();
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wolfbook.assistant.pg-vector", name = "enabled", havingValue = "true")
    public VectorStore assistantVectorStore(
            @Qualifier("assistantPgVectorJdbcTemplate") JdbcTemplate assistantPgVectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            AssistantProperties properties
    ) {
        int dimensions = properties.getVolcengine().getEmbeddingDimensions();
        return PgVectorStore.builder(assistantPgVectorJdbcTemplate, embeddingModel)
                .schemaName(properties.getPgVector().getSchemaName())
                .vectorTableName(properties.getPgVector().getTableName())
                .dimensions(dimensions)
                .indexType(indexTypeFor(dimensions))
                .initializeSchema(true)
                .build();
    }

    // pgvector 的 HNSW / IVFFlat 索引最多 2000 维，doubao-embedding-large 是 2048 维：超了就不建索引，按精确扫描检索（知识库条目少，够用）
    static PgVectorStore.PgIndexType indexTypeFor(int dimensions) {
        return dimensions > MAX_INDEXED_DIMENSIONS ? PgVectorStore.PgIndexType.NONE : PgVectorStore.PgIndexType.HNSW;
    }
}
