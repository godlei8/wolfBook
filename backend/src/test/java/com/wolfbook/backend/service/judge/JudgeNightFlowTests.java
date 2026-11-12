package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeNightFlowTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 22, 0, 0);
    private static final LocalDateTime LATER = NOW.plusSeconds(30);

    @Test
    void stepsCloseOnlyAtTheDeadline() {
        // 交了也不提前切步：单人步骤等满（零场外）；狼人那一步提前结束只靠全员确定后缩短截止时间
        assertFalse(JudgeNightFlow.shouldClose(LATER, NOW));
        assertTrue(JudgeNightFlow.shouldClose(NOW.minusSeconds(1), NOW));
        assertFalse(JudgeNightFlow.shouldClose(null, NOW));
    }

    @Test
    void allAliveWolvesMustConfirm() {
        assertTrue(JudgeNightFlow.allConfirmed(List.of(2, 4), Set.of(2, 4)));
        assertFalse(JudgeNightFlow.allConfirmed(List.of(2, 4), Set.of(2)));
        assertFalse(JudgeNightFlow.allConfirmed(List.of(), Set.of()));
    }

    @Test
    void confirmedWolfStepEndsFiveSecondsLaterButNeverAfterTheFullTime() {
        assertEquals(5, JudgeNightFlow.CONFIRM_GRACE_SECONDS);
        assertEquals(NOW.plusSeconds(5), JudgeNightFlow.confirmedDeadline(LATER, NOW));
        assertEquals(NOW.plusSeconds(3), JudgeNightFlow.confirmedDeadline(NOW.plusSeconds(3), NOW));
    }
}
