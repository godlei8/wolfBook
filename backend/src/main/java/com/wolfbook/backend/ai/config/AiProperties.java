package com.wolfbook.backend.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "wolfbook.ai")
public class AiProperties {

    private boolean enabled = true;
    private boolean knowledgeBaseEnabled = true;
    private final Postgres postgres = new Postgres();
    private final Chat chat = new Chat();
    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final WebSearch webSearch = new WebSearch();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isKnowledgeBaseEnabled() {
        return knowledgeBaseEnabled;
    }

    public void setKnowledgeBaseEnabled(boolean knowledgeBaseEnabled) {
        this.knowledgeBaseEnabled = knowledgeBaseEnabled;
    }

    public Postgres getPostgres() {
        return postgres;
    }

    public Chat getChat() {
        return chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public WebSearch getWebSearch() {
        return webSearch;
    }

    public static class Postgres {
        private boolean enabled = false;
        private boolean initialize = true;
        private String url = "";
        private String username = "";
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isInitialize() {
            return initialize;
        }

        public void setInitialize(boolean initialize) {
            this.initialize = initialize;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Chat {
        public static final String DEFAULT_MODEL = "MiniMax-M2.7";

        private String apiKey = "";
        private String baseUrl = "https://api.minimax.chat";
        private String model = DEFAULT_MODEL;
        private Duration timeout = Duration.ofSeconds(45);
        private int maxTokens = 1600;
        private double temperature = 0.2;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class Embedding {
        private String apiKey = "";
        private String baseUrl = "https://api.minimax.chat";
        private String model = "embo-01";
        private int dimensions = 1536;
        private Duration timeout = Duration.ofSeconds(30);

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Retrieval {
        private int topK = 8;
        private double minScore = 0.12;
        private int maxEvidenceChars = 6000;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public int getMaxEvidenceChars() {
            return maxEvidenceChars;
        }

        public void setMaxEvidenceChars(int maxEvidenceChars) {
            this.maxEvidenceChars = maxEvidenceChars;
        }
    }

    public static class WebSearch {
        private boolean enabled = true;
        private String mcpApiKey = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMcpApiKey() {
            return mcpApiKey;
        }

        public void setMcpApiKey(String mcpApiKey) {
            this.mcpApiKey = mcpApiKey;
        }
    }
}
