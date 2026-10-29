package com.wolfbook.backend.service.judge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteResolverTests {

    private static final Set<Integer> ALL = Set.of(1, 2, 3, 4, 5, 6, 7, 8);

    @Test
    void sheriffVoteWeighsOnePointFive() {
        VoteResolver.Outcome outcome = VoteResolver.resolve(List.of(
                ballot(1, 4), ballot(2, 4),
                sheriff(3, 6), ballot(5, 6)
        ), ALL);
        assertEquals(6, outcome.eliminatedSeat());
        assertEquals(2.5, outcome.tallies().get(0).weight());
        assertEquals(List.of(3, 5), outcome.tallies().get(0).voterSeats());
    }

    @Test
    void tieReturnsTiedSeatsAndNoElimination() {
        VoteResolver.Outcome outcome = VoteResolver.resolve(List.of(ballot(1, 4), ballot(2, 6), ballot(3, null)), ALL);
        assertNull(outcome.eliminatedSeat());
        assertEquals(List.of(4, 6), outcome.tiedSeats());
        assertEquals(List.of(3), outcome.abstainSeats());
    }

    @Test
    void allAbstainEliminatesNobody() {
        VoteResolver.Outcome outcome = VoteResolver.resolve(List.of(ballot(1, null), ballot(2, null)), ALL);
        assertNull(outcome.eliminatedSeat());
        assertTrue(outcome.tiedSeats().isEmpty());
        assertTrue(outcome.tallies().isEmpty());
    }

    @Test
    void ballotsOutsideAllowedTargetsAreIgnored() {
        // PK：只能投平票的 4 号和 6 号
        VoteResolver.Outcome outcome = VoteResolver.resolve(List.of(ballot(1, 4), ballot(2, 5), ballot(3, 5)), Set.of(4, 6));
        assertEquals(4, outcome.eliminatedSeat());
        assertEquals(1, outcome.tallies().size());
    }

    private static VoteResolver.Ballot ballot(int voter, Integer target) {
        return new VoteResolver.Ballot(voter, target, false);
    }

    private static VoteResolver.Ballot sheriff(int voter, Integer target) {
        return new VoteResolver.Ballot(voter, target, true);
    }
}
