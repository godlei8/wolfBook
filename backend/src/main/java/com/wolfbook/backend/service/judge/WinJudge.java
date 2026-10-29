package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.domain.JudgeRules;

import java.util.List;
import java.util.Optional;

/**
 * 胜负判定，每次有人出局后跑一遍。先查好人胜（最后一只狼和最后一个神职同时出局算好人胜），再查狼胜：
 * 屠边是神职或平民任一边全灭，屠城是神职和平民都全灭。狼人阵营按 faction，神职 / 平民按 role_type；
 * 翻牌的白痴仍算存活神职。第三方的胜负三期再做。
 */
public final class WinJudge {

    public static final String CAMP_GOOD = "好人";
    public static final String CAMP_WOLF = "狼人";

    private static final String FACTION_WOLF = "狼人";
    private static final String TYPE_GOD = "神职";
    private static final String TYPE_VILLAGER = "平民";

    public record Seat(boolean alive, String faction, String roleType) {
    }

    public record Verdict(String camp, String reason) {
    }

    private WinJudge() {
    }

    public static Optional<Verdict> judge(List<Seat> seats, String winCondition) {
        long wolves = count(seats, seat -> FACTION_WOLF.equals(seat.faction()));
        long gods = count(seats, seat -> !FACTION_WOLF.equals(seat.faction()) && TYPE_GOD.equals(seat.roleType()));
        long villagers = count(seats, seat -> !FACTION_WOLF.equals(seat.faction()) && TYPE_VILLAGER.equals(seat.roleType()));

        if (wolves == 0) {
            return Optional.of(new Verdict(CAMP_GOOD, "狼人阵营全部出局，好人阵营胜利"));
        }
        if (JudgeRules.WIN_CITY.equals(winCondition)) {
            return gods == 0 && villagers == 0
                    ? Optional.of(new Verdict(CAMP_WOLF, "好人全部出局，狼人阵营胜利"))
                    : Optional.empty();
        }
        if (gods == 0) {
            return Optional.of(new Verdict(CAMP_WOLF, "神职全部出局，狼人阵营胜利"));
        }
        if (villagers == 0) {
            return Optional.of(new Verdict(CAMP_WOLF, "平民全部出局，狼人阵营胜利"));
        }
        return Optional.empty();
    }

    private static long count(List<Seat> seats, java.util.function.Predicate<Seat> filter) {
        return seats.stream().filter(Seat::alive).filter(filter).count();
    }
}
