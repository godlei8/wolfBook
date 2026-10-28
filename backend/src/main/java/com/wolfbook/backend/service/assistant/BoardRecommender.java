package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从站内上架的板子里挑最合适的几个。纯计算、无状态，单测见 BoardRecommenderTests。
 * <p>
 * 以前只认「12人」这种阿拉伯数字和标签原词，「九个人新手玩什么」既读不出人数也读不出难度，
 * 只能把全部板子按名字排序随便给三个。现在中文数字、难度和标签的常见说法、角色名都算进分里。
 */
final class BoardRecommender {

    static final int LIMIT = 3;

    record Candidate(Board board, int score, String reason) {
    }

    private static final Pattern PLAYER_COUNT = Pattern.compile("(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(个)?人");

    // 用户的说法 → 板子上的难度
    private static final Map<String, String> DIFFICULTY_WORDS = new LinkedHashMap<>();
    // 用户的说法 → 板子上的标签
    private static final Map<String, String> TAG_WORDS = new LinkedHashMap<>();

    static {
        for (String word : List.of("新手", "入门", "简单", "小白", "第一次", "教学")) DIFFICULTY_WORDS.put(word, "入门");
        DIFFICULTY_WORDS.put("进阶", "进阶");
        DIFFICULTY_WORDS.put("有点难度", "进阶");
        for (String word : List.of("烧脑", "高阶", "硬核", "高手", "复杂", "难一点")) DIFFICULTY_WORDS.put(word, "烧脑");

        for (String word : List.of("经典", "标准", "常规")) TAG_WORDS.put(word, "经典");
        for (String word : List.of("教学", "新手", "带新")) TAG_WORDS.put(word, "教学");
        for (String word : List.of("娱乐", "聚会", "欢乐", "轻松", "好玩", "朋友")) TAG_WORDS.put(word, "娱乐");
        for (String word : List.of("快节奏", "节奏快", "快一点", "短一点")) TAG_WORDS.put(word, "快节奏");
        for (String word : List.of("高配", "神职多")) TAG_WORDS.put(word, "高配");
        for (String word : List.of("第三方", "情侣", "爱神", "丘比特", "混情")) TAG_WORDS.put(word, "第三方");
        TAG_WORDS.put("功能", "功能");
        TAG_WORDS.put("烧脑", "烧脑");
    }

    private BoardRecommender() {
    }

    /**
     * @param boards    全部板子（这里只挑上架的）
     * @param roleNames 角色 id → 名字，用来匹配「想玩有守卫的板子」
     */
    static List<Candidate> recommend(String query, List<Board> boards, Map<Integer, String> roleNames) {
        String text = query == null ? "" : query;
        Integer playerCount = parsePlayerCount(text);
        String difficulty = parseDifficulty(text);
        List<String> tags = parseTags(text);

        List<Candidate> ranked = boards.stream()
                .filter(board -> board.status() != null && board.status() == 1)
                .map(board -> score(board, text, playerCount, difficulty, tags, roleNames))
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        // 同分时配了背景图的（在首页、列表里更好看、内容也更全）排前面
                        .thenComparing(candidate -> StringUtils.hasText(candidate.board().backgroundImage()) ? 0 : 1)
                        .thenComparing(candidate -> candidate.board().id()))
                .toList();
        // 有对得上条件的就只给对得上的（「想玩第三方」只回爱神混情局，不硬凑三个）；一个都对不上才给热门的
        List<Candidate> matched = ranked.stream().filter(candidate -> candidate.score() > 0).limit(LIMIT).toList();
        return matched.isEmpty() ? ranked.stream().limit(LIMIT).toList() : matched;
    }

    private static Candidate score(Board board, String text, Integer playerCount, String difficulty, List<String> tags, Map<Integer, String> roleNames) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (playerCount != null && board.playerCount() != null) {
            if (playerCount.equals(board.playerCount())) {
                score += 5;
                reasons.add(playerCount + " 人局");
            } else if (Math.abs(board.playerCount() - playerCount) <= 1) {
                score += 1;
                reasons.add("人数接近（" + board.playerCount() + " 人）");
            } else {
                score -= 3;
            }
        }
        if (difficulty != null && difficulty.equals(board.difficulty())) {
            score += 3;
            reasons.add("难度" + difficulty);
        }
        List<String> boardTags = board.tags() == null ? List.of() : board.tags();
        for (String tag : tags) {
            if (boardTags.contains(tag)) {
                score += 2;
                reasons.add("偏" + tag);
            }
        }
        if (StringUtils.hasText(board.name()) && text.contains(board.name())) {
            score += 4;
            reasons.add("就是你提到的板子");
        }
        for (BoardRoleRef ref : board.roles() == null ? List.<BoardRoleRef>of() : board.roles()) {
            String roleName = roleNames.get(ref.roleId());
            // 「有守卫」「带骑士」：角色名至少两个字，避免「狼」这种单字到处命中
            if (roleName != null && roleName.length() >= 2 && text.contains(roleName)) {
                score += 2;
                reasons.add("有" + roleName);
            }
        }
        String reason = reasons.isEmpty() ? "站内热门板子" : String.join("，", reasons);
        return new Candidate(board, score, reason);
    }

    static Integer parsePlayerCount(String text) {
        Matcher matcher = PLAYER_COUNT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        if (Character.isDigit(value.charAt(0))) {
            return Integer.parseInt(value);
        }
        return parseChineseNumber(value);
    }

    /** 一 ~ 九十九的中文数字：九、十二、二十、两。 */
    static Integer parseChineseNumber(String value) {
        String digits = "零一二三四五六七八九";
        int tenIndex = value.indexOf('十');
        if (tenIndex < 0) {
            int digit = value.equals("两") ? 2 : digits.indexOf(value.charAt(0));
            return value.length() == 1 && digit > 0 ? digit : null;
        }
        int tens = tenIndex == 0 ? 1 : digits.indexOf(value.charAt(0));
        int ones = tenIndex == value.length() - 1 ? 0 : digits.indexOf(value.charAt(tenIndex + 1));
        if (tens <= 0 || ones < 0) {
            return null;
        }
        return tens * 10 + ones;
    }

    static String parseDifficulty(String text) {
        for (Map.Entry<String, String> entry : DIFFICULTY_WORDS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    static List<String> parseTags(String text) {
        List<String> tags = new ArrayList<>();
        TAG_WORDS.forEach((word, tag) -> {
            if (text.contains(word) && !tags.contains(tag)) {
                tags.add(tag);
            }
        });
        return tags;
    }
}
