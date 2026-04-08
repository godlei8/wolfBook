package com.wolfbook.backend.domain;

import java.util.List;

public record Role(
        Integer id,
        String name,
        String alias,
        String camp,
        String skill,
        String background,
        List<FaqItem> faqs,
        String portrait,
        String fullIllustration
) {
}
