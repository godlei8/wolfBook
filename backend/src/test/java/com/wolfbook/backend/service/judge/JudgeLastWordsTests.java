package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.service.judge.JudgeLastWords.Cause;
import com.wolfbook.backend.service.judge.JudgeLastWords.Item;
import com.wolfbook.backend.service.judge.JudgeLastWords.Out;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 通用流程 9、12「遗言」：谁有遗言、多长，以及出局待办的先后（先开枪，再遗言，最后移交警徽；多人遗言随机）。 */
class JudgeLastWordsTests {

    @Test
    void onlyTheFirstNightDeathsHaveLastWords() {
        assertEquals(120, JudgeLastWords.seconds(Cause.NIGHT, 1));
        assertEquals(0, JudgeLastWords.seconds(Cause.NIGHT, 2));
        assertEquals(0, JudgeLastWords.seconds(Cause.NIGHT, 5));
    }

    @Test
    void everyoneOutInTheDayHasLastWords() {
        assertEquals(120, JudgeLastWords.seconds(Cause.EXILE, 1));
        assertEquals(120, JudgeLastWords.seconds(Cause.EXILE, 4));
        assertEquals(120, JudgeLastWords.seconds(Cause.SHOT, 3));
    }

    @Test
    void anExplodedWolfHasSixtySecondsAndAJudgeKillHasNone() {
        assertEquals(60, JudgeLastWords.seconds(Cause.SELF_DESTRUCT, 1));
        assertEquals(60, JudgeLastWords.seconds(Cause.SELF_DESTRUCT, 3));
        assertEquals(0, JudgeLastWords.seconds(Cause.FORCE_OUT, 1));
        assertEquals(0, JudgeLastWords.seconds(Cause.FORCE_OUT, 3));
    }

    @Test
    void skillsComeFirstThenLastWordsThenBadges() {
        // 5 号是警长，第一夜和 7 号一起死：两人的技能询问 → 两人的遗言 → 5 号移交警徽
        List<Item> queue = JudgeLastWords.arrange(List.of(), 0,
                List.of(new Out(7, Cause.NIGHT, false), new Out(5, Cause.NIGHT, true)), 1, new Random(1));
        assertEquals(List.of(JudgeLastWords.SKILL_ASK, JudgeLastWords.SKILL_ASK, JudgeLastWords.LAST_WORDS,
                JudgeLastWords.LAST_WORDS, JudgeLastWords.BADGE), kinds(queue));
        assertEquals(List.of(5, 7), seats(queue).subList(0, 2), "技能询问按座位号");
        assertEquals(Set.of(5, 7), new HashSet<>(seats(queue).subList(2, 4)));
        assertEquals(5, queue.get(4).seat());
        assertEquals(15, queue.get(0).seconds());
        assertEquals(120, queue.get(2).seconds());
        assertEquals(30, queue.get(4).seconds());
    }

    @Test
    void secondNightDeathsGetNoLastWords() {
        List<Item> queue = JudgeLastWords.arrange(List.of(), 0, List.of(new Out(7, Cause.NIGHT, false)), 2, new Random(1));
        assertEquals(List.of(JudgeLastWords.SKILL_ASK), kinds(queue));
    }

    @Test
    void aJudgeKillAsksTheSkillButHasNoLastWords() {
        List<Item> queue = JudgeLastWords.arrange(List.of(), 0, List.of(new Out(4, Cause.FORCE_OUT, true)), 1, new Random(1));
        assertEquals(List.of(JudgeLastWords.SKILL_ASK, JudgeLastWords.BADGE), kinds(queue));
    }

    @Test
    void anExplodedWolfIsNotAskedForASkill() {
        // 自爆不问技能、不能开枪：只有 60 秒遗言，是警长再移交警徽
        List<Item> queue = JudgeLastWords.arrange(List.of(), 0, List.of(new Out(3, Cause.SELF_DESTRUCT, true)), 2, new Random(1));
        assertEquals(List.of(JudgeLastWords.LAST_WORDS, JudgeLastWords.BADGE), kinds(queue));
        assertEquals(60, queue.get(0).seconds());
    }

    @Test
    void aShotTargetIsAskedRightAfterTheShooterAndSpeaksAfterAllSkills() {
        // 第二夜死的猎人 6 号（没遗言）开枪带走警长 2 号；8 号同夜死、技能询问还没轮到
        List<Item> remaining = new ArrayList<>(List.of(new Item(8, JudgeLastWords.SKILL_ASK, 15)));
        List<Item> queue = JudgeLastWords.arrange(remaining, 0, List.of(new Out(2, Cause.SHOT, true)), 2, new Random(1));
        assertEquals(List.of(JudgeLastWords.SKILL_ASK, JudgeLastWords.SKILL_ASK, JudgeLastWords.LAST_WORDS, JudgeLastWords.BADGE),
                kinds(queue));
        assertEquals(List.of(2, 8, 2, 2), seats(queue), "被带走的人先问技能（连锁），白天出局有遗言，最后移交警徽");
    }

    @Test
    void theItemInProgressStaysFirst() {
        // 法官在 3 号的遗言途中点杀了 9 号：3 号这条（正在进行）不动，9 号的技能询问排在它后面
        List<Item> current = List.of(new Item(3, JudgeLastWords.LAST_WORDS, 120), new Item(3, JudgeLastWords.BADGE, 30));
        List<Item> queue = JudgeLastWords.arrange(current, 1, List.of(new Out(9, Cause.FORCE_OUT, false)), 1, new Random(1));
        assertEquals(List.of(3, 9, 3), seats(queue));
        assertEquals(List.of(JudgeLastWords.LAST_WORDS, JudgeLastWords.SKILL_ASK, JudgeLastWords.BADGE), kinds(queue));
    }

    @Test
    void lastWordsOrderIsRandom() {
        Set<List<Integer>> orders = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            List<Item> queue = JudgeLastWords.arrange(List.of(), 0,
                    List.of(new Out(1, Cause.NIGHT, false), new Out(2, Cause.NIGHT, false), new Out(3, Cause.NIGHT, false)), 1, random);
            orders.add(queue.stream().filter(item -> JudgeLastWords.LAST_WORDS.equals(item.kind())).map(Item::seat).toList());
        }
        assertEquals(6, orders.size(), "三个人的遗言六种先后都会出现：" + orders);
    }

    @Test
    void aHunterShotGoesBeforeAnyLastWords() {
        // 5 号猎人选了发动：开枪那条插在最前面，仍在所有遗言之前
        List<Item> remaining = List.of(new Item(7, JudgeLastWords.SKILL_ASK, 15), new Item(5, JudgeLastWords.LAST_WORDS, 120),
                new Item(7, JudgeLastWords.LAST_WORDS, 120));
        List<Item> queue = JudgeLastWords.withShot(remaining, 5);
        assertEquals(List.of(JudgeLastWords.HUNTER_SHOT, JudgeLastWords.SKILL_ASK, JudgeLastWords.LAST_WORDS, JudgeLastWords.LAST_WORDS),
                kinds(queue));
        assertEquals(20, queue.get(0).seconds());
    }

    private static List<String> kinds(List<Item> queue) {
        return queue.stream().map(Item::kind).toList();
    }

    private static List<Integer> seats(List<Item> queue) {
        return queue.stream().map(Item::seat).toList();
    }
}
