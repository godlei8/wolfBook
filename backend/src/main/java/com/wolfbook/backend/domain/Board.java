package com.wolfbook.backend.domain;

import com.wolfbook.backend.support.JudgeSupportLevels;

import java.util.List;

public record Board(
        Integer id,
        String name,
        Integer playerCount,
        String difficulty,
        List<String> tags,
        String coverImage,
        String cardDescription,
        String briefConfig,
        List<String> specialRules,
        List<String> tips,
        List<FaqItem> faqs,
        String winCondition,
        String ruleType,
        String judgeSupportLevel,
        Integer status,
        List<BoardRoleRef> roles
) {
    public Board(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String briefConfig,
            List<String> specialRules,
            List<String> tips,
            List<FaqItem> faqs,
            String winCondition,
            String ruleType,
            Integer status,
            List<BoardRoleRef> roles
    ) {
        this(id, name, playerCount, difficulty, tags, coverImage, null, briefConfig, specialRules, tips, faqs, winCondition, ruleType, JudgeSupportLevels.MANUAL_ONLY, status, roles);
    }

    public Board(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String briefConfig,
            List<String> specialRules,
            List<String> tips,
            List<FaqItem> faqs,
            String winCondition,
            String ruleType,
            String judgeSupportLevel,
            Integer status,
            List<BoardRoleRef> roles
    ) {
        this(id, name, playerCount, difficulty, tags, coverImage, null, briefConfig, specialRules, tips, faqs, winCondition, ruleType, judgeSupportLevel, status, roles);
    }
}
