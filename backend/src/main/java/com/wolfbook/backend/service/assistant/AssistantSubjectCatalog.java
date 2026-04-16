package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.BoardService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 助手的主体目录。
 *
 * <p>这里统一维护角色、板子和上传文档里临时识别出来的术语主体。查询规划、文档分块和答案漂移校验
 * 都依赖同一套主体识别规则，避免“金水”被误识别成“狼人”、“舞者”被相邻的“假面舞会”抢走。</p>
 */
@Service
class AssistantSubjectCatalog {

    private static final Pattern DEFINITION_PATTERN = Pattern.compile(
            "^(?:请问|问一下|帮我查一下|帮我查|查一下|查询|搜索|搜|介绍一下|介绍|告诉我)?\\s*" +
                    "([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?)\\s*" +
                    "(?:在?狼人杀(?:游戏)?(?:里|中|里面)?|在?游戏(?:里|中|里面)?)?\\s*" +
                    "(?:是啥|啥意思|是什么意思|什么意思|是什么|指什么|指的是啥|怎么理解|如何理解|有啥用|有什么用|是干嘛的)\\??$"
    );
    private static final Pattern DOMAIN_DEFINITION_PATTERN = Pattern.compile(
            "^(?:请问|问一下|帮我查一下|帮我查|查一下|查询|搜索|搜|介绍一下|介绍|告诉我)?\\s*" +
                    "([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?)\\s*" +
                    "在?狼人杀(?:游戏)?(?:里|中|里面)?\\s*" +
                    "(?:是啥|啥意思|是什么意思|什么意思|是什么|指什么|怎么理解|如何理解)\\??$"
    );
    private static final Pattern SEARCH_PATTERN = Pattern.compile(
            "^(?:请问|问一下|帮我查一下|帮我查|查一下|查询|搜索|搜|介绍一下|介绍|告诉我)?\\s*" +
                    "([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?)\\s*" +
                    "(?:的信息|资料|介绍|技能|能力|身份|角色|规则|玩法|机制|说明|怎么用|如何用)?\\??$"
    );
    private static final Pattern LEADING_ACTOR_PATTERN = Pattern.compile(
            "^(?:请问|问一下|帮我查一下|帮我查|查一下|查询|搜索|搜|介绍一下|介绍|告诉我)?\\s*" +
                    "([\\u4e00-\\u9fa5A-Za-z0-9]{2,8})\\s*" +
                    "(?:能不能|能否|是否|可不可以|可以不可以|会不会|能不能够|能不能自|能否自)"
    );

    private final RoleMapper roleMapper;
    private final BoardService boardService;

    AssistantSubjectCatalog(RoleMapper roleMapper, BoardService boardService) {
        this.roleMapper = roleMapper;
        this.boardService = boardService;
    }

    List<AssistantSubject> allSubjects() {
        List<AssistantSubject> subjects = new ArrayList<>();
        roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId))
                .forEach(role -> subjects.add(new AssistantSubject(
                        "ROLE",
                        "ROLE:" + role.getId(),
                        String.valueOf(role.getId()),
                        role.getName(),
                        subjectTerms(role.getName(), role.getAlias()),
                        0
                )));
        for (Board board : boardService.listAllBoards()) {
            subjects.add(new AssistantSubject(
                    "BOARD",
                    "BOARD:" + board.id(),
                    String.valueOf(board.id()),
                    board.name(),
                    subjectTerms(board.name(), null),
                    0
            ));
        }
        return subjects;
    }

    AssistantSubject resolve(String query, int roleBoost, int boardBoost) {
        String normalizedQuery = normalize(query).replace(" ", "");
        if (normalizedQuery.isBlank()) {
            return null;
        }
        return allSubjects().stream()
                .map(subject -> score(subject, normalizedQuery, roleBoost, boardBoost))
                .filter(subject -> subject.score() > 0)
                .max(Comparator.comparingInt(AssistantSubject::score)
                        .thenComparingInt(subject -> longestTermLength(subject.terms())))
                .orElse(null);
    }

    AssistantSubject resolveExactName(String name) {
        String normalizedName = normalize(name).replace(" ", "");
        if (normalizedName.isBlank()) {
            return null;
        }
        return allSubjects().stream()
                .filter(subject -> subject.terms().stream()
                        .map(this::normalize)
                        .map(value -> value.replace(" ", ""))
                        .anyMatch(normalizedName::equals))
                .max(Comparator.comparingInt(subject -> longestTermLength(subject.terms())))
                .map(subject -> new AssistantSubject(
                        subject.type(),
                        subject.sourceKey(),
                        subject.sourceId(),
                        subject.name(),
                        subject.terms(),
                        120
                ))
                .orElse(null);
    }

    AssistantSubject virtualSubject(String name) {
        String cleaned = cleanCandidate(name);
        String normalized = normalize(cleaned).replace(" ", "");
        if (normalized.length() < 2 || normalized.length() > 16 || isGenericSubject(cleaned)) {
            return null;
        }
        return new AssistantSubject("TERM", "TERM:" + normalized, normalized, cleaned, List.of(cleaned), 40);
    }

    AssistantSubject fallbackVirtualSubjectFromQuery(String query) {
        String candidate = explicitCandidate(query);
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        AssistantSubject exactSubject = resolveExactName(candidate);
        if (exactSubject != null) {
            return exactSubject;
        }
        return virtualSubject(candidate);
    }

    AssistantSubject resolveFromText(String text) {
        String normalizedText = normalize(text).replace(" ", "");
        if (normalizedText.isBlank()) {
            return null;
        }
        return allSubjects().stream()
                .map(subject -> score(subject, normalizedText, 0, 0))
                .filter(subject -> subject.score() > 0)
                .max(Comparator.comparingInt(AssistantSubject::score)
                        .thenComparingInt(subject -> longestTermLength(subject.terms())))
                .orElse(null);
    }

    boolean containsOtherSubject(String answer, AssistantSubject allowedSubject, List<AssistantSubject> extraAllowedSubjects) {
        if (allowedSubject != null && allowedSubject.isTerm()) {
            // 术语解释经常需要出现“狼人”“预言家”等角色名，比如“金水=查验后显示非狼人”。
            return false;
        }
        String normalizedAnswer = normalize(answer).replace(" ", "");
        if (normalizedAnswer.isBlank()) {
            return false;
        }
        Set<String> allowedKeys = new LinkedHashSet<>();
        if (allowedSubject != null) {
            allowedKeys.add(allowedSubject.sourceKey());
        }
        if (extraAllowedSubjects != null) {
            extraAllowedSubjects.stream()
                    .filter(subject -> subject != null && subject.sourceKey() != null)
                    .map(AssistantSubject::sourceKey)
                    .forEach(allowedKeys::add);
        }
        for (AssistantSubject subject : allSubjects()) {
            if (allowedKeys.contains(subject.sourceKey()) || isGenericSubject(subject.name())) {
                continue;
            }
            for (String term : subject.terms()) {
                String normalizedTerm = normalize(term).replace(" ", "");
                if (normalizedTerm.length() >= 2 && containsSubjectTerm(normalizedAnswer, normalizedTerm)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AssistantSubject score(AssistantSubject subject, String normalizedText, int roleBoost, int boardBoost) {
        int bestScore = 0;
        for (String term : subject.terms()) {
            String normalizedTerm = normalize(term).replace(" ", "");
            if (normalizedTerm.length() < 2 || !containsSubjectTerm(normalizedText, normalizedTerm)) {
                continue;
            }
            int score = normalizedTerm.length() * 10;
            if (startsWithSubjectTerm(normalizedText, normalizedTerm)) {
                score += 80;
            }
            if (subject.isRole()) {
                score += Math.max(roleBoost, 0);
            }
            if (subject.isBoard()) {
                score += Math.max(boardBoost, 0);
            }
            bestScore = Math.max(bestScore, score);
        }
        if (bestScore <= 0) {
            return subject;
        }
        return new AssistantSubject(subject.type(), subject.sourceKey(), subject.sourceId(), subject.name(), subject.terms(), bestScore);
    }

    private boolean containsSubjectTerm(String normalizedText, String normalizedTerm) {
        if (!"狼人".equals(normalizedTerm)) {
            return normalizedText.contains(normalizedTerm);
        }
        int start = normalizedText.indexOf(normalizedTerm);
        while (start >= 0) {
            int next = start + normalizedTerm.length();
            if (next >= normalizedText.length() || normalizedText.charAt(next) != '杀') {
                return true;
            }
            start = normalizedText.indexOf(normalizedTerm, start + 1);
        }
        return false;
    }

    private boolean startsWithSubjectTerm(String normalizedText, String normalizedTerm) {
        if (!normalizedText.startsWith(normalizedTerm)) {
            return false;
        }
        return !"狼人".equals(normalizedTerm)
                || normalizedText.length() <= normalizedTerm.length()
                || normalizedText.charAt(normalizedTerm.length()) != '杀';
    }

    private String explicitCandidate(String query) {
        String raw = query == null ? "" : query.trim();
        if (raw.isBlank()) {
            return "";
        }
        for (Pattern pattern : List.of(DOMAIN_DEFINITION_PATTERN, DEFINITION_PATTERN, SEARCH_PATTERN, LEADING_ACTOR_PATTERN)) {
            Matcher matcher = pattern.matcher(raw);
            if (matcher.find()) {
                return cleanCandidate(matcher.group(1));
            }
        }
        return "";
    }

    private String cleanCandidate(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("^(请问|问一下|帮我查一下|帮我查|查一下|查询|搜索|搜|介绍一下|介绍|告诉我)", "")
                .replaceAll("(在?狼人杀游戏?|在?狼人杀|游戏里|游戏中|里面|里|中)$", "")
                .replaceAll("(的信息|资料|介绍|技能|能力|身份|角色|规则|玩法|机制|说明)$", "")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", "")
                .trim();
        return cleaned;
    }

    private boolean isGenericSubject(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return Set.of(
                "狼人杀", "狼人杀游戏", "游戏", "角色", "身份", "规则", "玩法", "资料", "信息", "技能", "能力",
                "狼人", "好人", "村民", "平民", "神职", "第三方", "玩家", "法官", "阵营", "问题", "答案",
                "这个", "那个", "什么", "意思", "介绍", "机制", "说明"
        ).contains(name);
    }

    private List<String> subjectTerms(String name, String alias) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (name != null && !name.isBlank()) {
            terms.add(name.trim());
        }
        splitAliases(alias).forEach(terms::add);
        return terms.stream().toList();
    }

    private List<String> splitAliases(String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(alias.split("[,，、/|\\s]+"))
                .map(String::trim)
                .filter(item -> item.length() >= 2)
                .toList();
    }

    private int longestTermLength(List<String> terms) {
        return terms == null ? 0 : terms.stream()
                .map(this::normalize)
                .map(value -> value.replace(" ", ""))
                .mapToInt(String::length)
                .max()
                .orElse(0);
    }

    String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ")
                .trim();
    }
}
