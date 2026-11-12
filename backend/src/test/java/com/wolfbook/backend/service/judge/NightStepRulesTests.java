package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NightStepRulesTests {

    private static final NightPlan.NightStep WOLF_STEP = new NightPlan.NightStep(1, NightKind.WOLF, List.of(10, 11), "狼人", true, "狼人请睁眼", 60);
    private static final NightPlan.NightStep MECH_STEP = new NightPlan.NightStep(0, NightKind.MECH_WOLF, List.of(20), "机械狼", false, "机械狼请睁眼", 30);
    private static final NightPlan.NightStep GUARD_STEP = new NightPlan.NightStep(2, NightKind.GUARD, List.of(30), "守卫", false, "守卫请睁眼", 30);

    private static NightStepRules.Seat seat(int no, int roleId, boolean alive, Mechanic mechanic) {
        return new NightStepRules.Seat(no, roleId, alive, mechanic);
    }

    @Test
    void holdersAreTheAlivePlayersOfTheStepsRoles() {
        List<NightStepRules.Seat> seats = List.of(seat(1, 10, true, Mechanic.WOLF), seat(2, 10, false, Mechanic.WOLF),
                seat(3, 11, true, Mechanic.WOLF_KING), seat(4, 30, true, Mechanic.GUARD), seat(5, 20, true, Mechanic.MECH_WOLF));
        assertEquals(List.of(1, 3), NightStepRules.holders(WOLF_STEP, seats, false), "出局的狼不算");
        assertEquals(List.of(4), NightStepRules.holders(GUARD_STEP, seats, false));
        assertEquals(List.of(5), NightStepRules.holders(MECH_STEP, seats, false));
    }

    @Test
    void armedMechWolfHoldsTheWolfStep() {
        // 用户给定规则：狼人全出局后带刀的机械狼在狼人那一步选刀口
        List<NightStepRules.Seat> seats = List.of(seat(1, 10, false, Mechanic.WOLF), seat(5, 20, true, Mechanic.MECH_WOLF));
        assertEquals(List.of(5), NightStepRules.holders(WOLF_STEP, seats, true));
        assertEquals(List.of(), NightStepRules.holders(WOLF_STEP, seats, false));
    }

    @Test
    void wolfStepTakesOnlyKnivesAndOthersFollowTheirMechanic() {
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(WOLF_STEP, Mechanic.WOLF, "kill"));
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(WOLF_STEP, Mechanic.MECH_WOLF, "none"));
        assertEquals(NightStepRules.Verdict.NOT_ALLOWED, NightStepRules.check(WOLF_STEP, Mechanic.WOLF, "guard"));
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(GUARD_STEP, Mechanic.GUARD, "guard"));
        assertEquals(NightStepRules.Verdict.NOT_ALLOWED, NightStepRules.check(GUARD_STEP, Mechanic.GUARD, "poison"));
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(GUARD_STEP, null, "anything"), "没有机制的角色只播报，什么都照记");
    }

    @Test
    void mechWolfKnifesOnlyInTheWolfStep() {
        assertEquals(NightStepRules.Verdict.KNIFE_IN_WOLF_STEP, NightStepRules.check(MECH_STEP, Mechanic.MECH_WOLF, "kill"));
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(MECH_STEP, Mechanic.MECH_WOLF, "kill_pierce"));
        assertEquals(NightStepRules.Verdict.OK, NightStepRules.check(WOLF_STEP, Mechanic.MECH_WOLF, "kill"));
    }
}
