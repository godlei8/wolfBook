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
        return PgVectorStore.builder(assistantPgVectorJdbcTemplate, embeddingModel)
                .schemaName(properties.getPgVector().getSchemaName())
                .vectorTableName(properties.getPgVector().getTableName())
                .dimensions(properties.getVolcengine().getEmbeddingDimensions())
                .initializeSchema(true)
                .build();
    }
}
