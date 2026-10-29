package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.entity.RoleEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSkillRegistryTests {

    @Test
    void standardRolesAreRegistered() {
        assertEquals(NightKind.WITCH, RoleSkillRegistry.forRole("女巫", "好人").orElseThrow().nightKind());
        assertEquals(NightKind.GUARD, RoleSkillRegistry.forRole("守卫", "好人").orElseThrow().nightKind());
        assertEquals(NightKind.WOLF, RoleSkillRegistry.forRole("狼人", "狼人").orElseThrow().nightKind());
        assertEquals(NightKind.NONE, RoleSkillRegistry.forRole("白痴", "好人").orElseThrow().nightKind());
        assertTrue(RoleSkillRegistry.forRole("摄梦人", "好人").isEmpty());
    }

    @Test
    void witchActionsAreSavePoisonOrNone() {
        assertEquals(java.util.Set.of("save", "poison", "none"), RoleSkillRegistry.forRole("女巫", "好人").orElseThrow().actions());
    }

    @Test
    void wolfTeamExcludesSoloWolves() {
        assertTrue(RoleSkillRegistry.isWolfTeam("白狼王", "狼人"));
        assertTrue(RoleSkillRegistry.isWolfTeam("狼人", "狼人"));
        assertFalse(RoleSkillRegistry.isWolfTeam("石像鬼", "狼人"));
        assertFalse(RoleSkillRegistry.isWolfTeam("预言家", "好人"));
    }

    @Test
    void supportLevelFollowsTheLineup() {
        assertEquals("full", RoleSkillRegistry.supportLevel(roles("狼人", "预言家", "女巫", "守卫", "平民")));
        assertEquals("partial", RoleSkillRegistry.supportLevel(roles("狼人", "预言家", "摄梦人", "平民")));
        assertEquals("manual_only", RoleSkillRegistry.supportLevel(roles("摄梦人", "爱神")));
    }

    @Test
    void wolfVariantsAndThirdPartyKeepABoardPartial() {
        assertEquals("partial", RoleSkillRegistry.supportLevel(roles("狼人", "白狼王", "预言家", "女巫", "平民")));
    }

    @Test
    void legacyChineseActionNamesMapToTheEnglishOnes() {
        assertEquals("kill", RoleSkillRegistry.LEGACY_ACTIONS.get("刀人"));
        assertEquals("none", RoleSkillRegistry.LEGACY_ACTIONS.get("空守"));
        assertEquals("check", RoleSkillRegistry.LEGACY_ACTIONS.get("查验"));
    }

    private static List<RoleEntity> roles(String... names) {
        return Arrays.stream(names).map(name -> {
            RoleEntity role = new RoleEntity();
            role.setName(name);
            role.setFaction(switch (name) {
                case "狼人", "白狼王", "石像鬼" -> "狼人";
                case "爱神" -> "第三方";
                default -> "好人";
            });
            return role;
        }).toList();
    }
}
