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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void guardBlocksTheWitchsPoison() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(guard(4, 8), poison(2, 8)), seats(), RULES, 2, RANDOM);
        assertTrue(outcome.deadSeats().isEmpty());
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
        JudgeRules firstNight = new JudgeRules("side", true, JudgeRules.SELF_SAVE_FIRST_NIGHT, true, 120, 150).withDefaults();
        Map<Integer, NightResolver.SeatState> seats = seats();
        NightResolver.validate(save(2, 2), seats.get(2), seats.get(2), firstNight, 1, 2);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 2), save(2, 2)), seats, firstNight, 1, RANDOM);
        assertTrue(outcome.deadSeats().isEmpty());
    }

    @Test
    void witchCannotSaveHerselfByDefaultEvenOnNightOne() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        ApiException error = assertThrows(ApiException.class,
                () -> NightResolver.validate(save(2, 2), seats.get(2), seats.get(2), RULES, 1, 2));
        assertEquals(4000, error.getCode());
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
    void poisonedWolfKingCannotShoot() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(6, new NightResolver.SeatState(6, "黑狼王", "狼人", Mechanic.WOLF_KING, true, false, false, null));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(poison(2, 6)), seats, RULES, 2, RANDOM);
        assertEquals(Set.of(6), outcome.cannotShoot());
    }

    @Test
    void knifedWolfKingCannotShoot() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(6, new NightResolver.SeatState(6, "黑狼王", "狼人", Mechanic.WOLF_KING, true, false, false, null));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 6)), seats, RULES, 2, RANDOM);
        assertTrue(outcome.deadSeats().contains(6));
        assertEquals(Set.of(6), outcome.cannotShoot());
    }

    @Test
    void wolfKingCannotKnifeHimself() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(6, new NightResolver.SeatState(6, "黑狼王", "狼人", Mechanic.WOLF_KING, true, false, false, null));
        ApiException error = assertThrows(ApiException.class,
                () -> NightResolver.validate(kill(6, 6), seats.get(6), seats.get(6), RULES, 1, null, seats));
        assertEquals("狼王不能自刀", error.getMessage());
    }

    @Test
    void plainWolfMayStillKnifeHimself() {
        Map<Integer, NightResolver.SeatState> seats = seats();
        NightResolver.validate(kill(5, 5), seats.get(5), seats.get(5), RULES, 1, null, seats);
    }

    @Test
    void witchFeedbackFollowsTheMechanic() {
        // 2 号叫「配药人」：照样收到刀口
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(2, new NightResolver.SeatState(2, "配药人", "好人", Mechanic.WITCH, true, true, true, null));
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(kill(5, 7), kill(6, 7)), seats, RULES, 1, RANDOM);
        assertEquals("7 号", outcome.feedback().get(2));
    }

    // ---------- 通灵师、机械狼（08）。座位：1 通灵师、2 女巫、3 猎人、4 守卫、5 狼人、6 机械狼、7 / 8 平民 ----------

    private static Map<Integer, NightResolver.SeatState> seats08(Mechanic learned, String learnedRole, Integer learnedDay, boolean wolfAlive) {
        Map<Integer, NightResolver.SeatState> seats = seats();
        seats.put(1, new NightResolver.SeatState(1, "通灵师", "好人", Mechanic.PSYCHIC, true, false, false, null));
        seats.put(5, new NightResolver.SeatState(5, "狼人", "狼人", Mechanic.WOLF, wolfAlive, false, false, null));
        seats.put(6, mech(learned, learnedRole, learnedDay, true, null));
        return seats;
    }

    private static NightResolver.SeatState mech(Mechanic learned, String learnedRole, Integer learnedDay, boolean poisonLeft, Integer lastGuard) {
        return new NightResolver.SeatState(6, "机械狼", "狼人", Mechanic.MECH_WOLF, true, false, false, lastGuard,
                learned, learnedRole, learnedDay, poisonLeft);
    }

    private static NightResolver.Submission mechAct(String action, Integer target) {
        return new NightResolver.Submission(6, NightKind.MECH_WOLF, action, target);
    }

    private static NightResolver.Submission psychicCheck(int target) {
        return new NightResolver.Submission(1, NightKind.PSYCHIC, "check", target);
    }

    private static final JudgeRules BOUNCE = new JudgeRules(null, null, null, null, null, null, null, null, true).withDefaults();

    @Test
    void psychicSeesTheExactRole() {
        Map<Integer, NightResolver.SeatState> seats = seats08(null, null, null, true);
        assertEquals("狼人", NightResolver.resolve(List.of(psychicCheck(5)), seats, RULES, 1, RANDOM).feedback().get(1));
        assertEquals("女巫", NightResolver.resolve(List.of(psychicCheck(2)), seats, RULES, 1, RANDOM).feedback().get(1));
    }

    @Test
    void psychicSeesAnUnlearnedMechWolfAsItself() {
        assertEquals("机械狼", NightResolver.resolve(List.of(psychicCheck(6)), seats08(null, null, null, true), RULES, 1, RANDOM).feedback().get(1));
    }

    @Test
    void psychicSeesWhatTheMechWolfLearnedEvenTonight() {
        Map<Integer, NightResolver.SeatState> seats = seats08(null, null, null, true);
        assertEquals("女巫", NightResolver.resolve(List.of(mechAct("learn", 2), psychicCheck(6)), seats, RULES, 1, RANDOM).feedback().get(1));
        assertEquals("守卫", NightResolver.resolve(List.of(psychicCheck(6)), seats08(Mechanic.GUARD, "守卫", 1, true), RULES, 3, RANDOM).feedback().get(1));
    }

    @Test
    void mechWolfLearningGetsTheTargetsRole() {
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("learn", 2)), seats08(null, null, null, true), RULES, 1, RANDOM);
        assertEquals("2号是女巫", outcome.feedback().get(6));
        assertEquals(new NightResolver.Learned(Mechanic.WITCH, "女巫"), outcome.learnedTonight().get(6));
    }

    @Test
    void learningAWolfKingOrAnIdiotMapsToWolfOrVillager() {
        assertEquals(Mechanic.WOLF, NightResolver.learnedMechanicOf(new NightResolver.SeatState(9, "黑狼王", "狼人", Mechanic.WOLF_KING, true, false, false, null)));
        assertEquals(Mechanic.VILLAGER, NightResolver.learnedMechanicOf(new NightResolver.SeatState(9, "白痴", "好人", Mechanic.IDIOT, true, false, false, null)));
        assertEquals(Mechanic.VILLAGER, NightResolver.learnedMechanicOf(new NightResolver.SeatState(9, "魔术师", "好人", null, true, false, false, null)));
        assertEquals(Mechanic.GUARD, NightResolver.learnedMechanicOf(new NightResolver.SeatState(9, "守卫", "好人", Mechanic.GUARD, true, false, false, null)));
    }

    @Test
    void mechWolfSkillsStartTheNextNight() {
        Map<Integer, NightResolver.SeatState> sameNight = seats08(Mechanic.WITCH, "女巫", 1, true);
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("poison", 7), sameNight.get(6), sameNight.get(7), RULES, 1, null, sameNight));
        assertTrue(NightResolver.resolve(List.of(mechAct("poison", 7)), sameNight, RULES, 1, RANDOM).deadSeats().isEmpty());
        Map<Integer, NightResolver.SeatState> next = seats08(Mechanic.WITCH, "女巫", 1, true);
        NightResolver.validate(mechAct("poison", 7), next.get(6), next.get(7), RULES, 2, null, next);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("poison", 7)), next, RULES, 2, RANDOM);
        assertEquals(List.of(7), outcome.deadSeats());
        assertEquals(Set.of(6), outcome.mechPoisonUsedBy());
    }

    @Test
    void mechPoisonIgnoresGuardsAndTheWitch() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WITCH, "女巫", 1, true);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(guard(4, 3), mechAct("poison", 3)), seats, RULES, 2, RANDOM);
        assertEquals(List.of(3), outcome.deadSeats());
        assertEquals(Set.of(3), outcome.cannotShoot(), "被机械狼毒死的猎人不能开枪");
        assertEquals("平安夜", outcome.feedback().get(2), "女巫看不到机械狼的毒口");
    }

    @Test
    void mechPoisonAndKnifeOnTheSameSeatKillOnce() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WITCH, "女巫", 1, true);
        assertEquals(List.of(7), NightResolver.resolve(List.of(kill(5, 7), mechAct("poison", 7)), seats, RULES, 2, RANDOM).deadSeats());
    }

    @Test
    void usedMechPoisonIsRejected() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WITCH, "女巫", 1, true);
        seats.put(6, mech(Mechanic.WITCH, "女巫", 1, false, null));
        ApiException error = assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("poison", 7), seats.get(6), seats.get(7), RULES, 3, null, seats));
        assertEquals("毒药已经用过了", error.getMessage());
    }

    @Test
    void mechGuardBlocksKnifeAndPoison() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        assertTrue(NightResolver.resolve(List.of(kill(5, 7), mechAct("guard", 7)), seats, RULES, 2, RANDOM).deadSeats().isEmpty());
        NightResolver.Outcome poisoned = NightResolver.resolve(List.of(mechAct("guard", 7), poison(2, 7)), seats, RULES, 2, RANDOM);
        assertTrue(poisoned.deadSeats().isEmpty(), "弹毒关：正常挡毒，女巫也不死");
    }

    @Test
    void bounceSendsThePoisonBackToTheWitch() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("guard", 7), poison(2, 7)), seats, BOUNCE, 2, RANDOM);
        assertEquals(List.of(2), outcome.deadSeats());
        assertEquals(Set.of(2), outcome.poisonUsedBy());
    }

    @Test
    void bounceFiresEvenWhenTheRealGuardAlsoGuards() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(guard(4, 7), mechAct("guard", 7), poison(2, 7)), seats, BOUNCE, 2, RANDOM);
        assertEquals(List.of(2), outcome.deadSeats());
    }

    @Test
    void bouncedPoisonIsNotBlockedByTheGuard() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(guard(4, 2), mechAct("guard", 7), poison(2, 7)), seats, BOUNCE, 2, RANDOM);
        assertEquals(List.of(2), outcome.deadSeats());
    }

    @Test
    void bothGuardsOnTheSameSeatStillSave() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        assertTrue(NightResolver.resolve(List.of(kill(5, 7), guard(4, 7), mechAct("guard", 7)), seats, RULES, 2, RANDOM).deadSeats().isEmpty());
        assertEquals(List.of(7), NightResolver.resolve(List.of(kill(5, 7), guard(4, 7), mechAct("guard", 7), save(2, 7)), seats, RULES, 2, RANDOM).deadSeats());
        assertEquals(List.of(7), NightResolver.resolve(List.of(kill(5, 7), mechAct("guard", 7), save(2, 7)), seats, RULES, 2, RANDOM).deadSeats(),
                "被机械狼守的人又被救：同守同救照死");
    }

    @Test
    void mechGuardCannotGuardTheSameSeatTwice() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.GUARD, "守卫", 1, true);
        seats.put(6, mech(Mechanic.GUARD, "守卫", 1, true, 7));
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("guard", 7), seats.get(6), seats.get(7), RULES, 3, null, seats));
    }

    @Test
    void mechWolfIsNotArmedWhileAWolfLives() {
        Map<Integer, NightResolver.SeatState> seats = seats08(null, null, null, true);
        assertFalse(NightResolver.armed(seats));
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("kill", 7), seats.get(6), seats.get(7), RULES, 2, null, seats));
        assertTrue(NightResolver.armed(seats08(null, null, null, false)));
    }

    @Test
    void armedMechWolfsKillIsTheKnife() {
        Map<Integer, NightResolver.SeatState> seats = seats08(null, null, null, false);
        NightResolver.validate(mechAct("kill", 7), seats.get(6), seats.get(7), RULES, 2, null, seats);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("kill", 7)), seats, RULES, 2, RANDOM);
        assertEquals(7, outcome.knifeSeat());
        assertEquals(List.of(7), outcome.deadSeats());
        assertEquals("7 号", outcome.feedback().get(2));
    }

    @Test
    void pierceIgnoresEveryGuard() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WOLF, "狼人", 1, false);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("kill_pierce", 7), guard(4, 7)), seats, RULES, 2, RANDOM);
        assertEquals(List.of(7), outcome.deadSeats());
        assertNull(outcome.knifeSeat(), "女巫看不到破盾刀");
    }

    @Test
    void pierceAndKnifeOnTheSameSeatConsumeTheAntidote() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WOLF, "狼人", 1, false);
        NightResolver.Outcome outcome = NightResolver.resolve(List.of(mechAct("kill", 7), mechAct("kill_pierce", 7), save(2, 7)), seats, RULES, 2, RANDOM);
        assertEquals(List.of(7), outcome.deadSeats());
        assertEquals(Set.of(2), outcome.antidoteUsedBy());
    }

    @Test
    void twoKnivesOnTwoSeatsKillBoth() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.WOLF, "狼人", 1, false);
        assertEquals(List.of(7, 8), NightResolver.resolve(List.of(mechAct("kill", 7), mechAct("kill_pierce", 8)), seats, RULES, 2, RANDOM).deadSeats());
    }

    @Test
    void pierceNeedsTheWolfSkillAndArms() {
        Map<Integer, NightResolver.SeatState> notLearned = seats08(null, null, null, false);
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("kill_pierce", 7), notLearned.get(6), notLearned.get(7), RULES, 2, null, notLearned));
        Map<Integer, NightResolver.SeatState> wolfAlive = seats08(Mechanic.WOLF, "狼人", 1, true);
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("kill_pierce", 7), wolfAlive.get(6), wolfAlive.get(7), RULES, 2, null, wolfAlive));
        Map<Integer, NightResolver.SeatState> learnedTonight = seats08(Mechanic.WOLF, "狼人", 2, false);
        assertThrows(ApiException.class, () -> NightResolver.validate(mechAct("kill_pierce", 7), learnedTonight.get(6), learnedTonight.get(7), RULES, 2, null, learnedTonight),
                "学狼人当晚仍只有一刀");
    }

    @Test
    void mechWolfCannotLearnTwiceOrItself() {
        Map<Integer, NightResolver.SeatState> learned = seats08(Mechanic.WITCH, "女巫", 1, true);
        assertEquals("本局已经学过了", assertThrows(ApiException.class,
                () -> NightResolver.validate(mechAct("learn", 7), learned.get(6), learned.get(7), RULES, 2, null, learned)).getMessage());
        Map<Integer, NightResolver.SeatState> fresh = seats08(null, null, null, true);
        assertEquals("不能学习自己", assertThrows(ApiException.class,
                () -> NightResolver.validate(mechAct("learn", 6), fresh.get(6), fresh.get(6), RULES, 1, null, fresh)).getMessage());
    }

    @Test
    void mechWolfChecksOnceItLearnedThePsychic() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.PSYCHIC, "通灵师", 1, true);
        NightResolver.validate(mechAct("check", 3), seats.get(6), seats.get(3), RULES, 2, null, seats);
        assertEquals("猎人", NightResolver.resolve(List.of(mechAct("check", 3)), seats, RULES, 2, RANDOM).feedback().get(6));
    }

    @Test
    void learnedHunterCannotShootWhenPoisoned() {
        Map<Integer, NightResolver.SeatState> seats = seats08(Mechanic.HUNTER, "猎人", 1, true);
        assertEquals(Set.of(6), NightResolver.resolve(List.of(poison(2, 6)), seats, RULES, 2, RANDOM).cannotShoot());
        assertTrue(NightResolver.resolve(List.of(kill(5, 6)), seats, RULES, 2, RANDOM).cannotShoot().isEmpty());
    }

    @Test
    void mechWolfLearningTheHunterAndPoisonedTheSameNightCannotShoot() {
        Map<Integer, NightResolver.SeatState> seats = seats08(null, null, null, true);
        assertEquals(Set.of(6), NightResolver.resolve(List.of(mechAct("learn", 3), poison(2, 6)), seats, RULES, 1, RANDOM).cannotShoot());
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
