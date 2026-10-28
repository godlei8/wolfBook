package com.wolfbook.backend.service;

import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.RoleRelation;
import com.wolfbook.backend.domain.WorldLore;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.entity.WorldLoreEntity;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.mapper.WorldLoreMapper;
import com.wolfbook.backend.service.assistant.AssistantKnowledgeService;
import com.wolfbook.backend.support.DomainConverter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 世界观（夜镇编年史）：小镇简介、三方势力、编年史事件。整份存在 world_lore 表 id = 1 的一行 JSON 里，
 * 后台整份保存，小程序整份读取（事件里的出场角色展开成头像）。
 */
@Service
public class WorldService {

    static final int LORE_ID = 1;
    private static final Set<String> CAMPS = Set.of("good", "wolf", "third");
    private static final int MAX_FACTIONS = 6;
    private static final int MAX_EVENTS = 30;
    private static final int MAX_EVENT_ROLES = 10;

    private final WorldLoreMapper worldLoreMapper;
    private final RoleMapper roleMapper;
    private final BoardService boardService;
    private final AssistantKnowledgeService assistantKnowledgeService;
    private final DomainConverter converter;

    public WorldService(
            WorldLoreMapper worldLoreMapper,
            RoleMapper roleMapper,
            BoardService boardService,
            AssistantKnowledgeService assistantKnowledgeService,
            DomainConverter converter
    ) {
        this.worldLoreMapper = worldLoreMapper;
        this.roleMapper = roleMapper;
        this.boardService = boardService;
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.converter = converter;
    }

    /** 后台编辑用：原样返回，事件里只有 roleIds。 */
    public WorldLore getLore() {
        WorldLoreEntity entity = worldLoreMapper.selectById(LORE_ID);
        return entity == null ? WorldLore.empty() : converter.readWorldLore(entity.getContent());
    }

    /** 小程序用：事件里的出场角色展开成名字、真名和头像。 */
    public WolfbookDtos.WorldLoreView getView() {
        WorldLore lore = getLore();
        List<WolfbookDtos.WorldEventView> events = lore.events().stream()
                .map(event -> new WolfbookDtos.WorldEventView(
                        event.era(),
                        event.title(),
                        event.content(),
                        boardService.roleRelationViews(event.roleIds().stream().map(id -> new RoleRelation(id, null)).toList())
                ))
                .toList();
        return new WolfbookDtos.WorldLoreView(lore.townName(), lore.tagline(), lore.intro(), lore.coverImage(), lore.factions(), events);
    }

    public WorldLore save(WolfbookDtos.WorldLoreRequest request) {
        WorldLore lore = normalize(request);
        WorldLoreEntity entity = worldLoreMapper.selectById(LORE_ID);
        boolean created = entity == null;
        if (created) {
            entity = new WorldLoreEntity();
            entity.setId(LORE_ID);
        }
        entity.setContent(converter.writeWorldLore(lore));
        entity.setUpdateTime(LocalDateTime.now());
        if (created) {
            worldLoreMapper.insert(entity);
        } else {
            worldLoreMapper.updateById(entity);
        }
        assistantKnowledgeService.requestStructuredKnowledgeRebuild();
        return lore;
    }

    private WorldLore normalize(WolfbookDtos.WorldLoreRequest request) {
        List<WorldLore.Faction> factions = new ArrayList<>();
        for (WolfbookDtos.WorldFactionInput item : nullToEmpty(request.factions())) {
            String name = text(item.name());
            String description = text(item.description());
            if (name == null && description == null) {
                continue;
            }
            if (name == null) {
                throw new ApiException(4000, "势力要有名称");
            }
            String camp = item.camp() == null ? "good" : item.camp().trim();
            if (!CAMPS.contains(camp)) {
                throw new ApiException(4000, "势力阵营只能是好人、狼人或第三方");
            }
            factions.add(new WorldLore.Faction(name, camp, description));
        }
        if (factions.size() > MAX_FACTIONS) {
            throw new ApiException(4000, "势力最多 " + MAX_FACTIONS + " 个");
        }

        Set<Integer> existingRoleIds = roleMapper.selectList(null).stream().map(RoleEntity::getId).collect(Collectors.toSet());
        List<WorldLore.Event> events = new ArrayList<>();
        for (WolfbookDtos.WorldEventInput item : nullToEmpty(request.events())) {
            String title = text(item.title());
            String content = text(item.content());
            if (title == null && content == null) {
                continue;
            }
            if (title == null) {
                throw new ApiException(4000, "编年史事件要有标题");
            }
            Set<Integer> roleIds = new LinkedHashSet<>(nullToEmpty(item.roleIds()));
            if (roleIds.size() > MAX_EVENT_ROLES) {
                throw new ApiException(4000, "「" + title + "」的出场角色最多 " + MAX_EVENT_ROLES + " 个");
            }
            for (Integer roleId : roleIds) {
                if (roleId == null || !existingRoleIds.contains(roleId)) {
                    throw new ApiException(4000, "「" + title + "」里有不存在的角色");
                }
            }
            events.add(new WorldLore.Event(text(item.era()), title, content, List.copyOf(roleIds)));
        }
        if (events.size() > MAX_EVENTS) {
            throw new ApiException(4000, "编年史事件最多 " + MAX_EVENTS + " 个");
        }
        return new WorldLore(text(request.townName()), text(request.tagline()), text(request.intro()), text(request.coverImage()), factions, events);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
