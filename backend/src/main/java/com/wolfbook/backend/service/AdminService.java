package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.BoardRuleDetail;
import com.wolfbook.backend.domain.FaqItem;
import com.wolfbook.backend.domain.JudgeRules;
import com.wolfbook.backend.service.judge.NightPlan;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.domain.RoleRelation;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.*;
import com.wolfbook.backend.mapper.*;
import com.wolfbook.backend.service.assistant.AssistantKnowledgeService;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final AdminUserMapper adminUserMapper;
    private final BoardMapper boardMapper;
    private final BoardRoleMapper boardRoleMapper;
    private final RoleMapper roleMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ReportMapper reportMapper;
    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final BoardService boardService;
    private final CommunityService communityService;
    private final AssistantKnowledgeService assistantKnowledgeService;
    private final TokenService tokenService;
    private final DomainConverter converter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminService(
            AdminUserMapper adminUserMapper,
            BoardMapper boardMapper,
            BoardRoleMapper boardRoleMapper,
            RoleMapper roleMapper,
            PostMapper postMapper,
            CommentMapper commentMapper,
            ReportMapper reportMapper,
            AssistantQueryLogMapper assistantQueryLogMapper,
            BoardService boardService,
            CommunityService communityService,
            AssistantKnowledgeService assistantKnowledgeService,
            TokenService tokenService,
            DomainConverter converter
    ) {
        this.adminUserMapper = adminUserMapper;
        this.boardMapper = boardMapper;
        this.boardRoleMapper = boardRoleMapper;
        this.roleMapper = roleMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.reportMapper = reportMapper;
        this.assistantQueryLogMapper = assistantQueryLogMapper;
        this.boardService = boardService;
        this.communityService = communityService;
        this.assistantKnowledgeService = assistantKnowledgeService;
        this.tokenService = tokenService;
        this.converter = converter;
    }

    public WolfbookDtos.LoginResponse login(WolfbookDtos.AdminLoginRequest request) {
        AdminUserEntity adminUser = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUserEntity>()
                        .eq(AdminUserEntity::getUsername, request.username())
        );
        if (adminUser == null || !passwordEncoder.matches(request.password(), adminUser.getPassword())) {
            throw new ApiException(4001, "用户名或密码错误");
        }
        WolfbookDtos.UserView userView = new WolfbookDtos.UserView(
                adminUser.getUsername(),
                adminUser.getDisplayName(),
                "",
                adminUser.getStatus(),
                adminUser.getCreateTime()
        );
        return new WolfbookDtos.LoginResponse(tokenService.issueAdminToken(adminUser.getUsername()), userView);
    }

    /**
     * 校验管理员 token，并确认账号仍然存在且未被禁用；返回管理员用户名。
     */
    public String requireAdmin(String authorization) {
        String username = tokenService.requireAdmin(authorization);
        AdminUserEntity adminUser = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUserEntity>().eq(AdminUserEntity::getUsername, username)
        );
        if (adminUser == null || !Integer.valueOf(1).equals(adminUser.getStatus())) {
            throw new ApiException(4001, "管理员账号不可用，请重新登录");
        }
        return username;
    }

    public WolfbookDtos.DashboardSummary summary() {
        long openReports = reportMapper.selectCount(
                new LambdaQueryWrapper<ReportEntity>().eq(ReportEntity::getProcessStatus, "OPEN")
        );
        List<BoardEntity> boards = boardMapper.selectList(null);
        List<RoleEntity> roles = roleMapper.selectList(null);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long questionsToday = assistantQueryLogMapper.selectCount(
                new LambdaQueryWrapper<AssistantQueryLogEntity>().ge(AssistantQueryLogEntity::getCreateTime, startOfToday)
        );
        long failuresToday = assistantQueryLogMapper.selectCount(
                new LambdaQueryWrapper<AssistantQueryLogEntity>()
                        .ge(AssistantQueryLogEntity::getCreateTime, startOfToday)
                        .eq(AssistantQueryLogEntity::getSuccess, 0)
        );
        List<WolfbookDtos.RecentBoardView> recentBoards = boards.stream()
                .filter(board -> board.getUpdateTime() != null)
                .sorted(Comparator.comparing(BoardEntity::getUpdateTime).reversed())
                .limit(RECENT_BOARD_LIMIT)
                .map(board -> new WolfbookDtos.RecentBoardView(board.getId(), board.getName(), board.getUpdateTime()))
                .toList();
        return new WolfbookDtos.DashboardSummary(
                boards.size(),
                boards.stream().filter(board -> Integer.valueOf(1).equals(board.getStatus())).count(),
                boards.stream().filter(board -> isBlankText(board.getBackgroundImage())).count(),
                roles.size(),
                roles.stream().filter(role -> isBlankText(role.getFullIllustration())).count(),
                postMapper.selectCount(null),
                commentMapper.selectCount(null),
                openReports,
                questionsToday,
                failuresToday,
                recentBoards
        );
    }

    public List<Board> listBoards() {
        return boardService.listAllBoards().stream()
                .sorted(Comparator.comparing(Board::id))
                .toList();
    }

    public Board saveBoard(Integer id, WolfbookDtos.AdminBoardRequest request) {
        List<BoardRoleEntity> roleRefs = defaultBoardRoles(request.roles());
        roleRefs.forEach(ref -> boardService.getRole(ref.getRoleId()));
        int lineupSize = roleRefs.stream().mapToInt(BoardRoleEntity::getCount).sum();
        if (lineupSize != request.playerCount()) {
            throw new ApiException(4000, "阵容一共 " + lineupSize + " 人，和玩家人数 " + request.playerCount() + " 不一致");
        }
        BoardRuleDetail ruleDetail = normalizeRuleDetail(
                request.ruleDetail(),
                roleRefs.stream().map(BoardRoleEntity::getRoleId).collect(Collectors.toSet())
        );

        BoardEntity entity = id == null ? new BoardEntity() : getBoardEntity(id);
        entity.setName(request.name());
        entity.setPlayerCount(request.playerCount());
        entity.setDifficulty(request.difficulty());
        entity.setTags(converter.writeStringList(defaultList(request.tags())));
        entity.setCoverImage(request.coverImage());
        entity.setBackgroundImage(normalizeText(request.backgroundImage()));
        entity.setCardDescription(normalizeText(request.cardDescription()));
        entity.setBriefConfig(boardService.buildBriefConfig(request.roles()));
        entity.setSpecialRules(converter.writeStringList(defaultList(request.specialRules())));
        entity.setTips(converter.writeStringList(defaultList(request.tips())));
        entity.setFaqs(converter.writeFaqList(defaultFaqs(request.faqs())));
        entity.setRuleDetail(converter.writeRuleDetail(ruleDetail));
        entity.setWinCondition(request.winCondition() == null || request.winCondition().isBlank() ? "屠边" : request.winCondition());
        entity.setRuleType(request.ruleType() == null || request.ruleType().isBlank() ? "标准板" : request.ruleType());
        entity.setStatus(id == null ? 1 : entity.getStatus());
        entity.setCreateTime(id == null ? LocalDateTime.now() : entity.getCreateTime());
        entity.setUpdateTime(LocalDateTime.now());

        if (id == null) {
            boardMapper.insert(entity);
        } else {
            boardMapper.updateById(entity);
            boardRoleMapper.delete(new LambdaQueryWrapper<BoardRoleEntity>().eq(BoardRoleEntity::getBoardId, id));
        }

        for (BoardRoleEntity ref : roleRefs) {
            ref.setBoardId(entity.getId());
            boardRoleMapper.insert(ref);
        }
        assistantKnowledgeService.requestStructuredKnowledgeRebuild();
        return boardService.getBoard(entity.getId());
    }

    /**
     * 上架 / 下架：下架后小程序的板子列表、详情和建房都看不到它，已经开局的法官房间不受影响。
     */
    public Board updateBoardStatus(Integer id, String status) {
        BoardEntity entity = getBoardEntity(id);
        entity.setStatus(parseBoardStatus(status));
        entity.setUpdateTime(LocalDateTime.now());
        boardMapper.updateById(entity);
        return boardService.getBoard(id);
    }

    public void deleteBoard(Integer id) {
        boardService.getBoard(id);
        boardRoleMapper.delete(new LambdaQueryWrapper<BoardRoleEntity>().eq(BoardRoleEntity::getBoardId, id));
        boardMapper.deleteById(id);
        assistantKnowledgeService.requestStructuredKnowledgeRebuild();
    }

    public List<WolfbookDtos.AdminRoleView> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId))
                .stream()
                .map(this::toAdminRoleView)
                .toList();
    }

    public WolfbookDtos.AdminRoleView saveRole(Integer id, WolfbookDtos.AdminRoleRequest request) {
        RoleEntity entity = id == null ? new RoleEntity() : getRoleEntity(id);
        List<RoleRelation> relations = normalizeRelations(id, request.relations());
        entity.setName(request.name());
        entity.setAlias(normalizeText(request.alias()));
        entity.setTitle(normalizeText(request.title()));
        entity.setRelations(converter.writeRelations(relations));
        entity.setFaction(request.faction());
        entity.setRoleType(request.roleType());
        entity.setCamp(boardService.buildCampLabel(request.faction(), request.roleType()));
        entity.setSkill(request.skill());
        entity.setBackground(request.background());
        entity.setFaqs(converter.writeFaqList(defaultFaqs(request.faqs())));
        entity.setPortrait(request.portrait());
        entity.setFullIllustration(request.fullIllustration());
        String themeColor = normalizeText(request.themeColor());
        entity.setThemeColor(themeColor == null ? null : themeColor.toUpperCase(Locale.ROOT));
        entity.setCreateTime(id == null ? LocalDateTime.now() : entity.getCreateTime());

        if (id == null) {
            roleMapper.insert(entity);
        } else {
            roleMapper.updateById(entity);
        }
        assistantKnowledgeService.requestStructuredKnowledgeRebuild();
        return toAdminRoleView(entity);
    }

    public void deleteRole(Integer id) {
        boardService.getRole(id);
        boolean inUse = boardRoleMapper.selectCount(
                new LambdaQueryWrapper<BoardRoleEntity>().eq(BoardRoleEntity::getRoleId, id)
        ) > 0;
        if (inUse) {
            throw new ApiException(4002, "该角色已被板子使用，无法删除");
        }
        roleMapper.deleteById(id);
        assistantKnowledgeService.requestStructuredKnowledgeRebuild();
    }

    public PageResponse<WolfbookDtos.PostSummaryView> listPosts(int page, int size) {
        return communityService.listAllPosts(page, size);
    }

    public void updatePostStatus(Integer id, String status) {
        PostEntity post = getPostEntity(id);
        int numeric = "PUBLISHED".equalsIgnoreCase(status) || "ONLINE".equalsIgnoreCase(status) || "1".equals(status) ? 1 : 0;
        post.setStatus(numeric);
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
    }

    public void deletePost(Integer id) {
        communityService.deletePostCascade(id);
    }

    public PageResponse<WolfbookDtos.CommentView> listComments(int page, int size) {
        return communityService.listAllComments(page, size);
    }

    public void deleteComment(Integer id) {
        communityService.deleteCommentCascade(id);
    }

    public PageResponse<Report> listReports(int page, int size) {
        return communityService.listAllReports(page, size);
    }

    public Report processReport(Integer id, WolfbookDtos.ReportProcessRequest request, String authorization) {
        String adminUsername = requireAdmin(authorization);
        ReportEntity entity = reportMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "举报不存在");
        }
        entity.setProcessStatus(request.processStatus());
        entity.setProcessBy(adminUsername);
        entity.setProcessTime(LocalDateTime.now());
        reportMapper.updateById(entity);
        return converter.toReport(entity);
    }

    private int parseBoardStatus(String status) {
        String value = status == null ? "" : status.trim();
        if ("1".equals(value) || "ONLINE".equalsIgnoreCase(value)) {
            return 1;
        }
        if ("0".equals(value) || "OFFLINE".equalsIgnoreCase(value)) {
            return 0;
        }
        throw new ApiException(4000, "板子状态只能是上架或下架");
    }

    private boolean isBlankText(String value) {
        return value == null || value.isBlank();
    }

    private BoardEntity getBoardEntity(Integer id) {
        BoardEntity entity = boardMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "板子不存在");
        }
        return entity;
    }

    private RoleEntity getRoleEntity(Integer id) {
        RoleEntity entity = roleMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "角色不存在");
        }
        return entity;
    }

    private PostEntity getPostEntity(Integer id) {
        PostEntity entity = postMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "帖子不存在");
        }
        return entity;
    }

    private List<String> defaultList(List<String> input) {
        return input == null ? List.of() : input.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<FaqItem> defaultFaqs(List<WolfbookDtos.FaqInput> faqs) {
        if (faqs == null) {
            return List.of();
        }
        return faqs.stream()
                .filter(faq -> faq.question() != null && !faq.question().isBlank() && faq.answer() != null && !faq.answer().isBlank())
                .map(faq -> new FaqItem(faq.question(), faq.answer()))
                .toList();
    }

    private List<BoardRoleEntity> defaultBoardRoles(List<WolfbookDtos.BoardRoleInput> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new ApiException(4002, "至少配置一个角色");
        }
        return roles.stream()
                .filter(item -> item.roleId() != null && item.count() != null && item.count() > 0)
                .map(item -> {
                    BoardRoleEntity entity = new BoardRoleEntity();
                    entity.setRoleId(item.roleId());
                    entity.setCount(item.count());
                    return entity;
                })
                .toList();
    }

    private WolfbookDtos.AdminRoleView toAdminRoleView(RoleEntity role) {
        return new WolfbookDtos.AdminRoleView(
                role.getId(),
                role.getName(),
                role.getAlias(),
                role.getTitle(),
                role.getFaction(),
                role.getRoleType(),
                role.getCamp(),
                role.getSkill(),
                role.getBackground(),
                converter.readFaqList(role.getFaqs()).stream().map(faq -> new WolfbookDtos.FaqInput(faq.question(), faq.answer())).toList(),
                converter.readRelations(role.getRelations()).stream()
                        .map(relation -> new WolfbookDtos.RoleRelationInput(relation.roleId(), relation.label()))
                        .toList(),
                role.getPortrait(),
                role.getFullIllustration(),
                role.getThemeColor()
        );
    }

    private static final int MAX_RULE_ITEM_LENGTH = 300;
    private static final int MAX_RELATIONS = 8;

    /**
     * 人物关系：对方角色必须存在、不能是自己、同一个人只写一条，关系说明不能空，最多 8 条。
     */
    private List<RoleRelation> normalizeRelations(Integer selfId, List<WolfbookDtos.RoleRelationInput> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<RoleRelation> relations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (WolfbookDtos.RoleRelationInput item : input) {
            String label = normalizeText(item.label());
            if (item.roleId() == null || label == null) {
                throw new ApiException(4000, "人物关系要选角色并写一句关系");
            }
            if (item.roleId().equals(selfId)) {
                throw new ApiException(4000, "人物关系不能选自己");
            }
            if (!seen.add(item.roleId())) {
                throw new ApiException(4000, "同一个角色的关系只能写一条");
            }
            boardService.getRole(item.roleId());
            relations.add(new RoleRelation(item.roleId(), label));
        }
        if (relations.size() > MAX_RELATIONS) {
            throw new ApiException(4000, "人物关系最多 " + MAX_RELATIONS + " 条");
        }
        return relations;
    }
    private static final int RECENT_BOARD_LIMIT = 5;

    /**
     * 清洗后台提交的详细规则：去掉空条目和空章节，同一角色的细则只能有一组，
     * 夜间顺序和角色细则引用的角色必须在本板子的阵容里。
     */
    private BoardRuleDetail normalizeRuleDetail(WolfbookDtos.BoardRuleDetailInput input, Set<Integer> lineupRoleIds) {
        if (input == null) {
            return BoardRuleDetail.empty();
        }
        List<BoardRuleDetail.NightStep> nightOrder = input.nightOrder() == null ? List.of() : input.nightOrder().stream()
                .map(step -> new BoardRuleDetail.NightStep(step.roleId(), step.action().trim()))
                .toList();

        Set<Integer> rolesWithNotes = new HashSet<>();
        List<BoardRuleDetail.RoleRuleNote> roleRules = new ArrayList<>();
        for (WolfbookDtos.RoleRuleInput rule : input.roleRules() == null ? List.<WolfbookDtos.RoleRuleInput>of() : input.roleRules()) {
            List<String> items = cleanRuleItems(rule.items());
            if (items.isEmpty()) {
                continue;
            }
            if (!rolesWithNotes.add(rule.roleId())) {
                throw new ApiException(4000, "同一个角色的细则只能写一组");
            }
            roleRules.add(new BoardRuleDetail.RoleRuleNote(rule.roleId(), items));
        }

        List<BoardRuleDetail.RuleSection> sections = new ArrayList<>();
        for (WolfbookDtos.RuleSectionInput section : input.sections() == null ? List.<WolfbookDtos.RuleSectionInput>of() : input.sections()) {
            List<String> items = cleanRuleItems(section.items());
            if (!items.isEmpty()) {
                sections.add(new BoardRuleDetail.RuleSection(section.title().trim(), items));
            }
        }

        BoardRuleDetail detail = new BoardRuleDetail(normalizeText(input.overview()), nightOrder, roleRules, sections,
                normalizeJudgeRules(input.judgeRules()));
        for (Integer roleId : BoardService.referencedRoleIds(detail)) {
            if (!lineupRoleIds.contains(roleId)) {
                throw new ApiException(4000, "规则里引用的角色不在本板子的阵容中");
            }
        }
        if (!nightOrder.isEmpty() && !lineupRoleIds.isEmpty()) {
            Map<Integer, RoleEntity> rolesById = roleMapper.selectBatchIds(lineupRoleIds).stream()
                    .collect(Collectors.toMap(RoleEntity::getId, role -> role));
            List<BoardRoleRef> lineup = lineupRoleIds.stream().map(roleId -> new BoardRoleRef(roleId, 1)).toList();
            NightPlan.validateOrder(NightPlan.build(detail, lineup, rolesById, 1));
        }
        return detail;
    }

    /** 板子的法官规则：只校验填了的项，没填的留空（开局时用默认值）；胜负规则看板子的 win_condition，这里不存。 */
    private JudgeRules normalizeJudgeRules(JudgeRules input) {
        if (input == null) {
            return null;
        }
        String selfSave = normalizeText(input.witchSelfSave());
        if (selfSave != null && !JudgeRules.SELF_SAVE_FIRST_NIGHT.equals(selfSave) && !JudgeRules.SELF_SAVE_NEVER.equals(selfSave)) {
            throw new ApiException(4000, "法官规则取值不合法");
        }
        for (Integer seconds : java.util.Arrays.asList(input.speechSeconds(), input.sheriffSpeechSeconds())) {
            if (seconds != null && (seconds < 30 || seconds > 300)) {
                throw new ApiException(4000, "法官规则取值不合法");
            }
        }
        JudgeRules normalized = new JudgeRules(null, input.sheriffDefault(), selfSave, input.witchOnePotionPerNight(),
                input.speechSeconds(), input.sheriffSpeechSeconds());
        boolean empty = normalized.sheriffDefault() == null && normalized.witchSelfSave() == null
                && normalized.witchOnePotionPerNight() == null && normalized.speechSeconds() == null
                && normalized.sheriffSpeechSeconds() == null;
        return empty ? null : normalized;
    }

    private List<String> cleanRuleItems(List<String> items) {
        if (items == null) {
            return List.of();
        }
        List<String> cleaned = items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
        if (cleaned.stream().anyMatch(item -> item.length() > MAX_RULE_ITEM_LENGTH)) {
            throw new ApiException(4000, "单条规则不能超过 " + MAX_RULE_ITEM_LENGTH + " 字");
        }
        return cleaned;
    }
}
