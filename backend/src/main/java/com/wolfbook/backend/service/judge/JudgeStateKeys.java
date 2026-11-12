package com.wolfbook.backend.service.judge;

import java.util.Set;

/**
 * 房间 phase_data 和座位 status_json 里允许出现的键（一处登记，集成测试每跑完一条用例都会核对库里没有登记以外的键，
 * 写错键名、忘了登记新键都会被拦住）。加机制时新增的键先在这里登记并写清含义。
 */
public final class JudgeStateKeys {

    /**
     * 房间 phase_data：
     * pending 出局待办列表；pendingDeaths / nightDeaths 待公布的死者；knifeDecided / knifeSeat 狼人那一步定下的刀口；
     * wolfFullDeadline 狼人全员确定后原来的总时长；resumeDeadline / resumePhase 待办处理完回到哪；pausedAt 暂停时刻；
     * speech 发言顺序和当前发言人；sheriffOrder 警长定的发言方向；pkSeats / resolvedFrom 平票 PK；
     * nightNext / nightAfterPending 自爆后直接天黑；verdictReason 胜负原因；swapRequests 大厅换座申请。
     */
    public static final Set<String> PHASE_DATA = Set.of(
            "pending", "pendingDeaths", "nightDeaths", "knifeDecided", "knifeSeat", "wolfFullDeadline",
            "resumeDeadline", "resumePhase", "pausedAt", "speech", "sheriffOrder", "pkSeats", "resolvedFrom",
            "nightNext", "nightAfterPending", "verdictReason", "swapRequests");

    /**
     * 座位 status_json：antidote / poison 女巫的药；lastGuardSeat 上一夜守的人（守卫、学到守卫的机械狼）；
     * canShoot 出局时能不能开枪；mechLearned / mechLearnedRole / mechLearnedDay / mechPoison 机械狼学到的技能。
     */
    public static final Set<String> SEAT_STATUS = Set.of(
            "antidote", "poison", "lastGuardSeat", "canShoot", "mechLearned", "mechLearnedRole", "mechLearnedDay", "mechPoison");

    private JudgeStateKeys() {
    }
}
