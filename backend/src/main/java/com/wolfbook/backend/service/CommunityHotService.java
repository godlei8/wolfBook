package com.wolfbook.backend.service;

import com.wolfbook.backend.config.CommunityProperties;
import com.wolfbook.backend.entity.PostEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
public class CommunityHotService {

    private static final String HOT_POSTS_KEY = "wolfbook:community:posts:hot";

    private final CommunityProperties properties;
    private final StringRedisTemplate redisTemplate;

    public CommunityHotService(CommunityProperties properties, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.properties = properties;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public double calculateHotScore(PostEntity post) {
        if (post == null) {
            return 0d;
        }
        CommunityProperties.Hot hot = properties.getHot();
        double score =
                safe(post.getViewCount()) * hot.getViewWeight()
                        + safe(post.getLikeCount()) * hot.getLikeWeight()
                        + safe(post.getCommentCount()) * hot.getCommentWeight()
                        + safe(post.getFavoriteCount()) * hot.getFavoriteWeight()
                        + safe(post.getQualityScore()) * hot.getQualityWeight();
        if (Boolean.TRUE.equals(post.getFeatured())) {
            score += hot.getFeaturedBonus();
        }
        if (Boolean.TRUE.equals(post.getPinned())) {
            score += hot.getPinnedBonus();
        }
        LocalDateTime createTime = post.getCreateTime();
        if (createTime != null) {
            long hours = Math.max(0L, ChronoUnit.HOURS.between(createTime, LocalDateTime.now()));
            score -= hours * hot.getAgeDecayPerHour();
        }
        return Math.max(score, 0d);
    }

    public double refreshPostRanking(PostEntity post) {
        double score = calculateHotScore(post);
        if (post != null) {
            post.setHotScore(score);
        }
        updateRedisScore(post == null ? null : post.getId(), score);
        return score;
    }

    public List<Integer> listHotPostIds(int limit) {
        if (limit <= 0 || redisTemplate == null) {
            return List.of();
        }
        try {
            Set<String> rawIds = redisTemplate.opsForZSet().reverseRange(HOT_POSTS_KEY, 0, Math.max(0, limit - 1));
            if (rawIds == null || rawIds.isEmpty()) {
                return List.of();
            }
            return rawIds.stream()
                    .map(this::parseInteger)
                    .filter(id -> id != null && id > 0)
                    .toList();
        } catch (DataAccessException exception) {
            return List.of();
        }
    }

    public void removePost(Integer postId) {
        if (postId == null || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(HOT_POSTS_KEY, String.valueOf(postId));
        } catch (DataAccessException ignored) {
            // Keep MySQL path working even if Redis is unavailable.
        }
    }

    private void updateRedisScore(Integer postId, double score) {
        if (postId == null || redisTemplate == null) {
            return;
        }
        try {
            ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();
            zSet.add(HOT_POSTS_KEY, String.valueOf(postId), score);
            redisTemplate.expire(HOT_POSTS_KEY, Duration.ofDays(14));
        } catch (DataAccessException ignored) {
            // Keep MySQL path working even if Redis is unavailable.
        }
    }

    private double safe(Number value) {
        return value == null ? 0d : value.doubleValue();
    }

    private Integer parseInteger(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (Exception exception) {
            return null;
        }
    }
}
