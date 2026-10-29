package com.wolfbook.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.RoleEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 「按世界观起草」里解析 AI 回复的部分：AI 输出不总是干净的 JSON，这里兜住常见的几种情况。 */
class RoleDraftServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RoleEntity> roles = List.of(role(1, "预言家"), role(2, "女巫"), role(4, "守卫"), role(6, "狼人"));

    private static RoleEntity role(int id, String name) {
        RoleEntity entity = new RoleEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    @Test
    void shouldMatchRelationsByRealNameOrGivenName() {
        RoleEntity witch = role(2, "女巫");
        witch.setAlias("薇洛·艾许");
        RoleEntity villagers = role(9, "平民");
        villagers.setAlias("本·贝克 & 梅·索恩");
        String content = """
                {"alias": "艾略特·诺克斯", "title": "品血绅士", "background": "没落贵族的末裔。",
                 "relations": [{"role": "薇洛·艾许", "label": "认得她的银瓶"}, {"role": "本", "label": "提灯的孩子"}, {"role": "预言家", "label": "看不透他"}]}
                """;
        WolfbookDtos.RoleDraftView draft = RoleDraftService.parseDraft(content, List.of(role(1, "预言家"), witch, villagers), objectMapper);
        assertNotNull(draft);
        assertEquals(List.of(2, 9, 1), draft.relations().stream().map(WolfbookDtos.RoleRelationInput::roleId).toList());
    }

    @Test
    void shouldReadJsonWrappedInCodeFenceAndChatter() {
        String content = """
                好的，草稿如下：
                ```json
                {"alias": " 伊薇特·莱恩 ", "title": "钟楼信使", "background": "她是钟楼下长大的信使。",
                 "relations": [{"role": "女巫", "label": "常去买药"}, {"role": "狼人", "label": "送过信的怪客人"}]}
                ```
                """;
        WolfbookDtos.RoleDraftView draft = RoleDraftService.parseDraft(content, roles, objectMapper);
        assertNotNull(draft);
        assertEquals("伊薇特·莱恩", draft.alias());
        assertEquals("钟楼信使", draft.title());
        assertEquals(2, draft.relations().size());
        assertEquals(2, draft.relations().get(0).roleId());
        assertEquals(6, draft.relations().get(1).roleId());
    }

    @Test
    void shouldDropUnknownAndDuplicateRolesAndKeepAtMostThree() {
        String content = """
                {"alias": "甲", "title": "乙", "background": "丙",
                 "relations": [
                   {"role": "不存在的人", "label": "路人"},
                   {"role": "预言家", "label": "邻居"},
                   {"role": "预言家", "label": "重复"},
                   {"role": "女巫", "label": ""},
                   {"role": "守卫", "label": "旧识"},
                   {"role": "狼人", "label": "仇人"},
                   {"role": "女巫", "label": "第四条"}
                 ]}
                """;
        WolfbookDtos.RoleDraftView draft = RoleDraftService.parseDraft(content, roles, objectMapper);
        assertNotNull(draft);
        assertEquals(List.of(1, 4, 6), draft.relations().stream().map(WolfbookDtos.RoleRelationInput::roleId).toList());
        assertEquals("邻居", draft.relations().get(0).label());
    }

    @Test
    void shouldClipOverlongFields() {
        String longText = "长".repeat(80);
        String content = "{\"alias\": \"" + longText + "\", \"title\": \"" + longText + "\", \"background\": \"身世\","
                + " \"relations\": [{\"role\": \"预言家\", \"label\": \"" + longText + "\"}]}";
        WolfbookDtos.RoleDraftView draft = RoleDraftService.parseDraft(content, roles, objectMapper);
        assertNotNull(draft);
        assertEquals(50, draft.alias().length());
        assertEquals(40, draft.title().length());
        assertEquals(20, draft.relations().get(0).label().length());
    }

    @Test
    void shouldExplainCommonDeepSeekFailures() {
        RuntimeException noBalance = new IllegalStateException("DeepSeek completion failed",
                new IllegalStateException("DeepSeek request failed with status 402: {\"error\":\"Insufficient Balance\"}"));
        assertEquals("模型账户余额不足，充值或在「AI 助手」里换个模型再试", RoleDraftService.describeFailure(noBalance));
        assertEquals("AI 起草失败，稍后再试", RoleDraftService.describeFailure(new IllegalStateException("timeout")));
    }

    @Test
    void shouldReturnNullForGarbageOrEmptyDraft() {
        assertNull(RoleDraftService.parseDraft(null, roles, objectMapper));
        assertNull(RoleDraftService.parseDraft("抱歉，我写不出来。", roles, objectMapper));
        assertNull(RoleDraftService.parseDraft("{not json}", roles, objectMapper));
        assertNull(RoleDraftService.parseDraft("{\"alias\": \"\", \"title\": \"\", \"background\": \"\"}", roles, objectMapper));
    }
}
