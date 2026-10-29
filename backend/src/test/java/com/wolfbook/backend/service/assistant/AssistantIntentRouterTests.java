package com.wolfbook.backend.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.wolfbook.backend.service.assistant.AssistantIntentRouter.Intent.KNOWLEDGE;
import static com.wolfbook.backend.service.assistant.AssistantIntentRouter.Intent.RECOMMEND;
import static com.wolfbook.backend.service.assistant.AssistantIntentRouter.Intent.REFUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 问题分流：用的是 scripts/assistant-eval-questions.json 里的评测题。 */
class AssistantIntentRouterTests {

    private static final List<String> BLOCKED = List.of("站边", "投谁", "谁是狼", "今晚刀谁", "谁更像狼", "实时判");
    private static final List<String> BOARDS = List.of("狼美人骑士", "预女猎白", "守卫九人局", "白狼王骑士", "爱神混情局", "狼王守卫");
    private static final List<String> ROLES = List.of("预言家", "女巫", "猎人", "白痴", "守卫", "骑士", "狼美人", "白狼王", "狼王", "狼人", "平民");

    private static AssistantIntentRouter.Intent route(String message) {
        return AssistantIntentRouter.route(message, BLOCKED, BOARDS, ROLES, null, null).intent();
    }

    @Test
    void ruleQuestionsShouldBeAnsweredFromKnowledge() {
        assertEquals(KNOWLEDGE, route("女巫第一晚能自救吗？"));
        assertEquals(KNOWLEDGE, route("守卫能连续两晚守同一个人吗？"));
        assertEquals(KNOWLEDGE, route("白痴被投出去会怎样？"));
        assertEquals(KNOWLEDGE, route("白狼王什么时候可以自爆带人？"));
        assertEquals(KNOWLEDGE, route("夜镇的银钟是什么来历？"));
    }

    @Test
    void askingForBoardsShouldRecommend() {
        assertEquals(RECOMMEND, route("12人进阶推荐什么板子"));
        assertEquals(RECOMMEND, route("9个人新手玩什么好？"));
        assertEquals(RECOMMEND, route("有没有适合朋友聚会娱乐的板子？"));
        assertEquals(RECOMMEND, route("想玩有第三方阵营的板子"));
        assertEquals(RECOMMEND, route("十二人局"));
        assertEquals(RECOMMEND, route("推荐几个和预女猎白差不多的板子"));
    }

    @Test
    void questionsAboutANamedBoardShouldNotBeTreatedAsRecommendation() {
        assertEquals(KNOWLEDGE, route("预女猎白这个板子女巫能自救吗？"));
        assertEquals(KNOWLEDGE, route("狼美人骑士板子里骑士怎么决斗？"));
        assertEquals(KNOWLEDGE, route("守卫九人局适合新手吗"));
    }

    @Test
    void questionsAskedOnADetailPageShouldUseThatPage() {
        assertEquals(KNOWLEDGE, AssistantIntentRouter.route("这个板子怎么样", BLOCKED, BOARDS, ROLES, 1, null).intent());
        assertEquals(KNOWLEDGE, AssistantIntentRouter.route("她出局的时候会带走谁？", BLOCKED, BOARDS, ROLES, null, 7).intent());
    }

    @Test
    void inGameDecisionsShouldBeRefused() {
        var decision = AssistantIntentRouter.route("今晚刀谁比较好？", BLOCKED, BOARDS, ROLES, null, null);
        assertEquals(REFUSE, decision.intent());
        assertEquals("命中拦截词「今晚刀谁」", decision.reason());
    }
}
