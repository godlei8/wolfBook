package com.wolfbook.backend.service.judge;

/** 夜间一步的行动类型：决定这一步给谁什么按钮、怎么结算。 */
public enum NightKind {
    GUARD,
    WOLF,
    WITCH,
    SEER,
    HUNTER,
    /** 通灵师：查验一人的具体身份。 */
    PSYCHIC,
    /** 机械狼自己的一步：学习、学到的技能、带刀后的刀口。 */
    MECH_WOLF,
    /** 空步：只播报、等 5 秒（白痴）。 */
    IDLE,
    /** 没有夜间行动（平民）。 */
    NONE,
    /** 未登记的角色：系统只播报、给时间，不结算。 */
    GENERIC
}
