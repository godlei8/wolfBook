package com.wolfbook.backend.service.judge;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 角色的技能机制：引擎认机制不认名字。后台给角色选一个机制，名字、称号、身世随便起。
 * nightPriority 是没配夜间顺序时的排序（机械狼 0 → 守卫 1 → 狼队 2 → 女巫 3 → 预言家 / 通灵师 4 → 猎人 5 确认开枪状态 → 白痴 6 空步），没有夜间行动为 null。
 * solo 是单人神职，同一板子只能有一张。shooter 是出局时能开枪带走一人的机制（猎人、狼王；被毒、被点杀不能开）。
 * selfDestruct 是白天发言时能自爆的机制（狼人、狼王；自爆不能开枪；机械狼不能）。不做成角色上的开关：选了机制就有它的技能。
 */
public enum Mechanic {
    VILLAGER("villager", "平民", "没有技能", NightKind.NONE, Set.of(), null, false, false, false, false),
    WOLF("wolf", "狼人", "和狼队一起选刀口，白天可以自爆", NightKind.WOLF, Set.of("kill", "none"), 2, true, false, false, true),
    WOLF_KING("wolf_king", "狼王", "和狼队一起刀人，可以自刀；出局时可以开枪带走一人，被毒、自爆、最后一狼不能开；白天可以自爆", NightKind.WOLF, Set.of("kill", "none"), 2, true, true, true, true),
    GUARD("guard", "守卫", "守一人，不能连守，只挡狼刀、挡不住毒", NightKind.GUARD, Set.of("guard", "none"), 1, false, true, false, false),
    WITCH("witch", "女巫", "看到刀口后可救可毒，各一瓶", NightKind.WITCH, Set.of("save", "poison", "none"), 3, false, true, false, false),
    SEER("seer", "预言家", "查验一人是好人还是狼人", NightKind.SEER, Set.of("check"), 4, false, true, false, false),
    PSYCHIC("psychic", "通灵师", "查验一人的具体身份", NightKind.PSYCHIC, Set.of("check"), 4, false, true, false, false),
    HUNTER("hunter", "猎人", "出局时开枪带走一人，被毒不能开，开枪不翻牌", NightKind.HUNTER, Set.of("confirm"), 5, false, true, true, false),
    IDIOT("idiot", "白痴", "被放逐出局时翻牌公开身份，夜里有一个空步", NightKind.IDLE, Set.of("confirm"), 6, false, true, false, false),
    MECH_WOLF("mech_wolf", "机械狼", "和狼人互不认识，每晚最先睁眼；整局学一次别人的技能，狼人全出局后带刀；不能自爆",
            NightKind.MECH_WOLF, Set.of("learn", "none", "check", "poison", "guard", "kill", "kill_pierce"), 0, false, true, false, false);

    private final String code;
    private final String label;
    private final String brief;
    private final NightKind nightKind;
    private final Set<String> actions;
    private final Integer nightPriority;
    private final boolean wolfTeam;
    private final boolean solo;
    private final boolean shooter;
    private final boolean selfDestruct;

    Mechanic(String code, String label, String brief, NightKind nightKind, Set<String> actions, Integer nightPriority,
             boolean wolfTeam, boolean solo, boolean shooter, boolean selfDestruct) {
        this.code = code;
        this.label = label;
        this.brief = brief;
        this.nightKind = nightKind;
        this.actions = actions;
        this.nightPriority = nightPriority;
        this.wolfTeam = wolfTeam;
        this.solo = solo;
        this.shooter = shooter;
        this.selfDestruct = selfDestruct;
    }

    public static Optional<Mechanic> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(mechanic -> mechanic.code.equals(normalized)).findFirst();
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String brief() {
        return brief;
    }

    public NightKind nightKind() {
        return nightKind;
    }

    public Set<String> actions() {
        return actions;
    }

    public Integer nightPriority() {
        return nightPriority;
    }

    public boolean wolfTeam() {
        return wolfTeam;
    }

    /** 出局时能开枪带走一人（被毒、被点杀除外）。 */
    public boolean canShoot() {
        return shooter;
    }

    public boolean solo() {
        return solo;
    }

    /** 白天发言时能不能自爆（狼人、狼王）。 */
    public boolean canSelfDestruct() {
        return selfDestruct;
    }
}
