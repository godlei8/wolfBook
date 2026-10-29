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
        assertEquals("first_night", rules.witchSelfSave());
        assertTrue(rules.witchOnePotionPerNight());
        assertEquals(120, rules.speechSeconds());
        assertEquals(150, rules.sheriffSpeechSeconds());
    }

    @Test
    void cityIsReadFromTheBoardsWinConditionText() {
        assertEquals("city", JudgeRules.fromBoard(board("屠城", BoardRuleDetail.empty())).winCondition());
        assertEquals("side", JudgeRules.fromBoard(board("屠边", BoardRuleDetail.empty())).winCondition());
        assertEquals("side", JudgeRules.fromBoard(board(null, null)).winCondition());
    }

    @Test
    void partialJudgeRulesFallBackPerField() {
        JudgeRules partial = new JudgeRules(null, false, "never", false, null, null);
        JudgeRules rules = partial.withDefaults();
        assertEquals("never", rules.witchSelfSave());
        assertEquals(120, rules.speechSeconds());
        assertFalse(rules.sheriffDefault());
        assertFalse(rules.witchOnePotionPerNight());
    }

    @Test
    void boardJudgeRulesAreKeptButWinConditionComesFromTheBoardColumn() {
        BoardRuleDetail detail = new BoardRuleDetail(null, List.of(), List.of(), List.of(),
                new JudgeRules("side", true, "never", true, 90, null));
        JudgeRules rules = JudgeRules.fromBoard(board("屠城", detail));
        assertEquals("city", rules.winCondition());
        assertEquals("never", rules.witchSelfSave());
        assertEquals(90, rules.speechSeconds());
        assertEquals(150, rules.sheriffSpeechSeconds());
    }

    private static Board board(String winCondition, BoardRuleDetail detail) {
        return new Board(1, "测试板", 9, "入门", List.of(), null, null, null, null, List.of(), List.of(), List.of(),
                detail, winCondition, "标准板", 1, List.of());
    }
}
