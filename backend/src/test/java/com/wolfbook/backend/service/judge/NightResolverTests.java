package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.JudgeRules;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 座位：1 预言家、2 女巫、3 猎人、4 守卫、5 狼人、6 狼人、7 平民、8 平民。
 */
class NightResolverTests {

    private static final JudgeRules RULES = JudgeRules.defaults();
    private static final Random RANDOM = new Random(1);

    @Test
    void majorityKnifeWins() {
        assertEquals(3, NightResolver.tallyKnife(List.of(3, 3, 7), RANDOM));
    }

    @Test
    void tiedKnifeIsRandomAmongTied() {
        // 相邻的小种子第一次 nextInt(2) 结果一样，所以用同一个随机源连抽多次
        Random random = new Random(42);
        Set<Integer> outcomes = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            outcomes.add(NightResolver.tallyKnife(List.of(3, 7), random));
        }
        assertEquals(Set.of(3, 7), outcomes);
    }

    @Test
    void noPicksMeansNoKnife() {
        assertNull(NightResolver.tallyKnife(List.of(), RANDOM));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(), seats(), RULES, 1, RANDOM);
        assertNull(outcome.knifeSeat());
        assertTrue(outcome.deadSeats().isEmpty());
    }

    @Test
    void guardAndSaveOnTheSameSeatKillsHim() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(
                kill(5, 7), kill(6, 7), guard(4, 7), save(2, 7)
        ), seats(), RULES, 1, RANDOM);
        assertEquals(List.of(7), outcome.deadSeats());
    }

    @Test
    void guardAloneSaves() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 7), guard(4, 7)), seats(), RULES, 1, RANDOM);
        assertTrue(outcome.deadSeats().isEmpty());
    }

    @Test
    void saveAloneSaves() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 7), save(2, 7)), seats(), RULES, 1, RANDOM);
        assertTrue(outcome.deadSeats().isEmpty());
        assertEquals(Set.of(2), outcome.antidoteUsedBy());
    }

    @Test
    void poisonIgnoresGuard() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(guard(4, 8), poison(2, 8)), seats(), RULES, 2, RANDOM);
        assertEquals(List.of(8), outcome.deadSeats());
        assertEquals(Set.of(2), outcome.poisonUsedBy());
    }

    @Test
    void knifeAndPoisonKillTwoInSeatOrder() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 8), poison(2, 7)), seats(), RULES, 2, RANDOM);
        assertEquals(List.of(7, 8), outcome.deadSeats());
    }

    @Test
    void witchCannotSaveHerselfAfterFirstNight() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        ApiException error = assertThrows(ApiException.class,
                () -> NightResolver.validate(save(2, 2), seats.get(2), seats.get(2), RULES, 2, 2));
        assertEquals(4000, error.getCode());
    }

    @Test
    void witchMaySaveHerselfOnNightOneWhenRuleAllows() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        NightResolver.validate(save(2, 2), seats.get(2), seats.get(2), RULES, 1, 2);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 2), save(2, 2)), seats, RULES, 1, RANDOM);
        assertTrue(outcome.deadSeats().isEmpty());
    }

    @Test
    void witchNeverSelfSaveRuleBlocksNightOneToo() {
        JudgeRules never = new JudgeRules("side", true, JudgeRules.SELF_SAVE_NEVER, true, 120, 150);
        Map<Integer, NightResolver.SeatState> seats = seats();
        assertThrows(ApiException.class, () -> NightResolver.validate(save(2, 2), seats.get(2), seats.get(2), never, 1, 2));
    }

    @Test
    void witchCanOnlySaveTheKnifedSeat() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        assertThrows(ApiException.class, () -> NightResolver.validate(save(2, 8), seats.get(2), seats.get(8), RULES, 1, 7));
    }

    @Test
    void usedPotionCannotBeUsedAgain() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        NightResolver.SeatState emptyWitch = new NightResolver.SeatState(2, "女巫", "好人", mechanicOf("女巫"), true, false, false, null);
        assertThrows(ApiException.class, () -> NightResolver.validate(save(2, 7), emptyWitch, seats.get(7), RULES, 2, 7));
        assertThrows(ApiException.class, () -> NightResolver.validate(poison(2, 7), emptyWitch, seats.get(7), RULES, 2, 7));
    }

    @Test
    void poisonedHunterCannotShootEvenIfAlsoKnifed() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 3), poison(2, 3)), seats(), RULES, 2, RANDOM);
        assertEquals(List.of(3), outcome.deadSeats());
        assertEquals(Set.of(3), outcome.cannotShoot());
    }

    @Test
    void knifedHunterCanStillShoot() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 3)), seats(), RULES, 2, RANDOM);
        assertTrue(outcome.cannotShoot().isEmpty());
    }

    @Test
    void seerFeedbackNamesTheCamp() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(check(1, 5), kill(5, 7)), seats(), RULES, 1, RANDOM);
        assertEquals("狼人", outcome.feedback().get(1));
        NightResolver.Outcome good = NightResolver.resolve(List.of(check(1, 7)), seats(), RULES, 1, RANDOM);
        assertEquals("好人", good.feedback().get(1));
    }

    @Test
    void witchFeedbackNamesTheKnife() {
        assertEquals("7 号", NightResolver.resolve(List.of(kill(5, 7)), seats(), RULES, 1, RANDOM).feedback().get(2));
        assertEquals("平安夜", NightResolver.resolve(List.of(), seats(), RULES, 1, RANDOM).feedback().get(2));
    }

    @Test
    void consecutiveGuardIsRejected() {
        NightResolver.SeatState guard = new NightResolver.SeatState(4, "守卫", "好人", mechanicOf("守卫"), true, false, false, 5);
        assertThrows(ApiException.class, () -> NightResolver.validate(guard(4, 5), guard, seats().get(5), RULES, 2, null));
    }

    @Test
    void guardMayGuardHimself() {
        NightResolver.validate(guard(4, 4), seats().get(4), seats().get(4), RULES, 1, null);
    }

    @Test
    void deadTargetIsRejected() {
        NightResolver.SeatState dead = new NightResolver.SeatState(7, "平民", "好人", mechanicOf("平民"), false, false, false, null);
        assertThrows(ApiException.class, () -> NightResolver.validate(check(1, 7), seats().get(1), dead, RULES, 2, null));
    }

    @Test
    void wolvesMayKnifeAWolf() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 6), kill(6, 6)), seats(), RULES, 1, RANDOM);
        assertEquals(List.of(6), outcome.deadSeats());
    }

    private static Map<Integer, NightResolver.SeatState> seats() {
        Map<Integer, NightResolver.SeatState> seats = new LinkedHashMap<>();
        String[][] roles = {{"预言家", "好人"}, {"女巫", "好人"}, {"猎人", "好人"}, {"守卫", "好人"},
                {"狼人", "狼人"}, {"狼人", "狼人"}, {"平民", "好人"}, {"平民", "好人"}};
        for (int i = 0; i < roles.length; i++) {
            boolean witch = "女巫".equals(roles[i][0]);
            seats.put(i + 1, new NightResolver.SeatState(i + 1, roles[i][0], roles[i][1], mechanicOf(roles[i][0]), true, witch, witch, null));
        }
        return seats;
    }

    @Test
    void poisonedHunterIsFoundByMechanic() {
        // 3 号叫「枪手」，名字不在一期的表里，机制是猎人：被毒照样不能开枪
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(3, new NightResolver.SeatState(3, "枪手", "好人", Mechanic.HUNTER, true, false, false, null));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(poison(2, 3)), seats, RULES, 2, RANDOM);
        assertEquals(Set.of(3), outcome.cannotShoot());
    }

    @Test
    void witchFeedbackFollowsTheMechanic() {
        // 2 号叫「配药人」：照样收到刀口
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(2, new NightResolver.SeatState(2, "配药人", "好人", Mechanic.WITCH, true, true, true, null));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 7), kill(6, 7)), seats, RULES, 1, RANDOM);
        assertEquals("7 号", outcome.feedback().get(2));
    }

    private static Mechanic mechanicOf(String roleName) {
        return RoleSkillRegistry.legacyMechanic(roleName).orElse(null);
    }

    private static NightResolver.Submission kill(int actor, int target) {
        return new NightResolver.Submission(actor, NightKind.WOLF, "kill", target);
    }

    private static NightResolver.Submission guard(int actor, int target) {
        return new NightResolver.Submission(actor, NightKind.GUARD, "guard", target);
    }

    private static NightResolver.Submission save(int actor, int target) {
        return new NightResolver.Submission(actor, NightKind.WITCH, "save", target);
    }

    private static NightResolver.Submission poison(int actor, int target) {
        return new NightResolver.Submission(actor, NightKind.WITCH, "poison", target);
    }

    private static NightResolver.Submission check(int actor, int target) {
        return new NightResolver.Submission(actor, NightKind.SEER, "check", target);
    }
}
