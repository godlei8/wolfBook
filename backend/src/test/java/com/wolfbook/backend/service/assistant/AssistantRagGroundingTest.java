package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.entity.AssistantDocumentEntity;
import com.wolfbook.backend.entity.AssistantKnowledgeChunkEntity;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.AssistantKnowledgeChunkMapper;
import com.wolfbook.backend.mapper.AssistantDocumentMapper;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.assistant.AssistantPublishService;
import com.wolfbook.backend.service.BoardService;
import com.wolfbook.backend.config.AssistantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.support.UploadProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantRagGroundingTest {

    @Test
    void plannerLinksDancerRoleInsteadOfMaskBoardOrRole() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "舞者", null), role(2, "假面", null)),
                List.of(board(13, "假面舞会"))
        );
        AssistantQueryPlanner planner = new AssistantQueryPlanner(catalog);

        AssistantQueryPlan plan = planner.plan("搜舞者的信息");

        assertThat(plan.subject()).isNotNull();
        assertThat(plan.subject().sourceKey()).isEqualTo("ROLE:1");
        assertThat(plan.strictSubject()).isTrue();
    }

    @Test
    void plannerPrefersGlossaryTermOverDomainRole() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "狼人", null), role(2, "预言家", null)),
                List.of()
        );
        AssistantQueryPlanner planner = new AssistantQueryPlanner(catalog);

        AssistantQueryPlan plan = planner.plan("金水在狼人杀游戏里啥意思");

        assertThat(plan.subject()).isNotNull();
        assertThat(plan.subject().sourceKey()).isEqualTo("TERM:金水");
        assertThat(plan.strictSubject()).isTrue();
    }

    @Test
    void indexerSplitsMixedInlineRoleEntriesIntoSeparateSubjectChunks() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "舞者", null), role(2, "假面", null)),
                List.of(board(13, "假面舞会"))
        );
        AssistantKnowledgeIndexer indexer = new AssistantKnowledgeIndexer(
                mock(AssistantKnowledgeChunkMapper.class),
                mock(ObjectProvider.class),
                catalog
        );
        AssistantDocumentEntity document = new AssistantDocumentEntity();
        document.setId(10);
        document.setName("角色大全");
        document.setSourceType(AssistantConstants.SOURCE_DOCUMENT);
        document.setSourceKey("DOCUMENT:test");
        document.setContentText("""
                ### 13. 假面（京城大师赛·假面舞会）
                - 舞者（京城大师赛·假面舞会） - 技能：起舞，从第二夜开始必须选择三名玩家共舞。
                - 假面（京城大师赛·假面舞会） - 技能：面具，不与狼队见面。
                """);

        List<AssistantKnowledgeChunkEntity> chunks = indexer.buildChunks(document);

        assertThat(chunks)
                .extracting(AssistantKnowledgeChunkEntity::getSubjectKey)
                .contains("ROLE:1", "ROLE:2");
        AssistantKnowledgeChunkEntity dancer = chunks.stream()
                .filter(chunk -> "ROLE:1".equals(chunk.getSubjectKey()))
                .findFirst()
                .orElseThrow();
        assertThat(dancer.getContentText()).contains("起舞");
        assertThat(dancer.getContentText()).doesNotContain("面具");
    }

    @Test
    void indexerCreatesSeparateGlossarySubjectChunks() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "狼人", null), role(2, "预言家", null)),
                List.of()
        );
        AssistantKnowledgeIndexer indexer = new AssistantKnowledgeIndexer(
                mock(AssistantKnowledgeChunkMapper.class),
                mock(ObjectProvider.class),
                catalog
        );
        AssistantDocumentEntity document = new AssistantDocumentEntity();
        document.setId(11);
        document.setName("狼人杀术语");
        document.setSourceType(AssistantConstants.SOURCE_DOCUMENT);
        document.setSourceKey("DOCUMENT:terms");
        document.setContentText("""
                - 金水：预言家查验后显示为好人的玩家，通常不等于铁好人。
                - 狼人：夜间与狼队共同刀人。
                """);

        List<AssistantKnowledgeChunkEntity> chunks = indexer.buildChunks(document);

        AssistantKnowledgeChunkEntity goldWater = chunks.stream()
                .filter(chunk -> "TERM:金水".equals(chunk.getSubjectKey()))
                .findFirst()
                .orElseThrow();
        assertThat(goldWater.getChunkKind()).isEqualTo("GLOSSARY");
        assertThat(goldWater.getContentText()).contains("预言家查验");
        assertThat(goldWater.getContentText()).doesNotContain("共同刀人");
    }

    @Test
    void rerankerKeepsOnlyStrictSubjectHits() {
        AssistantReranker reranker = new AssistantReranker();
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "舞者的信息",
                "舞者的信息",
                new AssistantSubject("ROLE", "ROLE:1", "1", "舞者", List.of("舞者"), 100),
                true,
                false,
                false,
                List.of("舞者")
        );

        List<AssistantKnowledgeService.KnowledgeHit> hits = reranker.rerank(List.of(
                hit("舞者", "ROLE:1", "起舞", 20),
                hit("假面", "ROLE:2", "面具", 999)
        ), plan, 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().subjectKey()).isEqualTo("ROLE:1");
        assertThat(hits.getFirst().contextText()).contains("起舞");
    }

    @Test
    void validatorRejectsAnswerThatMentionsAnotherConcreteSubject() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "舞者", null), role(2, "假面", null)),
                List.of(board(13, "假面舞会"))
        );
        AssistantAnswerValidator validator = new AssistantAnswerValidator(catalog);
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "舞者的信息",
                "舞者的信息",
                new AssistantSubject("ROLE", "ROLE:1", "1", "舞者", List.of("舞者"), 100),
                true,
                false,
                false,
                List.of("舞者")
        );

        AssistantAnswerValidation validation = validator.validate("## 结论\n\n- 舞者可以起舞。\n- 假面拥有面具技能。", plan, List.of());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo("SUBJECT_DRIFT");
    }

    @Test
    void templateAnswerKeepsMultipleStrictSubjectSections() {
        AssistantAnswerGenerationService generationService = new AssistantAnswerGenerationService(mock(ObjectProvider.class));
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "假面",
                "假面",
                new AssistantSubject("ROLE", "ROLE:2", "2", "假面", List.of("假面"), 100),
                true,
                false,
                false,
                List.of("假面")
        );

        String answer = generationService.templateKnowledgeAnswer(plan, List.of(
                hit("假面 / 基础信息", "ROLE:2", "角色名称：假面\n阵营：好人\n类型：神职\n定位：功能位", 40, "BASE"),
                hit("假面 / 技能", "ROLE:2", "技能：面具，不与狼队见面，也不参与第一夜的刀人。", 60, "SKILL"),
                hit("假面 / 规则补充", "ROLE:2", "若被选中的玩家当晚恰好在舞池中，法官会秘密反转其阵营归属。", 50, "RULE"),
                hit("假面 / FAQ", "ROLE:2", "Q：假面第一晚能发动技能吗 -> A：不能，从第二夜开始。", 30, "FAQ")
        ));

        assertThat(answer).contains("### 基础信息");
        assertThat(answer).contains("### 技能与限制");
        assertThat(answer).contains("### 规则补充");
        assertThat(answer).contains("### 常见问题");
        assertThat(answer).contains("## 证据引用");
        assertThat(answer).contains("## 置信度");
        assertThat(answer).contains("## 后续建议");
        assertThat(answer).contains("不与狼队见面");
        assertThat(answer).contains("从第二夜开始");
        assertThat(answer).contains("[E");
    }

    @Test
    void templateAnswerExplainsGlossaryTermWithoutNeighborRoleContent() {
        AssistantAnswerGenerationService generationService = new AssistantAnswerGenerationService(mock(ObjectProvider.class));
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "金水在狼人杀游戏里啥意思",
                "金水在狼人杀游戏里啥意思",
                new AssistantSubject("TERM", "TERM:金水", "金水", "金水", List.of("金水"), 100),
                true,
                false,
                false,
                List.of("金水")
        );

        List<AssistantKnowledgeService.KnowledgeHit> filteredHits = new AssistantReranker().rerank(List.of(
                hit("金水 / 狼人杀术语", "TERM:金水", "金水：预言家查验后显示为好人的玩家，通常只能说明查验结果，不代表绝对铁好人。", 80, "GLOSSARY"),
                hit("狼人 / 狼人杀术语", "ROLE:1", "狼人：夜间与狼队共同刀人。", 999, "GLOSSARY")
        ), plan, 10);

        String answer = generationService.templateKnowledgeAnswer(plan, filteredHits);

        assertThat(answer).contains("### 术语解释");
        assertThat(answer).contains("## 证据引用");
        assertThat(answer).contains("预言家查验");
        assertThat(answer).doesNotContain("共同刀人");
    }

    @Test
    void validatorRejectsConclusionWithoutEvidenceCoverage() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "舞者", null)),
                List.of()
        );
        AssistantAnswerValidator validator = new AssistantAnswerValidator(catalog);
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "舞者的信息",
                "舞者的信息",
                new AssistantSubject("ROLE", "ROLE:1", "1", "舞者", List.of("舞者"), 100),
                true,
                false,
                false,
                List.of("舞者")
        );

        AssistantAnswerValidation validation = validator.validate("""
                ## 结论

                - 舞者从第二夜开始起舞。

                ## 证据引用

                - [E1] 舞者 / 技能：从第二夜开始必须选择三名玩家共舞。

                ## 置信度

                - 中（0.8）
                """, plan, List.of());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo("COVERAGE_GAP");
    }

    @Test
    void validatorMarksLowConfidenceAnswerAsInvalid() {
        AssistantSubjectCatalog catalog = subjectCatalog(
                List.of(role(1, "舞者", null)),
                List.of()
        );
        AssistantAnswerValidator validator = new AssistantAnswerValidator(catalog);
        AssistantQueryPlan plan = new AssistantQueryPlan(
                "舞者的信息",
                "舞者的信息",
                new AssistantSubject("ROLE", "ROLE:1", "1", "舞者", List.of("舞者"), 100),
                true,
                false,
                false,
                List.of("舞者")
        );

        AssistantAnswerValidation validation = validator.validate("""
                ## 结论

                - 舞者技能是起舞。[E1]

                ## 证据引用

                - [E1] 舞者 / 技能：从第二夜开始必须选择三名玩家共舞。

                ## 置信度

                - 低（0.3）
                """, plan, List.of());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void knowledgePruningKeepsMultipleChunksFromSameStructuredSubject() {
        AssistantKnowledgeService knowledgeService = new AssistantKnowledgeService(
                mock(AssistantDocumentMapper.class),
                mock(AssistantKnowledgeChunkMapper.class),
                mock(AssistantPublishService.class),
                mock(BoardService.class),
                mock(RoleMapper.class),
                new ObjectMapper(),
                new AssistantProperties(),
                mock(ObjectProvider.class),
                mock(AssistantKnowledgeIndexer.class),
                mock(UploadProvider.class)
        );

        List<AssistantKnowledgeService.KnowledgeHit> pruned = knowledgeService.pruneHits(List.of(
                hit("舞者 / 基础信息", "ROLE:1", "角色名称：舞者", 60, "BASE", "chunk-base"),
                hit("舞者 / 技能", "ROLE:1", "技能：起舞", 55, "SKILL", "chunk-skill"),
                hit("舞者 / FAQ", "ROLE:1", "Q：能否自选 -> A：不能", 50, "FAQ", "chunk-faq")
        ), 10);

        assertThat(pruned).hasSize(3);
        assertThat(pruned).extracting(AssistantKnowledgeService.KnowledgeHit::chunkKind)
                .containsExactly("BASE", "SKILL", "FAQ");
    }

    private AssistantKnowledgeService.KnowledgeHit hit(String title, String subjectKey, String context, double score) {
        return hit(title, subjectKey, context, score, "SKILL");
    }

    private AssistantKnowledgeService.KnowledgeHit hit(String title, String subjectKey, String context, double score, String chunkKind) {
        return hit(title, subjectKey, context, score, chunkKind, "chunk-" + subjectKey + "-" + chunkKind);
    }

    private AssistantKnowledgeService.KnowledgeHit hit(String title, String subjectKey, String context, double score, String chunkKind, String chunkUid) {
        AssistantDocumentEntity document = new AssistantDocumentEntity();
        document.setSourceKey(subjectKey);
        return new AssistantKnowledgeService.KnowledgeHit(
                document,
                title,
                AssistantConstants.SOURCE_STRUCTURED,
                context,
                context,
                score,
                subjectKey.replace("ROLE:", ""),
                chunkUid,
                subjectKey,
                title,
                chunkKind
        );
    }

    private AssistantSubjectCatalog subjectCatalog(List<RoleEntity> roles, List<Board> boards) {
        RoleMapper roleMapper = mock(RoleMapper.class);
        BoardService boardService = mock(BoardService.class);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(roles);
        when(boardService.listAllBoards()).thenReturn(boards);
        return new AssistantSubjectCatalog(roleMapper, boardService);
    }

    private RoleEntity role(Integer id, String name, String alias) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setName(name);
        role.setAlias(alias);
        role.setFaction("好人");
        role.setRoleType("神职");
        role.setCamp("好人阵营");
        role.setSkill(name + "技能");
        return role;
    }

    private Board board(Integer id, String name) {
        return new Board(
                id,
                name,
                12,
                "进阶",
                List.of("高配"),
                null,
                "简介",
                List.of(),
                List.of(),
                List.of(),
                "屠边",
                "标准板",
                1,
                List.of()
        );
    }
}
