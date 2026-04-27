package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.model.AiQueryPlan;
import com.wolfbook.backend.ai.model.AiRetrievalContext;
import com.wolfbook.backend.ai.service.AiQueryPlanner;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiQueryPlannerTest {

    private final AiQueryPlanner planner = new AiQueryPlanner();

    @Test
    void currentExplicitSubjectWinsOverPreviousContext() {
        AiRetrievalContext context = new AiRetrievalContext("自爆", Set.of("自爆"));

        AiQueryPlan plan = planner.plan("舞者技能", Set.of("自爆", "舞者", "金水"), context);

        assertThat(plan.subject()).isEqualTo("舞者");
        assertThat(plan.searchQuery()).contains("舞者");
        assertThat(plan.searchQuery()).doesNotContain("自爆");
        assertThat(plan.outOfScope()).isFalse();
    }

    @Test
    void followUpMayInheritConfirmedSubject() {
        AiRetrievalContext context = new AiRetrievalContext("舞者", Set.of("舞者"));

        AiQueryPlan plan = planner.plan("这个技能怎么用", Set.of("舞者", "自爆"), context);

        assertThat(plan.subject()).isEqualTo("舞者");
        assertThat(plan.searchQuery()).contains("舞者", "这个技能怎么用");
    }

    @Test
    void rejectsClearlyNonWerewolfQuestions() {
        AiQueryPlan plan = planner.plan("今天北京天气怎么样", Set.of("舞者", "自爆"), AiRetrievalContext.empty());

        assertThat(plan.outOfScope()).isTrue();
        assertThat(plan.intent()).isEqualTo("OUT_OF_SCOPE");
    }
}
