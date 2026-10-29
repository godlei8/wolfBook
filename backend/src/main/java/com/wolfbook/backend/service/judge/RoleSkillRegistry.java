package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.support.JudgeSupportLevels;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 电子法官能自动结算的角色。登记了的角色系统负责叫醒、收集行动、结算；没登记的只播报不结算。
 * 板子的支持级别也由它算：阵容全登记且没有第三方 = full（可以开无法官），部分登记 = partial，都没登记 = manual_only。
 * 白狼王、狼美人这类带额外技能的狼一期不登记（夜里照样跟狼队一起刀人，但技能要法官手动处理），所以带它们的板子是 partial。
 */
public final class RoleSkillRegistry {

    /** 一个角色的夜间行动：步骤类型、允许提交的 actionType。 */
    public record RoleSkill(NightKind nightKind, Set<String> actions) {
    }

    /** 小程序旧版提交的中文 actionType → 现在的英文枚举。 */
    public static final Map<String, String> LEGACY_ACTIONS = Map.of(
            "刀人", "kill",
            "空刀", "none",
            "解药", "save",
            "毒药", "poison",
            "不用药", "none",
            "查验", "check",
            "守护", "guard",
            "空守", "none",
            "确认", "confirm"
    );

    private static final Map<String, RoleSkill> REGISTERED = Map.of(
            "守卫", new RoleSkill(NightKind.GUARD, Set.of("guard", "none")),
            "狼人", new RoleSkill(NightKind.WOLF, Set.of("kill", "none")),
            "女巫", new RoleSkill(NightKind.WITCH, Set.of("save", "poison", "none")),
            "预言家", new RoleSkill(NightKind.SEER, Set.of("check")),
            "猎人", new RoleSkill(NightKind.HUNTER, Set.of("confirm")),
            "白痴", new RoleSkill(NightKind.NONE, Set.of()),
            "平民", new RoleSkill(NightKind.NONE, Set.of())
    );

    /** 狼人阵营里单独睁眼、不跟狼队一起刀人的角色（和小程序 utils/judge/night-order.js 同口径）。 */
    private static final Set<String> SOLO_WOLVES = Set.of("石像鬼", "机械狼");

    private static final String FACTION_WOLF = "狼人";
    private static final String FACTION_THIRD = "第三方";

    private RoleSkillRegistry() {
    }

    public static Optional<RoleSkill> forRole(String roleName, String faction) {
        return Optional.ofNullable(roleName == null ? null : REGISTERED.get(roleName.trim()));
    }

    /** 跟狼队一起睁眼刀人的角色。 */
    public static boolean isWolfTeam(String roleName, String faction) {
        return FACTION_WOLF.equals(faction) && (roleName == null || !SOLO_WOLVES.contains(roleName.trim()));
    }

    public static String supportLevel(List<RoleEntity> lineup) {
        if (lineup == null || lineup.isEmpty()) {
            return JudgeSupportLevels.MANUAL_ONLY;
        }
        long registered = lineup.stream().filter(role -> forRole(role.getName(), role.getFaction()).isPresent()).count();
        if (registered == 0) {
            return JudgeSupportLevels.MANUAL_ONLY;
        }
        boolean hasThirdParty = lineup.stream().anyMatch(role -> FACTION_THIRD.equals(role.getFaction()));
        return registered == lineup.size() && !hasThirdParty ? JudgeSupportLevels.FULL : JudgeSupportLevels.PARTIAL;
    }
}
