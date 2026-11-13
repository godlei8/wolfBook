package com.wolfbook.backend.service.judge;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 白天的计时：投票（放逐 30 秒、警长 30 秒）和上警报名（20 秒）到点或全员投完就收；发言只给倒计时，到点不强制换人。
 * 纯逻辑，读写库在 JudgeService（惰性推进 tick 里调用）。
 */
public final class JudgeDayClock {

    public static final int VOTE_SECONDS = 30;
    /** 公布死讯（死者的技能、遗言处理完）后停多久自动开始发言 / 天黑（用户给定 5 秒）。 */
    public static final int ANNOUNCE_SECONDS = 5;
    /** 有警长时给警长选发言顺序的时间，到点开始发言，没选系统随机警左 / 警右。 */
    public static final int SHERIFF_ORDER_SECONDS = 15;
    /** 票型公布停多久自动进入下一步（用户给定 10 秒）。 */
    public static final int RESULT_SECONDS = 10;
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
