package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼给模型的提示词。
 * <p>
 * 后台「系统 Prompt」「推荐 Prompt」只管人设和语气；回答格式、只用站内资料、不替玩家做局中决策这些规矩写在这里，
 * 改后台 Prompt 不会把它们弄丢。以前的固定模板（「## 结论 / ### 规则拆解 / ### 你可以怎么做」）
 * 一句话能答的问题也要套三段，这里改成先给结论、按需展开。
 */
final class AssistantPromptFactory {

    static final String ANSWER_RULES = """

            回答规矩：
            - 直接回答，第一句就给结论；一两句能说清的就别展开，不要套「结论 / 规则拆解 / 建议」这种固定段落。
            - 优先依据给你的站内资料。资料没写、但属于狼人杀通用规则的，可以按通用规则答，并在末尾加一句「这是常见规则，具体以你们板子的说明为准」；资料里写明了的就直接答，不要加这句。
            - 角色身世、人物关系、夜镇世界观这类设定只能用站内资料，资料里没有就直说站内还没写，不要编。
            - 只回答狼人杀和 Wolfbook 站内内容（规则、角色、板子、世界观、法官主持）。别的话题礼貌说明你只负责狼人杀，再给一个可以问的例子。
            - 不替玩家做局中决策（站边、判狼、刀谁、投谁）。
            - 用户说「这个板子」「她」「他」「第一个」时，结合当前页面和前面的对话判断指的是谁。
            - 用 Markdown：要点用短列表，关键词加粗；最多一个 ### 小标题，不要用 # 或 ## 大标题，不要出现「站内资料显示」「根据资料」这类话。
            """;

    static final String RECOMMEND_RULES = """

            推荐规矩：
            - 只从给你的候选板子里推荐，按给的顺序介绍；阵容、规则只能用候选资料里写的，不要自己补角色。
            - 第一句话直接说首选哪个、为什么，用用户自己的条件来说（别套用别的人数或难度）。
            - 然后每个板子一个「### 板子名」，下面一两句话（不超过 60 字）说适合什么人、亮点在哪，不要分点罗列人数难度。
            - 候选和用户的条件不完全对得上（人数、难度不同）时直说差在哪里；只有一个候选就只推荐这一个。
            - 不要用 # 或 ## 大标题。
            """;

    private static final int USER_TURN_LIMIT = 300;
    private static final int ASSISTANT_TURN_LIMIT = 600;
    private static final int CANDIDATE_TEXT_LIMIT = 700;

    /** 一份交给模型的资料。 */
    record Material(String title, String text) {
    }

    private AssistantPromptFactory() {
    }

    static Prompt knowledge(
            AssistantDtos.AdminAiConfig config,
            String pageText,
            List<AssistantConversationService.Turn> history,
            String question,
            List<Material> materials
    ) {
        StringBuilder user = new StringBuilder();
        if (materials.isEmpty()) {
            user.append("站内资料：没有找到直接相关的内容。\n\n");
        } else {
            user.append("站内资料：\n");
            for (Material material : materials) {
                user.append("【").append(material.title()).append("】\n").append(material.text().strip()).append("\n\n");
            }
        }
        user.append("问题：").append(question);
        return build(config.prompt().systemPrompt() + ANSWER_RULES, pageText, history, user.toString());
    }

    static Prompt recommendation(
            AssistantDtos.AdminAiConfig config,
            String pageText,
            List<AssistantConversationService.Turn> history,
            String question,
            List<Material> candidates
    ) {
        StringBuilder user = new StringBuilder("候选板子（已按匹配度排好）：\n");
        for (int index = 0; index < candidates.size(); index++) {
            Material candidate = candidates.get(index);
            user.append(index + 1).append(". ").append(candidate.title()).append('\n')
                    .append(limit(candidate.text(), CANDIDATE_TEXT_LIMIT)).append("\n\n");
        }
        user.append("问题：").append(question);
        String system = config.prompt().systemPrompt() + "\n" + config.prompt().recommendationPrompt() + RECOMMEND_RULES;
        return build(system, pageText, history, user.toString());
    }

    private static Prompt build(String system, String pageText, List<AssistantConversationService.Turn> history, String user) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(StringUtils.hasText(pageText) ? system + "\n当前页面：" + pageText : system));
        for (AssistantConversationService.Turn turn : history) {
            if (!StringUtils.hasText(turn.content())) continue;
            if ("ASSISTANT".equals(turn.role())) {
                messages.add(new AssistantMessage(limit(turn.content(), ASSISTANT_TURN_LIMIT)));
            } else {
                messages.add(new UserMessage(limit(turn.content(), USER_TURN_LIMIT)));
            }
        }
        messages.add(new UserMessage(user));
        return new Prompt(messages);
    }

    private static String limit(String text, int max) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
