package com.wolfbook.backend.config;

import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxEmbeddingModel;
import org.springframework.ai.minimax.MiniMaxEmbeddingOptions;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class AssistantAiConfiguration {

    @Bean(name = "appJdbcTemplate")
    @Primary
    public JdbcTemplate appJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${wolfbook.assistant.minimax.api-key:}')")
    public MiniMaxApi miniMaxApi(AssistantProperties properties, ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new MiniMaxApi(properties.getMiniMax().getBaseUrl(), properties.getMiniMax().getApiKey(), restClientBuilder);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${wolfbook.assistant.minimax.api-key:}')")
    public MiniMaxChatModel miniMaxChatModel(MiniMaxApi miniMaxApi, AssistantProperties properties) {
        MiniMaxChatOptions options = MiniMaxChatOptions.builder()
                .model(properties.getMiniMax().getChatModel())
                .temperature(properties.getTemperature())
                .build();
        return new MiniMaxChatModel(miniMaxApi, options);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${wolfbook.assistant.minimax.api-key:}')")
    public MiniMaxEmbeddingModel miniMaxEmbeddingModel(MiniMaxApi miniMaxApi, AssistantProperties properties) {
        MiniMaxEmbeddingOptions options = MiniMaxEmbeddingOptions.builder()
                .model(properties.getMiniMax().getEmbeddingModel())
                .build();
        return new MiniMaxEmbeddingModel(miniMaxApi, MetadataMode.NONE, options);
    }

    @Bean(name = "assistantPgVectorJdbcTemplate")
    @ConditionalOnProperty(prefix = "wolfbook.assistant.pgvector", name = "enabled", havingValue = "true")
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
    @ConditionalOnProperty(prefix = "wolfbook.assistant.pgvector", name = "enabled", havingValue = "true")
    public VectorStore assistantVectorStore(@Qualifier("assistantPgVectorJdbcTemplate") JdbcTemplate assistantPgVectorJdbcTemplate,
                                            MiniMaxEmbeddingModel miniMaxEmbeddingModel,
                                            AssistantProperties properties) {
        return PgVectorStore.builder(assistantPgVectorJdbcTemplate, miniMaxEmbeddingModel)
                .vectorTableName(properties.getPgVector().getTableName())
                .initializeSchema(true)
                .build();
    }
}
