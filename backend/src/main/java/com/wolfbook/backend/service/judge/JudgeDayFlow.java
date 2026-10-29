package com.wolfbook.backend.service.judge;

import com.wolfbook.backend.dto.WolfbookDtos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 白天的纯逻辑：发言顺序、对局记录。读写库、鉴权都在 JudgeService。
 */
public final class JudgeDayFlow {

    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String DEAD_LEFT = "dead_left";
    public static final String DEAD_RIGHT = "dead_right";

    private JudgeDayFlow() {
    }

    /**
     * 发言顺序。「左」是座位号递增方向（顺时针），「右」是递减。
     * 警左 / 警右：从警长旁边开始，警长最后；死左 / 死右：从昨夜第一个出局者旁边开始。
     * 没选的时候：有死者按死左，没有就随机起点、随机方向。
     */
    public static List<Integer> speechOrder(List<Integer> aliveSeats, int playerCount, String choice, Integer sheriffSeat,
                                            List<Integer> nightDeaths, Random random) {
        if (aliveSeats.isEmpty()) {
            return List.of();
        }
        Integer firstDeath = nightDeaths == null || nightDeaths.isEmpty() ? null : nightDeaths.get(0);
        String mode = choice;
        if (mode == null || ((LEFT.equals(mode) || RIGHT.equals(mode)) && sheriffSeat == null)
                || ((DEAD_LEFT.equals(mode) || DEAD_RIGHT.equals(mode)) && firstDeath == null)) {
            mode = firstDeath != null ? DEAD_LEFT : null;
        }
        int anchor;
        boolean clockwise;
        Integer last = null;
        if (mode == null) {
            anchor = aliveSeats.get(random.nextInt(aliveSeats.size()));
            clockwise = random.nextBoolean();
            return walk(aliveSeats, playerCount, anchor, clockwise, true, null);
        }
        switch (mode) {
            case LEFT -> {
                anchor = sheriffSeat;
                clockwise = true;
                last = sheriffSeat;
            }
            case RIGHT -> {
                anchor = sheriffSeat;
                clockwise = false;
                last = sheriffSeat;
            }
            case DEAD_RIGHT -> {
                anchor = firstDeath;
                clockwise = false;
            }
            default -> {
                anchor = firstDeath;
                clockwise = true;
            }
        }
        return walk(aliveSeats, playerCount, anchor, clockwise, false, last);
    }

    /** 法官直接指定从谁开始（含他自己），按方向绕一圈。 */
    public static List<Integer> speechOrderFrom(List<Integer> aliveSeats, int playerCount, int startSeat, boolean clockwise) {
        return walk(aliveSeats, playerCount, startSeat, clockwise, true, null);
    }

    /** 从 anchor 出发（includeAnchor 决定 anchor 自己算不算第一个）沿方向绕一圈，挑出存活座位；last 挪到最后。 */
    private static List<Integer> walk(List<Integer> aliveSeats, int playerCount, int anchor, boolean clockwise, boolean includeAnchor, Integer last) {
        List<Integer> order = new ArrayList<>();
        for (int step = includeAnchor ? 0 : 1; step <= playerCount; step++) {
            int seat = Math.floorMod(anchor - 1 + (clockwise ? step : -step), playerCount) + 1;
            if (aliveSeats.contains(seat) && !order.contains(seat) && !Objects.equals(seat, last)) {
                order.add(seat);
            }
        }
        if (last != null && aliveSeats.contains(last)) {
            order.add(last);
        }
        return order;
    }

    /** 对局记录用的一条公开事件（JudgeService 从 judge_action_events 转出来）。 */
    public record EventRecord(int dayNo, String type, Map<String, Object> payload, Map<String, Object> result) {
    }

    /** 把公开事件按天折叠成对局记录；只含公开信息，夜间行动不在这里。 */
    public static List<WolfbookDtos.JudgeRoundView> buildRounds(List<EventRecord> events) {
        Map<Integer, RoundBuilder> byDay = new LinkedHashMap<>();
        for (EventRecord event : events) {
            RoundBuilder round = byDay.computeIfAbsent(event.dayNo(), RoundBuilder::new);
            switch (event.type()) {
                case "night_resolved" -> round.nightDeaths = ints(event.payload().get("deadSeatNos"));
                case "sheriff_signup_closed" -> round.sheriffCandidates = ints(event.payload().get("candidates"));
                case "sheriff_withdraw" -> {
                    Integer seat = integer(event.payload().get("seatNo"));
                    if (seat != null) {
                        round.sheriffWithdrew.add(seat);
                    }
                    round.hasSheriff = true;
                }
                case "sheriff_resolved" -> {
                    round.hasSheriff = true;
                    if (round.sheriffCandidates.isEmpty()) {
                        round.sheriffCandidates = ints(event.payload().get("candidates"));
                    }
                    round.sheriffTallies = tallies(event.result().get("tallies"));
                    round.sheriffElected = integer(event.result().get("electedSeatNo"));
                    round.sheriffLost = Boolean.TRUE.equals(event.result().get("lost"));
                }
                case "speech_order" -> round.speechOrder = new WolfbookDtos.JudgeSpeechOrderView(
                        integer(event.payload().get("startSeat")),
                        string(event.payload().get("direction")),
                        ints(event.payload().get("order"))
                );
                case "vote_resolved" -> round.exile.add(new WolfbookDtos.JudgeExileRoundView(
                        tallies(event.result().get("tallies")),
                        ints(event.result().get("abstainSeatNos")),
                        integer(event.payload().get("eliminatedSeatNo")),
                        ints(event.result().get("pkSeatNos"))
                ));
                case "hunter_shot", "badge_passed", "idiot_revealed", "force_out" -> round.skills.add(new WolfbookDtos.JudgeSkillRecordView(
                        event.type(),
                        integer(event.payload().get("actorSeatNo")),
                        integer(event.payload().get("targetSeatNo"))
                ));
                default -> {
                }
            }
            if (event.type().startsWith("sheriff_")) {
                round.hasSheriff = true;
            }
        }
        return byDay.values().stream()
                .filter(RoundBuilder::hasContent)
                .map(RoundBuilder::build)
                .toList();
    }

    private static final class RoundBuilder {
        private final int dayNo;
        private List<Integer> nightDeaths = List.of();
        private boolean nightResolved;
        private boolean hasSheriff;
        private List<Integer> sheriffCandidates = List.of();
        private final List<Integer> sheriffWithdrew = new ArrayList<>();
        private List<WolfbookDtos.JudgeTallyRecordView> sheriffTallies = List.of();
        private Integer sheriffElected;
        private boolean sheriffLost;
        private WolfbookDtos.JudgeSpeechOrderView speechOrder;
        private final List<WolfbookDtos.JudgeExileRoundView> exile = new ArrayList<>();
        private final List<WolfbookDtos.JudgeSkillRecordView> skills = new ArrayList<>();

        private RoundBuilder(int dayNo) {
            this.dayNo = dayNo;
        }

        private boolean hasContent() {
            return !nightDeaths.isEmpty() || hasSheriff || speechOrder != null || !exile.isEmpty() || !skills.isEmpty() || nightResolved;
        }

        private WolfbookDtos.JudgeRoundView build() {
            return new WolfbookDtos.JudgeRoundView(
                    dayNo,
                    nightDeaths,
                    hasSheriff ? new WolfbookDtos.JudgeSheriffRoundView(sheriffCandidates, List.copyOf(sheriffWithdrew), sheriffTallies, sheriffElected, sheriffLost) : null,
                    speechOrder,
                    List.copyOf(exile),
                    List.copyOf(skills)
            );
        }
    }

    private static List<WolfbookDtos.JudgeTallyRecordView> tallies(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<WolfbookDtos.JudgeTallyRecordView> tallies = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Integer target = integer(map.get("targetSeatNo"));
                if (target == null) {
                    continue;
                }
                Object weight = map.get("weight") != null ? map.get("weight") : map.get("voteCount");
                tallies.add(new WolfbookDtos.JudgeTallyRecordView(target, weight instanceof Number number ? number.doubleValue() : 0,
                        ints(map.get("voterSeatNos"))));
            }
        }
        return tallies;
    }

    private static List<Integer> ints(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(JudgeDayFlow::integer).filter(Objects::nonNull).toList();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
