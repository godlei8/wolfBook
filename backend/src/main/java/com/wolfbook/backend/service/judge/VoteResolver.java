package com.wolfbook.backend.service.judge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投票结算（放逐和警长竞选共用）：警长一票算 1.5；最高票唯一则出局，并列返回平票者（调用方决定 PK 还是流失）；
 * targetSeat 为空是弃票。allowedTargets 之外的票作废（PK 时只能投平票者）。
 */
public final class VoteResolver {

    public static final double SHERIFF_WEIGHT = 1.5;

    public record Ballot(int voterSeat, Integer targetSeat, boolean sheriff) {
    }

    /** 投给同一个人的票：目标、加权票数、投票人座位（升序）。 */
    public record Tally(int targetSeat, double weight, List<Integer> voterSeats) {
    }

    public record Outcome(Integer eliminatedSeat, List<Integer> tiedSeats, List<Tally> tallies, List<Integer> abstainSeats) {
    }

    private VoteResolver() {
    }

    public static Outcome resolve(List<Ballot> ballots, Set<Integer> allowedTargets) {
        Map<Integer, List<Ballot>> byTarget = new LinkedHashMap<>();
        List<Integer> abstain = new ArrayList<>();
        for (Ballot ballot : ballots) {
            if (ballot.targetSeat() == null) {
                abstain.add(ballot.voterSeat());
            } else if (allowedTargets == null || allowedTargets.contains(ballot.targetSeat())) {
                byTarget.computeIfAbsent(ballot.targetSeat(), ignored -> new ArrayList<>()).add(ballot);
            }
        }
        List<Tally> tallies = byTarget.entrySet().stream()
                .map(entry -> new Tally(
                        entry.getKey(),
                        entry.getValue().stream().mapToDouble(ballot -> ballot.sheriff() ? SHERIFF_WEIGHT : 1).sum(),
                        entry.getValue().stream().map(Ballot::voterSeat).sorted().toList()
                ))
                .sorted(Comparator.comparingDouble(Tally::weight).reversed().thenComparingInt(Tally::targetSeat))
                .toList();
        if (tallies.isEmpty()) {
            return new Outcome(null, List.of(), List.of(), abstain.stream().sorted().toList());
        }
        double top = tallies.get(0).weight();
        List<Integer> leaders = tallies.stream().filter(tally -> tally.weight() == top).map(Tally::targetSeat).sorted().toList();
        return leaders.size() == 1
                ? new Outcome(leaders.get(0), List.of(), tallies, abstain.stream().sorted().toList())
                : new Outcome(null, leaders, tallies, abstain.stream().sorted().toList());
    }
}
