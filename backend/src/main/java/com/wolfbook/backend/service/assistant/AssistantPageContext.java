package com.wolfbook.backend.service.assistant;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Function;

/**
 * 小程序从哪个页面打开的助手（openAssistant 带上来的 pageContext）。
 * 用来让模型知道「这个板子」「她」指的是谁，也用来给检索补上板子 / 角色名。
 *
 * @param page     boards/detail、roles/detail、judge/room、world/index，其他页面为空
 * @param phase    法官房的当前阶段
 */
record AssistantPageContext(String page, Integer boardId, String boardName, Integer roleId, String roleName, String phase) {

    private static final int PAGE_TEXT_LIMIT = 1500;

    static AssistantPageContext from(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return new AssistantPageContext(null, null, null, null, null, null);
        }
        return new AssistantPageContext(
                text(context.get("page")),
                number(context.get("boardId")),
                text(context.get("boardName")),
                number(context.get("roleId")),
                text(context.get("roleName")),
                text(context.get("phase"))
        );
    }

    /** 当前页面对应的那份站内资料（"ROLE:7" / "BOARD:1"），它的全文已经写进系统提示词，检索结果里不用再放一遍。 */
    String pageKey() {
        if ("roles/detail".equals(page) && roleId != null) return "ROLE:" + roleId;
        if (("boards/detail".equals(page) || "judge/room".equals(page)) && boardId != null) return "BOARD:" + boardId;
        return "";
    }

    /** 检索时附带的名字：在角色页问「她出局会带走谁」，光拿这句话去搜什么也搜不到。 */
    String searchHint() {
        if (StringUtils.hasText(roleName)) return roleName;
        if (StringUtils.hasText(boardName)) return boardName;
        return "";
    }

    /**
     * 写进系统提示词的「用户正在看什么」，带上该板子 / 角色的站内资料全文；不在详情页时返回 null。
     *
     * @param structuredText sourceKey（"BOARD:1"）→ 站内资料全文
     */
    String describe(Function<String, String> structuredText) {
        if ("roles/detail".equals(page) && roleId != null) {
            String name = StringUtils.hasText(roleName) ? roleName : "这个角色";
            return "用户正在看角色「" + name + "」的详情页，问题里的「她 / 他 / 这个角色」就是指" + name + "。"
                    + material("角色资料", structuredText.apply("ROLE:" + roleId));
        }
        if ("boards/detail".equals(page) && boardId != null) {
            String name = StringUtils.hasText(boardName) ? boardName : "这个板子";
            return "用户正在看板子「" + name + "」的详情页，问题里的「这个板子 / 这局」就是指" + name + "。"
                    + material("板子资料", structuredText.apply("BOARD:" + boardId));
        }
        if ("judge/room".equals(page)) {
            String name = StringUtils.hasText(boardName) ? "「" + boardName + "」" : "";
            String text = "用户正在电子法官房里用" + name + "板子开线下局"
                    + (StringUtils.hasText(phase) ? "，当前阶段：" + phase : "")
                    + "。用户多半是在局中速查规则，回答要短，直接给判定。";
            return boardId == null ? text : text + material("板子资料", structuredText.apply("BOARD:" + boardId));
        }
        if ("world/index".equals(page)) {
            return "用户正在看夜镇编年史（世界观）页面。";
        }
        return null;
    }

    private static String material(String label, String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() > PAGE_TEXT_LIMIT) {
            trimmed = trimmed.substring(0, PAGE_TEXT_LIMIT) + "…";
        }
        return "\n" + label + "：\n" + trimmed;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && text.trim().matches("\\d{1,9}")) {
            return Integer.parseInt(text.trim());
        }
        return null;
    }
}
