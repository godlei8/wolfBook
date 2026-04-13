package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.FaqItem;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.*;
import com.wolfbook.backend.mapper.*;
import com.wolfbook.backend.service.assistant.AssistantKnowledgeService;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class AdminService {

    private final AdminUserMapper adminUserMapper;
    private final BoardMapper boardMapper;
    private final BoardRoleMapper boardRoleMapper;
    private final RoleMapper roleMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ReportMapper reportMapper;
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

    public void requireAdmin(String authorization) {
        tokenService.requireAdmin(authorization);
    }

    public WolfbookDtos.DashboardSummary summary() {
        long openReports = reportMapper.selectCount(
                new LambdaQueryWrapper<ReportEntity>().eq(ReportEntity::getProcessStatus, "OPEN")
        );
        return new WolfbookDtos.DashboardSummary(
                boardMapper.selectCount(null),
                roleMapper.selectCount(null),
                postMapper.selectCount(null),
                commentMapper.selectCount(null),
                openReports
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

        BoardEntity entity = id == null ? new BoardEntity() : getBoardEntity(id);
        entity.setName(request.name());
        entity.setPlayerCount(request.playerCount());
        entity.setDifficulty(request.difficulty());
        entity.setTags(converter.writeStringList(defaultList(request.tags())));
        entity.setCoverImage(request.coverImage());
        entity.setCardDescription(normalizeText(request.cardDescription()));
        entity.setBriefConfig(boardService.buildBriefConfig(request.roles()));
        entity.setSpecialRules(converter.writeStringList(defaultList(request.specialRules())));
        entity.setTips(converter.writeStringList(defaultList(request.tips())));
        entity.setFaqs(converter.writeFaqList(defaultFaqs(request.faqs())));
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
        entity.setName(request.name());
        entity.setAlias(request.alias());
        entity.setFaction(request.faction());
        entity.setRoleType(request.roleType());
        entity.setCamp(boardService.buildCampLabel(request.faction(), request.roleType()));
        entity.setSkill(request.skill());
        entity.setBackground(request.background());
        entity.setFaqs(converter.writeFaqList(defaultFaqs(request.faqs())));
        entity.setPortrait(request.portrait());
        entity.setFullIllustration(request.fullIllustration());
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
        communityService.updatePostStatus(id, status);
    }

    public void updatePostFeatured(Integer id, boolean enabled) {
        communityService.updatePostFeatured(id, enabled);
    }

    public void updatePostPinned(Integer id, boolean enabled) {
        communityService.updatePostPinned(id, enabled);
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
        String adminUsername = tokenService.requireAdmin(authorization);
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
                role.getFaction(),
                role.getRoleType(),
                role.getCamp(),
                role.getSkill(),
                role.getBackground(),
                converter.readFaqList(role.getFaqs()).stream().map(faq -> new WolfbookDtos.FaqInput(faq.question(), faq.answer())).toList(),
                role.getPortrait(),
                role.getFullIllustration()
        );
    }
}
