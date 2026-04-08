package com.wolfbook.backend.support;

import com.wolfbook.backend.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class MockContentSafetyProvider implements ContentSafetyProvider {

    private static final Set<String> BANNED_WORDS = Set.of("违法", "博彩", "诈骗", "spam");

    @Override
    public void ensureSafeText(String text) {
        String safeText = text == null ? "" : text.toLowerCase();
        BANNED_WORDS.stream()
                .filter(safeText::contains)
                .findFirst()
                .ifPresent(word -> {
                    throw new ApiException(4003, "内容安全校验未通过，包含敏感词: " + word);
                });
    }

    @Override
    public void ensureSafeImages(List<String> images) {
        if (images == null) {
            return;
        }
        if (images.size() > 9) {
            throw new ApiException(4003, "图片不能超过 9 张");
        }
    }
}
