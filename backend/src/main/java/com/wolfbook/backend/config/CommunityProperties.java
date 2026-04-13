package com.wolfbook.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wolfbook.community")
public class CommunityProperties {

    private int feedHotCandidateLimit = 80;
    private int relatedPostLimit = 4;
    private Hot hot = new Hot();
    private Meilisearch meilisearch = new Meilisearch();

    public int getFeedHotCandidateLimit() {
        return feedHotCandidateLimit;
    }

    public void setFeedHotCandidateLimit(int feedHotCandidateLimit) {
        this.feedHotCandidateLimit = feedHotCandidateLimit;
    }

    public int getRelatedPostLimit() {
        return relatedPostLimit;
    }

    public void setRelatedPostLimit(int relatedPostLimit) {
        this.relatedPostLimit = relatedPostLimit;
    }

    public Hot getHot() {
        return hot;
    }

    public void setHot(Hot hot) {
        this.hot = hot;
    }

    public Meilisearch getMeilisearch() {
        return meilisearch;
    }

    public void setMeilisearch(Meilisearch meilisearch) {
        this.meilisearch = meilisearch;
    }

    public static class Hot {
        private double viewWeight = 0.2d;
        private double likeWeight = 1.0d;
        private double commentWeight = 2.0d;
        private double favoriteWeight = 2.5d;
        private double featuredBonus = 18.0d;
        private double pinnedBonus = 28.0d;
        private double qualityWeight = 0.1d;
        private double ageDecayPerHour = 0.08d;

        public double getViewWeight() {
            return viewWeight;
        }

        public void setViewWeight(double viewWeight) {
            this.viewWeight = viewWeight;
        }

        public double getLikeWeight() {
            return likeWeight;
        }

        public void setLikeWeight(double likeWeight) {
            this.likeWeight = likeWeight;
        }

        public double getCommentWeight() {
            return commentWeight;
        }

        public void setCommentWeight(double commentWeight) {
            this.commentWeight = commentWeight;
        }

        public double getFavoriteWeight() {
            return favoriteWeight;
        }

        public void setFavoriteWeight(double favoriteWeight) {
            this.favoriteWeight = favoriteWeight;
        }

        public double getFeaturedBonus() {
            return featuredBonus;
        }

        public void setFeaturedBonus(double featuredBonus) {
            this.featuredBonus = featuredBonus;
        }

        public double getPinnedBonus() {
            return pinnedBonus;
        }

        public void setPinnedBonus(double pinnedBonus) {
            this.pinnedBonus = pinnedBonus;
        }

        public double getQualityWeight() {
            return qualityWeight;
        }

        public void setQualityWeight(double qualityWeight) {
            this.qualityWeight = qualityWeight;
        }

        public double getAgeDecayPerHour() {
            return ageDecayPerHour;
        }

        public void setAgeDecayPerHour(double ageDecayPerHour) {
            this.ageDecayPerHour = ageDecayPerHour;
        }
    }

    public static class Meilisearch {
        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:7700";
        private String apiKey = "";
        private String indexName = "community_posts";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }
    }
}
