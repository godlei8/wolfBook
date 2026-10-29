package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.BoardRuleDetail;
import com.wolfbook.backend.entity.RoleEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 夜里按什么顺序叫谁：板子配了 ruleDetail.nightOrder 就按它走，没配按通用顺序从阵容里挑。
 * 狼队合并成一步；没有夜间行动的角色（平民、白痴）和阵容里没有的角色都不叫；没登记的角色只播报不结算。
 * 小程序 utils/judge/night-order.js 是同一套规则，板子详情页还在用它展示顺序。
 */
public final class NightPlan {

    /** 通用夜间顺序（板子没配 nightOrder 时）。 */
    static final List<String> DEFAULT_ORDER = List.of("守卫", "狼人", "女巫", "预言家", "猎人");

    private static final int WOLF_FIRST_NIGHT_SECONDS = 90;
    private static final int WOLF_SECONDS = 60;
    private static final int ROLE_SECONDS = 30;
    private static final int HUNTER_SECONDS = 10;
    private static final int GENERIC_SECONDS = 25;

    /**
     * 一步：index 从 0 开始；team 表示整个狼队一起睁眼（roleIds 是阵容里所有狼队角色）。
     */
    public record NightStep(int index, NightKind kind, List<Integer> roleIds, String roleName, boolean team, String line, int durationSeconds) {
    }

    private NightPlan() {
    }

    public static List<NightStep> build(Board board, Map<Integer, RoleEntity> rolesById, int dayNo) {
        return build(board.ruleDetail(), board.roles(), rolesById, dayNo);
    }

    public static List<NightStep> build(BoardRuleDetail detail, List<BoardRoleRef> lineup, Map<Integer, RoleEntity> rolesById, int dayNo) {
        List<RoleEntity> lineupRoles = (lineup == null ? List.<BoardRoleRef>of() : lineup).stream()
                .map(ref -> rolesById.get(ref.roleId()))
                .filter(Objects::nonNull)
                .toList();
        List<Integer> wolfTeamIds = lineupRoles.stream()
                .filter(role -> RoleSkillRegistry.isWolfTeam(role.getName(), role.getFaction()))
                .map(RoleEntity::getId)
                .distinct()
                .toList();

        List<RoleEntity> ordered = new ArrayList<>();
        List<BoardRuleDetail.NightStep> configured = detail == null ? List.of() : detail.nightOrder();
        if (!configured.isEmpty()) {
            Set<Integer> seen = new LinkedHashSet<>();
            for (BoardRuleDetail.NightStep step : configured) {
                RoleEntity role = rolesById.get(step.roleId());
                if (role != null && lineupRoles.contains(role) && seen.add(role.getId())) {
                    ordered.add(role);
                }
            }
        } else {
            for (String name : DEFAULT_ORDER) {
                lineupRoles.stream().filter(role -> name.equals(role.getName())).findFirst().ifPresent(ordered::add);
            }
            // 阵容里没有叫「狼人」的角色但有别的狼队角色（全是狼王之类），也要叫狼队
            if (!wolfTeamIds.isEmpty() && ordered.stream().noneMatch(role -> wolfTeamIds.contains(role.getId()))) {
                RoleEntity lead = rolesById.get(wolfTeamIds.get(0));
                int at = ordered.stream().anyMatch(role -> "守卫".equals(role.getName())) ? 1 : 0;
                ordered.add(at, lead);
            }
        }

        List<NightStep> steps = new ArrayList<>();
        boolean wolvesDone = false;
        for (RoleEntity role : ordered) {
            if (wolfTeamIds.contains(role.getId())) {
                if (wolvesDone) {
                    continue;
                }
                wolvesDone = true;
                steps.add(step(steps.size(), NightKind.WOLF, wolfTeamIds, "狼人", true, dayNo));
                continue;
            }
            NightKind kind = RoleSkillRegistry.forRole(role.getName(), role.getFaction())
                    .map(RoleSkillRegistry.RoleSkill::nightKind)
                    .orElse(NightKind.GENERIC);
            if (kind == NightKind.NONE) {
                continue;
            }
            steps.add(step(steps.size(), kind, List.of(role.getId()), role.getName(), false, dayNo));
        }
        return List.copyOf(steps);
    }

    /** 女巫要看到刀口，必须在狼队之后；守卫要在刀之前守，必须在狼队之前。 */
    public static void validateOrder(List<NightStep> steps) {
        int wolf = indexOf(steps, NightKind.WOLF);
        if (wolf < 0) {
            return;
        }
        int witch = indexOf(steps, NightKind.WITCH);
        int guard = indexOf(steps, NightKind.GUARD);
        if ((witch >= 0 && witch < wolf) || (guard >= 0 && guard > wolf)) {
            throw new ApiException(4000, "夜间顺序：守卫须在狼人前、女巫须在狼人后");
        }
    }

    private static int indexOf(List<NightStep> steps, NightKind kind) {
        for (NightStep step : steps) {
            if (step.kind() == kind) {
                return step.index();
            }
        }
        return -1;
    }

    private static NightStep step(int index, NightKind kind, List<Integer> roleIds, String roleName, boolean team, int dayNo) {
        return new NightStep(index, kind, List.copyOf(roleIds), roleName, team, roleName + "请睁眼，" + hint(kind), duration(kind, dayNo));
    }

    private static String hint(NightKind kind) {
        return switch (kind) {
            case GUARD -> "你今晚要守护谁？";
            case WOLF -> "请确认同伴，选择今晚要击杀的目标。";
            case WITCH -> "今晚他倒下了，你要用解药吗？你要用毒药吗？";
            case SEER -> "你要查验谁？";
            case HUNTER -> "确认你今晚的开枪状态。";
            default -> "请行动。";
        };
    }

    private static int duration(NightKind kind, int dayNo) {
        return switch (kind) {
            case WOLF -> dayNo <= 1 ? WOLF_FIRST_NIGHT_SECONDS : WOLF_SECONDS;
            case GUARD, WITCH, SEER -> ROLE_SECONDS;
            case HUNTER -> HUNTER_SECONDS;
            default -> GENERIC_SECONDS;
        };
    }
}
