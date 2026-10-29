package com.wolfbook.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.WorldLore;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.assistant.AssistantConfigService;
import com.wolfbook.backend.service.assistant.DeepSeekChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台「按世界观起草」：把写作规范（resources/prompts/role-writing-guide.md）、世界观和现有角色一起交给 DeepSeek，
 * 让它给一个新角色写真名、称号、身世和人物关系的草稿。只返回草稿，不落库，管理员改完自己保存。
 */
@Service
public class RoleDraftService {

    private static final Logger log = LoggerFactory.getLogger(RoleDraftService.class);
    private static final int MAX_RELATIONS = 3;
    private static final int MAX_LABEL = 20;
    private static final double TEMPERATURE = 0.8;

    private final DeepSeekChatService deepSeekChatService;
    private final AssistantConfigService assistantConfigService;
    private final WorldService worldService;
    private final RoleMapper roleMapper;
    private final ObjectMapper objectMapper;
    private final String writingGuide;

    public RoleDraftService(
            DeepSeekChatService deepSeekChatService,
            AssistantConfigService assistantConfigService,
            WorldService worldService,
            RoleMapper roleMapper,
            ObjectMapper objectMapper
    ) {
        this.deepSeekChatService = deepSeekChatService;
        this.assistantConfigService = assistantConfigService;
        this.worldService = worldService;
        this.roleMapper = roleMapper;
        this.objectMapper = objectMapper;
        this.writingGuide = readGuide();
    }

    public WolfbookDtos.RoleDraftView draft(WolfbookDtos.RoleDraftRequest request) {
        AssistantDtos.AdminAiConfig config = assistantConfigService.getRuntimeConfig();
        if (config.provider() == null || !StringUtils.hasText(config.provider().apiKey())) {
            throw new ApiException(4000, "还没有可用的模型，先到「AI 助手」页的模型管理里配一个");
        }
        List<RoleEntity> others = roleMapper.selectList(null).stream()
                .filter(role -> !role.getId().equals(request.roleId()))
                .toList();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(buildSystemPrompt(worldService.getLore(), others)),
                new UserMessage(buildUserPrompt(request))
        ));
        String content;
        try {
            content = deepSeekChatService.complete(prompt, config.provider(), TEMPERATURE);
        } catch (RuntimeException exception) {
            log.warn("Role draft request failed", exception);
            throw new ApiException(5000, describeFailure(exception));
        }
        WolfbookDtos.RoleDraftView draft = parseDraft(content, others, objectMapper);
        if (draft == null) {
            log.warn("Role draft could not be parsed: {}", content);
            throw new ApiException(5000, "AI 这次没写出能用的草稿，再点一次试试");
        }
        return draft;
    }

    private String buildSystemPrompt(WorldLore lore, List<RoleEntity> roles) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 Wolfbook 狼人杀世界观「夜镇编年史」的设定作者。下面是写作规范、世界观和已有角色，")
                .append("请为管理员给出的新角色写一份草稿，必须和已有设定衔接、不能推翻它们。\n\n");
        builder.append(writingGuide).append("\n\n");

        builder.append("# 世界观\n");
        if (lore.townName() != null) builder.append("小镇：").append(lore.townName()).append('\n');
        if (lore.intro() != null) builder.append(lore.intro()).append('\n');
        for (WorldLore.Faction faction : lore.factions()) {
            builder.append("势力「").append(faction.name()).append("」：").append(nullToEmpty(faction.description())).append('\n');
        }
        Map<Integer, String> names = roles.stream().collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName, (a, b) -> a));
        for (WorldLore.Event event : lore.events()) {
            builder.append("编年史 ").append(nullToEmpty(event.era())).append("「").append(event.title()).append("」：")
                    .append(nullToEmpty(event.content()));
            List<String> cast = event.roleIds().stream().map(names::get).filter(StringUtils::hasText).toList();
            if (!cast.isEmpty()) builder.append("（出场：").append(String.join("、", cast)).append("）");
            builder.append('\n');
        }

        builder.append("\n# 已有角色（角色名｜真名｜称号｜阵营｜身世）\n");
        for (RoleEntity role : roles) {
            builder.append(role.getName()).append("｜").append(nullToEmpty(role.getAlias())).append("｜")
                    .append(nullToEmpty(role.getTitle())).append("｜").append(role.getFaction()).append("｜")
                    .append(nullToEmpty(role.getBackground())).append('\n');
        }

        builder.append("""

                # 输出
                只输出一个 JSON 对象，不要任何解释、不要代码块标记：
                {"alias": "真名", "title": "称号", "background": "身世，100 到 150 字", "relations": [{"role": "已有角色的角色名", "label": "一句话关系，不超过 20 字"}]}
                relations 写 2 到 3 条，role 填上面「已有角色」里的角色名（比如「女巫」），不要填真名。
                """);
        return builder.toString();
    }

    private String buildUserPrompt(WolfbookDtos.RoleDraftRequest request) {
        StringBuilder builder = new StringBuilder("新角色：\n");
        builder.append("角色名：").append(request.name().trim()).append('\n');
        builder.append("阵营：").append(nullToEmpty(request.faction())).append('\n');
        builder.append("类型：").append(nullToEmpty(request.roleType())).append('\n');
        builder.append("技能：").append(nullToEmpty(request.skill())).append('\n');
        if (StringUtils.hasText(request.notes())) {
            builder.append("管理员的补充要求：").append(request.notes().trim()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 解析 AI 的回复：容忍代码块标记和前后废话，只取第一个 { 到最后一个 }。
     * 关系按角色名找 id，找不到的、重复的、指向自己的丢掉，最多 3 条；字段超长就截断。解析不出来返回 null。
     */
    static WolfbookDtos.RoleDraftView parseDraft(String content, List<RoleEntity> roles, ObjectMapper objectMapper) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content.substring(start, end + 1));
        } catch (IOException exception) {
            return null;
        }
        String alias = clip(root.path("alias").asText(""), 50);
        String title = clip(root.path("title").asText(""), 40);
        String background = root.path("background").asText("").trim();
        if (alias.isEmpty() && title.isEmpty() && background.isEmpty()) {
            return null;
        }
        // 模型有时用真名（薇洛·艾许）或只写名（薇洛）指代角色，三种都认；角色名优先
        Map<String, Integer> idsByName = new java.util.HashMap<>();
        for (RoleEntity role : roles) {
            if (StringUtils.hasText(role.getAlias())) {
                String realName = role.getAlias().trim();
                idsByName.putIfAbsent(realName, role.getId());
                idsByName.putIfAbsent(realName.split("[·&]")[0].trim(), role.getId());
            }
        }
        for (RoleEntity role : roles) {
            idsByName.put(role.getName().trim(), role.getId());
        }
        List<WolfbookDtos.RoleRelationInput> relations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : root.path("relations")) {
            Integer roleId = idsByName.get(item.path("role").asText("").trim());
            String label = clip(item.path("label").asText(""), MAX_LABEL);
            if (roleId == null || label.isEmpty() || !seen.add(roleId)) {
                continue;
            }
            relations.add(new WolfbookDtos.RoleRelationInput(roleId, label));
            if (relations.size() == MAX_RELATIONS) break;
        }
        return new WolfbookDtos.RoleDraftView(alias, title, background, relations);
    }

    /** DeepSeek 返回的常见错误翻成管理员能处理的话；只看状态码，不把对方的原始报错透出去。 */
    static String describeFailure(Throwable exception) {
        String message = "";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) message += cause.getMessage();
        }
        if (message.contains("status 402")) return "模型账户余额不足，充值或在「AI 助手」里换个模型再试";
        if (message.contains("status 401")) return "DeepSeek Key 无效，到「AI 助手」页检查一下";
        if (message.contains("status 429")) return "DeepSeek 请求太频繁，过一会儿再试";
        return "AI 起草失败，稍后再试";
    }

    private static String clip(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String readGuide() {
        try {
            return new ClassPathResource("prompts/role-writing-guide.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing prompts/role-writing-guide.md", exception);
        }
    }
}
