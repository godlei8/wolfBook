package com.wolfbook.backend.service.judge;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 夜里这一步该谁行动、能做什么（纯逻辑，有单测；读写库在 JudgeService）。
 * 持有者：活着的、角色在这一步里的玩家；狼人那一步另加狼队全出局后带刀的机械狼（用户给定规则：刀口在狼人那一步选）。
 */
public final class NightStepRules {

    /** 座位上和夜间步骤有关的信息。mechanic 为空是没设机制的角色（只播报）。 */
    public record Seat(int seatNo, Integer roleId, boolean alive, Mechanic mechanic) {
    }

    /** 能不能这样行动：OK；这个角色不能这样行动；带刀机械狼在自己那一步出普通刀（要到狼人那一步）。 */
    public enum Verdict {
        OK, NOT_ALLOWED, KNIFE_IN_WOLF_STEP
    }

    private static final Set<String> WOLF_STEP_ACTIONS = Set.of(NightResolver.KILL, NightResolver.NONE);

    private NightStepRules() {
    }

    /** 这一步活着的持有者座位（升序）。armed：狼队已全出局、机械狼带刀。 */
    public static List<Integer> holders(NightPlan.NightStep step, List<Seat> seats, boolean armed) {
        boolean armedMech = step.kind() == NightKind.WOLF && armed;
        return seats.stream()
                .filter(Seat::alive)
                .filter(seat -> (seat.roleId() != null && step.roleIds().contains(seat.roleId()))
                        || (armedMech && seat.mechanic() == Mechanic.MECH_WOLF))
                .map(Seat::seatNo)
                .sorted()
                .toList();
    }

    /** 持有者在这一步提交 action 合不合规矩（只看步骤和机制；目标、用药、连守这些由 NightResolver.validate 管）。 */
    public static Verdict check(NightPlan.NightStep step, Mechanic mechanic, String action) {
        if (step.kind() == NightKind.WOLF) {
            return WOLF_STEP_ACTIONS.contains(action) ? Verdict.OK : Verdict.NOT_ALLOWED;
        }
        if (mechanic == null) {
            return Verdict.OK;
        }
        if (!mechanic.actions().contains(action)) {
            return Verdict.NOT_ALLOWED;
        }
        if (mechanic == Mechanic.MECH_WOLF && Objects.equals(NightResolver.KILL, action)) {
            return Verdict.KNIFE_IN_WOLF_STEP;
        }
        return Verdict.OK;
    }
}
