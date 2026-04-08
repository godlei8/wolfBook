package com.wolfbook.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wolfbook.assistant")
public class AssistantProperties {

    private boolean enabled = true;
    private int historyWindow = 12;
    private int maxSessionsPerUser = 20;
    private int maxSuggestions = 3;
    private int topK = 4;
    private double similarityThreshold = 0.45;
    private double temperature = 0.35;
    private boolean webSearchEnabled = true;
    private int webSearchTimeoutSeconds = 12;
    private final MiniMax miniMax = new MiniMax();
    private final PgVector pgVector = new PgVector();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getHistoryWindow() {
        return historyWindow;
    }

    public void setHistoryWindow(int historyWindow) {
        this.historyWindow = historyWindow;
    }

    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }

    public void setMaxSessionsPerUser(int maxSessionsPerUser) {
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    public int getMaxSuggestions() {
        return maxSuggestions;
    }

    public void setMaxSuggestions(int maxSuggestions) {
        this.maxSuggestions = maxSuggestions;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isWebSearchEnabled() {
        return webSearchEnabled;
    }

    public void setWebSearchEnabled(boolean webSearchEnabled) {
        this.webSearchEnabled = webSearchEnabled;
    }

    public int getWebSearchTimeoutSeconds() {
        return webSearchTimeoutSeconds;
    }

    public void setWebSearchTimeoutSeconds(int webSearchTimeoutSeconds) {
        this.webSearchTimeoutSeconds = webSearchTimeoutSeconds;
    }

    public MiniMax getMiniMax() {
        return miniMax;
    }

    public PgVector getPgVector() {
        return pgVector;
    }

    public static class MiniMax {
        private String apiKey;
        private String baseUrl = "https://api.minimax.io";
        private String chatModel = "MiniMax-M2.7";
        private String embeddingModel = "text-embedding-3-small";

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

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class PgVector {
        private boolean enabled;
        private String url;
        private String username;
        private String password;
        private String tableName = "assistant_vector_store";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }
}
