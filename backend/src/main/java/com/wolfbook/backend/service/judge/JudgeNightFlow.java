package com.wolfbook.backend.service.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 夜间步骤什么时候关（惰性推进，没有定时器：任何一次轮询或提交进来时判断）。纯逻辑，读写库在 JudgeService。
 * 持有者都已出局的步骤（假步骤）照样播报、照样等满这一步的时长：活人的单人步骤也一定等满，时长一样才听不出谁死了。
 */
public final class JudgeNightFlow {

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
}
