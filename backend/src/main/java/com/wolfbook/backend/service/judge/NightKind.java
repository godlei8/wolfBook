package com.wolfbook.backend.service.judge;

/** 夜间一步的行动类型：决定这一步给谁什么按钮、怎么结算。 */
public enum NightKind {
    GUARD,
    WOLF,
    WITCH,
    SEER,
    HUNTER,
    /** 没有夜间行动（平民、白痴）。 */
    NONE,
    /** 未登记的角色：系统只播报、给时间，不结算。 */
    GENERIC
}
