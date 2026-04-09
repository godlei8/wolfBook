package com.wolfbook.backend.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxEmbeddingOptions;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
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
import com.wolfbook.backend.support.SafeMiniMaxEmbeddingModel;

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
        return new MiniMaxApi(resolveMiniMaxBaseUrl(properties.getMiniMax().getBaseUrl()), properties.getMiniMax().getApiKey(), restClientBuilder);
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
    public EmbeddingModel assistantEmbeddingModel(AssistantProperties properties, ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        if (isOllamaEmbeddingProvider(properties)) {
            OllamaApi ollamaApi = OllamaApi.builder()
                    .baseUrl(resolveOllamaBaseUrl(properties.getOllama().getBaseUrl()))
                    .restClientBuilder(restClientBuilder)
                    .build();
            OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                    .model(resolveOllamaEmbeddingModel(properties.getOllama().getEmbeddingModel()))
                    .build();
            ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                    .pullModelStrategy(PullModelStrategy.NEVER)
                    .build();
            return OllamaEmbeddingModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(options)
                    .observationRegistry(ObservationRegistry.NOOP)
                    .modelManagementOptions(modelManagementOptions)
                    .build();
        }
        if (isMiniMaxEmbeddingProvider(properties)) {
            if (!StringUtils.hasText(properties.getMiniMax().getApiKey())) {
                throw new IllegalStateException("MiniMax embedding provider requires wolfbook.assistant.minimax.api-key");
            }
            MiniMaxEmbeddingOptions options = MiniMaxEmbeddingOptions.builder()
                    .model(resolveEmbeddingModel(properties.getMiniMax().getEmbeddingModel()))
                    .build();
            return new SafeMiniMaxEmbeddingModel(
                    resolveMiniMaxBaseUrl(properties.getMiniMax().getBaseUrl()),
                    properties.getMiniMax().getApiKey(),
                    MetadataMode.NONE,
                    options,
                    restClientBuilder
            );
        }
        throw new IllegalStateException("Unsupported assistant embedding provider: " + properties.getEmbeddingProvider());
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
                                            EmbeddingModel assistantEmbeddingModel,
                                            AssistantProperties properties) {
        return PgVectorStore.builder(assistantPgVectorJdbcTemplate, assistantEmbeddingModel)
                .vectorTableName(properties.getPgVector().getTableName())
                .initializeSchema(true)
                .build();
    }

    private boolean isMiniMaxEmbeddingProvider(AssistantProperties properties) {
        return "minimax".equalsIgnoreCase(properties.getEmbeddingProvider());
    }

    private boolean isOllamaEmbeddingProvider(AssistantProperties properties) {
        return !StringUtils.hasText(properties.getEmbeddingProvider())
                || "ollama".equalsIgnoreCase(properties.getEmbeddingProvider());
    }

    private String resolveMiniMaxBaseUrl(String configuredBaseUrl) {
        if (!StringUtils.hasText(configuredBaseUrl)) {
            return "https://api.minimax.chat";
        }
        String normalized = configuredBaseUrl.trim();
        if ("https://api.minimax.io".equalsIgnoreCase(normalized)) {
            return "https://api.minimax.chat";
        }
        return normalized;
    }

    private String resolveEmbeddingModel(String configuredModel) {
        if (!StringUtils.hasText(configuredModel)) {
            return MiniMaxApi.DEFAULT_EMBEDDING_MODEL;
        }
        String normalized = configuredModel.trim();
        if ("text-embedding-3-small".equalsIgnoreCase(normalized)) {
            return MiniMaxApi.DEFAULT_EMBEDDING_MODEL;
        }
        return normalized;
    }

    private String resolveOllamaBaseUrl(String configuredBaseUrl) {
        if (!StringUtils.hasText(configuredBaseUrl)) {
            return "http://127.0.0.1:11434";
        }
        return configuredBaseUrl.trim();
    }

    private String resolveOllamaEmbeddingModel(String configuredModel) {
        if (!StringUtils.hasText(configuredModel)) {
            return AssistantProperties.OLLAMA_DEFAULT_EMBEDDING_MODEL;
        }
        return configuredModel.trim();
    }
}
