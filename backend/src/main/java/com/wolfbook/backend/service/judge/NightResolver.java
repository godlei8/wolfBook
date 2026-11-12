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
 * 一夜的结算，纯函数：输入今晚的提交和座位状态，输出死亡名单和每个人的私密反馈。规则口径是 docs/judge/house-rules/：
 * <ul>
 *   <li>普通刀口：狼队按票数取多数、平票随机、都没选是空刀；狼队全出局后由机械狼（带刀）出。</li>
 *   <li>守护（守卫、学到守卫的机械狼）或解药单独救得活普通刀；既被守又被救照死。</li>
 *   <li>破盾刀（学到狼人的带刀机械狼）无视所有守护；和普通刀同一人时女巫用了解药也照死、解药照耗。</li>
 *   <li>女巫的毒：守卫挡不住；只有学到守卫的机械狼守的人挡得住，板子开了「弹毒」时毒反弹给女巫。</li>
 *   <li>学到女巫的机械狼的毒：谁也挡不住，女巫看不到。</li>
 *   <li>被毒死的猎人 / 狼王 / 学到猎人的机械狼不能开枪（被刀照样能开）。</li>
 *   <li>机械狼学到的技能从下一晚起生效（学到猎人的开枪除外）。</li>
 * </ul>
 */
public final class NightResolver {

    public static final String KILL = "kill";
    public static final String KILL_PIERCE = "kill_pierce";
    public static final String GUARD = "guard";
    public static final String SAVE = "save";
    public static final String POISON = "poison";
    public static final String CHECK = "check";
    public static final String LEARN = "learn";
    public static final String CONFIRM = "confirm";
    public static final String NONE = "none";

    private static final String FACTION_WOLF = "狼人";

    /** 一条夜间提交：谁（座位）、这一步的类型、动作、目标座位（空刀 / 不用药等没有目标）。机械狼一晚可以有多条。 */
    public record Submission(int actorSeat, NightKind kind, String action, Integer targetSeat) {
    }

    /**
     * 结算要用到的座位状态。mechanic 是座位上的技能机制（可空：没有引擎认识的机制）；
     * 女巫还剩什么药、守卫（或学到守卫的机械狼）上一夜守了谁；机械狼学到的机制、对方的角色名、哪一晚学的、学来的毒还在不在。
     */
    public record SeatState(int seat, String roleName, String faction, Mechanic mechanic, boolean alive, boolean antidoteLeft,
                            boolean poisonLeft, Integer lastGuardSeat, Mechanic learned, String learnedRoleName, Integer learnedDay,
                            boolean mechPoisonLeft) {

        public SeatState(int seat, String roleName, String faction, Mechanic mechanic, boolean alive, boolean antidoteLeft,
                         boolean poisonLeft, Integer lastGuardSeat) {
            this(seat, roleName, faction, mechanic, alive, antidoteLeft, poisonLeft, lastGuardSeat, null, null, null, true);
        }
    }

    /** 机械狼今晚学到的：机制（已按规则换算）和对方的角色名。 */
    public record Learned(Mechanic mechanic, String roleName) {
    }

    /**
     * 结算结果。knifeSeat 是普通刀口（女巫看到的那个）；deadSeats 按座位号排序；
     * feedback 是只发给本人的信息（预言家：好人 / 狼人；通灵师：角色名；女巫：刀口；机械狼学习：对方身份）；
     * cannotShoot 是今晚死了、出局后不能开枪的座位；learnedTonight 是今晚学了技能的机械狼。
     */
    public record Outcome(Integer knifeSeat, List<Integer> deadSeats, Map<Integer, String> feedback, Set<Integer> cannotShoot,
                          Set<Integer> antidoteUsedBy, Set<Integer> poisonUsedBy, Map<Integer, Learned> learnedTonight,
                          Set<Integer> mechPoisonUsedBy) {
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

    /** 机械狼学到的技能今晚能不能用：学的那一晚不能，之后都能。 */
    public static boolean learnedEffective(SeatState seat, int dayNo) {
        return seat != null && seat.learned() != null && seat.learnedDay() != null && dayNo > seat.learnedDay();
    }

    /** 机械狼带刀：场上没有活着的狼队成员（狼人、狼王）。 */
    public static boolean armed(Map<Integer, SeatState> seats) {
        if (seats == null) {
            return false;
        }
        return seats.values().stream().noneMatch(seat -> seat.alive()
                && RoleSkillRegistry.isWolfTeam(seat.mechanic(), seat.roleName(), seat.faction()));
    }

    /** 机械狼学到的机制：只看对方机制。狼王按狼人；白痴、没登记的和其余没有技能分支的按平民。 */
    public static Mechanic learnedMechanicOf(SeatState target) {
        Mechanic mechanic = target == null ? null : target.mechanic();
        if (mechanic == null) {
            return Mechanic.VILLAGER;
        }
        return switch (mechanic) {
            case WOLF, WOLF_KING -> Mechanic.WOLF;
            case WITCH, GUARD, PSYCHIC, SEER, HUNTER -> mechanic;
            default -> Mechanic.VILLAGER;
        };
    }

    /** 通灵师看到的具体身份：机械狼学过（含今晚刚学）显示学到的角色名，没学过显示自己的角色名；其余人显示角色名。 */
    public static String identityOf(SeatState target, List<Submission> submissions, Map<Integer, SeatState> seats) {
        if (target == null) {
            return "";
        }
        if (target.mechanic() == Mechanic.MECH_WOLF) {
            if (target.learnedRoleName() != null) {
                return target.learnedRoleName();
            }
            SeatState learnedTonight = submissions.stream()
                    .filter(submission -> submission.actorSeat() == target.seat() && LEARN.equals(submission.action()))
                    .map(submission -> seats.get(submission.targetSeat()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (learnedTonight != null) {
                return learnedTonight.roleName();
            }
        }
        return target.roleName();
    }

    public static Outcome resolve(List<Submission> submissions, Map<Integer, SeatState> seats, JudgeRules rules, int dayNo, Random random) {
        boolean armed = armed(seats);
        boolean bounce = rules != null && Boolean.TRUE.equals(rules.poisonBounce());

        // 1. 收集
        List<Integer> wolfPicks = new ArrayList<>();
        Integer guarded = null;
        Integer mechGuarded = null;
        Integer mechKill = null;
        Integer pierce = null;
        Integer mechPoisoned = null;
        Set<Integer> mechPoisonUsedBy = new TreeSet<>();
        Map<Integer, Learned> learnedTonight = new LinkedHashMap<>();
        Map<Integer, String> feedback = new LinkedHashMap<>();
        for (Submission submission : submissions) {
            SeatState actor = seats.get(submission.actorSeat());
            if (submission.kind() == NightKind.WOLF && KILL.equals(submission.action())) {
                wolfPicks.add(submission.targetSeat());
            }
            if (submission.kind() == NightKind.GUARD && GUARD.equals(submission.action())
                    && (actor == null || !Objects.equals(actor.lastGuardSeat(), submission.targetSeat()))) {
                guarded = submission.targetSeat();
            }
            if (submission.kind() != NightKind.MECH_WOLF || actor == null || submission.targetSeat() == null) {
                continue;
            }
            boolean effective = learnedEffective(actor, dayNo);
            switch (submission.action()) {
                case LEARN -> {
                    if (actor.learned() == null && submission.targetSeat() != actor.seat()) {
                        SeatState target = seats.get(submission.targetSeat());
                        if (target != null) {
                            learnedTonight.put(actor.seat(), new Learned(learnedMechanicOf(target), target.roleName()));
                            feedback.put(actor.seat(), target.seat() + "号是" + target.roleName());
                        }
                    }
                }
                case GUARD -> {
                    if (effective && actor.learned() == Mechanic.GUARD && !Objects.equals(actor.lastGuardSeat(), submission.targetSeat())) {
                        mechGuarded = submission.targetSeat();
                    }
                }
                case POISON -> {
                    if (effective && actor.learned() == Mechanic.WITCH && actor.mechPoisonLeft()) {
                        mechPoisoned = submission.targetSeat();
                        mechPoisonUsedBy.add(actor.seat());
                    }
                }
                case KILL -> {
                    if (armed) {
                        mechKill = submission.targetSeat();
                    }
                }
                case KILL_PIERCE -> {
                    if (armed && effective && actor.learned() == Mechanic.WOLF) {
                        pierce = submission.targetSeat();
                    }
                }
                default -> {
                }
            }
        }
        Integer knife = wolfPicks.stream().anyMatch(Objects::nonNull) ? tallyKnife(wolfPicks, random) : mechKill;

        Integer saved = null;
        Integer poisoned = null;
        Integer poisoner = null;
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
                if (rules == null || !rules.witchOnePotionPerNight() || !antidoteUsedBy.contains(witch.seat())) {
                    poisoned = submission.targetSeat();
                    poisoner = witch.seat();
                    poisonUsedBy.add(witch.seat());
                }
            }
        }

        // 2. 谁死
        Set<Integer> dead = new TreeSet<>();
        Set<Integer> poisonDead = new TreeSet<>();
        if (knife != null) {
            boolean shielded = Objects.equals(knife, guarded) || Objects.equals(knife, mechGuarded);
            if (shielded == Objects.equals(knife, saved)) {
                dead.add(knife);
            }
        }
        if (pierce != null) {
            dead.add(pierce);
        }
        if (poisoned != null) {
            if (Objects.equals(poisoned, mechGuarded)) {
                if (bounce && poisoner != null) {
                    dead.add(poisoner);
                    poisonDead.add(poisoner);
                }
            } else {
                // 守卫只挡狼刀，挡不住毒（2026-11-12 用户改定）
                dead.add(poisoned);
                poisonDead.add(poisoned);
            }
        }
        if (mechPoisoned != null) {
            dead.add(mechPoisoned);
            poisonDead.add(mechPoisoned);
        }

        // 3. 不能开枪：被毒死的开枪角色
        Set<Integer> cannotShoot = new TreeSet<>();
        for (Integer seat : dead) {
            SeatState target = seats.get(seat);
            if (target == null) {
                continue;
            }
            Learned learnedNow = learnedTonight.get(seat);
            boolean shooter = (target.mechanic() != null && target.mechanic().canShoot())
                    || (target.mechanic() == Mechanic.MECH_WOLF && (target.learned() == Mechanic.HUNTER
                    || (learnedNow != null && learnedNow.mechanic() == Mechanic.HUNTER)));
            if (poisonDead.contains(seat) && shooter) {
                cannotShoot.add(seat);
            }
        }

        // 4. 私密反馈
        for (Submission submission : submissions) {
            if (!CHECK.equals(submission.action()) || submission.targetSeat() == null) {
                continue;
            }
            SeatState target = seats.get(submission.targetSeat());
            if (submission.kind() == NightKind.SEER) {
                feedback.put(submission.actorSeat(), target != null && FACTION_WOLF.equals(target.faction()) ? "狼人" : "好人");
            } else if (submission.kind() == NightKind.PSYCHIC) {
                feedback.put(submission.actorSeat(), identityOf(target, submissions, seats));
            } else if (submission.kind() == NightKind.MECH_WOLF) {
                SeatState actor = seats.get(submission.actorSeat());
                if (actor != null && actor.learned() == Mechanic.PSYCHIC && learnedEffective(actor, dayNo)) {
                    feedback.put(submission.actorSeat(), identityOf(target, submissions, seats));
                }
            }
        }
        for (SeatState seat : seats.values()) {
            if (seat.alive() && seat.mechanic() == Mechanic.WITCH) {
                feedback.put(seat.seat(), knife == null ? "平安夜" : knife + " 号");
            }
        }

        return new Outcome(knife, List.copyOf(dead), Map.copyOf(feedback), Set.copyOf(cannotShoot),
                Set.copyOf(antidoteUsedBy), Set.copyOf(poisonUsedBy), Map.copyOf(learnedTonight), Set.copyOf(mechPoisonUsedBy));
    }

    /** 同下，不知道全场座位（只用于不涉及机械狼带刀的校验）。 */
    public static void validate(Submission submission, SeatState actor, SeatState target, JudgeRules rules, int dayNo, Integer knifeSeat) {
        validate(submission, actor, target, rules, dayNo, knifeSeat, null);
    }

    /**
     * 提交时校验，不合法抛 4000。knifeSeat 是狼队那一步关闭时定下的刀口（女巫那一步才用得到）；seats 是全场座位（判断机械狼带没带刀）。
     */
    public static void validate(Submission submission, SeatState actor, SeatState target, JudgeRules rules, int dayNo, Integer knifeSeat,
                                Map<Integer, SeatState> seats) {
        String action = submission.action();
        if (NONE.equals(action) || CONFIRM.equals(action)) {
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
            case MECH_WOLF -> validateMechWolf(action, actor, target, dayNo, seats);
            default -> {
            }
        }
    }

    private static void validateMechWolf(String action, SeatState actor, SeatState target, int dayNo, Map<Integer, SeatState> seats) {
        boolean effective = learnedEffective(actor, dayNo);
        boolean allowed;
        switch (action) {
            case LEARN -> {
                if (actor.learned() != null) {
                    throw new ApiException(4000, "本局已经学过了");
                }
                if (actor.seat() == target.seat()) {
                    throw new ApiException(4000, "不能学习自己");
                }
                allowed = true;
            }
            case CHECK -> allowed = effective && actor.learned() == Mechanic.PSYCHIC;
            case POISON -> {
                if (effective && actor.learned() == Mechanic.WITCH && !actor.mechPoisonLeft()) {
                    throw new ApiException(4000, "毒药已经用过了");
                }
                allowed = effective && actor.learned() == Mechanic.WITCH;
            }
            case GUARD -> {
                if (effective && actor.learned() == Mechanic.GUARD && Objects.equals(actor.lastGuardSeat(), target.seat())) {
                    throw new ApiException(4000, "不能连续两晚守护同一个人");
                }
                allowed = effective && actor.learned() == Mechanic.GUARD;
            }
            case KILL -> allowed = armed(seats);
            case KILL_PIERCE -> allowed = armed(seats) && effective && actor.learned() == Mechanic.WOLF;
            default -> allowed = false;
        }
        if (!allowed) {
            throw new ApiException(4000, "你现在不能这样行动");
        }
    }

    private static boolean canSelfSave(int witchSeat, Integer targetSeat, JudgeRules rules, int dayNo) {
        if (!Objects.equals(witchSeat, targetSeat)) {
            return true;
        }
        return rules != null && JudgeRules.SELF_SAVE_FIRST_NIGHT.equals(rules.witchSelfSave()) && dayNo <= 1;
    }
}
