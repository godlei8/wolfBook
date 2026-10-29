package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeNightFlowTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 22, 0, 0);
    private static final LocalDateTime LATER = NOW.plusSeconds(30);

    @Test
    void wolfStepClosesWhenAllAliveWolvesSubmitted() {
        assertTrue(JudgeNightFlow.shouldClose(NightKind.WOLF, List.of(2, 4), Set.of(2, 4), LATER, NOW));
        assertFalse(JudgeNightFlow.shouldClose(NightKind.WOLF, List.of(2, 4), Set.of(2), LATER, NOW));
    }

    @Test
    void singleHolderStepWaitsForDeadlineEvenAfterSubmit() {
        // 零场外：守卫交了也不提前切步，不然全场都知道守卫动作快慢
        assertFalse(JudgeNightFlow.shouldClose(NightKind.GUARD, List.of(3), Set.of(3), LATER, NOW));
        assertFalse(JudgeNightFlow.shouldClose(NightKind.WITCH, List.of(5), Set.of(5), LATER, NOW));
    }

    @Test
    void closesWhenDeadlinePassed() {
        assertTrue(JudgeNightFlow.shouldClose(NightKind.GUARD, List.of(3), Set.of(), NOW.minusSeconds(1), NOW));
        assertTrue(JudgeNightFlow.shouldClose(NightKind.WOLF, List.of(2, 4), Set.of(), NOW.minusSeconds(1), NOW));
    }

    @Test
    void noDeadlineNeverCloses() {
        assertFalse(JudgeNightFlow.shouldClose(NightKind.GUARD, List.of(3), Set.of(3), null, NOW));
    }
}
