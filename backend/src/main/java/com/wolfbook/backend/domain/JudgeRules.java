package com.wolfbook.backend.domain;

/**
 * 一局电子法官的规则：开局时从板子拷进房间（judge_rooms.rules_json），板子之后再改也不影响进行中的对局。
 * 胜负规则不在这里配，取板子表的 win_condition 列（含「屠城」按屠城，其余按屠边）。
 * 字段允许为空，表示「用默认值」，由 {@link #withDefaults()} 补齐。
 */
public record JudgeRules(
        String winCondition,
        Boolean sheriffDefault,
        String witchSelfSave,
        Boolean witchOnePotionPerNight,
        Integer speechSeconds,
        Integer sheriffSpeechSeconds
) {

    public static final String WIN_SIDE = "side";
    public static final String WIN_CITY = "city";
    public static final String SELF_SAVE_FIRST_NIGHT = "first_night";
    public static final String SELF_SAVE_NEVER = "never";
    public static final int DEFAULT_SPEECH_SECONDS = 120;
    public static final int DEFAULT_SHERIFF_SPEECH_SECONDS = 150;

    public static JudgeRules defaults() {
        return new JudgeRules(WIN_SIDE, true, SELF_SAVE_FIRST_NIGHT, true, DEFAULT_SPEECH_SECONDS, DEFAULT_SHERIFF_SPEECH_SECONDS);
    }

    /** 板子的法官规则（没配的项用默认值），胜负规则按板子的 win_condition 文字判断。 */
    public static JudgeRules fromBoard(Board board) {
        BoardRuleDetail detail = board == null ? null : board.ruleDetail();
        JudgeRules configured = detail == null || detail.judgeRules() == null ? defaults() : detail.judgeRules().withDefaults();
        String winCondition = board != null && board.winCondition() != null && board.winCondition().contains("屠城") ? WIN_CITY : WIN_SIDE;
        return new JudgeRules(
                winCondition,
                configured.sheriffDefault(),
                configured.witchSelfSave(),
                configured.witchOnePotionPerNight(),
                configured.speechSeconds(),
                configured.sheriffSpeechSeconds()
        );
    }

    public JudgeRules withDefaults() {
        JudgeRules fallback = defaults();
        return new JudgeRules(
                winCondition == null ? fallback.winCondition() : winCondition,
                sheriffDefault == null ? fallback.sheriffDefault() : sheriffDefault,
                witchSelfSave == null ? fallback.witchSelfSave() : witchSelfSave,
                witchOnePotionPerNight == null ? fallback.witchOnePotionPerNight() : witchOnePotionPerNight,
                speechSeconds == null || speechSeconds <= 0 ? fallback.speechSeconds() : speechSeconds,
                sheriffSpeechSeconds == null || sheriffSpeechSeconds <= 0 ? fallback.sheriffSpeechSeconds() : sheriffSpeechSeconds
        );
    }
}
