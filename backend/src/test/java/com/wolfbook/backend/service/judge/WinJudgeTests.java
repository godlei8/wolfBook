package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WinJudgeTests {

    @Test
    void goodWinsWhenAllWolvesAreOut() {
        Optional<WinJudge.Verdict> verdict = WinJudge.judge(seats(0, 1, 2, 3, 1, 1), "side");
        assertEquals("好人", verdict.orElseThrow().camp());
        assertTrue(verdict.get().reason().contains("狼人阵营全部出局"));
    }

    @Test
    void sideRuleWolvesWinWhenAllGodsAreOut() {
        Optional<WinJudge.Verdict> verdict = WinJudge.judge(seats(1, 2, 0, 3, 3, 1), "side");
        assertEquals("狼人", verdict.orElseThrow().camp());
        assertTrue(verdict.get().reason().contains("神职全部出局"));
    }

    @Test
    void sideRuleWolvesWinWhenAllVillagersAreOut() {
        Optional<WinJudge.Verdict> verdict = WinJudge.judge(seats(1, 2, 2, 3, 0, 3), "side");
        assertTrue(verdict.orElseThrow().reason().contains("平民全部出局"));
    }

    @Test
    void cityRuleNeedsAllGoodOut() {
        assertTrue(WinJudge.judge(seats(1, 2, 0, 3, 3, 1), "city").isEmpty());
        assertEquals("狼人", WinJudge.judge(seats(1, 2, 0, 3, 0, 3), "city").orElseThrow().camp());
    }

    @Test
    void simultaneousWipeoutGoesToGood() {
        // 最后一只狼和最后一个神职同时出局：好人胜
        assertEquals("好人", WinJudge.judge(seats(0, 2, 0, 3, 2, 1), "side").orElseThrow().camp());
    }

    @Test
    void nothingYet() {
        assertTrue(WinJudge.judge(seats(2, 1, 2, 1, 2, 1), "side").isEmpty());
    }

    /** 存活狼、出局狼、存活神、出局神、存活民、出局民 */
    private static List<WinJudge.Seat> seats(int wolves, int deadWolves, int gods, int deadGods, int villagers, int deadVillagers) {
        List<WinJudge.Seat> seats = new ArrayList<>();
        add(seats, wolves, true, "狼人", "狼人");
        add(seats, deadWolves, false, "狼人", "狼人");
        add(seats, gods, true, "好人", "神职");
        add(seats, deadGods, false, "好人", "神职");
        add(seats, villagers, true, "好人", "平民");
        add(seats, deadVillagers, false, "好人", "平民");
        return seats;
    }

    private static void add(List<WinJudge.Seat> seats, int count, boolean alive, String faction, String roleType) {
        for (int i = 0; i < count; i++) {
            seats.add(new WinJudge.Seat(alive, faction, roleType));
        }
    }
}
