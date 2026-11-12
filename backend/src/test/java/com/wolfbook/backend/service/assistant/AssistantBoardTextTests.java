package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.BoardRuleDetail;
import com.wolfbook.backend.domain.JudgeRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 板子资料里给助手的电子法官规则：问「这个板子女巫能自救吗」时按板子的设置答，不再说「按常见规则」。 */
class AssistantBoardTextTests {

    private static final Map<Integer, String> ROLE_NAMES = Map.of(2, "女巫", 6, "狼人", 9, "平民");

    @Test
    void witchBoardsSayTheWitchNeverSavesHerself() {
        assertEquals("电子法官规则：女巫不能自救（被刀的是自己时不能用解药）；一晚只能用一瓶药",
                AssistantKnowledgeService.judgeRuleText(board(null, List.of(2, 6, 9)), ROLE_NAMES));
        BoardRuleDetail firstNight = new BoardRuleDetail(null, List.of(), List.of(), List.of(),
                new JudgeRules(null, null, JudgeRules.SELF_SAVE_FIRST_NIGHT, null, null, null));
        assertEquals("电子法官规则：女巫不能自救（被刀的是自己时不能用解药）；一晚只能用一瓶药",
                AssistantKnowledgeService.judgeRuleText(board(firstNight, List.of(2, 6, 9)), ROLE_NAMES),
                "全程不可自救，旧板子存的 first_night 也不算");
    }

    @Test
    void boardsWithoutAWitchSayNothing() {
        assertEquals("", AssistantKnowledgeService.judgeRuleText(board(null, List.of(6, 9)), ROLE_NAMES));
    }

    private static Board board(BoardRuleDetail detail, List<Integer> roleIds) {
        return new Board(2, "预女猎白", 12, "入门", List.of(), null, null, null, null, List.of(), List.of(), List.of(),
                detail, "屠边", "标准板", 1, roleIds.stream().map(id -> new BoardRoleRef(id, 1)).toList());
    }
}
