package com.wolfbook.backend.domain;

import java.time.LocalDateTime;
import java.util.List;

public record Post(
        Integer id,
        String openid,
        String postType,
        String title,
        String summary,
        String content,
        List<String> images,
        Integer boardId,
        String boardName,
        List<String> roleTags,
        List<String> tagList,
        String sessionId,
        Integer qualityScore,
        Double hotScore,
        Integer viewCount,
        Integer likeCount,
        Integer commentCount,
        Integer favoriteCount,
        String status,
        Boolean featured,
        Boolean pinned,
        String rejectReason,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
