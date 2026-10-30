package com.wolfbook.backend.service.judge;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 角色的技能机制：引擎认机制不认名字。后台给角色选一个机制，名字、称号、身世随便起。
 * nightPriority 是没配夜间顺序时的排序（守卫 1 → 狼队 2 → 女巫 3 → 预言家 4 → 猎人 5 确认开枪状态），没有夜间行动为 null。
 * solo 是单人神职，同一板子只能有一张。
 */
public enum Mechanic {
    VILLAGER("villager", "平民", "没有技能", NightKind.NONE, Set.of(), null, false, false),
    WOLF("wolf", "狼人", "和狼队一起选刀口", NightKind.WOLF, Set.of("kill", "none"), 2, true, false),
    GUARD("guard", "守卫", "守一人，不能连守，守不住毒", NightKind.GUARD, Set.of("guard", "none"), 1, false, true),
    WITCH("witch", "女巫", "看到刀口后可救可毒，各一瓶", NightKind.WITCH, Set.of("save", "poison", "none"), 3, false, true),
    SEER("seer", "预言家", "查验一人是好人还是狼人", NightKind.SEER, Set.of("check"), 4, false, true),
    HUNTER("hunter", "猎人", "出局时开枪带走一人，被毒不能开", NightKind.HUNTER, Set.of("confirm"), 5, false, true),
    IDIOT("idiot", "白痴", "被放逐时翻牌免死，之后不能投票", NightKind.NONE, Set.of(), null, false, true);

    private final String code;
    private final String label;
    private final String brief;
    private final NightKind nightKind;
    private final Set<String> actions;
    private final Integer nightPriority;
    private final boolean wolfTeam;
    private final boolean solo;

    Mechanic(String code, String label, String brief, NightKind nightKind, Set<String> actions, Integer nightPriority,
             boolean wolfTeam, boolean solo) {
        this.code = code;
        this.label = label;
        this.brief = brief;
        this.nightKind = nightKind;
        this.actions = actions;
        this.nightPriority = nightPriority;
        this.wolfTeam = wolfTeam;
        this.solo = solo;
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

    public boolean solo() {
        return solo;
    }
}
