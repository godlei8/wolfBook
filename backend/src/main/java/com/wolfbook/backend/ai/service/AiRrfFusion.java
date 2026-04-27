package com.wolfbook.backend.ai.service;

import com.wolfbook.backend.ai.model.AiRetrievalCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiRrfFusion {

    private static final double RRF_K = 60.0;

    @SafeVarargs
    public final List<AiRetrievalCandidate> fuse(List<AiRetrievalCandidate>... rankedLists) {
        return fuse(10, rankedLists);
    }

    public List<AiRetrievalCandidate> fuse(List<AiRetrievalCandidate> first, List<AiRetrievalCandidate> second, int limit) {
        return fuse(limit, first, second);
    }

    @SafeVarargs
    public final List<AiRetrievalCandidate> fuse(int limit, List<AiRetrievalCandidate>... rankedLists) {
        Map<String, AiRetrievalCandidate> candidates = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> sources = new LinkedHashMap<>();
        for (List<AiRetrievalCandidate> rankedList : rankedLists) {
            if (rankedList == null) {
                continue;
            }
            for (int index = 0; index < rankedList.size(); index++) {
                AiRetrievalCandidate candidate = rankedList.get(index);
                candidates.putIfAbsent(candidate.chunkUid(), candidate);
                scores.merge(candidate.chunkUid(), 1.0 / (RRF_K + index + 1), Double::sum);
                sources.computeIfAbsent(candidate.chunkUid(), ignored -> new ArrayList<>()).add(candidate.source());
            }
        }
        return candidates.values().stream()
                .map(candidate -> candidate.withScoreAndSource(
                        scores.getOrDefault(candidate.chunkUid(), candidate.score()),
                        String.join("+", sources.getOrDefault(candidate.chunkUid(), List.of(candidate.source())))
                ))
                .sorted(Comparator.comparingDouble(AiRetrievalCandidate::score).reversed())
                .limit(Math.max(limit, 0))
                .toList();
    }
}
