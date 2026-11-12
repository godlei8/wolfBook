package com.wolfbook.backend.domain;

/**
 * 一局电子法官的规则：开局时从板子拷进房间（judge_rooms.rules_json），板子之后再改也不影响进行中的对局。
 * 胜负规则不在这里配，取板子表的 win_condition 列（含「屠城」按屠城，其余按屠边）。
 * 字段允许为空，表示「用默认值」，由 {@link #withDefaults()} 补齐。
 * 女巫自救跟板子走：默认不能自救，板子可以设成「首夜可以自救」，别的写法都按不能自救。
 * 板子配置分两层：通用配置项（节奏：上警、发言、行动时长，建房时房主可调）和板子专属配置项（女巫自救、机械狼弹毒，
 * 只在阵容里有对应角色时有意义，默认值写在机制上，建房请求改不了）。
 * 一晚一瓶是全局规则：补默认值时一律换成全局值，板子、建房请求和旧房间 rules_json 里写的别的值都不算数。
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
        Integer roleSeconds,
        /** 板子专属配置（阵容有机械狼时）：学到守卫的机械狼挡女巫的毒时，把毒反弹给女巫。默认不开。 */
        Boolean poisonBounce
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

    /** 加弹毒之前的 8 个字段，弹毒留空 = 默认不开。 */
    public JudgeRules(String winCondition, Boolean sheriffDefault, String witchSelfSave, Boolean witchOnePotionPerNight,
                      Integer speechSeconds, Integer sheriffSpeechSeconds, Integer wolfSeconds, Integer roleSeconds) {
        this(winCondition, sheriffDefault, witchSelfSave, witchOnePotionPerNight, speechSeconds, sheriffSpeechSeconds,
                wolfSeconds, roleSeconds, null);
    }

    public static JudgeRules defaults() {
        return new JudgeRules(WIN_SIDE, true, SELF_SAVE_NEVER, true, DEFAULT_SPEECH_SECONDS, DEFAULT_SHERIFF_SPEECH_SECONDS,
                DEFAULT_WOLF_SECONDS, DEFAULT_ROLE_SECONDS, false);
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
                configured.roleSeconds(),
                configured.poisonBounce()
        );
    }

    public JudgeRules withDefaults() {
        JudgeRules fallback = defaults();
        return new JudgeRules(
                winCondition == null ? fallback.winCondition() : winCondition,
                sheriffDefault == null ? fallback.sheriffDefault() : sheriffDefault,
                SELF_SAVE_FIRST_NIGHT.equals(witchSelfSave) ? SELF_SAVE_FIRST_NIGHT : SELF_SAVE_NEVER,
                fallback.witchOnePotionPerNight(),
                speechSeconds == null || speechSeconds <= 0 ? fallback.speechSeconds() : speechSeconds,
                sheriffSpeechSeconds == null || sheriffSpeechSeconds <= 0 ? fallback.sheriffSpeechSeconds() : sheriffSpeechSeconds,
                wolfSeconds == null || wolfSeconds <= 0 ? fallback.wolfSeconds() : wolfSeconds,
                roleSeconds == null || roleSeconds <= 0 ? fallback.roleSeconds() : roleSeconds,
                Boolean.TRUE.equals(poisonBounce)
        );
    }
}
