package com.wolfbook.backend.domain;

import java.time.LocalDateTime;

public record UserProfile(
        String openid,
        String nickname,
        String avatar,
        Integer status,
        LocalDateTime createTime
) {
}
