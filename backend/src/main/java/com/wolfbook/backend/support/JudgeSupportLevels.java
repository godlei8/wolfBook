package com.wolfbook.backend.support;

import org.springframework.util.StringUtils;

import java.util.Locale;

public final class JudgeSupportLevels {

    public static final String MANUAL_ONLY = "manual_only";
    public static final String PARTIAL = "partial";
    public static final String FULL = "full";

    private JudgeSupportLevels() {
    }

    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return MANUAL_ONLY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (FULL.equals(normalized) || PARTIAL.equals(normalized)) {
            return normalized;
        }
        return MANUAL_ONLY;
    }

    public static String resolve(String requested, String current) {
        return normalize(StringUtils.hasText(requested) ? requested : current);
    }

    public static boolean isManualOnly(String value) {
        return MANUAL_ONLY.equals(normalize(value));
    }
}
