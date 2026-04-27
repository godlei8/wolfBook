package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.model.AiQueryPlan;
import com.wolfbook.backend.ai.model.AiRetrievalContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AiQueryPlanner {

    private static final List<String> WEREWOLF_TERMS = List.of(
            "狼人杀", "狼人", "预言家", "女巫", "猎人", "白痴", "守卫", "骑士", "狼美人", "狼王",
            "白狼王", "舞者", "假面", "金水", "银水", "查杀", "自爆", "警徽", "上警", "悍跳",
            "板子", "屠边", "神职", "平民", "票型", "发言", "放逐", "遗言", "夜间"
    );
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "这个", "该", "它", "他", "她", "上面", "刚才", "继续", "怎么用", "有什么限制", "这个技能"
    );
    private static final List<String> WEB_MARKERS = List.of("最新", "最近", "新闻", "赛事", "公告", "版本更新");
    private static final List<String> NON_GAME_MARKERS = List.of("天气", "股票", "基金", "汇率", "菜谱", "旅游", "房价");

    public AiQueryPlan plan(String question, Set<String> knownSubjects, AiRetrievalContext context) {
        String normalizedQuestion = normalize(question);
        String explicitSubject = findExplicitSubject(normalizedQuestion, knownSubjects);
        boolean outOfScope = isOutOfScope(normalizedQuestion, explicitSubject);
        if (outOfScope) {
            return new AiQueryPlan(question, normalizedQuestion, null, "OUT_OF_SCOPE", true, false, List.of("非狼人杀问题"));
        }

        boolean followUp = explicitSubject == null && isFollowUp(normalizedQuestion);
        String inheritedSubject = followUp && context != null ? normalize(context.confirmedEntity()) : null;
        String subject = explicitSubject != null ? explicitSubject : emptyToNull(inheritedSubject);
        String searchQuery = subject == null || normalizedQuestion.contains(subject)
                ? normalizedQuestion
                : subject + " " + normalizedQuestion;
        String intent = webPreferred(normalizedQuestion) ? "NEWS" : subject == null ? "GENERAL" : "ENTITY";
        return new AiQueryPlan(question, searchQuery.trim(), subject, intent, false, webPreferred(normalizedQuestion), List.of());
    }

    private String findExplicitSubject(String question, Set<String> knownSubjects) {
        if (knownSubjects == null || knownSubjects.isEmpty()) {
            return WEREWOLF_TERMS.stream()
                    .filter(question::contains)
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
        }
        return knownSubjects.stream()
                .map(this::normalize)
                .filter(subject -> !subject.isBlank())
                .filter(question::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private boolean isFollowUp(String question) {
        return FOLLOW_UP_MARKERS.stream().anyMatch(question::contains);
    }

    private boolean webPreferred(String question) {
        return WEB_MARKERS.stream().anyMatch(question::contains);
    }

    private boolean isOutOfScope(String question, String explicitSubject) {
        if (explicitSubject != null) {
            return false;
        }
        boolean hasWerewolfTerm = WEREWOLF_TERMS.stream().anyMatch(question::contains);
        if (hasWerewolfTerm) {
            return false;
        }
        return NON_GAME_MARKERS.stream().anyMatch(question::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
