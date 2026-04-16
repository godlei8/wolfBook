package com.wolfbook.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wolfbook.backend.config.CommunityProperties;
import com.wolfbook.backend.entity.PostEntity;
import com.wolfbook.backend.support.CommunityStatuses;
import com.wolfbook.backend.support.DomainConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CommunitySearchService {

    private static final String LEGACY_UNTITLED_POST = "Untitled post";
    private static final String DEFAULT_POST_TITLE = "未命名帖子";

    private final CommunityProperties properties;
    private final DomainConverter converter;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AtomicBoolean settingsInitialized = new AtomicBoolean(false);

    public CommunitySearchService(
            CommunityProperties properties,
            DomainConverter converter,
            ObjectMapper objectMapper,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider
    ) {
        this.properties = properties;
        this.converter = converter;
        this.objectMapper = objectMapper;
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        String baseUrl = normalizeBaseUrl(properties.getMeilisearch().getBaseUrl());
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
            if (StringUtils.hasText(properties.getMeilisearch().getApiKey())) {
                builder.defaultHeader("Authorization", "Bearer " + properties.getMeilisearch().getApiKey().trim());
            }
        }
        this.restClient = builder.build();
    }

    public SearchResult search(String query, String postType, Integer boardId, String sort, int page, int size) {
        if (!isEnabled() || !StringUtils.hasText(query)) {
            return SearchResult.empty();
        }
        try {
            ensureSettings();
            ObjectNode body = objectMapper.createObjectNode();
            body.put("q", query.trim());
            body.put("offset", Math.max(0, page - 1) * Math.max(1, size));
            body.put("limit", Math.max(1, size));
            String filter = buildFilter(postType, boardId, null);
            if (StringUtils.hasText(filter)) {
                body.put("filter", filter);
            }
            ArrayNode sorts = normalizeSort(sort);
            if (sorts != null) {
                body.set("sort", sorts);
            }
            JsonNode response = restClient.post()
                    .uri(indexPath("/search"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return readSearchResult(response);
        } catch (Exception exception) {
            return SearchResult.empty();
        }
    }

    public List<Integer> findRelatedPostIds(PostEntity post, int limit) {
        if (!isEnabled() || post == null || post.getId() == null || limit <= 0) {
            return List.of();
        }
        try {
            ensureSettings();
            ObjectNode body = objectMapper.createObjectNode();
            body.put("q", buildRelatedQuery(post));
            body.put("limit", limit);
            String filter = buildFilter(post.getPostType(), post.getBoardId(), post.getId());
            if (StringUtils.hasText(filter)) {
                body.put("filter", filter);
            }
            body.set("sort", normalizeSort("hot"));
            JsonNode response = restClient.post()
                    .uri(indexPath("/search"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return readSearchResult(response).ids();
        } catch (Exception exception) {
            return List.of();
        }
    }

    public void syncPost(PostEntity post) {
        if (!isEnabled() || post == null || post.getId() == null) {
            return;
        }
        try {
            ensureSettings();
            ArrayNode payload = objectMapper.createArrayNode();
            payload.add(toDocument(post));
            restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(indexPath("/documents")).queryParam("primaryKey", "id").build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // Keep MySQL path working even if Meilisearch is unavailable.
        }
    }

    public void deletePost(Integer postId) {
        if (!isEnabled() || postId == null) {
            return;
        }
        try {
            restClient.delete()
                    .uri(indexPath("/documents/" + postId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // Keep MySQL path working even if Meilisearch is unavailable.
        }
    }

    public boolean isEnabled() {
        return properties.getMeilisearch().isEnabled()
                && StringUtils.hasText(normalizeBaseUrl(properties.getMeilisearch().getBaseUrl()));
    }

    private void ensureSettings() {
        if (!settingsInitialized.compareAndSet(false, true)) {
            return;
        }
        ObjectNode settings = objectMapper.createObjectNode();
        settings.set("searchableAttributes", arrayOf("title", "summary", "content", "boardName", "tagList", "roleTags"));
        settings.set("filterableAttributes", arrayOf("postType", "boardId", "boardName", "tagList", "status", "featured", "pinned"));
        settings.set("sortableAttributes", arrayOf("hotScore", "qualityScore", "createTimeEpoch", "likeCount", "commentCount", "favoriteCount"));
        settings.set("rankingRules", arrayOf("words", "typo", "proximity", "attribute", "sort", "exactness"));
        ObjectNode typoTolerance = objectMapper.createObjectNode();
        typoTolerance.put("enabled", true);
        settings.set("typoTolerance", typoTolerance);
        restClient.patch()
                .uri(indexPath("/settings"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(settings)
                .retrieve()
                .toBodilessEntity();
    }

    private JsonNode toDocument(PostEntity post) {
        String displayTitle = displayTitle(post);
        String displaySummary = displaySummary(post);
        ObjectNode document = objectMapper.createObjectNode();
        document.put("id", post.getId());
        document.put("openid", safeText(post.getOpenid()));
        document.put("postType", safeText(post.getPostType()));
        document.put("title", displayTitle);
        document.put("summary", displaySummary);
        document.put("content", safeText(post.getContent()));
        if (post.getBoardId() != null) {
            document.put("boardId", post.getBoardId());
        } else {
            document.putNull("boardId");
        }
        document.put("boardName", safeText(post.getBoardName()));
        document.set("roleTags", objectMapper.valueToTree(converter.readStringList(post.getRoleTags())));
        document.set("tagList", objectMapper.valueToTree(converter.readStringList(post.getTagList())));
        document.put("status", safeText(post.getStatus()));
        document.put("featured", Boolean.TRUE.equals(post.getFeatured()));
        document.put("pinned", Boolean.TRUE.equals(post.getPinned()));
        document.put("qualityScore", safeInt(post.getQualityScore()));
        document.put("hotScore", safeDouble(post.getHotScore()));
        document.put("viewCount", safeInt(post.getViewCount()));
        document.put("likeCount", safeInt(post.getLikeCount()));
        document.put("commentCount", safeInt(post.getCommentCount()));
        document.put("favoriteCount", safeInt(post.getFavoriteCount()));
        document.put("createTime", post.getCreateTime() == null ? "" : post.getCreateTime().toString());
        document.put("createTimeEpoch", post.getCreateTime() == null
                ? 0L
                : post.getCreateTime().toInstant(ZoneOffset.ofHours(8)).toEpochMilli());
        return document;
    }

    private SearchResult readSearchResult(JsonNode response) {
        if (response == null || !response.has("hits")) {
            return SearchResult.empty();
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonNode hit : response.path("hits")) {
            if (hit.hasNonNull("id")) {
                ids.add(hit.get("id").asInt());
            }
        }
        long total = response.path("estimatedTotalHits").asLong(ids.size());
        if (response.has("totalHits")) {
            total = response.path("totalHits").asLong(total);
        }
        return new SearchResult(ids, total);
    }

    private String buildFilter(String postType, Integer boardId, Integer excludeId) {
        List<String> filters = new ArrayList<>();
        filters.add("status = " + quote(CommunityStatuses.POST_PUBLISHED));
        if (StringUtils.hasText(postType)) {
            filters.add("postType = " + quote(postType.trim()));
        }
        if (boardId != null && boardId > 0) {
            filters.add("boardId = " + boardId);
        }
        if (excludeId != null && excludeId > 0) {
            filters.add("id != " + excludeId);
        }
        return String.join(" AND ", filters);
    }

    private ArrayNode normalizeSort(String sort) {
        ArrayNode array = objectMapper.createArrayNode();
        String normalized = StringUtils.hasText(sort) ? sort.trim().toLowerCase() : "hot";
        if ("latest".equals(normalized) || "newest".equals(normalized)) {
            array.add("pinned:desc");
            array.add("createTimeEpoch:desc");
            return array;
        }
        array.add("pinned:desc");
        array.add("featured:desc");
        array.add("hotScore:desc");
        array.add("createTimeEpoch:desc");
        return array;
    }

    private ArrayNode arrayOf(String... values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private String buildRelatedQuery(PostEntity post) {
        if (post.getBoardId() != null && StringUtils.hasText(post.getBoardName())) {
            return post.getBoardName().trim();
        }
        List<String> tags = converter.readStringList(post.getTagList());
        if (!tags.isEmpty()) {
            return tags.getFirst();
        }
        return displayTitle(post);
    }

    private String indexPath(String suffix) {
        return "/indexes/" + properties.getMeilisearch().getIndexName().trim() + suffix;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String displayTitle(PostEntity post) {
        String title = safeText(post == null ? null : post.getTitle());
        if (!isPlaceholderTitle(title)) {
            return title;
        }
        String fallback = excerpt(post == null ? null : post.getSummary(), 40);
        if (fallback.isBlank()) {
            fallback = excerpt(post == null ? null : post.getContent(), 40);
        }
        return fallback.isBlank() ? DEFAULT_POST_TITLE : fallback;
    }

    private String displaySummary(PostEntity post) {
        String summary = excerpt(post == null ? null : post.getSummary(), 120);
        if (!summary.isBlank()) {
            return summary;
        }
        String fallback = excerpt(post == null ? null : post.getContent(), 120);
        if (!fallback.isBlank()) {
            return fallback;
        }
        return displayTitle(post);
    }

    private boolean isPlaceholderTitle(String title) {
        return !StringUtils.hasText(title) || LEGACY_UNTITLED_POST.equalsIgnoreCase(title.trim());
    }

    private String excerpt(String value, int maxLength) {
        String normalized = safeText(value).replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0d : value;
    }

    private String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    public record SearchResult(List<Integer> ids, long total) {
        static SearchResult empty() {
            return new SearchResult(List.of(), 0L);
        }
    }
}
