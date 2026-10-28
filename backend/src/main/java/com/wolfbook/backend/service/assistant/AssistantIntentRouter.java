package com.wolfbook.backend.service.assistant;

import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断一个问题该怎么答：推荐板子、按资料回答（规则 / 角色 / 世界观），还是直接婉拒。
 * <p>
 * 纯规则、无状态，单测覆盖（AssistantIntentRouterTests）。以前只要句子里有「板子」「推荐」「几人」就当成推荐，
 * 「预女猎白这个板子女巫能自救吗」也会被拿去推荐板子；现在点了具体板子名、角色名或者在详情页里问的，都按资料回答。
 */
public final class AssistantIntentRouter {

    public enum Intent {
        /** 推荐板子 */
        RECOMMEND,
        /** 按站内资料和通用规则回答 */
        KNOWLEDGE,
        /** 命中拦截词（局中替玩家站边、判狼、决定刀谁），直接婉拒 */
        REFUSE
    }

    public record Decision(Intent intent, String reason) {
    }

    private static final Pattern PLAYER_COUNT = Pattern.compile("(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(个)?人");
    // 明确在要推荐：「推荐」「玩什么」「哪个板子」「有没有…板子」「想玩…的板子」「适合…的板子」
    private static final Pattern RECOMMEND_SIGNAL = Pattern.compile(
            "推荐|玩什么|玩哪个|玩啥|哪个板|哪些板|什么板子|啥板子|有没有.{0,12}板|想玩.{0,12}板|适合.{0,12}(板|局|玩)|开什么局|选什么板");
    // 在问规则 / 机制：带了这些就不是要推荐（除非同时明确说了「推荐」）
    private static final Pattern RULE_SIGNAL = Pattern.compile(
            "能不能|能否|可不可以|可以.{0,6}吗|会不会|怎么(用|玩|打|判|算|决斗|发动)|什么时候|如何|规则|技能|能.{0,8}吗|会.{0,8}吗|算不算|是不是|为什么|带走谁|几张|多少");

    private AssistantIntentRouter() {
    }

    /**
     * @param message        用户问题
     * @param blockedKeywords 后台配置的拦截词
     * @param boardNames     站内所有板子名
     * @param roleNames      站内所有角色名
     * @param pageBoardId    从板子详情 / 法官房进来时的板子 id，没有为 null
     * @param pageRoleId     从角色详情进来时的角色 id，没有为 null
     */
    public static Decision route(
            String message,
            Collection<String> blockedKeywords,
            Collection<String> boardNames,
            Collection<String> roleNames,
            Integer pageBoardId,
            Integer pageRoleId
    ) {
        String text = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        for (String keyword : blockedKeywords == null ? List.<String>of() : blockedKeywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return new Decision(Intent.REFUSE, "命中拦截词「" + keyword + "」");
            }
        }

        String mentionedBoard = firstMentioned(text, boardNames);
        String mentionedRole = firstMentioned(text, roleNames);
        boolean recommendSignal = RECOMMEND_SIGNAL.matcher(text).find();
        boolean explicitRecommend = text.contains("推荐");
        boolean ruleSignal = RULE_SIGNAL.matcher(text).find();
        boolean playerCount = PLAYER_COUNT.matcher(text).find();

        // 明确说了「推荐」，哪怕带着板子名（「推荐几个像预女猎白的板子」）也是推荐
        if (explicitRecommend && !ruleSignal) {
            return new Decision(Intent.RECOMMEND, "问题里有「推荐」");
        }
        if (mentionedBoard != null) {
            return new Decision(Intent.KNOWLEDGE, "问的是具体板子「" + mentionedBoard + "」");
        }
        if (recommendSignal && !ruleSignal) {
            return new Decision(Intent.RECOMMEND, "在挑板子");
        }
        if (mentionedRole != null || ruleSignal) {
            return new Decision(Intent.KNOWLEDGE, mentionedRole != null ? "问的是角色「" + mentionedRole + "」" : "在问规则");
        }
        if (pageBoardId != null || pageRoleId != null) {
            return new Decision(Intent.KNOWLEDGE, "在详情页里问，按当前页面回答");
        }
        // 只报了人数 / 难度（「12人进阶」「新手局」），多半是想要板子
        if (playerCount || text.contains("新手") || text.contains("进阶") || text.contains("烧脑")) {
            return new Decision(Intent.RECOMMEND, "只给了人数或难度");
        }
        return new Decision(Intent.KNOWLEDGE, "默认按资料回答");
    }

    private static String firstMentioned(String text, Collection<String> names) {
        if (names == null) {
            return null;
        }
        String best = null;
        for (String name : names) {
            if (!StringUtils.hasText(name)) continue;
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            // 取最长的命中：「白狼王」优先于「狼王」「狼人」
            if (text.contains(normalized) && (best == null || normalized.length() > best.length())) {
                best = normalized;
            }
        }
        return best;
    }
}
