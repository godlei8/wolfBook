package com.wolfbook.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
            List<String> images
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
            @Valid @Size(max = 20, message = "规则章节最多 20 个") List<RuleSectionInput> sections
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
            String alias,
            @NotBlank(message = "阵营不能为空") String faction,
            @NotBlank(message = "角色类型不能为空") String roleType,
            @NotBlank(message = "技能描述不能为空") String skill,
            String background,
            @Valid List<FaqInput> faqs,
            String portrait,
            String fullIllustration,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "主题色格式应为 #RRGGBB") String themeColor
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
            String faction,
            String roleType,
            String camp,
            String skill,
            String background,
            List<FaqInput> faqs,
            String portrait,
            String fullIllustration,
            String themeColor
    ) {
    }

    public record RelatedBoardView(Integer id, String name, String coverImage, Integer playerCount) {
    }

    public record RoleDetailView(
            Integer id,
            String name,
            String alias,
            String faction,
            String roleType,
            String camp,
            String skill,
            String background,
            List<FaqInput> faqs,
            String portrait,
            String fullIllustration,
            String themeColor,
            List<RelatedBoardView> boards
    ) {
    }

    public record PostSummaryView(
            Integer id,
            @JsonInclude(JsonInclude.Include.NON_NULL) String openid,
            String nickname,
            String avatar,
            String content,
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
            long roleCount,
            long postCount,
            long commentCount,
            long openReportCount
    ) {
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
            @Size(max = 500, message = "note is too long") String note
    ) {
    }

    public record JudgeVoteRequest(@NotNull(message = "targetSeatNo is required") Integer targetSeatNo) {
    }

    public record JudgeRoomSummaryView(
            String roomId,
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
            JudgePendingActionView pendingNightAction,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }
}
