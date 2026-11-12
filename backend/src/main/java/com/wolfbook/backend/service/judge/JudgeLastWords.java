package com.wolfbook.backend.service.judge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 出局待办（通用流程 9、12「遗言」）：纯逻辑，读写库在 JudgeService。
 * <ul>
 *   <li>每个出局者一条技能询问（自爆的不问）；选了发动再插一条开枪。</li>
 *   <li>遗言：第一夜死的 120 秒，之后夜里死的没有；白天被放逐、被开枪带走 120 秒；自爆的狼 60 秒；被法官点杀的没有。</li>
 *   <li>出局的警长一条移交警徽。</li>
 * </ul>
 * 整条队列始终是三段：技能（询问、开枪）→ 遗言（多人随机先后）→ 移交警徽。连锁出局的技能询问排在技能段最前面，
 * 所以无论连锁多深，都是先把技能处理完、再留遗言、最后移交警徽。
 */
public final class JudgeLastWords {

    public static final String SKILL_ASK = "skill_ask";
    public static final String HUNTER_SHOT = "hunter_shot";
    public static final String LAST_WORDS = "last_words";
    public static final String BADGE = "badge";

    public static final int SKILL_ASK_SECONDS = 15;
    public static final int HUNTER_SHOT_SECONDS = 20;
    public static final int BADGE_SECONDS = 30;
    public static final int LAST_WORDS_SECONDS = 120;
    public static final int SELF_DESTRUCT_LAST_WORDS_SECONDS = 60;

    /** 怎么出局的。 */
    public enum Cause {
        /** 夜里死（天亮公布）。 */
        NIGHT,
        /** 白天被放逐。 */
        EXILE,
        /** 被猎人 / 狼王开枪带走。 */
        SHOT,
        /** 狼人自爆。 */
        SELF_DESTRUCT,
        /** 被法官点杀。 */
        FORCE_OUT
    }

    /** 一个出局者：座位、出局方式、是不是警长。 */
    public record Out(int seat, Cause cause, boolean sheriff) {
    }

    /** 队列里的一条：轮到它时才开始计时（seconds），截止时间由编排写。 */
    public record Item(int seat, String kind, int seconds) {
    }

    private JudgeLastWords() {
    }

    /** 这个人的遗言秒数，0 = 没有遗言。dayNo 是公布死讯的那一天（第一夜死的人在第 1 天公布）。 */
    public static int seconds(Cause cause, int dayNo) {
        return switch (cause) {
            case NIGHT -> dayNo <= 1 ? LAST_WORDS_SECONDS : 0;
            case EXILE, SHOT -> LAST_WORDS_SECONDS;
            case SELF_DESTRUCT -> SELF_DESTRUCT_LAST_WORDS_SECONDS;
            case FORCE_OUT -> 0;
        };
    }

    /**
     * 把新出局的人排进队列。queue 的前 keep 条（正在进行的那条）不动；其余按三段重排：
     * 新的技能询问（按座位号）排在技能段最前面，新的遗言随机插进还没开始的遗言里，新的警徽排在最后。
     */
    public static List<Item> arrange(List<Item> queue, int keep, List<Out> outs, int dayNo, Random random) {
        List<Item> head = new ArrayList<>(queue.subList(0, Math.min(keep, queue.size())));
        List<Item> rest = queue.subList(Math.min(keep, queue.size()), queue.size());
        List<Item> skills = new ArrayList<>();
        List<Item> words = new ArrayList<>();
        List<Item> badges = new ArrayList<>();
        List<Out> sorted = outs.stream().sorted(Comparator.comparingInt(Out::seat)).toList();
        for (Out out : sorted) {
            if (out.cause() != Cause.SELF_DESTRUCT) {
                skills.add(new Item(out.seat(), SKILL_ASK, SKILL_ASK_SECONDS));
            }
        }
        for (Item item : rest) {
            switch (segment(item.kind())) {
                case 0 -> skills.add(item);
                case 1 -> words.add(item);
                default -> badges.add(item);
            }
        }
        for (Out out : sorted) {
            int seconds = seconds(out.cause(), dayNo);
            if (seconds > 0) {
                words.add(random.nextInt(words.size() + 1), new Item(out.seat(), LAST_WORDS, seconds));
            }
            if (out.sheriff()) {
                badges.add(new Item(out.seat(), BADGE, BADGE_SECONDS));
            }
        }
        head.addAll(skills);
        head.addAll(words);
        head.addAll(badges);
        return head;
    }

    /** 当事人选了发动技能：开枪那条排在最前面（还在所有遗言之前）。queue 是已经去掉当前这条的剩余队列。 */
    public static List<Item> withShot(List<Item> queue, int seat) {
        List<Item> result = new ArrayList<>();
        result.add(new Item(seat, HUNTER_SHOT, HUNTER_SHOT_SECONDS));
        result.addAll(queue);
        return result;
    }

    private static int segment(String kind) {
        if (LAST_WORDS.equals(kind)) {
            return 1;
        }
        return BADGE.equals(kind) ? 2 : 0;
    }
}
