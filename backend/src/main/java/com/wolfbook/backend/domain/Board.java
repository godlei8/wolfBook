package com.wolfbook.backend.domain;

import java.util.List;

public record Board(
        Integer id,
        String name,
        Integer playerCount,
        String difficulty,
        List<String> tags,
        String coverImage,
        String backgroundImage,
        String cardDescription,
        String briefConfig,
        List<String> specialRules,
        List<String> tips,
        List<FaqItem> faqs,
        BoardRuleDetail ruleDetail,
        String winCondition,
        String ruleType,
        Integer status,
        List<BoardRoleRef> roles
) {
}
