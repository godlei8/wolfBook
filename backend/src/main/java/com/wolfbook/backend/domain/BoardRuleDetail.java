package com.wolfbook.backend.domain;

import java.util.List;

/**
 * 板子的详细规则，整体存为 boards.rule_detail 一列 JSON。
 * 夜间顺序和角色细则通过 roleId 引用本板子阵容里的角色，保存时由 AdminService 校验。
 */
public record BoardRuleDetail(
        String overview,
        List<NightStep> nightOrder,
        List<RoleRuleNote> roleRules,
        List<RuleSection> sections
) {

    public BoardRuleDetail {
        nightOrder = nightOrder == null ? List.of() : List.copyOf(nightOrder);
        roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public static BoardRuleDetail empty() {
        return new BoardRuleDetail(null, List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return (overview == null || overview.isBlank()) && nightOrder.isEmpty() && roleRules.isEmpty() && sections.isEmpty();
    }

    /** 夜间行动顺序里的一步：哪个角色、做什么。 */
    public record NightStep(Integer roleId, String action) {
    }

    /** 某个角色在本板子里的细则，比如「女巫首夜可以自救」。 */
    public record RoleRuleNote(Integer roleId, List<String> items) {
        public RoleRuleNote {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 不绑定角色的规则章节，比如「警长竞选」「平票处理」「遗言」。 */
    public record RuleSection(String title, List<String> items) {
        public RuleSection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
