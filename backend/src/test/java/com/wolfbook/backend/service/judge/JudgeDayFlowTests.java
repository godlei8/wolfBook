package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgeDayFlowTests {

    private static final List<Integer> ALIVE = List.of(1, 2, 3, 5, 6, 7, 8, 9);

    @Test
    void deadLeftStartsClockwiseAfterTheFirstDeath() {
        assertEquals(List.of(5, 6, 7, 8, 9, 1, 2, 3), JudgeDayFlow.speechOrder(ALIVE, 9, "dead_left", null, List.of(4), new Random(1)));
    }

    @Test
    void deadRightGoesCounterClockwise() {
        assertEquals(List.of(3, 2, 1, 9, 8, 7, 6, 5), JudgeDayFlow.speechOrder(ALIVE, 9, "dead_right", null, List.of(4), new Random(1)));
    }

    @Test
    void sheriffLeftStartsBesideTheSheriffWhoSpeaksLast() {
        assertEquals(List.of(7, 8, 9, 1, 2, 3, 5, 6), JudgeDayFlow.speechOrder(ALIVE, 9, "left", 6, List.of(4), new Random(1)));
        assertEquals(List.of(5, 3, 2, 1, 9, 8, 7, 6), JudgeDayFlow.speechOrder(ALIVE, 9, "right", 6, List.of(4), new Random(1)));
    }

    @Test
    void withoutChoiceDeathsMeanDeadLeft() {
        assertEquals(List.of(5, 6, 7, 8, 9, 1, 2, 3), JudgeDayFlow.speechOrder(ALIVE, 9, null, null, List.of(4), new Random(1)));
    }

    @Test
    void withoutChoiceOrDeathsEveryoneSpeaksOnceFromSomewhere() {
        List<Integer> order = JudgeDayFlow.speechOrder(ALIVE, 9, null, null, List.of(), new Random(7));
        assertEquals(8, order.size());
        assertEquals(ALIVE, order.stream().sorted().toList());
    }
}
