package com.wolfbook.backend.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeRulesTests {

    @Test
    void defaultsMatchTheSpec() {
        JudgeRules rules = JudgeRules.defaults();
        assertEquals("side", rules.winCondition());
        assertTrue(rules.sheriffDefault());
        assertEquals("never", rules.witchSelfSave());
        assertTrue(rules.witchOnePotionPerNight());
        assertEquals(120, rules.speechSeconds());
        assertEquals(150, rules.sheriffSpeechSeconds());
    }

    @Test
    void poisonBounceDefaultsToFalse() {
        assertFalse(JudgeRules.defaults().poisonBounce());
        assertFalse(new JudgeRules(null, null, null, null, null, null, null, null).withDefaults().poisonBounce());
        assertTrue(new JudgeRules(null, null, null, null, null, null, null, null, true).withDefaults().poisonBounce());
        assertTrue(JudgeRules.resolve(new BoardRuleDetail(null, List.of(), List.of(), List.of(),
                new JudgeRules(null, null, null, null, null, null, null, null, true)), null).poisonBounce());
    }

    @Test
    void defaultsIncludeActionSeconds() {
        JudgeRules rules = JudgeRules.defaults();
        assertEquals(60, rules.wolfSeconds());
        assertEquals(30, rules.roleSeconds());
        // 旧的 6 参构造器照旧能用，新字段补默认值
        assertEquals(60, new JudgeRules(null, true, null, true, null, null).withDefaults().wolfSeconds());
    }

    @Test
    void cityIsReadFromTheBoardsWinConditionText() {
        assertEquals("city", JudgeRules.fromBoard(board("屠城", BoardRuleDetail.empty())).winCondition());
        assertEquals("side", JudgeRules.fromBoard(board("屠边", BoardRuleDetail.empty())).winCondition());
        assertEquals("side", JudgeRules.fromBoard(board(null, null)).winCondition());
    }

    @Test
    void partialJudgeRulesFallBackPerField() {
        JudgeRules partial = new JudgeRules(null, false, "first_night", false, null, null);
        JudgeRules rules = partial.withDefaults();
        // 女巫全程不可自救、一晚一瓶都是全局规则：写了别的也不算数
        assertEquals("never", rules.witchSelfSave());
        assertEquals(120, rules.speechSeconds());
        assertFalse(rules.sheriffDefault());
        assertTrue(rules.witchOnePotionPerNight());
    }

    @Test
    void witchNeverSavesHerself() {
        assertEquals("never", new JudgeRules(null, null, null, null, null, null).withDefaults().witchSelfSave());
        assertEquals("never", new JudgeRules(null, null, "never", null, null, null).withDefaults().witchSelfSave());
        assertEquals("never", new JudgeRules(null, null, "bogus", null, null, null).withDefaults().witchSelfSave());
        assertEquals("never", new JudgeRules(null, null, "first_night", null, null, null).withDefaults().witchSelfSave(),
                "roles/女巫.md：全程不可自救，旧板子和旧房间存的 first_night 也不算");
    }

    @Test
    void boardJudgeRulesAreKeptButWinConditionComesFromTheBoardColumn() {
        BoardRuleDetail detail = new BoardRuleDetail(null, List.of(), List.of(), List.of(),
                new JudgeRules("side", true, "first_night", true, 90, null));
        JudgeRules rules = JudgeRules.fromBoard(board("屠城", detail));
        assertEquals("city", rules.winCondition());
        assertEquals("never", rules.witchSelfSave());
        assertEquals("never", JudgeRules.fromBoard(board("屠边", BoardRuleDetail.empty())).witchSelfSave());
        assertEquals(90, rules.speechSeconds());
        assertEquals(150, rules.sheriffSpeechSeconds());
    }

    private static Board board(String winCondition, BoardRuleDetail detail) {
        return new Board(1, "测试板", 9, "入门", List.of(), null, null, null, null, List.of(), List.of(), List.of(),
                detail, winCondition, "标准板", 1, List.of());
    }
}
