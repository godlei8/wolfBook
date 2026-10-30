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
        Integer sheriffSpeechSeconds,
        /** 狼队那一步的时长（首夜另加 30 秒互认队友）。 */
        Integer wolfSeconds,
        /** 守卫、女巫、预言家这类单人角色那一步的时长（假步骤同一档）。 */
        Integer roleSeconds
) {

    public static final String WIN_SIDE = "side";
    public static final String WIN_CITY = "city";
    public static final String SELF_SAVE_FIRST_NIGHT = "first_night";
    public static final String SELF_SAVE_NEVER = "never";
    public static final int DEFAULT_SPEECH_SECONDS = 120;
    public static final int DEFAULT_SHERIFF_SPEECH_SECONDS = 150;
    public static final int DEFAULT_WOLF_SECONDS = 60;
    public static final int DEFAULT_ROLE_SECONDS = 30;

    /** 二期之前的 6 个字段（板子里存的 judgeRules 和老房间的 rules_json 只有这些），行动时长留空 = 默认。 */
    public JudgeRules(String winCondition, Boolean sheriffDefault, String witchSelfSave, Boolean witchOnePotionPerNight,
                      Integer speechSeconds, Integer sheriffSpeechSeconds) {
        this(winCondition, sheriffDefault, witchSelfSave, witchOnePotionPerNight, speechSeconds, sheriffSpeechSeconds, null, null);
    }

    public static JudgeRules defaults() {
        return new JudgeRules(WIN_SIDE, true, SELF_SAVE_FIRST_NIGHT, true, DEFAULT_SPEECH_SECONDS, DEFAULT_SHERIFF_SPEECH_SECONDS,
                DEFAULT_WOLF_SECONDS, DEFAULT_ROLE_SECONDS);
    }

    /** 板子的法官规则（没配的项用默认值），胜负规则按板子的 win_condition 文字判断。 */
    public static JudgeRules fromBoard(Board board) {
        return resolve(board == null ? null : board.ruleDetail(), board == null ? null : board.winCondition());
    }

    /** 同 fromBoard，给只有板子行数据（规则详情 + 胜利条件文字）的地方用，比如板子详情接口。 */
    public static JudgeRules resolve(BoardRuleDetail detail, String boardWinCondition) {
        JudgeRules configured = detail == null || detail.judgeRules() == null ? defaults() : detail.judgeRules().withDefaults();
        String winCondition = boardWinCondition != null && boardWinCondition.contains("屠城") ? WIN_CITY : WIN_SIDE;
        return new JudgeRules(
                winCondition,
                configured.sheriffDefault(),
                configured.witchSelfSave(),
                configured.witchOnePotionPerNight(),
                configured.speechSeconds(),
                configured.sheriffSpeechSeconds(),
                configured.wolfSeconds(),
                configured.roleSeconds()
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
                sheriffSpeechSeconds == null || sheriffSpeechSeconds <= 0 ? fallback.sheriffSpeechSeconds() : sheriffSpeechSeconds,
                wolfSeconds == null || wolfSeconds <= 0 ? fallback.wolfSeconds() : wolfSeconds,
                roleSeconds == null || roleSeconds <= 0 ? fallback.roleSeconds() : roleSeconds
        );
    }
}
