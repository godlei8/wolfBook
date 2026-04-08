package com.wolfbook.backend.domain;

import java.time.LocalDateTime;
import java.util.List;

public record Post(
        Integer id,
        String openid,
        String content,
        List<String> images,
        Integer likeCount,
        Integer commentCount,
        Integer status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
