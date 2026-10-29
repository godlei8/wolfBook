package com.wolfbook.backend.domain;

import java.time.LocalDateTime;

public record Comment(
        Integer id,
        Integer postId,
        String openid,
        String content,
        Integer likeCount,
        String status,
        LocalDateTime createTime
) {
}
