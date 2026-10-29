package com.wolfbook.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wolfbook.backend.domain.JudgeRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class WolfbookDtos {

    private WolfbookDtos() {
    }

    public record LoginRequest(@NotBlank(message = "code 不能为空") String code) {
    }

    public record UpdateUserRequest(
            @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称长度不能超过 50") String nickname,
            @NotBlank(message = "头像不能为空") String avatar
    ) {
    }

    public record CreatePostRequest(
            @NotBlank(message = "帖子内容不能为空") @Size(max = 500, message = "帖子内容不能超过 500 字") String content,
            List<String> images,
            @Size(max = 40, message = "标题不能超过 40 字") String title,
            @Pattern(regexp = "^(战报|教学|讨论)?$", message = "分类只能是战报、教学或讨论") String category,
            Integer boardId
    ) {
    }

    public record CreateCommentRequest(
            @NotNull(message = "postId 不能为空") Integer postId,
            @NotBlank(message = "评论不能为空") @Size(max = 200, message = "评论不能超过 200 字") String content
    ) {
    }

    public record ReportRequest(
            @NotBlank(message = "targetType 不能为空") String targetType,
            @NotNull(message = "targetId 不能为空") Integer targetId,
            @NotBlank(message = "reason 不能为空") @Size(max = 200, message = "举报原因不能超过 200 字") String reason
    ) {
    }

    public record AdminLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    public record BoardRoleInput(
            @NotNull(message = "角色不能为空") Integer roleId,
            @NotNull(message = "数量不能为空") Integer count
    ) {
    }

    public record FaqInput(
            @NotBlank(message = "问题不能为空") String question,
            @NotBlank(message = "答案不能为空") String answer
    ) {
    }

    public record AdminBoardRequest(
            @NotBlank(message = "板子名称不能为空") @Size(max = 100, message = "板子名称不能超过 100") String name,
            @NotNull(message = "人数不能为空") Integer playerCount,
            @NotBlank(message = "难度不能为空") String difficulty,
            List<String> tags,
            String coverImage,
            String backgroundImage,
            String cardDescription,
            String winCondition,
            String ruleType,
            List<String> specialRules,
            List<String> tips,
            @Valid List<FaqInput> faqs,
            @Valid BoardRuleDetailInput ruleDetail,
            @Valid @NotEmpty(message = "至少配置一个角色") List<BoardRoleInput> roles
    ) {
    }

    public record BoardRuleDetailInput(
            @Size(max = 2000, message = "玩法概述不能超过 2000 字") String overview,
            @Valid @Size(max = 30, message = "夜间行动最多 30 步") List<NightStepInput> nightOrder,
            @Valid @Size(max = 30, message = "角色细则最多 30 组") List<RoleRuleInput> roleRules,
            @Valid @Size(max = 20, message = "规则章节最多 20 个") List<RuleSectionInput> sections,
            JudgeRules judgeRules
    ) {
    }

    public record NightStepInput(
            @NotNull(message = "夜间行动的角色不能为空") Integer roleId,
            @NotBlank(message = "夜间行动的说明不能为空") @Size(max = 200, message = "夜间行动说明不能超过 200 字") String action
    ) {
    }

    public record RoleRuleInput(
            @NotNull(message = "角色细则的角色不能为空") Integer roleId,
            @Size(max = 20, message = "每个角色最多 20 条细则") List<String> items
    ) {
    }

    public record RuleSectionInput(
            @NotBlank(message = "规则章节标题不能为空") @Size(max = 30, message = "规则章节标题不能超过 30 字") String title,
            @Size(max = 30, message = "每个章节最多 30 条规则") List<String> items
    ) {
    }

    public record AdminRoleRequest(
            @NotBlank(message = "角色名称不能为空") @Size(max = 50, message = "角色名称不能超过 50") String name,
            @Size(max = 50, message = "真名不能超过 50") String alias,
            @Size(max = 40, message = "称号不能超过 40") String title,
            @NotBlank(message = "阵营不能为空") String faction,
            @NotBlank(message = "角色类型不能为空") String roleType,
            @NotBlank(message = "技能描述不能为空") String skill,
            String background,
            @Valid List<FaqInput> faqs,
            @Valid List<RoleRelationInput> relations,
            String portrait,
            String fullIllustration,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "主题色格式应为 #RRGGBB") String themeColor
    ) {
    }

    /** 「按世界观起草」：roleId 是编辑中的已有角色（新建时为空，起草时把它从已有角色里排除）。 */
    public record RoleDraftRequest(
            Integer roleId,
            @NotBlank(message = "先填角色名") @Size(max = 50, message = "角色名不能超过 50") String name,
            String faction,
            String roleType,
            @Size(max = 1000, message = "技能不能超过 1000 字") String skill,
            @Size(max = 300, message = "补充要求不能超过 300 字") String notes
    ) {
    }

    public record RoleDraftView(String alias, String title, String background, List<RoleRelationInput> relations) {
    }

    public record RoleRelationInput(
            @NotNull(message = "关系里的角色不能为空") Integer roleId,
            @Size(max = 20, message = "关系说明不能超过 20 字") String label
    ) {
    }

    /** 人物关系展开后的样子：对方的名字、真名、头像和阵营，小程序直接画头像。 */
    public record RoleRelationView(
            Integer roleId,
            String name,
            String alias,
            String label,
            String faction,
            String roleType,
            String portrait,
            String fullIllustration,
            String themeColor
    ) {
    }

    public record WorldFactionInput(
            @Size(max = 30, message = "势力名称不能超过 30") String name,
            String camp,
            @Size(max = 1000, message = "势力介绍不能超过 1000 字") String description
    ) {
    }

    public record WorldEventInput(
            @Size(max = 30, message = "事件时间不能超过 30") String era,
            @Size(max = 40, message = "事件标题不能超过 40") String title,
            @Size(max = 3000, message = "事件正文不能超过 3000 字") String content,
            List<Integer> roleIds
    ) {
    }

    public record WorldLoreRequest(
            @Size(max = 30, message = "小镇名称不能超过 30") String townName,
            @Size(max = 60, message = "一句话简介不能超过 60") String tagline,
            @Size(max = 3000, message = "小镇简介不能超过 3000 字") String intro,
            String coverImage,
            @Valid List<WorldFactionInput> factions,
            @Valid List<WorldEventInput> events
    ) {
    }

    /** 编年史事件展开后的样子：roles 是出场角色的头像信息。 */
    public record WorldEventView(String era, String title, String content, List<RoleRelationView> roles) {
    }

    public record WorldLoreView(
            String townName,
            String tagline,
            String intro,
            String coverImage,
            List<com.wolfbook.backend.domain.WorldLore.Faction> factions,
            List<WorldEventView> events
    ) {
    }

    public record AdminStatusRequest(@NotBlank(message = "status 不能为空") String status) {
    }

    public record ReportProcessRequest(@NotBlank(message = "处理状态不能为空") String processStatus) {
    }

    public record UserView(String openid, String nickname, String avatar, Integer status, LocalDateTime createTime) {
    }

    public record LoginResponse(String token, UserView user) {
    }

    public record FavoriteBoardsView(List<Integer> boardIds, List<BoardCardView> boards) {
    }

    public record NoteRecordInput(
            @NotBlank(message = "记录 id 不能为空") String id,
            @NotBlank(message = "记录类型不能为空") String type,
            @NotNull(message = "轮次不能为空") Integer round,
            @NotBlank(message = "记录内容不能为空") @Size(max = 5000, message = "记录内容不能超过 5000 字") String content,
            String player,
            String timestamp
    ) {
    }

    public record NoteSessionSaveRequest(
            @NotBlank(message = "对局 id 不能为空") String sessionId,
            @NotBlank(message = "板子模式不能为空") String boardMode,
            Integer boardId,
            @NotBlank(message = "板子名称不能为空") @Size(max = 100, message = "板子名称不能超过 100") String boardName,
            @NotNull(message = "人数不能为空") Integer playerCount,
            String createTime,
            String updateTime,
            @Valid List<NoteRecordInput> records
    ) {
    }

    public record NoteRecordView(
            String id,
            String type,
            Integer round,
            String content,
            String player,
            LocalDateTime timestamp
    ) {
    }

    public record NoteSessionView(
            String sessionId,
            String boardMode,
            Integer boardId,
            String boardName,
            Integer playerCount,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            List<NoteRecordView> records
    ) {
    }

    public record BoardCardView(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String backgroundImage,
            String winCondition,
            String briefConfig,
            String cardDescription,
            String campSummary,
            String lineupSummary,
            List<BoardRoleView> roles,
            List<BoardRoleView> featuredRoles,
            String summary
    ) {
    }

    public record BoardRoleView(Integer roleId, String name, String faction, String roleType, String camp, String portrait, String fullIllustration, String themeColor, Integer count) {
    }

    public record BoardDetailView(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String backgroundImage,
            String briefConfig,
            String cardDescription,
            List<String> specialRules,
            List<String> tips,
            List<FaqInput> faqs,
            BoardRuleDetailView ruleDetail,
            String winCondition,
            String ruleType,
            List<BoardRoleView> roles,
            String judgeSupportLevel
    ) {
    }

    public record BoardRuleDetailView(
            String overview,
            List<NightStepView> nightOrder,
            List<RoleRuleView> roleRules,
            List<RuleSectionView> sections
    ) {
    }

    public record NightStepView(Integer order, Integer roleId, String roleName, String faction, String portrait, String themeColor, String action) {
    }

    public record RoleRuleView(Integer roleId, String roleName, String faction, String portrait, String themeColor, List<String> items) {
    }

    public record RuleSectionView(String title, List<String> items) {
    }

    public record RoleListItemView(
            Integer id,
            String name,
            String alias,
            String title,
            String faction,
            String roleType,
            String camp,
            String portrait,
            String fullIllustration,
            String themeColor
    ) {
    }

    public record AdminRoleView(
            Integer id,
            String name,
            String alias,
            String title,
            String faction,
            String roleType,
            String camp,
            String skill,
            String background,
            List<FaqInput> faqs,
            List<RoleRelationInput> relations,
            String portrait,
            String fullIllustration,
            String themeColor
    ) {
    }

    /** 角色详情里「出现在这些板子」：wakeOrder 是该角色在这个板子夜间顺序里第几个醒来（没写夜间顺序时为 null），lineup 是板子里另外几个角色。 */
    public record RelatedBoardView(Integer id, String name, String coverImage, String backgroundImage, Integer playerCount, Integer wakeOrder, List<BoardRoleView> lineup) {
    }

    public record RoleDetailView(
            Integer id,
            String name,
            String alias,
            String title,
            String faction,
            String roleType,
            String camp,
            String skill,
            String background,
            List<FaqInput> faqs,
            String portrait,
            String fullIllustration,
            String themeColor,
            List<RoleRelationView> relations,
            List<RelatedBoardView> boards
    ) {
    }

    public record PostSummaryView(
            Integer id,
            @JsonInclude(JsonInclude.Include.NON_NULL) String openid,
            String nickname,
            String avatar,
            String content,
            String title,
            String category,
            Integer boardId,
            String boardName,
            List<String> images,
            Integer likeCount,
            Integer commentCount,
            Integer status,
            boolean liked,
            LocalDateTime createTime
    ) {
    }

    public record CommentView(
            Integer id,
            Integer postId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String openid,
            String nickname,
            String avatar,
            String content,
            Integer likeCount,
            boolean liked,
            String status,
            LocalDateTime createTime
    ) {
    }

    public record PostDetailView(
            Integer id,
            @JsonInclude(JsonInclude.Include.NON_NULL) String openid,
            String nickname,
            String avatar,
            String content,
            String title,
            String category,
            Integer boardId,
            String boardName,
            List<String> images,
            Integer likeCount,
            Integer commentCount,
            Integer status,
            boolean liked,
            LocalDateTime createTime,
            List<CommentView> comments
    ) {
    }

    public record ToggleLikeResponse(boolean liked, int likeCount) {
    }

    public record UploadResponse(String url) {
    }

    public record DashboardSummary(
            long boardCount,
            long onlineBoardCount,
            long boardsWithoutBackground,
            long roleCount,
            long rolesWithoutIllustration,
            long postCount,
            long commentCount,
            long openReportCount,
            long assistantQuestionsToday,
            long assistantFailuresToday,
            List<RecentBoardView> recentBoards
    ) {
    }

    public record RecentBoardView(Integer id, String name, LocalDateTime updateTime) {
    }

    public record JudgeRoomCreateRequest(
            @NotNull(message = "boardId is required") Integer boardId,
            @NotBlank(message = "judgeMode is required") String judgeMode
    ) {
    }

    public record JudgeRoomAdvanceRequest(
            @NotBlank(message = "action is required") String action,
            List<Integer> deadSeatNos,
            Integer eliminatedSeatNo,
            String winnerCamp,
            @Size(max = 500, message = "announcement is too long") String announcement
    ) {
    }

    public record JudgeNightActionRequest(
            @Size(max = 50, message = "actionType is too long") String actionType,
            Integer targetSeatNo,
            @Size(max = 500, message = "note is too long") String note,
            Integer actorSeatNo
    ) {
    }

    public record JudgeVoteRequest(@NotNull(message = "targetSeatNo is required") Integer targetSeatNo) {
    }

    public record JudgeRoomSummaryView(
            String roomId,
            String roomCode,
            Integer boardId,
            String boardName,
            Integer playerCount,
            String judgeMode,
            String judgeSupportLevel,
            String roomStatus,
            Integer currentDay,
            String currentPhase,
            String winnerCamp,
            Integer joinedCount,
            Integer readyCount,
            boolean owned,
            boolean joined,
            String latestAnnouncement,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    public record JudgePlayerView(
            String playerId,
            boolean self,
            String nickname,
            String avatar,
            Integer seatNo,
            boolean roomOwner,
            boolean judgeObserver,
            boolean playing,
            boolean ready,
            boolean alive,
            Integer roleId,
            String roleName,
            String faction,
            Integer deathDay,
            String deathPhase
    ) {
    }

    public record JudgeEventView(
            String eventId,
            Integer dayNo,
            String phase,
            String actorPlayerId,
            Integer actorSeatNo,
            List<Integer> targetSeatNos,
            String actionType,
            Map<String, Object> payload,
            Map<String, Object> resultPayload,
            String visibility,
            LocalDateTime createTime
    ) {
    }

    public record JudgeVoteTallyView(
            Integer targetSeatNo,
            String targetNickname,
            Integer voteCount,
            List<Integer> voterSeatNos
    ) {
    }

    public record JudgeNightActionView(
            String actorPlayerId,
            Integer actorSeatNo,
            String actorNickname,
            Integer targetSeatNo,
            String actionType,
            String note,
            Integer dayNo,
            LocalDateTime createTime
    ) {
    }

    public record JudgePendingActionView(
            boolean submitted,
            Integer targetSeatNo,
            String note
    ) {
    }

    public record JudgeRoomSnapshotView(
            String roomId,
            String roomCode,
            Integer boardId,
            String boardName,
            Integer playerCount,
            String judgeMode,
            String judgeSupportLevel,
            String roomStatus,
            Integer currentDay,
            String currentPhase,
            String winnerCamp,
            String latestAnnouncement,
            boolean judgeViewer,
            boolean joinedViewer,
            boolean canJoin,
            boolean canReady,
            boolean canStart,
            boolean canAdvance,
            boolean canVote,
            boolean canSubmitNightAction,
            Integer joinedCount,
            Integer readyCount,
            String roomTip,
            String winnerSuggestion,
            JudgePlayerView selfPlayer,
            List<JudgePlayerView> players,
            List<JudgeEventView> timeline,
            List<JudgeVoteTallyView> voteTallies,
            List<JudgeNightActionView> nightActions,
            List<JudgeNightActionView> nightHistory,
            JudgePendingActionView pendingNightAction,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }
}
