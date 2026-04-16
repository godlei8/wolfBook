package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.FaqItem;
import com.wolfbook.backend.domain.Role;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.BoardEntity;
import com.wolfbook.backend.entity.BoardRoleEntity;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.BoardMapper;
import com.wolfbook.backend.mapper.BoardRoleMapper;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.JudgeSupportLevels;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 板子和角色资料服务。
 *
 * <p>负责前台板库查询、后台板子/角色 CRUD、板子角色配置、FAQ 读写，
 * 以及给法官局、AI 助手等模块提供结构化的板子/角色资料。</p>
 */
@Service
public class BoardService {

    private static final String FACTION_GOOD = "好人";
    private static final String FACTION_WOLF = "狼人";
    private static final String FACTION_THIRD = "第三方";
    private static final String TYPE_VILLAGER = "平民";

    private final BoardMapper boardMapper;
    private final BoardRoleMapper boardRoleMapper;
    private final RoleMapper roleMapper;
    private final DomainConverter converter;

    public BoardService(BoardMapper boardMapper, BoardRoleMapper boardRoleMapper, RoleMapper roleMapper, DomainConverter converter) {
        this.boardMapper = boardMapper;
        this.boardRoleMapper = boardRoleMapper;
        this.roleMapper = roleMapper;
        this.converter = converter;
    }

    public PageResponse<WolfbookDtos.BoardCardView> listBoards(String playerCount, String difficulty, String tag, String keyword, int page, int size) {
        List<BoardEntity> boardEntities = boardMapper.selectList(
                new LambdaQueryWrapper<BoardEntity>()
                        .eq(BoardEntity::getStatus, 1)
                        .orderByAsc(BoardEntity::getId)
        );
        Map<Integer, List<BoardRoleEntity>> roleMap = loadBoardRoleMap(boardEntities.stream().map(BoardEntity::getId).toList());
        Map<Integer, RoleEntity> rolesById = loadRolesById(
                roleMap.values().stream().flatMap(List::stream).map(BoardRoleEntity::getRoleId).collect(Collectors.toSet())
        );

        List<WolfbookDtos.BoardCardView> all = boardEntities.stream()
                .filter(board -> matchesPlayerCount(board, playerCount))
                .filter(board -> isBlank(difficulty) || "全部".equals(difficulty) || "all".equalsIgnoreCase(difficulty) || board.getDifficulty().equalsIgnoreCase(difficulty))
                .filter(board -> matchesTag(board, tag))
                .filter(board -> isBlank(keyword) || board.getName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                .map(board -> toBoardCardView(board, roleMap.getOrDefault(board.getId(), List.of()), rolesById))
                .toList();

        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int fromIndex = Math.min((safePage - 1) * safeSize, all.size());
        int toIndex = Math.min(fromIndex + safeSize, all.size());
        return new PageResponse<>(all.subList(fromIndex, toIndex), all.size(), safePage, safeSize);
    }

    public List<WolfbookDtos.BoardCardView> listBoardCardsByIds(List<Integer> boardIds) {
        if (boardIds == null || boardIds.isEmpty()) {
            return List.of();
        }
        List<BoardEntity> boardEntities = boardMapper.selectBatchIds(boardIds).stream()
                .filter(board -> board.getStatus() != null && board.getStatus() == 1)
                .toList();
        if (boardEntities.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<BoardRoleEntity>> roleMap = loadBoardRoleMap(boardEntities.stream().map(BoardEntity::getId).toList());
        Map<Integer, RoleEntity> rolesById = loadRolesById(
                roleMap.values().stream().flatMap(List::stream).map(BoardRoleEntity::getRoleId).collect(Collectors.toSet())
        );
        Map<Integer, WolfbookDtos.BoardCardView> viewMap = boardEntities.stream()
                .map(board -> toBoardCardView(board, roleMap.getOrDefault(board.getId(), List.of()), rolesById))
                .collect(Collectors.toMap(WolfbookDtos.BoardCardView::id, item -> item));
        return boardIds.stream()
                .map(viewMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public WolfbookDtos.BoardDetailView getBoardDetail(Integer id) {
        BoardEntity boardEntity = getBoardEntity(id);
        List<BoardRoleEntity> roleEntities = boardRoleMapper.selectList(
                new LambdaQueryWrapper<BoardRoleEntity>()
                        .eq(BoardRoleEntity::getBoardId, id)
        );
        Map<Integer, RoleEntity> rolesById = loadRolesById(roleEntities.stream().map(BoardRoleEntity::getRoleId).collect(Collectors.toSet()));
        List<WolfbookDtos.BoardRoleView> roles = roleEntities.stream()
                .map(item -> toBoardRoleView(item, rolesById))
                .toList();

        return new WolfbookDtos.BoardDetailView(
                boardEntity.getId(),
                boardEntity.getName(),
                boardEntity.getPlayerCount(),
                boardEntity.getDifficulty(),
                converter.readStringList(boardEntity.getTags()),
                boardEntity.getCoverImage(),
                boardEntity.getBriefConfig(),
                boardEntity.getCardDescription(),
                converter.readStringList(boardEntity.getSpecialRules()),
                converter.readStringList(boardEntity.getTips()),
                converter.readFaqList(boardEntity.getFaqs()).stream().map(this::toFaqInput).toList(),
                boardEntity.getWinCondition(),
                boardEntity.getRuleType(),
                roles,
                JudgeSupportLevels.normalize(boardEntity.getJudgeSupportLevel())
        );
    }

    public List<WolfbookDtos.RoleListItemView> listRoles(String camp) {
        return roleMapper.selectList(
                        new LambdaQueryWrapper<RoleEntity>()
                                .orderByAsc(RoleEntity::getId)
                ).stream()
                .filter(role -> matchesRoleGroup(role, camp))
                .map(role -> new WolfbookDtos.RoleListItemView(
                        role.getId(),
                        role.getName(),
                        role.getAlias(),
                        role.getFaction(),
                        role.getRoleType(),
                        role.getCamp(),
                        role.getPortrait()
                ))
                .toList();
    }

    public WolfbookDtos.RoleDetailView getRoleDetail(Integer id) {
        RoleEntity role = getRoleEntity(id);
        List<Integer> boardIds = boardRoleMapper.selectList(
                        new LambdaQueryWrapper<BoardRoleEntity>()
                                .eq(BoardRoleEntity::getRoleId, id)
                ).stream()
                .map(BoardRoleEntity::getBoardId)
                .distinct()
                .toList();

        List<WolfbookDtos.RelatedBoardView> boards = boardIds.isEmpty()
                ? List.of()
                : boardMapper.selectBatchIds(boardIds).stream()
                .sorted(Comparator.comparing(BoardEntity::getId))
                .map(board -> new WolfbookDtos.RelatedBoardView(board.getId(), board.getName(), board.getCoverImage(), board.getPlayerCount()))
                .toList();

        return new WolfbookDtos.RoleDetailView(
                role.getId(),
                role.getName(),
                role.getAlias(),
                role.getFaction(),
                role.getRoleType(),
                role.getCamp(),
                role.getSkill(),
                role.getBackground(),
                converter.readFaqList(role.getFaqs()).stream().map(this::toFaqInput).toList(),
                role.getPortrait(),
                role.getFullIllustration(),
                boards
        );
    }

    public List<Board> listAllBoards() {
        List<BoardEntity> boardEntities = boardMapper.selectList(new LambdaQueryWrapper<BoardEntity>().orderByAsc(BoardEntity::getId));
        Map<Integer, List<BoardRoleEntity>> roleMap = loadBoardRoleMap(boardEntities.stream().map(BoardEntity::getId).toList());
        return boardEntities.stream()
                .map(board -> converter.toBoard(board, roleMap.getOrDefault(board.getId(), List.of())))
                .toList();
    }

    public Board getBoard(Integer id) {
        BoardEntity boardEntity = getBoardEntity(id);
        List<BoardRoleEntity> roleEntities = boardRoleMapper.selectList(
                new LambdaQueryWrapper<BoardRoleEntity>()
                        .eq(BoardRoleEntity::getBoardId, id)
        );
        return converter.toBoard(boardEntity, roleEntities);
    }

    public Role getRole(Integer id) {
        return converter.toRole(getRoleEntity(id));
    }

    public String buildBriefConfig(List<WolfbookDtos.BoardRoleInput> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        Map<Integer, RoleEntity> rolesById = loadRolesById(roles.stream().map(WolfbookDtos.BoardRoleInput::roleId).collect(Collectors.toSet()));
        return roles.stream()
                .map(item -> {
                    RoleEntity role = rolesById.get(item.roleId());
                    if (role == null) {
                        throw new ApiException(4004, "角色不存在");
                    }
                    return role.getName() + "x" + item.count();
                })
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    public String buildCampLabel(String faction, String roleType) {
        if (isBlank(faction) && isBlank(roleType)) {
            return "";
        }
        if (isBlank(roleType)) {
            return faction;
        }
        return faction + "·" + roleType;
    }

    private WolfbookDtos.BoardCardView toBoardCardView(BoardEntity board, List<BoardRoleEntity> roleEntities, Map<Integer, RoleEntity> rolesById) {
        List<WolfbookDtos.BoardRoleView> roleViews = roleEntities.stream()
                .map(item -> toBoardRoleView(item, rolesById))
                .toList();
        List<WolfbookDtos.BoardRoleView> featuredRoles = roleViews.stream()
                .filter(this::shouldShowFeaturedRole)
                .limit(7)
                .toList();
        return new WolfbookDtos.BoardCardView(
                board.getId(),
                board.getName(),
                board.getPlayerCount(),
                board.getDifficulty(),
                converter.readStringList(board.getTags()),
                board.getCoverImage(),
                board.getBriefConfig(),
                board.getCardDescription(),
                buildCampSummary(roleViews),
                buildLineupSummary(roleViews),
                roleViews,
                featuredRoles,
                buildCardSummary(board),
                JudgeSupportLevels.normalize(board.getJudgeSupportLevel())
        );
    }

    private WolfbookDtos.BoardRoleView toBoardRoleView(BoardRoleEntity ref, Map<Integer, RoleEntity> rolesById) {
        RoleEntity role = rolesById.get(ref.getRoleId());
        if (role == null) {
            throw new ApiException(4004, "角色不存在");
        }
        return new WolfbookDtos.BoardRoleView(
                role.getId(),
                role.getName(),
                role.getFaction(),
                role.getRoleType(),
                role.getCamp(),
                role.getPortrait(),
                ref.getCount()
        );
    }

    private WolfbookDtos.FaqInput toFaqInput(FaqItem faqItem) {
        return new WolfbookDtos.FaqInput(faqItem.question(), faqItem.answer());
    }

    private BoardEntity getBoardEntity(Integer id) {
        BoardEntity board = boardMapper.selectById(id);
        if (board == null) {
            throw new ApiException(4004, "板子不存在");
        }
        return board;
    }

    private RoleEntity getRoleEntity(Integer id) {
        RoleEntity role = roleMapper.selectById(id);
        if (role == null) {
            throw new ApiException(4004, "角色不存在");
        }
        return role;
    }

    private Map<Integer, List<BoardRoleEntity>> loadBoardRoleMap(List<Integer> boardIds) {
        if (boardIds == null || boardIds.isEmpty()) {
            return Map.of();
        }
        return boardRoleMapper.selectList(
                        new LambdaQueryWrapper<BoardRoleEntity>()
                                .in(BoardRoleEntity::getBoardId, boardIds)
                ).stream()
                .collect(Collectors.groupingBy(BoardRoleEntity::getBoardId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Integer, RoleEntity> loadRolesById(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return roleMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RoleEntity::getId, item -> item));
    }

    private boolean matchesPlayerCount(BoardEntity board, String filter) {
        if (isBlank(filter) || "全部".equals(filter) || "all".equalsIgnoreCase(filter)) {
            return true;
        }
        if ("15+".equals(filter)) {
            return board.getPlayerCount() >= 15;
        }
        return String.valueOf(board.getPlayerCount()).equals(filter);
    }

    private boolean matchesTag(BoardEntity board, String tag) {
        if (isBlank(tag) || "全部".equals(tag) || "all".equalsIgnoreCase(tag)) {
            return true;
        }
        return converter.readStringList(board.getTags()).contains(tag);
    }

    private boolean matchesRoleGroup(RoleEntity role, String filter) {
        if (isBlank(filter) || "全部".equals(filter) || "all".equalsIgnoreCase(filter)) {
            return true;
        }
        return role.getCamp().startsWith(filter)
                || role.getFaction().startsWith(filter)
                || role.getRoleType().startsWith(filter);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String buildCardSummary(BoardEntity board) {
        if (!isBlank(board.getCardDescription())) {
            return board.getCardDescription().trim();
        }

        List<String> tips = converter.readStringList(board.getTips()).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (!tips.isEmpty()) {
            return tips.get(0);
        }

        List<String> rules = converter.readStringList(board.getSpecialRules()).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (!rules.isEmpty()) {
            return rules.get(0);
        }

        return board.getWinCondition() == null ? "" : board.getWinCondition();
    }

    private String buildCampSummary(List<WolfbookDtos.BoardRoleView> roles) {
        int wolfCount = roles.stream().filter(this::isWolfRole).mapToInt(WolfbookDtos.BoardRoleView::count).sum();
        int villagerCount = roles.stream().filter(this::isVillagerRole).mapToInt(WolfbookDtos.BoardRoleView::count).sum();
        int godCount = roles.stream().filter(this::isGodRole).mapToInt(WolfbookDtos.BoardRoleView::count).sum();
        int thirdCount = roles.stream().filter(this::isThirdPartyRole).mapToInt(WolfbookDtos.BoardRoleView::count).sum();
        String summary = "狼人x" + wolfCount + " · 平民x" + villagerCount + " · 神职x" + godCount;
        if (thirdCount > 0) {
            summary += " · 第三方x" + thirdCount;
        }
        return summary;
    }

    private String buildLineupSummary(List<WolfbookDtos.BoardRoleView> roles) {
        List<String> segments = roles.stream()
                .map(this::formatRoleSegment)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (segments.isEmpty()) {
            return buildCampSummary(roles);
        }
        return String.join(" ", segments);
    }

    private String formatRoleSegment(WolfbookDtos.BoardRoleView role) {
        if (role.count() > 1 || isWolfRole(role) || isVillagerRole(role) || isThirdPartyRole(role)) {
            return role.count() + role.name();
        }
        return role.name();
    }

    private boolean shouldShowFeaturedRole(WolfbookDtos.BoardRoleView role) {
        return role != null;
    }

    private boolean isGodRole(WolfbookDtos.BoardRoleView role) {
        return FACTION_GOOD.equals(role.faction()) && !isVillagerRole(role);
    }

    private boolean isVillagerRole(WolfbookDtos.BoardRoleView role) {
        return FACTION_GOOD.equals(role.faction()) && TYPE_VILLAGER.equals(role.roleType());
    }

    private boolean isWolfRole(WolfbookDtos.BoardRoleView role) {
        return FACTION_WOLF.equals(role.faction());
    }

    private boolean isThirdPartyRole(WolfbookDtos.BoardRoleView role) {
        return FACTION_THIRD.equals(role.faction());
    }
}
