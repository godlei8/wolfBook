package com.wolfbook.backend.service.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 夜间步骤什么时候关（惰性推进，没有定时器：任何一次轮询或提交进来时判断）。纯逻辑，读写库在 JudgeService。
 */
public final class JudgeNightFlow {

    private static final int DECOY_MIN_SECONDS = 15;
    private static final int DECOY_MAX_SECONDS = 25;

    private JudgeNightFlow() {
    }

    /**
     * 到截止时间就关。狼队那一步所有活着的狼都交了可以提前关（狼本来互相知道）；
     * 单人步骤交了也等到点——提前切步等于告诉全场这个人动作快慢（零场外）。
     */
    public static boolean shouldClose(NightKind kind, List<Integer> aliveHolderSeats, Set<Integer> submittedSeats,
                                      LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            return false;
        }
        if (now.isAfter(deadline)) {
            return true;
        }
        return kind == NightKind.WOLF && !aliveHolderSeats.isEmpty() && submittedSeats.containsAll(aliveHolderSeats);
    }

    /**
     * 这一步给多长时间。持有者都已出局时是假步骤：照样播报，随机等 15–25 秒，别人听不出他死了。
     * holdersAlive 为空表示这个角色还没发到谁手上（第一夜开局前），按正常时长。
     */
    public static int stepSeconds(NightPlan.NightStep step, List<Boolean> holdersAlive, Random random) {
        if (!holdersAlive.isEmpty() && holdersAlive.stream().noneMatch(Boolean.TRUE::equals)) {
            return DECOY_MIN_SECONDS + random.nextInt(DECOY_MAX_SECONDS - DECOY_MIN_SECONDS + 1);
        }
        return step.durationSeconds();
    }
}
