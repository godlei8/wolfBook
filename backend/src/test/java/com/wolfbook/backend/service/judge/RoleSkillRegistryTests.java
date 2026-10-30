package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.support.JudgeSupportLevels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSkillRegistryTests {

    @Test
    void fromCodeIgnoresCaseAndUnknown() {
        assertEquals(Optional.of(Mechanic.GUARD), Mechanic.fromCode(" Guard "));
        assertTrue(Mechanic.fromCode("demon_hunter").isEmpty());
        assertTrue(Mechanic.fromCode(null).isEmpty());
        assertTrue(Mechanic.fromCode("  ").isEmpty());
    }

    @Test
    void mechanicsCarryTheirNightBehaviour() {
        assertEquals(NightKind.WITCH, Mechanic.WITCH.nightKind());
        assertEquals(Set.of("save", "poison", "none"), Mechanic.WITCH.actions());
        assertEquals(NightKind.NONE, Mechanic.IDIOT.nightKind());
        assertTrue(Mechanic.WOLF.wolfTeam());
        assertFalse(Mechanic.WOLF.solo());
        assertTrue(Mechanic.GUARD.solo());
    }

    @Test
    void nightPriorityMatchesTheSpec() {
        assertEquals(List.of(1, 2, 3, 4), List.of(Mechanic.GUARD.nightPriority(), Mechanic.WOLF.nightPriority(),
                Mechanic.WITCH.nightPriority(), Mechanic.SEER.nightPriority()));
    }

    @Test
    void renamedRoleWithAMechanicCountsAsSupported() {
        // 角色叫「看门人」，机制是守卫：板子仍是 full
        assertEquals(JudgeSupportLevels.FULL, RoleSkillRegistry.supportLevel(List.of(
                role("看门人", "好人", "guard"), role("狼人", "狼人", "wolf"), role("村民", "好人", "villager"))));
    }

    @Test
    void roleWithoutMechanicMakesTheBoardPartial() {
        assertEquals(JudgeSupportLevels.PARTIAL, RoleSkillRegistry.supportLevel(List.of(
                role("守卫", "好人", null), role("狼人", "狼人", "wolf"))));
        assertEquals(JudgeSupportLevels.MANUAL_ONLY, RoleSkillRegistry.supportLevel(List.of(
                role("摄梦人", "好人", null), role("爱神", "第三方", null))));
    }

    @Test
    void thirdPartyKeepsABoardPartial() {
        assertEquals(JudgeSupportLevels.PARTIAL, RoleSkillRegistry.supportLevel(List.of(
                role("狼人", "狼人", "wolf"), role("情侣", "第三方", "villager"))));
    }

    @Test
    void wolfTeamFollowsTheMechanicFirst() {
        assertTrue(RoleSkillRegistry.isWolfTeam(Mechanic.WOLF, "夜行者", "狼人"));
        assertFalse(RoleSkillRegistry.isWolfTeam(Mechanic.SEER, "狼人", "狼人"));
        // 没有机制时沿用一期规则：狼人阵营里除了石像鬼、机械狼都跟狼队一起
        assertTrue(RoleSkillRegistry.isWolfTeam(null, "白狼王", "狼人"));
        assertFalse(RoleSkillRegistry.isWolfTeam(null, "石像鬼", "狼人"));
        assertFalse(RoleSkillRegistry.isWolfTeam(null, "预言家", "好人"));
    }

    @Test
    void legacyNamesMapToMechanicsForOldSeats() {
        assertEquals(Optional.of(Mechanic.GUARD), RoleSkillRegistry.legacyMechanic("守卫"));
        assertEquals(Optional.of(Mechanic.VILLAGER), RoleSkillRegistry.legacyMechanic("平民"));
        assertTrue(RoleSkillRegistry.legacyMechanic("摄梦人").isEmpty());
    }

    @Test
    void legacyChineseActionNamesMapToTheEnglishOnes() {
        assertEquals("kill", RoleSkillRegistry.LEGACY_ACTIONS.get("刀人"));
        assertEquals("none", RoleSkillRegistry.LEGACY_ACTIONS.get("空守"));
        assertEquals("check", RoleSkillRegistry.LEGACY_ACTIONS.get("查验"));
    }

    private static RoleEntity role(String name, String faction, String mechanic) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setFaction(faction);
        role.setMechanic(mechanic);
        return role;
    }
}
