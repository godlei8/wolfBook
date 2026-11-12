package com.wolfbook.backend.service.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 夜间步骤什么时候关（惰性推进，没有定时器：任何一次轮询或提交进来时判断）。纯逻辑，读写库在 JudgeService。
 * 持有者都已出局的步骤（假步骤）照样播报、照样等满这一步的时长：活人的单人步骤也一定等满，时长一样才听不出谁死了。
 * 狼人那一步（用户给定规则）：所有存活的狼人（含带刀的机械狼）都点了确定，5 秒后结束；有人重新选择就回到原来的总时长。
 */
public final class JudgeNightFlow {

    /** 狼人全员确定后再等几秒。 */
    public static final int CONFIRM_GRACE_SECONDS = 5;

    private JudgeNightFlow() {
    }

    /** 到截止时间就关；交了也不提前切步（单人步骤提前切等于告诉全场这个人动作快慢）。 */
    public static boolean shouldClose(LocalDateTime deadline, LocalDateTime now) {
        return deadline != null && now.isAfter(deadline);
    }

    /** 狼人那一步所有存活的持有者都点了确定。 */
    public static boolean allConfirmed(List<Integer> aliveHolderSeats, Set<Integer> confirmedSeats) {
        return !aliveHolderSeats.isEmpty() && confirmedSeats.containsAll(aliveHolderSeats);
    }

    /** 全员确定后的截止时间：5 秒后，但不晚于原来的总时长。 */
    public static LocalDateTime confirmedDeadline(LocalDateTime fullDeadline, LocalDateTime now) {
        LocalDateTime grace = now.plusSeconds(CONFIRM_GRACE_SECONDS);
        return fullDeadline != null && fullDeadline.isBefore(grace) ? fullDeadline : grace;
    }
}
