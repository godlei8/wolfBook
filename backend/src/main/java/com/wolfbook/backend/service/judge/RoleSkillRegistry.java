package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.support.JudgeSupportLevels;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 电子法官认得的角色：按角色的 mechanic（技能机制）登记，不看名字。有机制的角色系统负责叫醒、收集行动、结算；没有的只播报不结算。
 * 板子的支持级别也由它算：阵容全有机制且没有第三方 = full（可以开无法官），部分有 = partial，都没有 = manual_only。
 * 按名字的映射只留给升级前已经开局的座位兜底（那时座位上还没存机制）。
 */
public final class RoleSkillRegistry {

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

    /** 一期按名字登记的角色，只给座位上没有机制的旧数据兜底。 */
    private static final Map<String, Mechanic> LEGACY_NAMES = Map.of(
            "守卫", Mechanic.GUARD,
            "狼人", Mechanic.WOLF,
            "女巫", Mechanic.WITCH,
            "预言家", Mechanic.SEER,
            "猎人", Mechanic.HUNTER,
            "白痴", Mechanic.IDIOT,
            "平民", Mechanic.VILLAGER
    );

    /** 狼人阵营里单独睁眼、不跟狼队一起刀人的角色（没有机制时用，和小程序 utils/judge/night-order.js 同口径）。 */
    private static final Set<String> SOLO_WOLVES = Set.of("石像鬼", "机械狼");

    private static final String FACTION_WOLF = "狼人";
    private static final String FACTION_THIRD = "第三方";

    private RoleSkillRegistry() {
    }

    public static Optional<Mechanic> legacyMechanic(String roleName) {
        return Optional.ofNullable(roleName == null ? null : LEGACY_NAMES.get(roleName.trim()));
    }

    /** 跟狼队一起睁眼刀人的角色：有机制看机制，没有机制按阵营（排除石像鬼、机械狼）。 */
    public static boolean isWolfTeam(Mechanic mechanic, String roleName, String faction) {
        if (mechanic != null) {
            return mechanic.wolfTeam();
        }
        return FACTION_WOLF.equals(faction) && (roleName == null || !SOLO_WOLVES.contains(roleName.trim()));
    }

    public static String supportLevel(List<RoleEntity> lineup) {
        if (lineup == null || lineup.isEmpty()) {
            return JudgeSupportLevels.MANUAL_ONLY;
        }
        long known = lineup.stream().filter(role -> Mechanic.fromCode(role.getMechanic()).isPresent()).count();
        if (known == 0) {
            return JudgeSupportLevels.MANUAL_ONLY;
        }
        boolean hasThirdParty = lineup.stream().anyMatch(role -> FACTION_THIRD.equals(role.getFaction()));
        return known == lineup.size() && !hasThirdParty ? JudgeSupportLevels.FULL : JudgeSupportLevels.PARTIAL;
    }
}
