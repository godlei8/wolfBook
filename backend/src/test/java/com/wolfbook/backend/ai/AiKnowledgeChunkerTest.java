package com.wolfbook.backend.ai;

import com.wolfbook.backend.ai.model.AiKnowledgeChunk;
import com.wolfbook.backend.ai.service.AiKnowledgeChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiKnowledgeChunkerTest {

    private final AiKnowledgeChunker chunker = new AiKnowledgeChunker();

    @Test
    void roleSectionsInheritNearestExplicitSubject() {
        String markdown = """
                # 狼人杀角色知识库（优化版）
                ## [ROLE-037] 舞者
                ### 技能说明
                舞者可以在夜间选择两名玩家交换技能作用目标。
                ### 玩法提示
                舞者需要结合警徽流和发言判断收益。
                ### FAQ 1
                问：舞者能否连续选择同一目标？
                答：以所在板子规则为准。
                ## [ROLE-001] 狼人
                ### 技能说明
                狼人夜间可以共同选择一名玩家击杀。
                """;

        List<AiKnowledgeChunk> chunks = chunker.chunk("roles", "狼人杀角色知识库（优化版）", markdown);

        assertThat(chunks)
                .filteredOn(chunk -> chunk.content().contains("舞者"))
                .allSatisfy(chunk -> assertThat(chunk.subject()).isEqualTo("舞者"));
        assertThat(chunks)
                .filteredOn(chunk -> chunk.content().contains("FAQ"))
                .allSatisfy(chunk -> assertThat(chunk.subject()).isEqualTo("舞者"));
    }

    @Test
    void aggregateTitlesAreNotUsedAsSubjects() {
        String markdown = """
                # 狼人杀术语大全
                ## 定义
                这是一段总览，不应该归属到定义主体。
                ## 术语：金水
                金水是预言家查验出来的好人身份信息。
                """;

        List<AiKnowledgeChunk> chunks = chunker.chunk("terms", "狼人杀术语大全", markdown);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("总览");
            assertThat(chunk.subject()).isNull();
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("金水");
            assertThat(chunk.subject()).isEqualTo("金水");
        });
    }
}
