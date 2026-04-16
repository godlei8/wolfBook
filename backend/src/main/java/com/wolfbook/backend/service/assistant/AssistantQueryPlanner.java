package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * AI 助手查询规划器。
 *
 * <p>用户问题先被转成稳定的查询计划：识别角色、板子、术语主体，判断是否必须严格按主体检索，
 * 再决定是否允许联网。后续检索、生成、校验都只消费这个计划，避免每层各自猜主体。</p>
 */
@Service
class AssistantQueryPlanner {

    private final AssistantSubjectCatalog subjectCatalog;

    AssistantQueryPlanner(AssistantSubjectCatalog subjectCatalog) {
        this.subjectCatalog = subjectCatalog;
    }

    AssistantQueryPlan plan(String query, List<String> recentMessages) {
        String effectiveQuery = enrichFollowUp(query, recentMessages);

        // “金水是啥 / 金水在狼人杀里啥意思”这类定义题必须先抽术语，不能让“狼人杀”里的“狼人”抢主体。
        AssistantSubject subject = subjectCatalog.fallbackVirtualSubjectFromQuery(effectiveQuery);
        if (subject == null) {
            int roleBoost = containsAny(effectiveQuery, List.of("技能", "能力", "身份", "角色", "信息", "介绍", "发动", "怎么用")) ? 30 : 0;
            int boardBoost = containsAny(effectiveQuery, List.of("板子", "局", "配置", "人数", "阵容", "规则", "流程", "有哪些角色")) ? 30 : 0;
            subject = subjectCatalog.resolve(effectiveQuery, roleBoost, boardBoost);
        }

        boolean boardCatalogQuery = subject != null
                && subject.isBoard()
                && containsAny(effectiveQuery, List.of("有哪些角色", "角色有哪些", "阵容", "配置", "身份有哪些"));
        boolean strictSubject = subject != null && (!subject.isBoard() || !boardCatalogQuery);
        return new AssistantQueryPlan(
                query,
                effectiveQuery,
                subject,
                strictSubject,
                boardCatalogQuery,
                isTimeSensitiveQuery(effectiveQuery),
                isExplicitWebSearchQuery(effectiveQuery),
                AssistantKeywordMatcher.extractTokens(effectiveQuery)
        );
    }

    AssistantQueryPlan plan(String query) {
        return plan(query, List.of());
    }

    private String enrichFollowUp(String query, List<String> recentMessages) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return "";
        }
        if (subjectCatalog.fallbackVirtualSubjectFromQuery(normalized) != null
                || subjectCatalog.resolve(normalized, 0, 0) != null
                || !looksLikeFollowUp(normalized)
                || recentMessages == null) {
            return normalized;
        }
        for (int index = recentMessages.size() - 1; index >= 0; index--) {
            String message = recentMessages.get(index);
            if (message == null || message.contains(normalized)) {
                continue;
            }
            AssistantSubject subject = subjectCatalog.resolveFromText(message);
            if (subject == null) {
                subject = subjectCatalog.fallbackVirtualSubjectFromQuery(message);
            }
            if (subject != null) {
                return subject.name() + " " + normalized;
            }
        }
        return normalized;
    }

    private boolean looksLikeFollowUp(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.length() <= 30 && containsAny(normalized, List.of(
                "它", "他", "她", "这个", "那个", "该角色", "这个角色", "技能呢", "怎么用", "能不能", "可以吗", "呢"
        ));
    }

    private boolean isTimeSensitiveQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String keyword : List.of(
                "最新", "今天", "今日", "刚刚", "近期", "最近",
                "新闻", "赛事", "版本", "更新", "公告", "实时",
                "latest", "today", "recent", "news", "version", "update"
        )) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitWebSearchQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String keyword : List.of(
                "联网", "网上", "搜索", "搜一下", "查一下", "查一查", "帮我查", "帮我搜",
                "web search", "search online", "look up online"
        )) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
