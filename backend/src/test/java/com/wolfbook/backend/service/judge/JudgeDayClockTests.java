package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeDayClockTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 21, 0, 0);

    @Test
    void shouldCloseVoteWhenEveryoneVotedOrTimeIsUp() {
        Set<Integer> eligible = Set.of(1, 2, 3);
        assertTrue(JudgeDayClock.shouldCloseVote(eligible, Set.of(1, 2, 3), NOW.plusSeconds(20), NOW), "全员投完");
        assertFalse(JudgeDayClock.shouldCloseVote(eligible, Set.of(1, 2), NOW.plusSeconds(20), NOW), "还有人没投、没到点");
        assertTrue(JudgeDayClock.shouldCloseVote(eligible, Set.of(1), NOW.minusSeconds(1), NOW), "到点没投的算弃票");
        assertFalse(JudgeDayClock.shouldCloseVote(eligible, Set.of(1), null, NOW), "没有截止时间（旧流程）不自动收");
    }

    @Test
    void nobodyEligibleClosesRightAway() {
        assertTrue(JudgeDayClock.shouldCloseVote(Set.of(), Set.of(), NOW.plusSeconds(20), NOW));
    }
}
