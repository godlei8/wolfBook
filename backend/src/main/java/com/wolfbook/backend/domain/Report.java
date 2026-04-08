package com.wolfbook.backend.domain;

import java.time.LocalDateTime;

public record Report(
        Integer id,
        String targetType,
        Integer targetId,
        String openid,
        String reason,
        String processStatus,
        String processBy,
        LocalDateTime processTime,
        LocalDateTime createTime
) {
}
