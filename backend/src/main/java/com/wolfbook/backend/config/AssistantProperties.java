package com.wolfbook.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "wolfbook.assistant")
public class AssistantProperties {

    public static final String DEEPSEEK_PLATFORM = "DEEPSEEK";
    public static final String DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-flash";
    public static final String DEEPSEEK_DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String VOLCENGINE_DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String VOLCENGINE_DEFAULT_EMBEDDING_MODEL = "doubao-embedding-large-text-250515";
    public static final String VOLCENGINE_DEFAULT_SEARCH_MODEL = "doubao-seed-1-6-thinking-250715";
    public static final int VOLCENGINE_DEFAULT_EMBEDDING_DIMENSIONS = 2048;

    private boolean enabled = true;
    private int historyWindow = 12;
    private int maxSessionsPerUser = 20;
    private int maxSuggestions = 3;
    private int topK = 4;
    private double similarityThreshold = 0.45;
    private double temperature = 0.35;
    private boolean webSearchEnabled = true;
    private final DeepSeek deepSeek = new DeepSeek();
    private final Volcengine volcengine = new Volcengine();
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

    public DeepSeek getDeepSeek() {
        return deepSeek;
    }

    public Volcengine getVolcengine() {
        return volcengine;
    }

    public PgVector getPgVector() {
        return pgVector;
    }

    public static class DeepSeek {
        private String baseUrl = DEEPSEEK_DEFAULT_BASE_URL;
        private String model = DEEPSEEK_DEFAULT_MODEL;
        private String defaultApiKey = "";

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

        public String getDefaultApiKey() {
            return defaultApiKey;
        }

        public void setDefaultApiKey(String defaultApiKey) {
            this.defaultApiKey = defaultApiKey;
        }
    }

    public static class Volcengine {
        private String baseUrl = VOLCENGINE_DEFAULT_BASE_URL;
        private String embeddingModel = VOLCENGINE_DEFAULT_EMBEDDING_MODEL;
        private int embeddingDimensions = VOLCENGINE_DEFAULT_EMBEDDING_DIMENSIONS;
        private String embeddingApiKey = "";
        private String searchModel = VOLCENGINE_DEFAULT_SEARCH_MODEL;
        private String searchApiKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public String getEmbeddingApiKey() {
            return embeddingApiKey;
        }

        public void setEmbeddingApiKey(String embeddingApiKey) {
            this.embeddingApiKey = embeddingApiKey;
        }

        public String getSearchModel() {
            return searchModel;
        }

        public void setSearchModel(String searchModel) {
            this.searchModel = searchModel;
        }

        public String getSearchApiKey() {
            return searchApiKey;
        }

        public void setSearchApiKey(String searchApiKey) {
            this.searchApiKey = searchApiKey;
        }

        public boolean hasEmbeddingConfig() {
            return StringUtils.hasText(embeddingApiKey) && StringUtils.hasText(embeddingModel);
        }

        public boolean hasSearchConfig() {
            return StringUtils.hasText(searchApiKey) && StringUtils.hasText(searchModel);
        }
    }

    public static class PgVector {
        private boolean enabled = false;
        private String url = "";
        private String username = "";
        private String password = "";
        private String tableName = "assistant_vector_store";
        private String schemaName = "public";

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

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }
    }
}
