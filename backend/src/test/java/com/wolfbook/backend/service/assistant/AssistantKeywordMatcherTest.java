package com.wolfbook.backend.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantKeywordMatcherTest {

    @Test
    void shortChineseKeywordMatchesContentAndBuildsNearbySnippet() {
        AssistantKeywordMatcher.KeywordMatch match = AssistantKeywordMatcher.match(
                "假面",
                "高配角色资料",
                "roles.md",
                "角色补充说明",
                "前置说明很多。假面是一个需要结合板规理解的特殊身份，夜间信息和白天节奏都要单独看。",
                AssistantConstants.SOURCE_DOCUMENT
        );

        assertThat(match.score()).isGreaterThan(0);
        assertThat(match.snippet()).contains("假面");
        assertThat(match.snippet()).contains("特殊身份");
    }

    @Test
    void questionPhraseStillExtractsChineseKeyword() {
        AssistantKeywordMatcher.KeywordMatch match = AssistantKeywordMatcher.match(
                "假面是什么",
                "狼人杀扩展角色",
                "knowledge.txt",
                "假面角色说明",
                "该资料解释假面的阵营、技能限制和常见结算顺序。",
                AssistantConstants.SOURCE_DOCUMENT
        );

        assertThat(match.score()).isGreaterThan(0);
        assertThat(match.snippet()).contains("假面");
    }

    @Test
    void unrelatedQueryDoesNotMatch() {
        AssistantKeywordMatcher.KeywordMatch match = AssistantKeywordMatcher.match(
                "假面",
                "预言家",
                "seer.md",
                "预言家每晚查验身份",
                "预言家是信息位，通常围绕警徽流展开。",
                AssistantConstants.SOURCE_STRUCTURED
        );

        assertThat(match.score()).isZero();
        assertThat(match.snippet()).isBlank();
    }

    @Test
    void genericSkillQuestionKeepsNamedRoleAsPrimarySignal() {
        AssistantKeywordMatcher.KeywordMatch exactRole = AssistantKeywordMatcher.match(
                "假面的技能",
                "假面",
                null,
                "假面角色说明",
                "技能：可在夜间获得伪装身份，并影响白天身份展示。",
                AssistantConstants.SOURCE_STRUCTURED
        );

        AssistantKeywordMatcher.KeywordMatch genericSkill = AssistantKeywordMatcher.match(
                "假面的技能",
                "猎人",
                null,
                "猎人角色说明",
                "技能：出局时可发动技能带走一名玩家。",
                AssistantConstants.SOURCE_STRUCTURED
        );

        assertThat(exactRole.score()).isGreaterThan(genericSkill.score());
        assertThat(genericSkill.score()).isZero();
    }

    @Test
    void tokenExtractionDropsQuestionGlueAndGenericWords() {
        assertThat(AssistantKeywordMatcher.extractTokens("女巫的技能是什么"))
                .contains("女巫")
                .doesNotContain("技能", "是什么", "的技");
    }

    @Test
    void focusContentKeepsOnlyTheAskedSubjectInsideMixedRoleSection() {
        var tokens = AssistantKeywordMatcher.extractTokens("搜舞者的信息");
        String focused = AssistantKeywordMatcher.focusContent(
                """
                        ### 13. 假面（京城大师赛·假面舞会）
                        - 舞者（京城大师赛·假面舞会） - 技能：起舞，从第二夜开始必须选择三名玩家共舞。
                        - 假面（京城大师赛·假面舞会） - 技能：面具，不与狼人见面，也不参与第一夜刀人。
                        """,
                tokens
        );

        assertThat(focused).contains("舞者");
        assertThat(focused).contains("起舞");
        assertThat(focused).doesNotContain("假面（京城大师赛·假面舞会） - 技能：面具");
    }

    @Test
    void focusTitleUsesAskedSubjectInsteadOfParentSectionTitle() {
        var tokens = AssistantKeywordMatcher.extractTokens("舞者的信息");
        String focused = AssistantKeywordMatcher.focusContent(
                "- 舞者（京城大师赛·假面舞会） - 技能：起舞。\n- 假面（京城大师赛·假面舞会） - 技能：面具。",
                tokens
        );

        assertThat(AssistantKeywordMatcher.focusTitle("狼人杀角色大全 / 假面舞会", focused, tokens))
                .startsWith("舞者");
    }

    @Test
    void focusContentDropsPipeDelimitedRoleCatalogEntries() {
        String focused = AssistantKeywordMatcher.focusContent(
                "|舞者|好人 神职|共舞 判定||村民|好人 村民|无||丘比特|第三方|连情侣||狼美人|狼人|魅惑|",
                List.of("舞者")
        );

        assertThat(focused).contains("舞者");
        assertThat(focused).contains("共舞");
        assertThat(focused).doesNotContain("村民");
        assertThat(focused).doesNotContain("丘比特");
        assertThat(focused).doesNotContain("狼美人");
    }
}
