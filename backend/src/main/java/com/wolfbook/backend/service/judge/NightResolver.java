package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.JudgeRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一夜的结算，纯函数：输入今晚的提交和座位状态，输出死亡名单和每个人的私密反馈。
 * 规则：刀口按狼的票数取多数、平票随机、都没选是空刀；守卫或解药单独救得活，同守同救照样死；
 * 毒药守不住；被毒的猎人不能开枪。
 */
public final class NightResolver {

    public static final String KILL = "kill";
    public static final String GUARD = "guard";
    public static final String SAVE = "save";
    public static final String POISON = "poison";
    public static final String CHECK = "check";
    public static final String NONE = "none";

    private static final String FACTION_WOLF = "狼人";

    /** 一条夜间提交：谁（座位）、这一步的类型、动作、目标座位（空刀 / 不用药等没有目标）。 */
    public record Submission(int actorSeat, NightKind kind, String action, Integer targetSeat) {
    }

    /** 结算要用到的座位状态：女巫还剩什么药、守卫上一夜守了谁。 */
    public record SeatState(int seat, String roleName, String faction, boolean alive, boolean antidoteLeft,
                            boolean poisonLeft, Integer lastGuardSeat) {
    }

    /**
     * 结算结果。deadSeats 按座位号排序；feedback 是只发给本人的信息（预言家：好人 / 狼人；女巫：刀口）；
     * cannotShoot 是今晚被毒、出局后不能开枪的猎人。
     */
    public record Outcome(Integer knifeSeat, List<Integer> deadSeats, Map<Integer, String> feedback, Set<Integer> cannotShoot,
                          Set<Integer> antidoteUsedBy, Set<Integer> poisonUsedBy) {
    }

    private NightResolver() {
    }

    /** 狼队刀口：票多的那个；平票在平票的人里随机；没人选就是空刀（null）。 */
    public static Integer tallyKnife(List<Integer> picks, Random random) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Integer pick : picks) {
            if (pick != null) {
                counts.merge(pick, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        int top = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> tied = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == top)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return tied.size() == 1 ? tied.get(0) : tied.get(random.nextInt(tied.size()));
    }

    public static Outcome resolve(List<Submission> submissions, Map<Integer, SeatState> seats, JudgeRules rules, int dayNo, Random random) {
        List<Integer> wolfPicks = new ArrayList<>();
        Integer guarded = null;
        Map<Integer, String> feedback = new LinkedHashMap<>();
        for (Submission submission : submissions) {
            if (submission.kind() == NightKind.WOLF && KILL.equals(submission.action())) {
                wolfPicks.add(submission.targetSeat());
            }
            if (submission.kind() == NightKind.GUARD && GUARD.equals(submission.action())) {
                SeatState guard = seats.get(submission.actorSeat());
                if (guard == null || !Objects.equals(guard.lastGuardSeat(), submission.targetSeat())) {
                    guarded = submission.targetSeat();
                }
            }
        }
        Integer knife = tallyKnife(wolfPicks, random);

        Integer saved = null;
        Integer poisoned = null;
        Set<Integer> antidoteUsedBy = new TreeSet<>();
        Set<Integer> poisonUsedBy = new TreeSet<>();
        for (Submission submission : submissions) {
            if (submission.kind() != NightKind.WITCH) {
                continue;
            }
            SeatState witch = seats.get(submission.actorSeat());
            if (witch == null) {
                continue;
            }
            if (SAVE.equals(submission.action()) && witch.antidoteLeft() && knife != null && knife.equals(submission.targetSeat())
                    && canSelfSave(witch.seat(), submission.targetSeat(), rules, dayNo)) {
                saved = submission.targetSeat();
                antidoteUsedBy.add(witch.seat());
            } else if (POISON.equals(submission.action()) && witch.poisonLeft() && submission.targetSeat() != null) {
                if (!rules.witchOnePotionPerNight() || !antidoteUsedBy.contains(witch.seat())) {
                    poisoned = submission.targetSeat();
                    poisonUsedBy.add(witch.seat());
                }
            }
        }

        Set<Integer> dead = new TreeSet<>();
        if (knife != null && (Objects.equals(knife, saved) == Objects.equals(knife, guarded))) {
            dead.add(knife);
        }
        if (poisoned != null) {
            dead.add(poisoned);
        }

        Set<Integer> cannotShoot = new TreeSet<>();
        if (poisoned != null) {
            SeatState target = seats.get(poisoned);
            if (target != null && RoleSkillRegistry.legacyMechanic(target.roleName())
                    .map(mechanic -> mechanic == Mechanic.HUNTER).orElse(false)) {
                cannotShoot.add(poisoned);
            }
        }

        for (Submission submission : submissions) {
            if (submission.kind() == NightKind.SEER && CHECK.equals(submission.action()) && submission.targetSeat() != null) {
                SeatState target = seats.get(submission.targetSeat());
                feedback.put(submission.actorSeat(), target != null && FACTION_WOLF.equals(target.faction()) ? "狼人" : "好人");
            }
        }
        for (SeatState seat : seats.values()) {
            if (seat.alive() && RoleSkillRegistry.legacyMechanic(seat.roleName())
                    .map(mechanic -> mechanic == Mechanic.WITCH).orElse(false)) {
                feedback.put(seat.seat(), knife == null ? "平安夜" : knife + " 号");
            }
        }

        return new Outcome(knife, List.copyOf(dead), Map.copyOf(feedback), Set.copyOf(cannotShoot),
                Set.copyOf(antidoteUsedBy), Set.copyOf(poisonUsedBy));
    }

    /**
     * 提交时校验，不合法抛 4000。knifeSeat 是狼队那一步关闭时定下的刀口（女巫那一步才用得到）。
     */
    public static void validate(Submission submission, SeatState actor, SeatState target, JudgeRules rules, int dayNo, Integer knifeSeat) {
        String action = submission.action();
        if (NONE.equals(action)) {
            return;
        }
        if (target == null || !target.alive()) {
            throw new ApiException(4000, "目标已经出局");
        }
        switch (submission.kind()) {
            case GUARD -> {
                if (Objects.equals(actor.lastGuardSeat(), target.seat())) {
                    throw new ApiException(4000, "不能连续两晚守护同一个人");
                }
            }
            case WITCH -> {
                if (SAVE.equals(action)) {
                    if (!actor.antidoteLeft()) {
                        throw new ApiException(4000, "解药已经用过了");
                    }
                    if (!Objects.equals(knifeSeat, target.seat())) {
                        throw new ApiException(4000, "解药只能救今晚被刀的人");
                    }
                    if (!canSelfSave(actor.seat(), target.seat(), rules, dayNo)) {
                        throw new ApiException(4000, "本局规则下女巫现在不能自救");
                    }
                } else if (POISON.equals(action) && !actor.poisonLeft()) {
                    throw new ApiException(4000, "毒药已经用过了");
                }
            }
            default -> {
            }
        }
    }

    private static boolean canSelfSave(int witchSeat, Integer targetSeat, JudgeRules rules, int dayNo) {
        if (!Objects.equals(witchSeat, targetSeat)) {
            return true;
        }
        return JudgeRules.SELF_SAVE_FIRST_NIGHT.equals(rules.witchSelfSave()) && dayNo <= 1;
    }
}
