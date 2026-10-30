package com.wolfbook.backend.service.judge;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 白天的计时：投票（放逐 30 秒、警长 30 秒）和上警报名（20 秒）到点或全员投完就收；发言只给倒计时，到点不强制换人。
 * 纯逻辑，读写库在 JudgeService（惰性推进 tick 里调用）。
 */
public final class JudgeDayClock {

    public static final int VOTE_SECONDS = 30;
    public static final int SHERIFF_SIGNUP_SECONDS = 20;

    private JudgeDayClock() {
    }

    /** 能投票的人都投了（弃票也算投了），或者过了截止时间。没有截止时间（旧流程房间）不自动收。 */
    public static boolean shouldCloseVote(Set<Integer> eligibleSeats, Set<Integer> votedSeats, LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            return false;
        }
        return now.isAfter(deadline) || votedSeats.containsAll(eligibleSeats);
    }
}
