package com.wolfbook.backend.domain;

import java.time.LocalDateTime;

public record AdminUser(
        Integer id,
        String username,
        String password,
        String displayName,
        Integer status,
        LocalDateTime createTime
) {
}
