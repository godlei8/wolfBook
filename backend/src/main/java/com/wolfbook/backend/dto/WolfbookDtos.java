package com.wolfbook.backend.dto;

import com.wolfbook.backend.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class WolfbookDtos {

    private WolfbookDtos() {
    }

    public record LoginRequest(@NotBlank(message = "code is required") String code) {
    }

    public record UpdateUserRequest(
            @NotBlank(message = "nickname is required") @Size(max = 50, message = "nickname is too long") String nickname,
            @NotBlank(message = "avatar is required") String avatar
    ) {
    }

    public record CreatePostRequest(
            @NotBlank(message = "postType is required") String postType,
            @NotBlank(message = "title is required") @Size(max = 60, message = "title is too long") String title,
            @NotBlank(message = "content is required") @Size(max = 5000, message = "content is too long") String content,
            @Size(max = 200, message = "summary is too long") String summary,
            List<String> images,
            Integer boardId,
            @Size(max = 100, message = "boardName is too long") String boardName,
            List<String> roleTags,
            List<String> tagList,
            String sessionId
    ) {
    }

    public record CreateCommentRequest(
            @NotNull(message = "postId is required") Integer postId,
            Integer parentCommentId,
            String replyToOpenid,
            @NotBlank(message = "content is required") @Size(max = 600, message = "content is too long") String content
    ) {
    }

    public record ReportRequest(
            @NotBlank(message = "targetType is required") String targetType,
            @NotNull(message = "targetId is required") Integer targetId,
            @NotBlank(message = "reason is required") @Size(max = 200, message = "reason is too long") String reason
    ) {
    }

    public record AdminLoginRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password
    ) {
    }

    public record BoardRoleInput(
            @NotNull(message = "roleId is required") Integer roleId,
            @NotNull(message = "count is required") Integer count
    ) {
    }

    public record FaqInput(
            @NotBlank(message = "question is required") String question,
            @NotBlank(message = "answer is required") String answer
    ) {
    }

    public record AdminBoardRequest(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name is too long") String name,
            @NotNull(message = "playerCount is required") Integer playerCount,
            @NotBlank(message = "difficulty is required") String difficulty,
            List<String> tags,
            String coverImage,
            String cardDescription,
            String winCondition,
            String ruleType,
            String judgeSupportLevel,
            List<String> specialRules,
            List<String> tips,
            @Valid List<FaqInput> faqs,
            @Valid @NotEmpty(message = "roles are required") List<BoardRoleInput> roles
    ) {
    }

    public record AdminRoleRequest(
            @NotBlank(message = "name is required") @Size(max = 50, message = "name is too long") String name,
            String alias,
            @NotBlank(message = "faction is required") String faction,
            @NotBlank(message = "roleType is required") String roleType,
            @NotBlank(message = "skill is required") String skill,
            String background,
            @Valid List<FaqInput> faqs,
            String portrait,
            String fullIllustration
    ) {
    }

    public record AdminStatusRequest(@NotBlank(message = "status is required") String status) {
    }

    public record ToggleFlagRequest(@NotNull(message = "enabled is required") Boolean enabled) {
    }

    public record ReportProcessRequest(@NotBlank(message = "processStatus is required") String processStatus) {
    }

    public record UserView(String openid, String nickname, String avatar, Integer status, LocalDateTime createTime) {
    }

    public record LoginResponse(String token, UserView user) {
    }

    public record FavoriteBoardsView(List<Integer> boardIds, List<BoardCardView> boards) {
    }

    public record NotePlayerInput(
            Integer seatNo,
            String nickname,
            Boolean alive,
            Boolean isSheriff,
            String claimedRole,
            String realRole,
            String factionHint,
            Integer suspicionLevel,
            List<String> tags,
            Integer deathDay,
            String deathPhase,
            String deathReason,
            Boolean isFocus,
            String note,
            String createTime,
            String updateTime
    ) {
    }

    public record NoteSessionSummaryInput(
            Integer aliveCount,
            Integer deadCount,
            Integer todayOutSeat,
            List<Integer> latestWolfPackSeats,
            String latestWolfPackText,
            Integer keyEventCount,
            String latestRecordType,
            String latestRecordPreview
    ) {
    }

    public record NoteRecordInput(
            @NotBlank(message = "record id is required") String id,
            @NotBlank(message = "record type is required") String type,
            String scene,
            Integer day,
            Integer round,
            String phase,
            List<Integer> actorSeats,
            List<Integer> targetSeats,
            String player,
            @Size(max = 5000, message = "content is too long") String content,
            Map<String, Object> payload,
            List<String> tags,
            Boolean editable,
            String timestamp,
            String createTime,
            String updateTime
    ) {
    }

    public record NoteSessionSaveRequest(
            @NotBlank(message = "sessionId is required") String sessionId,
            Integer version,
            @NotBlank(message = "boardMode is required") String boardMode,
            Integer boardId,
            @NotBlank(message = "boardName is required") @Size(max = 100, message = "boardName is too long") String boardName,
            @NotNull(message = "playerCount is required") Integer playerCount,
            String status,
            Integer currentDay,
            String currentPhase,
            String resultCamp,
            Integer sheriffSeat,
            @Valid List<NotePlayerInput> players,
            NoteSessionSummaryInput summary,
            String createTime,
            String updateTime,
            @Valid List<NoteRecordInput> records
    ) {
    }

    public record NotePlayerView(
            Integer seatNo,
            String nickname,
            Boolean alive,
            Boolean isSheriff,
            String claimedRole,
            String realRole,
            String factionHint,
            Integer suspicionLevel,
            List<String> tags,
            Integer deathDay,
            String deathPhase,
            String deathReason,
            Boolean isFocus,
            String note,
            String createTime,
            String updateTime
    ) {
    }

    public record NoteSessionSummaryView(
            Integer aliveCount,
            Integer deadCount,
            Integer todayOutSeat,
            List<Integer> latestWolfPackSeats,
            String latestWolfPackText,
            Integer keyEventCount,
            String latestRecordType,
            String latestRecordPreview
    ) {
    }

    public record NoteRecordView(
            String id,
            String type,
            String scene,
            Integer day,
            Integer round,
            String phase,
            List<Integer> actorSeats,
            List<Integer> targetSeats,
            String content,
            String player,
            Map<String, Object> payload,
            List<String> tags,
            Boolean editable,
            String timestamp,
            String createTime,
            String updateTime
    ) {
    }

    public record NoteSessionView(
            String sessionId,
            Integer version,
            String boardMode,
            Integer boardId,
            String boardName,
            Integer playerCount,
            String status,
            Integer currentDay,
            String currentPhase,
            String resultCamp,
            Integer sheriffSeat,
            List<NotePlayerView> players,
            NoteSessionSummaryView summary,
            String createTime,
            String updateTime,
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
            String briefConfig,
            String cardDescription,
            String campSummary,
            String lineupSummary,
            List<BoardRoleView> roles,
            List<BoardRoleView> featuredRoles,
            String summary,
            String judgeSupportLevel
    ) {
    }

    public record BoardRoleView(Integer roleId, String name, String faction, String roleType, String camp, String portrait, Integer count) {
    }

    public record BoardDetailView(
            Integer id,
            String name,
            Integer playerCount,
            String difficulty,
            List<String> tags,
            String coverImage,
            String briefConfig,
            String cardDescription,
            List<String> specialRules,
            List<String> tips,
            List<FaqInput> faqs,
            String winCondition,
            String ruleType,
            List<BoardRoleView> roles,
            String judgeSupportLevel
    ) {
    }

    public record RoleListItemView(
            Integer id,
            String name,
            String alias,
            String faction,
            String roleType,
            String camp,
            String portrait
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
            String fullIllustration
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
            List<RelatedBoardView> boards
    ) {
    }

    public record PostSummaryView(
            Integer id,
            String openid,
            String nickname,
            String avatar,
            String postType,
            String title,
            String summary,
            String content,
            List<String> images,
            Integer boardId,
            String boardName,
            List<String> roleTags,
            List<String> tagList,
            String sessionId,
            Integer qualityScore,
            Double hotScore,
            Integer viewCount,
            Integer likeCount,
            Integer commentCount,
            Integer favoriteCount,
            String status,
            boolean featured,
            boolean pinned,
            boolean liked,
            boolean favorited,
            boolean owned,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    public record PostBoardView(
            Integer boardId,
            String boardName,
            String coverImage,
            Integer playerCount
    ) {
    }

    public record CommentView(
            Integer id,
            Integer postId,
            String openid,
            String nickname,
            String avatar,
            Integer parentCommentId,
            String replyToOpenid,
            String replyToNickname,
            String content,
            Integer likeCount,
            boolean liked,
            boolean owned,
            boolean postAuthor,
            String status,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    public record PostDetailView(
            Integer id,
            String openid,
            String nickname,
            String avatar,
            String postType,
            String title,
            String summary,
            String content,
            List<String> images,
            Integer boardId,
            String boardName,
            List<String> roleTags,
            List<String> tagList,
            String sessionId,
            Integer qualityScore,
            Double hotScore,
            Integer viewCount,
            Integer likeCount,
            Integer commentCount,
            Integer favoriteCount,
            String status,
            boolean featured,
            boolean pinned,
            boolean liked,
            boolean favorited,
            boolean owned,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            PostBoardView board,
            List<CommentView> comments,
            List<PostSummaryView> relatedPosts
    ) {
    }

    public record ToggleLikeResponse(boolean liked, int likeCount) {
    }

    public record ToggleFavoriteResponse(boolean favorited, int favoriteCount) {
    }

    public record HotBoardTopicView(
            Integer boardId,
            String boardName,
            String coverImage,
            Integer playerCount,
            Integer postCount,
            Double hotScore
    ) {
    }

    public record CommunityFeedView(
            PageResponse<PostSummaryView> posts,
            List<HotBoardTopicView> hotBoards,
            List<String> suggestedTags
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
            String userId,
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
}
