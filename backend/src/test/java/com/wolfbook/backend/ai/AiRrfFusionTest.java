package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.model.AiRetrievalCandidate;
import com.wolfbook.backend.ai.service.AiRrfFusion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiRrfFusionTest {

    @Test
    void combinesMultipleRankedListsWithoutDuplicates() {
        AiRrfFusion fusion = new AiRrfFusion();
        AiRetrievalCandidate dancer = new AiRetrievalCandidate("c1", "舞者", "舞者技能", 0.91, "vector");
        AiRetrievalCandidate witch = new AiRetrievalCandidate("c2", "女巫", "女巫自救", 0.88, "vector");
        AiRetrievalCandidate dancerExact = new AiRetrievalCandidate("c1", "舞者", "舞者技能", 1.0, "fts");

        List<AiRetrievalCandidate> results = fusion.fuse(List.of(dancer, witch), List.of(dancerExact), 5);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().chunkUid()).isEqualTo("c1");
        assertThat(results.getFirst().source()).contains("vector", "fts");
    }
}
