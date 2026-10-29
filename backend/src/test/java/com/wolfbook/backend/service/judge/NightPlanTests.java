package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.BoardRuleDetail;
import com.wolfbook.backend.entity.RoleEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightPlanTests {

    private static final Map<Integer, RoleEntity> ROLES = Map.of(
            1, role(1, "预言家", "好人"),
            2, role(2, "女巫", "好人"),
            3, role(3, "猎人", "好人"),
            4, role(4, "守卫", "好人"),
            6, role(6, "狼人", "狼人"),
            8, role(8, "白痴", "好人"),
            9, role(9, "平民", "好人"),
            10, role(10, "白狼王", "狼人"),
            15, role(15, "织梦人", "好人")
    );

    @Test
    void defaultOrderOnlyIncludesRolesInTheLineup() {
        // 守卫九人局：3 狼、预言家、女巫、守卫、3 平民，没有猎人
        List<NightPlan.NightStep> steps = NightPlan.build(BoardRuleDetail.empty(),
                List.of(new BoardRoleRef(6, 3), new BoardRoleRef(1, 1), new BoardRoleRef(2, 1), new BoardRoleRef(4, 1), new BoardRoleRef(9, 3)),
                ROLES, 1);
        assertEquals(List.of(NightKind.GUARD, NightKind.WOLF, NightKind.WITCH, NightKind.SEER), steps.stream().map(NightPlan.NightStep::kind).toList());
        assertTrue(steps.get(1).team());
        assertEquals(List.of(0, 1, 2, 3), steps.stream().map(NightPlan.NightStep::index).toList());
    }

    @Test
    void configuredOrderMergesWolvesIntoOneStep() {
        BoardRuleDetail detail = new BoardRuleDetail(null, List.of(
                new BoardRuleDetail.NightStep(6, "狼人睁眼"),
                new BoardRuleDetail.NightStep(10, "白狼王睁眼"),
                new BoardRuleDetail.NightStep(2, "女巫睁眼"),
                new BoardRuleDetail.NightStep(15, "织梦人睁眼"),
                new BoardRuleDetail.NightStep(8, "白痴睁眼")
        ), List.of(), List.of());
        List<NightPlan.NightStep> steps = NightPlan.build(detail,
                List.of(new BoardRoleRef(6, 2), new BoardRoleRef(10, 1), new BoardRoleRef(2, 1), new BoardRoleRef(15, 1), new BoardRoleRef(8, 1)),
                ROLES, 2);
        // 白痴没有夜间行动，不叫；织梦人没登记，只播报
        assertEquals(List.of(NightKind.WOLF, NightKind.WITCH, NightKind.GENERIC), steps.stream().map(NightPlan.NightStep::kind).toList());
        assertEquals(List.of(6, 10), steps.get(0).roleIds());
        assertEquals("织梦人", steps.get(2).roleName());
        assertEquals(25, steps.get(2).durationSeconds());
    }

    @Test
    void wolfDurationIs90OnTheFirstNightThen60() {
        List<BoardRoleRef> lineup = List.of(new BoardRoleRef(6, 2), new BoardRoleRef(1, 1), new BoardRoleRef(9, 2));
        assertEquals(90, NightPlan.build(BoardRuleDetail.empty(), lineup, ROLES, 1).get(0).durationSeconds());
        assertEquals(60, NightPlan.build(BoardRuleDetail.empty(), lineup, ROLES, 2).get(0).durationSeconds());
        assertEquals(30, NightPlan.build(BoardRuleDetail.empty(), lineup, ROLES, 2).get(1).durationSeconds());
    }

    @Test
    void lineStartsWithTheRoleName() {
        List<NightPlan.NightStep> steps = NightPlan.build(BoardRuleDetail.empty(),
                List.of(new BoardRoleRef(6, 2), new BoardRoleRef(3, 1)), ROLES, 1);
        assertTrue(steps.get(0).line().startsWith("狼人请睁眼"));
        assertTrue(steps.get(1).line().startsWith("猎人请睁眼"));
    }

    @Test
    void witchBeforeWolvesIsRejected() {
        BoardRuleDetail detail = new BoardRuleDetail(null, List.of(
                new BoardRuleDetail.NightStep(2, "女巫"),
                new BoardRuleDetail.NightStep(6, "狼人")
        ), List.of(), List.of());
        List<NightPlan.NightStep> steps = NightPlan.build(detail, List.of(new BoardRoleRef(2, 1), new BoardRoleRef(6, 2)), ROLES, 1);
        ApiException error = assertThrows(ApiException.class, () -> NightPlan.validateOrder(steps));
        assertEquals(4000, error.getCode());
    }

    @Test
    void guardAfterWolvesIsRejected() {
        BoardRuleDetail detail = new BoardRuleDetail(null, List.of(
                new BoardRuleDetail.NightStep(6, "狼人"),
                new BoardRuleDetail.NightStep(4, "守卫")
        ), List.of(), List.of());
        List<NightPlan.NightStep> steps = NightPlan.build(detail, List.of(new BoardRoleRef(4, 1), new BoardRoleRef(6, 2)), ROLES, 1);
        assertThrows(ApiException.class, () -> NightPlan.validateOrder(steps));
    }

    private static RoleEntity role(int id, String name, String faction) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setName(name);
        role.setFaction(faction);
        return role;
    }
}
