package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import com.wolfbook.backend.domain.JudgeRules;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.JudgeActionEventEntity;
import com.wolfbook.backend.entity.JudgeRoomEntity;
import com.wolfbook.backend.entity.JudgeRoomPlayerEntity;
import com.wolfbook.backend.entity.RoleEntity;
import com.wolfbook.backend.mapper.JudgeActionEventMapper;
import com.wolfbook.backend.mapper.JudgeRoomMapper;
import com.wolfbook.backend.mapper.JudgeRoomPlayerMapper;
import com.wolfbook.backend.mapper.RoleMapper;
import com.wolfbook.backend.service.judge.NightKind;
import com.wolfbook.backend.service.judge.NightPlan;
import com.wolfbook.backend.service.judge.NightResolver;
import com.wolfbook.backend.service.judge.RoleSkillRegistry;
import com.wolfbook.backend.service.judge.VoteResolver;
import com.wolfbook.backend.service.judge.WinJudge;
import com.wolfbook.backend.support.JudgeSupportLevels;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 法官房间服务。
 *
 * <p>负责从板子创建房间、玩家座位、身份分配、夜晚/白天流程、事件记录和房间快照。
 * 这是交互最重的服务，读代码时可先找“创建房间、加入房间、开始游戏、提交行动”几个入口方法。</p>
 */
@Service
public class JudgeService {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ROOM_STATUS_LOBBY = "lobby";
    private static final String ROOM_STATUS_PLAYING = "playing";
    private static final String ROOM_STATUS_FINISHED = "finished";
    private static final String JUDGE_MODE_OBSERVER = "observer";
    private static final String JUDGE_MODE_JOINED = "joined";
    private static final String PHASE_LOBBY = "lobby";
    private static final String PHASE_NIGHT_ACTION = "night_action";
    private static final String PHASE_DAY_SPEECH = "day_speech";
    private static final String PHASE_EXILE_VOTE = "exile_vote";
    private static final String PHASE_RESULT = "result";
    /** 新阶段机（auto_judge_enabled 的房间）：天亮后先公布死讯 / 上警，旧房间直接进发言。 */
    private static final String PHASE_DAY_ANNOUNCE = "day_announce";
    private static final String VISIBILITY_PUBLIC = "public";
    private static final String VISIBILITY_SYSTEM = "system";
    private static final String VISIBILITY_JUDGE_ONLY = "judge_only";
    private static final String VISIBILITY_ACTOR_ONLY = "actor_only";
    private static final String EVENT_ROOM_CREATED = "room_created";
    private static final String EVENT_PLAYER_JOINED = "player_joined";
    private static final String EVENT_READY_CHANGED = "ready_changed";
    private static final String EVENT_GAME_STARTED = "game_started";
    private static final String EVENT_BROADCAST = "broadcast";
    private static final String EVENT_NIGHT_SUBMIT = "night_action_submit";
    private static final String EVENT_VOTE_SUBMIT = "vote_submit";
    private static final String EVENT_NIGHT_RESOLVED = "night_resolved";
    private static final String EVENT_VOTE_OPENED = "vote_opened";
    private static final String EVENT_VOTE_RESOLVED = "vote_resolved";
    private static final String EVENT_GAME_FINISHED = "game_finished";
    private static final String EVENT_NIGHT_OVERRIDE = "night_override";
    private static final String EVENT_NIGHT_FEEDBACK = "night_feedback";
    private static final String EVENT_STEP_CLOSED = "step_closed";
    private static final String EVENT_GAME_FINISHED_AUTO = "game_finished_auto";
    private static final String DRAW = "流局";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final UserService userService;
    private final BoardService boardService;
    private final RoleMapper roleMapper;
    private final JudgeRoomMapper judgeRoomMapper;
    private final JudgeRoomPlayerMapper judgeRoomPlayerMapper;
    private final JudgeActionEventMapper judgeActionEventMapper;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public JudgeService(
            UserService userService,
            BoardService boardService,
            RoleMapper roleMapper,
            JudgeRoomMapper judgeRoomMapper,
            JudgeRoomPlayerMapper judgeRoomPlayerMapper,
            JudgeActionEventMapper judgeActionEventMapper,
            ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.boardService = boardService;
        this.roleMapper = roleMapper;
        this.judgeRoomMapper = judgeRoomMapper;
        this.judgeRoomPlayerMapper = judgeRoomPlayerMapper;
        this.judgeActionEventMapper = judgeActionEventMapper;
        this.objectMapper = objectMapper;
    }

    public List<WolfbookDtos.JudgeRoomSummaryView> listRecentRooms(String authorization) {
        String openid = requireOpenid(authorization);
        Map<String, JudgeRoomEntity> roomsById = new LinkedHashMap<>();

        judgeRoomMapper.selectList(
                        new LambdaQueryWrapper<JudgeRoomEntity>()
                                .eq(JudgeRoomEntity::getOwnerUserId, openid)
                                .orderByDesc(JudgeRoomEntity::getUpdateTime)
                ).forEach(room -> roomsById.put(room.getRoomId(), room));

        List<String> joinedRoomIds = judgeRoomPlayerMapper.selectList(
                        new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                                .eq(JudgeRoomPlayerEntity::getUserId, openid)
                                .orderByDesc(JudgeRoomPlayerEntity::getUpdateTime)
                ).stream()
                .map(JudgeRoomPlayerEntity::getRoomId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        if (!joinedRoomIds.isEmpty()) {
            judgeRoomMapper.selectBatchIds(joinedRoomIds).forEach(room -> roomsById.putIfAbsent(room.getRoomId(), room));
        }

        List<JudgeRoomEntity> rooms = roomsById.values().stream()
                .sorted(Comparator.comparing(this::roomUpdateTime).reversed())
                .limit(12)
                .toList();
        Map<String, List<JudgeRoomPlayerEntity>> playerMap = loadPlayersByRoomIds(rooms.stream().map(JudgeRoomEntity::getRoomId).toList());
        return rooms.stream()
                .map(room -> toSummaryView(room, playerMap.getOrDefault(room.getRoomId(), List.of()), openid))
                .toList();
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView createRoom(String authorization, WolfbookDtos.JudgeRoomCreateRequest request) {
        UserProfile user = userService.requireUser(authorization);
        WolfbookDtos.BoardDetailView board = boardService.getBoardDetail(request.boardId());
        String judgeSupportLevel = JudgeSupportLevels.normalize(board.judgeSupportLevel());
        String judgeMode = normalizeJudgeMode(request.judgeMode(), judgeSupportLevel);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);

        JudgeRoomEntity room = new JudgeRoomEntity();
        room.setRoomId(makeId("room"));
        room.setRoomCode(generateRoomCode());
        room.setBoardId(board.id());
        room.setBoardName(board.name());
        room.setPlayerCount(Math.max(1, board.playerCount()));
        room.setJudgeMode(judgeMode);
        room.setJudgeSupportLevel(judgeSupportLevel);
        room.setOwnerUserId(user.openid());
        room.setOwnerPlayerId(null);
        room.setOwnerSeatNo(null);
        room.setRoomStatus(ROOM_STATUS_LOBBY);
        room.setCurrentDay(1);
        room.setCurrentPhase(PHASE_LOBBY);
        boolean autoJudge = Boolean.TRUE.equals(request.autoJudge());
        JudgeRules rules = JudgeRules.fromBoard(boardService.getBoard(board.id()));
        room.setAutoJudgeEnabled(autoJudge);
        room.setSheriffEnabled(autoJudge && (request.sheriffEnabled() != null ? request.sheriffEnabled() : rules.sheriffDefault()));
        room.setRulesJson(writeJson(rules));
        room.setCanRollback(true);
        room.setWinnerCamp(null);
        room.setLatestAnnouncement("房间已创建，等待玩家入座");
        room.setCreateTime(now);
        room.setUpdateTime(now);
        judgeRoomMapper.insert(room);

        JudgeRoomPlayerEntity judgeObserver = new JudgeRoomPlayerEntity();
        judgeObserver.setPlayerId(makeId("player"));
        judgeObserver.setRoomId(room.getRoomId());
        judgeObserver.setUserId(user.openid());
        judgeObserver.setNickname(user.nickname());
        judgeObserver.setAvatar(user.avatar());
        judgeObserver.setSeatNo(null);
        judgeObserver.setRoomOwner(true);
        judgeObserver.setJudgeObserver(true);
        judgeObserver.setPlaying(false);
        judgeObserver.setReady(true);
        judgeObserver.setAlive(true);
        judgeObserver.setCreateTime(now);
        judgeObserver.setUpdateTime(now);
        judgeRoomPlayerMapper.insert(judgeObserver);

        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                judgeObserver.getPlayerId(),
                List.of(),
                EVENT_ROOM_CREATED,
                mapOf("judgeMode", judgeMode, "boardName", board.name()),
                mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return buildSnapshot(user.openid(), room.getRoomId());
    }

    public WolfbookDtos.JudgeRoomSnapshotView getRoomSnapshot(String authorization, String roomId) {
        return buildSnapshot(requireOpenid(authorization), resolveRoomId(roomId));
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView joinRoom(String authorization, String roomId) {
        roomId = resolveRoomId(roomId);
        UserProfile user = userService.requireUser(authorization);
        JudgeRoomEntity room = getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，当前不能加入");
        }

        JudgeRoomPlayerEntity existing = findPlayer(roomId, user.openid());
        if (existing != null) {
            return buildSnapshot(user.openid(), roomId);
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        if (countJoinedPlayers(players) >= room.getPlayerCount()) {
            throw new ApiException(4002, "房间已满");
        }

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        JudgeRoomPlayerEntity player = new JudgeRoomPlayerEntity();
        player.setPlayerId(makeId("player"));
        player.setRoomId(roomId);
        player.setUserId(user.openid());
        player.setNickname(user.nickname());
        player.setAvatar(user.avatar());
        player.setSeatNo(nextSeatNo(players, room.getPlayerCount()));
        player.setRoomOwner(false);
        player.setJudgeObserver(false);
        player.setPlaying(true);
        player.setReady(false);
        player.setAlive(true);
        player.setCreateTime(now);
        player.setUpdateTime(now);
        try {
            judgeRoomPlayerMapper.insert(player);
        } catch (DuplicateKeyException exception) {
            // 两人同时入座算出了同一个座号，由唯一键兜底
            throw new ApiException(4002, "座位刚被其他玩家占用，请重试");
        }

        room.setLatestAnnouncement(player.getNickname() + " 已加入房间");
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                List.of(),
                EVENT_PLAYER_JOINED,
                mapOf("seatNo", player.getSeatNo(), "nickname", player.getNickname()),
                mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return buildSnapshot(user.openid(), roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView toggleReady(String authorization, String roomId) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，无法修改准备状态");
        }

        JudgeRoomPlayerEntity player = requirePlayingPlayer(roomId, openid);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        player.setReady(!Boolean.TRUE.equals(player.getReady()));
        player.setUpdateTime(now);
        judgeRoomPlayerMapper.updateById(player);

        room.setLatestAnnouncement(player.getNickname() + (Boolean.TRUE.equals(player.getReady()) ? " 已准备" : " 取消准备"));
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                List.of(),
                EVENT_READY_CHANGED,
                mapOf("seatNo", player.getSeatNo(), "ready", Boolean.TRUE.equals(player.getReady())),
                mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return buildSnapshot(openid, roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView startRoom(String authorization, String roomId) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = requireJudgeRoom(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "房间不在可开局状态");
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        List<JudgeRoomPlayerEntity> playingPlayers = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .sorted(Comparator.comparing(JudgeRoomPlayerEntity::getSeatNo))
                .toList();
        if (playingPlayers.size() != room.getPlayerCount()) {
            throw new ApiException(4002, "玩家人数未满，暂时无法开局");
        }
        if (playingPlayers.stream().anyMatch(player -> !Boolean.TRUE.equals(player.getReady()))) {
            throw new ApiException(4002, "仍有玩家未准备");
        }

        Map<Integer, RoleEntity> roleMap = loadBoardRoleMap(room.getBoardId());
        List<RoleEntity> shuffledRoles = buildShuffledRoles(boardService.getBoard(room.getBoardId()), roleMap);
        if (shuffledRoles.size() != playingPlayers.size()) {
            throw new ApiException(4002, "板子角色配置与人数不匹配");
        }

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        room.setRoomStatus(ROOM_STATUS_PLAYING);
        room.setCurrentDay(1);
        room.setCurrentPhase(PHASE_NIGHT_ACTION);
        room.setLatestAnnouncement("身份已发放，进入第一夜");
        room.setUpdateTime(now);
        openNight(room, 1, now);
        updateRoomGuarded(room, ROOM_STATUS_LOBBY, null);

        for (int index = 0; index < playingPlayers.size(); index++) {
            JudgeRoomPlayerEntity player = playingPlayers.get(index);
            RoleEntity role = shuffledRoles.get(index);
            player.setReady(true);
            player.setAlive(true);
            player.setRoleId(role.getId());
            player.setRoleName(role.getName());
            player.setFaction(role.getFaction());
            player.setDeathDay(null);
            player.setDeathPhase(null);
            player.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(player);
        }

        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_GAME_STARTED,
                mapOf("playerCount", room.getPlayerCount()),
                mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return buildSnapshot(openid, roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView advanceRoom(String authorization, String roomId, WolfbookDtos.JudgeRoomAdvanceRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = requireJudgeRoom(roomId, openid);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            throw new ApiException(4002, ROOM_STATUS_FINISHED.equals(room.getRoomStatus()) ? "对局已结束" : "对局尚未开始");
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        switch (normalizeAction(request.action())) {
            case "broadcast" -> handleBroadcast(room, request, now);
            case "start_vote" -> handleStartVote(room, request, now);
            case "next_step" -> handleNextStep(room, players, request, now);
            case "resolve_night" -> handleResolveNight(room, players, request, now);
            case "resolve_vote" -> handleResolveVote(room, players, request, now);
            case "finish" -> handleFinish(room, players, request, now);
            default -> throw new ApiException(4000, "不支持的推进动作");
        }
        return buildSnapshot(openid, roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView submitNightAction(String authorization, String roomId, WolfbookDtos.JudgeNightActionRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = getRoom(roomId);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间行动阶段");
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity player;
        if (request.actorSeatNo() != null) {
            // 线下面杀：玩家闭眼指人，由法官替这个座位记下夜间行动
            requireJudgeRoom(roomId, openid);
            player = requireAliveSeat(players, request.actorSeatNo());
        } else {
            player = requireAlivePlayingPlayer(roomId, openid);
        }
        JudgeRoomPlayerEntity targetPlayer = null;
        if (request.targetSeatNo() != null) {
            targetPlayer = requireAliveSeat(players, request.targetSeatNo());
        }
        Integer targetSeatNo = targetPlayer == null ? null : targetPlayer.getSeatNo();
        String actionLabel = safeText(request.actionType(), "night_action");
        String action = RoleSkillRegistry.LEGACY_ACTIONS.getOrDefault(actionLabel, actionLabel);
        validateNightAction(room, players, player, targetPlayer, action);
        deletePhaseEvent(roomId, room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), EVENT_NIGHT_SUBMIT);

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                targetPlayer == null ? List.of() : List.of(targetPlayer.getPlayerId()),
                EVENT_NIGHT_SUBMIT,
                mapOf(
                        // actionType 原样保留（旧版小程序按中文名识别刀口、用药），结算读英文的 action
                        "actionType", actionLabel,
                        "action", action,
                        "targetSeatNo", targetSeatNo,
                        "note", trimToNull(request.note())
                ),
                Map.of(),
                VISIBILITY_JUDGE_ONLY,
                now
        );
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        return buildSnapshot(openid, roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView submitVote(String authorization, String roomId, WolfbookDtos.JudgeVoteRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = getRoom(roomId);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_EXILE_VOTE.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是投票阶段");
        }

        JudgeRoomPlayerEntity player = requireAlivePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity targetPlayer = requireAliveSeatPlayer(players, request.targetSeatNo());
        deletePhaseEvent(roomId, room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), EVENT_VOTE_SUBMIT);

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                List.of(targetPlayer.getPlayerId()),
                EVENT_VOTE_SUBMIT,
                mapOf("targetSeatNo", targetPlayer.getSeatNo()),
                Map.of(),
                VISIBILITY_JUDGE_ONLY,
                now
        );
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        return buildSnapshot(openid, roomId);
    }

    private WolfbookDtos.JudgeRoomSnapshotView buildSnapshot(String openid, String roomId) {
        JudgeRoomEntity room = getRoom(roomId);
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity self = players.stream()
                .filter(player -> openid.equals(player.getUserId()))
                .findFirst()
                .orElse(null);
        boolean judgeViewer = self != null && Boolean.TRUE.equals(self.getJudgeObserver());
        boolean joinedViewer = self != null && Boolean.TRUE.equals(self.getPlaying()) && !judgeViewer;
        if (self == null && !ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4003, "对局已开始，当前无法旁观");
        }

        List<JudgeActionEventEntity> events = loadEvents(roomId);
        int joinedCount = countJoinedPlayers(players);
        int readyCount = countReadyPlayers(players);
        boolean roomFinished = ROOM_STATUS_FINISHED.equals(room.getRoomStatus());
        boolean revealAllRoles = judgeViewer || roomFinished;
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));

        List<WolfbookDtos.JudgePlayerView> playerViews = players.stream()
                .sorted(playerComparator())
                .map(player -> toPlayerView(
                        player,
                        revealAllRoles || (self != null && Objects.equals(self.getPlayerId(), player.getPlayerId())),
                        self != null && Objects.equals(self.getPlayerId(), player.getPlayerId())
                ))
                .toList();
        List<WolfbookDtos.JudgeEventView> timeline = events.stream()
                .filter(event -> canViewEvent(event, judgeViewer, self == null ? null : self.getPlayerId()))
                .sorted(Comparator.comparing(JudgeActionEventEntity::getCreateTime))
                .map(event -> toEventView(event, playersById))
                .toList();

        return new WolfbookDtos.JudgeRoomSnapshotView(
                room.getRoomId(),
                room.getRoomCode(),
                room.getBoardId(),
                room.getBoardName(),
                room.getPlayerCount(),
                room.getJudgeMode(),
                JudgeSupportLevels.normalize(room.getJudgeSupportLevel()),
                room.getRoomStatus(),
                safeInt(room.getCurrentDay(), 1),
                safeText(room.getCurrentPhase(), PHASE_LOBBY),
                safeText(room.getWinnerCamp(), ""),
                safeText(room.getLatestAnnouncement(), ""),
                judgeViewer,
                joinedViewer,
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) && self == null && joinedCount < room.getPlayerCount(),
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) && joinedViewer,
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) && judgeViewer && joinedCount == room.getPlayerCount() && readyCount == room.getPlayerCount(),
                ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && judgeViewer,
                joinedViewer && Boolean.TRUE.equals(self.getAlive()) && PHASE_EXILE_VOTE.equals(room.getCurrentPhase()) && ROOM_STATUS_PLAYING.equals(room.getRoomStatus()),
                joinedViewer && Boolean.TRUE.equals(self.getAlive()) && PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) && ROOM_STATUS_PLAYING.equals(room.getRoomStatus()),
                joinedCount,
                readyCount,
                buildRoomTip(room, joinedCount, readyCount, judgeViewer),
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) ? "" : buildWinnerSuggestion(room, players),
                self == null ? null : toPlayerView(self, revealAllRoles || Boolean.TRUE.equals(self.getPlaying()), true),
                playerViews,
                timeline,
                (judgeViewer || roomFinished) ? buildVoteTallies(room, players, events) : List.of(),
                judgeViewer ? buildNightActions(room, players, events) : List.of(),
                (judgeViewer || roomFinished) ? buildNightHistory(players, events) : List.of(),
                self == null ? new WolfbookDtos.JudgePendingActionView(false, null, "") : buildPendingNightAction(room, self, events, playersById),
                room.getCreateTime(),
                roomUpdateTime(room),
                buildStepView(room, self, judgeViewer),
                roomRules(room),
                openid.equals(room.getOwnerUserId()),
                roomFinished && StringUtils.hasText(room.getWinnerCamp())
                        ? new WolfbookDtos.JudgeVerdictView(room.getWinnerCamp(), safeText(asString(readMap(room.getPhaseData()).get("verdictReason")), ""))
                        : null
        );
    }

    /** 夜里当前这一步：持有者和法官看得到台词，其他人只知道是第几步、哪个角色（播报里本来就会念）。 */
    private WolfbookDtos.JudgeStepView buildStepView(JudgeRoomEntity room, JudgeRoomPlayerEntity self, boolean judgeViewer) {
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) || room.getNightStep() == null) {
            return null;
        }
        List<NightPlan.NightStep> steps = nightSteps(room, safeInt(room.getCurrentDay(), 1));
        if (room.getNightStep() >= steps.size()) {
            return null;
        }
        NightPlan.NightStep step = steps.get(room.getNightStep());
        boolean holder = self != null && Boolean.TRUE.equals(self.getPlaying()) && self.getRoleId() != null
                && step.roleIds().contains(self.getRoleId());
        return new WolfbookDtos.JudgeStepView(
                step.index(),
                steps.size(),
                step.kind().name().toLowerCase(Locale.ROOT),
                step.roleName(),
                judgeViewer || holder ? step.line() : "",
                room.getStepDeadline()
        );
    }

    private void handleBroadcast(JudgeRoomEntity room, WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        String announcement = requireAnnouncement(request.announcement());
        room.setLatestAnnouncement(announcement);
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_BROADCAST,
                mapOf("announcement", announcement),
                Map.of(),
                VISIBILITY_SYSTEM,
                now
        );
    }

    private void handleStartVote(JudgeRoomEntity room, WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        if (!PHASE_DAY_SPEECH.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是发言阶段");
        }
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : "进入放逐投票阶段";
        room.setCurrentPhase(PHASE_EXILE_VOTE);
        room.setLatestAnnouncement(announcement);
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_SPEECH);
        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_VOTE_OPENED,
                mapOf("announcement", announcement),
                Map.of(),
                VISIBILITY_SYSTEM,
                now
        );
    }

    /** 法官手动关掉当前夜间步骤：狼队那一步关闭时定下刀口；最后一步关闭后直接按引擎结算这一夜。 */
    private void handleNextStep(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        if (!PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) || room.getNightStep() == null) {
            throw new ApiException(4002, "当前不是夜间行动阶段");
        }
        int current = room.getNightStep();
        if (request.stepIndex() != null && request.stepIndex() != current) {
            throw new ApiException(4002, "这一步已经过去了，请刷新后重试");
        }
        int dayNo = safeInt(room.getCurrentDay(), 1);
        List<NightPlan.NightStep> steps = nightSteps(room, dayNo);
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        if (current < steps.size() && steps.get(current).kind() == NightKind.WOLF) {
            List<Integer> picks = tonightSubmissions(room, players).stream()
                    .filter(submission -> submission.kind() == NightKind.WOLF && NightResolver.KILL.equals(submission.action()))
                    .map(NightResolver.Submission::targetSeat)
                    .toList();
            phaseData.put("knifeDecided", true);
            phaseData.put("knifeSeat", NightResolver.tallyKnife(picks, random));
        }
        addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, null, List.of(), EVENT_STEP_CLOSED,
                mapOf("stepIndex", current), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        room.setPhaseData(writeJson(phaseData));
        if (current + 1 >= steps.size()) {
            room.setUpdateTime(now);
            resolveNight(room, players, null, null, null, now, current);
            return;
        }
        room.setNightStep(current + 1);
        room.setStepDeadline(now.plusSeconds(steps.get(current + 1).durationSeconds()));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, current);
    }

    private void handleResolveNight(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        if (!PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间结算阶段");
        }
        resolveNight(room, players, request.deadSeatNos(), request.winnerCamp(), request.announcement(), now, null);
    }

    /**
     * 结算一夜。deadSeatNos 为 null 时按引擎算死亡；传了（哪怕是空列表，旧版小程序的平安夜）就以法官为准，另记一条改判记录。
     * 无论哪种，女巫用药、守卫上一夜、被毒猎人的状态都按今晚的提交更新，预言家 / 女巫的私密反馈照发。
     * expectedNightStep 不为空时按「当前步骤」做并发保护（next_step 的最后一步）。
     */
    private void resolveNight(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            List<Integer> requestedDeadSeatNos,
            String requestedWinnerCamp,
            String requestedAnnouncement,
            LocalDateTime now,
            Integer expectedNightStep
    ) {
        int dayNo = safeInt(room.getCurrentDay(), 1);
        JudgeRules rules = roomRules(room);
        List<NightResolver.Submission> submissions = tonightSubmissions(room, players);
        Map<String, Object> phaseData = readMap(room.getPhaseData());
        if (Boolean.TRUE.equals(phaseData.get("knifeDecided"))) {
            // 狼队那一步已经定了刀口（平票随机过一次），结算时不再重抽
            Integer knife = asPositiveInteger(phaseData.get("knifeSeat"));
            List<NightResolver.Submission> fixed = new ArrayList<>(submissions.stream()
                    .filter(submission -> submission.kind() != NightKind.WOLF)
                    .toList());
            if (knife != null) {
                fixed.add(new NightResolver.Submission(0, NightKind.WOLF, NightResolver.KILL, knife));
            }
            submissions = fixed;
        }
        Map<Integer, NightResolver.SeatState> seats = seatStates(players);
        NightResolver.Outcome outcome = NightResolver.resolve(submissions, seats, rules, dayNo, random);

        List<Integer> deadSeatNos;
        boolean overridden = requestedDeadSeatNos != null;
        if (overridden) {
            deadSeatNos = normalizeSeatNos(requestedDeadSeatNos);
            requireAliveSeats(players, deadSeatNos);
        } else {
            deadSeatNos = outcome.deadSeats();
        }
        applyNightStatus(players, submissions, outcome, now);
        markPlayersDead(players, deadSeatNos, dayNo, PHASE_NIGHT_ACTION, now);
        for (Map.Entry<Integer, String> entry : outcome.feedback().entrySet()) {
            JudgeRoomPlayerEntity actor = findSeatPlayer(players, entry.getKey());
            if (actor != null) {
                addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, actor.getPlayerId(), List.of(), EVENT_NIGHT_FEEDBACK,
                        mapOf("feedback", entry.getValue()), Map.of(), VISIBILITY_ACTOR_ONLY, now);
            }
        }
        if (overridden) {
            addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, null, List.of(), EVENT_NIGHT_OVERRIDE,
                    mapOf("deadSeatNos", deadSeatNos, "engineDeadSeatNos", outcome.deadSeats()), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        }

        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, requestedWinnerCamp);
        boolean finished = verdict.isPresent();
        String announcement = StringUtils.hasText(requestedAnnouncement)
                ? requestedAnnouncement.trim()
                : buildNightAnnouncement(deadSeatNos) + verdict.map(value -> "。" + value.reason()).orElse("");
        String nextPhase = finished ? PHASE_RESULT : (Boolean.TRUE.equals(room.getAutoJudgeEnabled()) ? PHASE_DAY_ANNOUNCE : PHASE_DAY_SPEECH);
        room.setLatestAnnouncement(announcement);
        room.setWinnerCamp(verdict.map(WinJudge.Verdict::camp).orElse(null));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        room.setCurrentPhase(nextPhase);
        room.setNightStep(null);
        room.setStepDeadline(null);
        room.setPhaseData(verdict.map(value -> writeJson(mapOf("verdictReason", value.reason()))).orElse(null));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, expectedNightStep);
        addEvent(
                room.getRoomId(),
                dayNo,
                nextPhase,
                null,
                resolveTargetPlayerIds(players, deadSeatNos),
                EVENT_NIGHT_RESOLVED,
                mapOf("deadSeatNos", deadSeatNos),
                mapOf("announcement", announcement, "winnerCamp", room.getWinnerCamp()),
                VISIBILITY_PUBLIC,
                now
        );
        verdict.ifPresent(value -> addFinishedEvent(room, value, requestedWinnerCamp, announcement, now));
    }

    private void handleResolveVote(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        if (!PHASE_EXILE_VOTE.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是投票结算阶段");
        }
        List<JudgeActionEventEntity> voteEvents = loadEvents(room.getRoomId()).stream()
                .filter(event -> Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .filter(event -> PHASE_EXILE_VOTE.equals(event.getPhase()))
                .filter(event -> EVENT_VOTE_SUBMIT.equals(event.getActionType()))
                .toList();
        List<WolfbookDtos.JudgeVoteTallyView> tallies = buildVoteTallies(room, players, voteEvents);
        VoteResolver.Outcome voteOutcome = VoteResolver.resolve(ballotsOf(players, voteEvents), aliveSeats(players));
        Integer eliminatedSeatNo = normalizeSeatNo(request.eliminatedSeatNo());
        if (eliminatedSeatNo != null) {
            requireAliveSeat(players, eliminatedSeatNo);
        } else {
            eliminatedSeatNo = voteOutcome.eliminatedSeat();
        }
        if (eliminatedSeatNo != null) {
            markPlayersDead(players, List.of(eliminatedSeatNo), room.getCurrentDay(), PHASE_EXILE_VOTE, now);
        }

        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, request.winnerCamp());
        boolean finished = verdict.isPresent();
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : buildVoteAnnouncement(eliminatedSeatNo, tallies) + verdict.map(value -> "。" + value.reason()).orElse("");

        int resolvedDay = safeInt(room.getCurrentDay(), 1);
        room.setLatestAnnouncement(announcement);
        room.setWinnerCamp(verdict.map(WinJudge.Verdict::camp).orElse(null));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        room.setCurrentDay(finished ? resolvedDay : resolvedDay + 1);
        room.setCurrentPhase(finished ? PHASE_RESULT : PHASE_NIGHT_ACTION);
        if (finished) {
            room.setPhaseData(writeJson(mapOf("verdictReason", verdict.get().reason())));
        } else {
            openNight(room, resolvedDay + 1, now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_EXILE_VOTE);

        addEvent(
                room.getRoomId(),
                resolvedDay,
                finished ? PHASE_RESULT : PHASE_NIGHT_ACTION,
                null,
                resolveTargetPlayerIds(players, eliminatedSeatNo == null ? List.of() : List.of(eliminatedSeatNo)),
                EVENT_VOTE_RESOLVED,
                mapOf("eliminatedSeatNo", eliminatedSeatNo),
                mapOf("announcement", announcement, "winnerCamp", room.getWinnerCamp(),
                        "tallies", summarizeTallies(voteOutcome), "abstainSeatNos", voteOutcome.abstainSeats()),
                VISIBILITY_PUBLIC,
                now
        );
        verdict.ifPresent(value -> addFinishedEvent(room, value, request.winnerCamp(), announcement, now));
    }

    private void handleFinish(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        // 不填胜方就是流局（桌上出了岔子、提前散场）；胜负由引擎在每次出局后自动判，不再按人数猜
        String winnerCamp = safeText(trimToNull(request.winnerCamp()), DRAW);
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : "对局已结束";
        room.setWinnerCamp(winnerCamp);
        room.setLatestAnnouncement(announcement);
        room.setRoomStatus(ROOM_STATUS_FINISHED);
        room.setCurrentPhase(PHASE_RESULT);
        room.setNightStep(null);
        room.setStepDeadline(null);
        room.setPhaseData(writeJson(mapOf("verdictReason", DRAW.equals(winnerCamp) ? "法官结束了对局（流局）" : "法官判定" + winnerCamp + "胜利")));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, null);
        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                PHASE_RESULT,
                null,
                List.of(),
                EVENT_GAME_FINISHED,
                mapOf("winnerCamp", winnerCamp),
                mapOf("announcement", announcement),
                VISIBILITY_SYSTEM,
                now
        );
    }

    // ---------- 结算引擎的胶水：读写房间 / 座位状态 ----------

    /** 开一个新的夜晚：从第 0 步开始，截止时间按这一步的时长，清空上一阶段的临时数据。 */
    private void openNight(JudgeRoomEntity room, int dayNo, LocalDateTime now) {
        List<NightPlan.NightStep> steps = nightSteps(room, dayNo);
        room.setNightStep(steps.isEmpty() ? null : 0);
        room.setStepDeadline(steps.isEmpty() ? null : now.plusSeconds(steps.get(0).durationSeconds()));
        room.setPhaseData(writeJson(Map.of()));
    }

    private List<NightPlan.NightStep> nightSteps(JudgeRoomEntity room, int dayNo) {
        Board board = boardService.getBoard(room.getBoardId());
        return NightPlan.build(board, loadBoardRoleMap(room.getBoardId()), dayNo);
    }

    /** 本局规则：开局时拷进房间的；老房间没有就现取板子的。 */
    private JudgeRules roomRules(JudgeRoomEntity room) {
        if (StringUtils.hasText(room.getRulesJson())) {
            try {
                return objectMapper.readValue(room.getRulesJson(), JudgeRules.class).withDefaults();
            } catch (Exception ignored) {
                // 读不出来就按板子现取
            }
        }
        return JudgeRules.fromBoard(boardService.getBoard(room.getBoardId()));
    }

    private NightKind nightKindOf(JudgeRoomPlayerEntity player) {
        if (RoleSkillRegistry.isWolfTeam(player.getRoleName(), player.getFaction())) {
            return NightKind.WOLF;
        }
        return RoleSkillRegistry.forRole(player.getRoleName(), player.getFaction())
                .map(RoleSkillRegistry.RoleSkill::nightKind)
                .orElse(NightKind.GENERIC);
    }

    /** 今晚的夜间提交（每个座位最后一条），旧事件没有 action 字段时按中文 actionType 换算。 */
    private List<NightResolver.Submission> tonightSubmissions(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        Map<Integer, NightResolver.Submission> bySeat = new LinkedHashMap<>();
        for (JudgeActionEventEntity event : loadEvents(room.getRoomId())) {
            if (!EVENT_NIGHT_SUBMIT.equals(event.getActionType())
                    || !Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1))
                    || !PHASE_NIGHT_ACTION.equals(event.getPhase())) {
                continue;
            }
            JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
            if (actor == null || actor.getSeatNo() == null) {
                continue;
            }
            Map<String, Object> payload = readMap(event.getPayloadJson());
            String label = safeText(asString(payload.get("actionType")), "");
            String action = safeText(asString(payload.get("action")), RoleSkillRegistry.LEGACY_ACTIONS.getOrDefault(label, label));
            bySeat.put(actor.getSeatNo(), new NightResolver.Submission(actor.getSeatNo(), nightKindOf(actor), action,
                    asPositiveInteger(payload.get("targetSeatNo"))));
        }
        return List.copyOf(bySeat.values());
    }

    private Map<Integer, NightResolver.SeatState> seatStates(List<JudgeRoomPlayerEntity> players) {
        Map<Integer, NightResolver.SeatState> seats = new LinkedHashMap<>();
        players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && player.getSeatNo() != null)
                .sorted(Comparator.comparing(JudgeRoomPlayerEntity::getSeatNo))
                .forEach(player -> {
                    Map<String, Object> status = readMap(player.getStatusJson());
                    seats.put(player.getSeatNo(), new NightResolver.SeatState(
                            player.getSeatNo(),
                            player.getRoleName(),
                            player.getFaction(),
                            Boolean.TRUE.equals(player.getAlive()),
                            !Boolean.FALSE.equals(status.get("antidote")),
                            !Boolean.FALSE.equals(status.get("poison")),
                            asPositiveInteger(status.get("lastGuardSeat"))
                    ));
                });
        return seats;
    }

    /** 把今晚的用药、守护、猎人能否开枪写回座位状态。 */
    private void applyNightStatus(List<JudgeRoomPlayerEntity> players, List<NightResolver.Submission> submissions,
                                  NightResolver.Outcome outcome, LocalDateTime now) {
        for (JudgeRoomPlayerEntity player : players) {
            if (player.getSeatNo() == null || !Boolean.TRUE.equals(player.getPlaying())) {
                continue;
            }
            Map<String, Object> status = new LinkedHashMap<>(readMap(player.getStatusJson()));
            boolean changed = false;
            int seat = player.getSeatNo();
            if (outcome.antidoteUsedBy().contains(seat)) {
                status.put("antidote", false);
                changed = true;
            }
            if (outcome.poisonUsedBy().contains(seat)) {
                status.put("poison", false);
                changed = true;
            }
            if (outcome.cannotShoot().contains(seat)) {
                status.put("canShoot", false);
                changed = true;
            }
            if (nightKindOf(player) == NightKind.GUARD) {
                Integer guarded = submissions.stream()
                        .filter(submission -> submission.actorSeat() == seat && NightResolver.GUARD.equals(submission.action()))
                        .map(NightResolver.Submission::targetSeat)
                        .findFirst()
                        .orElse(null);
                status.put("lastGuardSeat", guarded);
                changed = true;
            }
            if (changed) {
                player.setStatusJson(writeJson(status));
                player.setUpdateTime(now);
                judgeRoomPlayerMapper.updateById(player);
            }
        }
    }

    /**
     * 提交夜间行动时的校验：用药、连守这些规则对所有房间都生效；
     * 新阶段机的房间还要求「现在轮到你」、动作是这个角色能做的。
     */
    private void validateNightAction(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, JudgeRoomPlayerEntity actor,
                                     JudgeRoomPlayerEntity target, String action) {
        NightKind kind = nightKindOf(actor);
        int dayNo = safeInt(room.getCurrentDay(), 1);
        if (Boolean.TRUE.equals(room.getAutoJudgeEnabled()) && room.getNightStep() != null) {
            List<NightPlan.NightStep> steps = nightSteps(room, dayNo);
            NightPlan.NightStep step = room.getNightStep() < steps.size() ? steps.get(room.getNightStep()) : null;
            if (step == null || !step.roleIds().contains(actor.getRoleId())) {
                throw new ApiException(4003, "现在还没轮到你行动");
            }
            boolean allowed = kind == NightKind.WOLF
                    ? Set.of(NightResolver.KILL, NightResolver.NONE).contains(action)
                    : RoleSkillRegistry.forRole(actor.getRoleName(), actor.getFaction())
                            .map(skill -> skill.actions().contains(action))
                            .orElse(true);
            if (!allowed) {
                throw new ApiException(4000, "这个角色不能这样行动");
            }
        }
        if (!NightResolver.NONE.equals(action) && target == null) {
            return;
        }
        Map<Integer, NightResolver.SeatState> seats = seatStates(players);
        NightResolver.SeatState actorState = seats.get(actor.getSeatNo());
        NightResolver.SeatState targetState = target == null ? null : seats.get(target.getSeatNo());
        if (actorState == null) {
            return;
        }
        NightResolver.validate(new NightResolver.Submission(actor.getSeatNo(), kind, action, target == null ? null : target.getSeatNo()),
                actorState, targetState, roomRules(room), dayNo, knifeForValidation(room, players));
    }

    /** 女巫校验解药用的刀口：狼队那一步已经定了就用定下的，否则看当前狼队的唯一多数（还没定 / 平票时为空）。 */
    private Integer knifeForValidation(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        Map<String, Object> phaseData = readMap(room.getPhaseData());
        if (Boolean.TRUE.equals(phaseData.get("knifeDecided"))) {
            return asPositiveInteger(phaseData.get("knifeSeat"));
        }
        Map<Integer, Long> counts = tonightSubmissions(room, players).stream()
                .filter(submission -> submission.kind() == NightKind.WOLF && NightResolver.KILL.equals(submission.action())
                        && submission.targetSeat() != null)
                .collect(Collectors.groupingBy(NightResolver.Submission::targetSeat, Collectors.counting()));
        long top = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<Integer> leaders = counts.entrySet().stream().filter(entry -> entry.getValue() == top).map(Map.Entry::getKey).toList();
        return leaders.size() == 1 ? leaders.get(0) : null;
    }

    private List<VoteResolver.Ballot> ballotsOf(List<JudgeRoomPlayerEntity> players, List<JudgeActionEventEntity> voteEvents) {
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        List<VoteResolver.Ballot> ballots = new ArrayList<>();
        for (JudgeActionEventEntity event : voteEvents) {
            JudgeRoomPlayerEntity voter = playersById.get(event.getActorPlayerId());
            if (voter == null || voter.getSeatNo() == null) {
                continue;
            }
            ballots.add(new VoteResolver.Ballot(voter.getSeatNo(), asPositiveInteger(readMap(event.getPayloadJson()).get("targetSeatNo")),
                    Boolean.TRUE.equals(voter.getSheriff())));
        }
        return ballots;
    }

    private Set<Integer> aliveSeats(List<JudgeRoomPlayerEntity> players) {
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()) && player.getSeatNo() != null)
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .collect(Collectors.toSet());
    }

    /** 这次出局之后对局结束了吗：法官明确传了胜方就以法官为准，否则按板子的屠边 / 屠城规则自动判。 */
    private Optional<WinJudge.Verdict> verdictOf(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String requestedWinnerCamp) {
        String explicit = trimToNull(requestedWinnerCamp);
        if (explicit != null) {
            return Optional.of(new WinJudge.Verdict(explicit, "法官判定" + explicit + "胜利"));
        }
        return WinJudge.judge(winSeats(players), roomRules(room).winCondition());
    }

    private List<WinJudge.Seat> winSeats(List<JudgeRoomPlayerEntity> players) {
        Set<Integer> roleIds = players.stream().map(JudgeRoomPlayerEntity::getRoleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> roleTypes = roleIds.isEmpty() ? Map.of() : roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(RoleEntity::getId, role -> safeText(role.getRoleType(), "")));
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .map(player -> new WinJudge.Seat(Boolean.TRUE.equals(player.getAlive()), player.getFaction(),
                        roleTypes.getOrDefault(player.getRoleId(), "")))
                .toList();
    }

    private void addFinishedEvent(JudgeRoomEntity room, WinJudge.Verdict verdict, String requestedWinnerCamp, String announcement, LocalDateTime now) {
        boolean automatic = trimToNull(requestedWinnerCamp) == null;
        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                PHASE_RESULT,
                null,
                List.of(),
                automatic ? EVENT_GAME_FINISHED_AUTO : EVENT_GAME_FINISHED,
                mapOf("winnerCamp", verdict.camp()),
                mapOf("announcement", announcement, "reason", verdict.reason()),
                VISIBILITY_SYSTEM,
                now
        );
    }

    private JudgeRoomEntity requireJudgeRoom(String roomId, String openid) {
        JudgeRoomEntity room = getRoom(roomId);
        JudgeRoomPlayerEntity self = findPlayer(roomId, openid);
        if (self == null || !Boolean.TRUE.equals(self.getJudgeObserver())) {
            throw new ApiException(4003, "当前只有法官可以执行此操作");
        }
        return room;
    }

    private JudgeRoomPlayerEntity requirePlayingPlayer(String roomId, String openid) {
        JudgeRoomPlayerEntity player = findPlayer(roomId, openid);
        if (player == null || !Boolean.TRUE.equals(player.getPlaying())) {
            throw new ApiException(4003, "你尚未作为玩家加入当前房间");
        }
        return player;
    }

    private JudgeRoomPlayerEntity requireAlivePlayingPlayer(String roomId, String openid) {
        JudgeRoomPlayerEntity player = requirePlayingPlayer(roomId, openid);
        if (!Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4002, "你当前已出局，无法继续操作");
        }
        return player;
    }

    /** 法官/夜间操作引用的座位必须是在座且存活的玩家，否则直接拒绝，避免公告里出现不存在的座位。 */
    private JudgeRoomPlayerEntity requireAliveSeat(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        JudgeRoomPlayerEntity player = findSeatPlayer(players, seatNo);
        if (player == null) {
            throw new ApiException(4000, "座位 " + seatNo + " 不存在");
        }
        if (!Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4000, seatNo + "号已经出局");
        }
        return player;
    }

    private void requireAliveSeats(List<JudgeRoomPlayerEntity> players, Collection<Integer> seatNos) {
        for (Integer seatNo : seatNos) {
            requireAliveSeat(players, seatNo);
        }
    }

    /**
     * 带条件的房间更新：只有房间仍处于预期状态/阶段时才写入，否则说明有并发操作先完成了，
     * 抛错让当前事务回滚（例如法官连点两次“开局”或“结算”）。
     */
    private void updateRoomGuarded(JudgeRoomEntity room, String expectedStatus, String expectedPhase) {
        updateRoomGuarded(room, expectedStatus, expectedPhase, null);
    }

    /** 同上，另外要求夜里还停在 expectedNightStep 这一步（两台手机同时推进同一步时，后到的那个失败）。 */
    private void updateRoomGuarded(JudgeRoomEntity room, String expectedStatus, String expectedPhase, Integer expectedNightStep) {
        LambdaUpdateWrapper<JudgeRoomEntity> wrapper = new LambdaUpdateWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, room.getRoomId())
                .eq(JudgeRoomEntity::getRoomStatus, expectedStatus);
        if (expectedPhase != null) {
            wrapper.eq(JudgeRoomEntity::getCurrentPhase, expectedPhase);
        }
        if (expectedNightStep != null) {
            wrapper.eq(JudgeRoomEntity::getNightStep, expectedNightStep);
        }
        if (judgeRoomMapper.update(room, wrapper) != 1) {
            throw new ApiException(4002, "房间状态刚刚发生变化，请刷新后重试");
        }
    }

    private JudgeRoomPlayerEntity requireAliveSeatPlayer(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        JudgeRoomPlayerEntity player = findSeatPlayer(players, seatNo);
        if (player == null || !Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4002, "目标玩家当前不可选");
        }
        return player;
    }

    private JudgeRoomEntity getRoom(String roomId) {
        JudgeRoomEntity room = judgeRoomMapper.selectById(roomId);
        if (room == null) {
            throw new ApiException(4004, "房间不存在");
        }
        return room;
    }

    private JudgeRoomPlayerEntity findPlayer(String roomId, String openid) {
        return judgeRoomPlayerMapper.selectOne(
                new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                        .eq(JudgeRoomPlayerEntity::getRoomId, roomId)
                        .eq(JudgeRoomPlayerEntity::getUserId, openid)
                        .last("limit 1")
        );
    }

    private List<JudgeRoomPlayerEntity> loadPlayers(String roomId) {
        return judgeRoomPlayerMapper.selectList(
                new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                        .eq(JudgeRoomPlayerEntity::getRoomId, roomId)
                        .orderByAsc(JudgeRoomPlayerEntity::getSeatNo)
                        .orderByAsc(JudgeRoomPlayerEntity::getCreateTime)
        );
    }

    private Map<String, List<JudgeRoomPlayerEntity>> loadPlayersByRoomIds(List<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Map.of();
        }
        return judgeRoomPlayerMapper.selectList(
                        new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                                .in(JudgeRoomPlayerEntity::getRoomId, roomIds)
                                .orderByAsc(JudgeRoomPlayerEntity::getSeatNo)
                                .orderByAsc(JudgeRoomPlayerEntity::getCreateTime)
                ).stream()
                .collect(Collectors.groupingBy(JudgeRoomPlayerEntity::getRoomId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<JudgeActionEventEntity> loadEvents(String roomId) {
        return judgeActionEventMapper.selectList(
                new LambdaQueryWrapper<JudgeActionEventEntity>()
                        .eq(JudgeActionEventEntity::getRoomId, roomId)
                        .orderByAsc(JudgeActionEventEntity::getCreateTime)
        );
    }

    private int countJoinedPlayers(List<JudgeRoomPlayerEntity> players) {
        return (int) players.stream().filter(player -> Boolean.TRUE.equals(player.getPlaying())).count();
    }

    private int countReadyPlayers(List<JudgeRoomPlayerEntity> players) {
        return (int) players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .filter(player -> Boolean.TRUE.equals(player.getReady()))
                .count();
    }

    private int nextSeatNo(List<JudgeRoomPlayerEntity> players, int playerCount) {
        Set<Integer> used = players.stream()
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (int seatNo = 1; seatNo <= playerCount; seatNo++) {
            if (!used.contains(seatNo)) {
                return seatNo;
            }
        }
        throw new ApiException(4002, "房间已满");
    }

    private Map<Integer, RoleEntity> loadBoardRoleMap(Integer boardId) {
        Board board = boardService.getBoard(boardId);
        Set<Integer> roleIds = board.roles().stream()
                .map(BoardRoleRef::roleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(RoleEntity::getId, role -> role));
    }

    private List<RoleEntity> buildShuffledRoles(Board board, Map<Integer, RoleEntity> roleMap) {
        List<RoleEntity> roles = new ArrayList<>();
        for (BoardRoleRef ref : board.roles()) {
            RoleEntity role = roleMap.get(ref.roleId());
            if (role == null) {
                throw new ApiException(4004, "板子存在缺失角色配置");
            }
            for (int count = 0; count < Math.max(0, ref.count()); count++) {
                roles.add(role);
            }
        }
        Collections.shuffle(roles);
        return roles;
    }

    private WolfbookDtos.JudgeRoomSummaryView toSummaryView(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String openid) {
        JudgeRoomPlayerEntity self = players.stream()
                .filter(player -> openid.equals(player.getUserId()))
                .findFirst()
                .orElse(null);
        return new WolfbookDtos.JudgeRoomSummaryView(
                room.getRoomId(),
                room.getRoomCode(),
                room.getBoardId(),
                room.getBoardName(),
                room.getPlayerCount(),
                room.getJudgeMode(),
                JudgeSupportLevels.normalize(room.getJudgeSupportLevel()),
                safeText(room.getRoomStatus(), ROOM_STATUS_LOBBY),
                safeInt(room.getCurrentDay(), 1),
                safeText(room.getCurrentPhase(), PHASE_LOBBY),
                safeText(room.getWinnerCamp(), ""),
                countJoinedPlayers(players),
                countReadyPlayers(players),
                openid.equals(room.getOwnerUserId()),
                self != null,
                safeText(room.getLatestAnnouncement(), ""),
                room.getCreateTime(),
                roomUpdateTime(room)
        );
    }

    private WolfbookDtos.JudgePlayerView toPlayerView(JudgeRoomPlayerEntity player, boolean revealRole, boolean self) {
        return new WolfbookDtos.JudgePlayerView(
                player.getPlayerId(),
                self,
                safeText(player.getNickname(), "未命名玩家"),
                safeText(player.getAvatar(), ""),
                player.getSeatNo(),
                Boolean.TRUE.equals(player.getRoomOwner()),
                Boolean.TRUE.equals(player.getJudgeObserver()),
                Boolean.TRUE.equals(player.getPlaying()),
                Boolean.TRUE.equals(player.getReady()),
                Boolean.TRUE.equals(player.getAlive()),
                revealRole ? player.getRoleId() : null,
                revealRole ? trimToNull(player.getRoleName()) : null,
                revealRole ? trimToNull(player.getFaction()) : null,
                player.getDeathDay(),
                trimToNull(player.getDeathPhase())
        );
    }

    private WolfbookDtos.JudgeEventView toEventView(JudgeActionEventEntity event, Map<String, JudgeRoomPlayerEntity> playersById) {
        JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
        List<Integer> targetSeatNos = readStringList(event.getTargetPlayerIdsJson()).stream()
                .map(playersById::get)
                .filter(Objects::nonNull)
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .filter(Objects::nonNull)
                .toList();
        return new WolfbookDtos.JudgeEventView(
                event.getEventId(),
                safeInt(event.getDayNo(), 1),
                safeText(event.getPhase(), PHASE_LOBBY),
                event.getActorPlayerId(),
                actor == null ? null : actor.getSeatNo(),
                targetSeatNos,
                safeText(event.getActionType(), ""),
                readMap(event.getPayloadJson()),
                readMap(event.getResultPayloadJson()),
                safeText(event.getVisibility(), VISIBILITY_PUBLIC),
                event.getCreateTime()
        );
    }

    private boolean canViewEvent(JudgeActionEventEntity event, boolean judgeViewer, String selfPlayerId) {
        return switch (safeText(event.getVisibility(), VISIBILITY_PUBLIC)) {
            case VISIBILITY_PUBLIC, VISIBILITY_SYSTEM -> true;
            case VISIBILITY_JUDGE_ONLY -> judgeViewer;
            case VISIBILITY_ACTOR_ONLY -> StringUtils.hasText(selfPlayerId) && selfPlayerId.equals(event.getActorPlayerId());
            default -> judgeViewer;
        };
    }

    private List<WolfbookDtos.JudgeVoteTallyView> buildVoteTallies(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            List<JudgeActionEventEntity> events
    ) {
        Map<Integer, List<Integer>> tallyMap = new LinkedHashMap<>();
        Map<Integer, String> seatNicknameMap = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .filter(player -> player.getSeatNo() != null)
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getSeatNo, JudgeRoomPlayerEntity::getNickname, (left, right) -> left));

        for (JudgeActionEventEntity event : events) {
            if (!EVENT_VOTE_SUBMIT.equals(event.getActionType())) {
                continue;
            }
            if (!Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1))) {
                continue;
            }
            if (!PHASE_EXILE_VOTE.equals(event.getPhase())) {
                continue;
            }
            Map<String, Object> payload = readMap(event.getPayloadJson());
            Integer targetSeatNo = asPositiveInteger(payload.get("targetSeatNo"));
            Integer actorSeatNo = players.stream()
                    .filter(player -> Objects.equals(player.getPlayerId(), event.getActorPlayerId()))
                    .map(JudgeRoomPlayerEntity::getSeatNo)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (targetSeatNo == null || actorSeatNo == null) {
                continue;
            }
            tallyMap.computeIfAbsent(targetSeatNo, ignored -> new ArrayList<>()).add(actorSeatNo);
        }

        return tallyMap.entrySet().stream()
                .map(entry -> new WolfbookDtos.JudgeVoteTallyView(
                        entry.getKey(),
                        safeText(seatNicknameMap.get(entry.getKey()), entry.getKey() + "号"),
                        entry.getValue().size(),
                        entry.getValue().stream().sorted().toList()
                ))
                .sorted(Comparator.comparing(WolfbookDtos.JudgeVoteTallyView::voteCount).reversed()
                        .thenComparing(WolfbookDtos.JudgeVoteTallyView::targetSeatNo))
                .toList();
    }

    private List<WolfbookDtos.JudgeNightActionView> buildNightActions(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            List<JudgeActionEventEntity> events
    ) {
        if (!PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            return List.of();
        }
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        return events.stream()
                .filter(event -> EVENT_NIGHT_SUBMIT.equals(event.getActionType()))
                .filter(event -> Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .filter(event -> PHASE_NIGHT_ACTION.equals(event.getPhase()))
                .map(event -> toNightActionView(event, playersById))
                .toList();
    }

    /** 历次夜晚的全部夜间行动：对局中只给法官（主持台据此判断女巫的药是否已用过等），结束后全场可见，结果页据此写战绩。 */
    private List<WolfbookDtos.JudgeNightActionView> buildNightHistory(
            List<JudgeRoomPlayerEntity> players,
            List<JudgeActionEventEntity> events
    ) {
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        return events.stream()
                .filter(event -> EVENT_NIGHT_SUBMIT.equals(event.getActionType()))
                .sorted(Comparator.comparing(JudgeActionEventEntity::getCreateTime))
                .map(event -> toNightActionView(event, playersById))
                .toList();
    }

    private WolfbookDtos.JudgeNightActionView toNightActionView(
            JudgeActionEventEntity event,
            Map<String, JudgeRoomPlayerEntity> playersById
    ) {
        JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
        Map<String, Object> payload = readMap(event.getPayloadJson());
        return new WolfbookDtos.JudgeNightActionView(
                event.getActorPlayerId(),
                actor == null ? null : actor.getSeatNo(),
                actor == null ? "未知玩家" : safeText(actor.getNickname(), "未知玩家"),
                asPositiveInteger(payload.get("targetSeatNo")),
                safeText(asString(payload.get("actionType")), "night_action"),
                safeText(asString(payload.get("note")), ""),
                safeInt(event.getDayNo(), 1),
                event.getCreateTime()
        );
    }

    private WolfbookDtos.JudgePendingActionView buildPendingNightAction(
            JudgeRoomEntity room,
            JudgeRoomPlayerEntity self,
            List<JudgeActionEventEntity> events,
            Map<String, JudgeRoomPlayerEntity> playersById
    ) {
        if (!PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) || !Boolean.TRUE.equals(self.getPlaying())) {
            return new WolfbookDtos.JudgePendingActionView(false, null, "");
        }
        JudgeActionEventEntity latest = events.stream()
                .filter(event -> EVENT_NIGHT_SUBMIT.equals(event.getActionType()))
                .filter(event -> Objects.equals(event.getActorPlayerId(), self.getPlayerId()))
                .filter(event -> Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .filter(event -> PHASE_NIGHT_ACTION.equals(event.getPhase()))
                .reduce((left, right) -> right)
                .orElse(null);
        if (latest == null) {
            return new WolfbookDtos.JudgePendingActionView(false, null, "");
        }
        Map<String, Object> payload = readMap(latest.getPayloadJson());
        Integer targetSeatNo = asPositiveInteger(payload.get("targetSeatNo"));
        if (targetSeatNo == null) {
            targetSeatNo = readStringList(latest.getTargetPlayerIdsJson()).stream()
                    .map(playersById::get)
                    .filter(Objects::nonNull)
                    .map(JudgeRoomPlayerEntity::getSeatNo)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return new WolfbookDtos.JudgePendingActionView(
                true,
                targetSeatNo,
                safeText(asString(payload.get("note")), "")
        );
    }

    private void deletePhaseEvent(String roomId, Integer dayNo, String phase, String actorPlayerId, String actionType) {
        judgeActionEventMapper.delete(
                new LambdaQueryWrapper<JudgeActionEventEntity>()
                        .eq(JudgeActionEventEntity::getRoomId, roomId)
                        .eq(JudgeActionEventEntity::getDayNo, dayNo)
                        .eq(JudgeActionEventEntity::getPhase, phase)
                        .eq(JudgeActionEventEntity::getActorPlayerId, actorPlayerId)
                        .eq(JudgeActionEventEntity::getActionType, actionType)
        );
    }

    private void markPlayersDead(List<JudgeRoomPlayerEntity> players, List<Integer> seatNos, Integer dayNo, String phase, LocalDateTime now) {
        if (seatNos == null || seatNos.isEmpty()) {
            return;
        }
        Set<Integer> deadSeatSet = new LinkedHashSet<>(seatNos);
        for (JudgeRoomPlayerEntity player : players) {
            if (player.getSeatNo() == null || !deadSeatSet.contains(player.getSeatNo())) {
                continue;
            }
            if (!Boolean.TRUE.equals(player.getPlaying())) {
                continue;
            }
            player.setAlive(false);
            player.setDeathDay(dayNo);
            player.setDeathPhase(phase);
            player.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(player);
        }
    }

    private List<String> resolveTargetPlayerIds(List<JudgeRoomPlayerEntity> players, List<Integer> seatNos) {
        if (seatNos == null || seatNos.isEmpty()) {
            return List.of();
        }
        Set<Integer> targets = new LinkedHashSet<>(seatNos);
        return players.stream()
                .filter(player -> player.getSeatNo() != null)
                .filter(player -> targets.contains(player.getSeatNo()))
                .map(JudgeRoomPlayerEntity::getPlayerId)
                .toList();
    }

    /** 胜负提示：只有达成了板子的胜利条件才有（对局会随之自动结束），其余时候是空串。 */
    private String buildWinnerSuggestion(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        return WinJudge.judge(winSeats(players), roomRules(room).winCondition()).map(WinJudge.Verdict::reason).orElse("");
    }

    private String buildRoomTip(JudgeRoomEntity room, int joinedCount, int readyCount, boolean judgeViewer) {
        if (ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            if (judgeViewer) {
                if (joinedCount < room.getPlayerCount()) {
                    return "大厅中，还差 " + (room.getPlayerCount() - joinedCount) + " 位玩家";
                }
                if (readyCount < room.getPlayerCount()) {
                    return "玩家已到齐，等待所有人准备后开局";
                }
                return "人数与准备状态已满足，可以开始发牌";
            }
            return "点击准备，等待法官开局";
        }
        if (ROOM_STATUS_FINISHED.equals(room.getRoomStatus())) {
            return "对局已结束，可查看身份与时间线";
        }
        return switch (safeText(room.getCurrentPhase(), PHASE_LOBBY)) {
            case PHASE_NIGHT_ACTION -> judgeViewer ? "收齐夜间提交后，点击结算夜晚" : "当前为夜间阶段，提交你的夜间动作";
            case PHASE_DAY_SPEECH -> judgeViewer ? "发言阶段可广播信息，准备好后开启投票" : "当前为白天发言阶段，等待法官开启放逐投票";
            case PHASE_EXILE_VOTE -> judgeViewer ? "查看票型后结算放逐结果" : "当前为放逐投票阶段，请尽快提交投票";
            default -> "房间运行中";
        };
    }

    private String buildNightAnnouncement(List<Integer> deadSeatNos) {
        if (deadSeatNos == null || deadSeatNos.isEmpty()) {
            return "昨夜平安夜";
        }
        return "昨夜出局：" + deadSeatNos.stream().map(seat -> seat + "号").collect(Collectors.joining("、"));
    }

    private String buildVoteAnnouncement(Integer eliminatedSeatNo, List<WolfbookDtos.JudgeVoteTallyView> tallies) {
        if (eliminatedSeatNo == null) {
            if (tallies == null || tallies.isEmpty()) {
                return "本轮无人投票";
            }
            return "本轮票型未形成唯一放逐结果";
        }
        return eliminatedSeatNo + "号被放逐出局";
    }

    /** 票型（含警长加权），写进放逐结算事件，对局记录按它展示。 */
    private List<Map<String, Object>> summarizeTallies(VoteResolver.Outcome outcome) {
        return outcome.tallies().stream()
                .map(tally -> mapOf("targetSeatNo", tally.targetSeat(), "voteCount", tally.voterSeats().size(),
                        "weight", tally.weight(), "voterSeatNos", tally.voterSeats()))
                .toList();
    }

    private List<Map<String, Object>> summarizeTallies(List<WolfbookDtos.JudgeVoteTallyView> tallies) {
        if (tallies == null || tallies.isEmpty()) {
            return List.of();
        }
        return tallies.stream()
                .map(tally -> mapOf("targetSeatNo", tally.targetSeatNo(), "voteCount", tally.voteCount(), "voterSeatNos", tally.voterSeatNos()))
                .toList();
    }

    private JudgeRoomPlayerEntity findSeatPlayer(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        Integer normalizedSeatNo = normalizeSeatNo(seatNo);
        if (normalizedSeatNo == null) {
            return null;
        }
        return players.stream()
                .filter(player -> Objects.equals(player.getSeatNo(), normalizedSeatNo))
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .findFirst()
                .orElse(null);
    }

    private Comparator<JudgeRoomPlayerEntity> playerComparator() {
        return Comparator
                .comparing((JudgeRoomPlayerEntity player) -> player.getSeatNo() == null ? Integer.MAX_VALUE : player.getSeatNo())
                .thenComparing(JudgeRoomPlayerEntity::getCreateTime);
    }

    private void addEvent(
            String roomId,
            Integer dayNo,
            String phase,
            String actorPlayerId,
            List<String> targetPlayerIds,
            String actionType,
            Map<String, Object> payload,
            Map<String, Object> resultPayload,
            String visibility,
            LocalDateTime createTime
    ) {
        JudgeActionEventEntity event = new JudgeActionEventEntity();
        event.setEventId(makeId("evt"));
        event.setRoomId(roomId);
        event.setDayNo(safeInt(dayNo, 1));
        event.setPhase(safeText(phase, PHASE_LOBBY));
        event.setActorPlayerId(actorPlayerId);
        event.setTargetPlayerIdsJson(writeJson(targetPlayerIds == null ? List.of() : targetPlayerIds));
        event.setActionType(actionType);
        event.setPayloadJson(writeJson(payload == null ? Map.of() : payload));
        event.setResultPayloadJson(writeJson(resultPayload == null ? Map.of() : resultPayload));
        event.setVisibility(visibility);
        event.setCreateTime(createTime == null ? LocalDateTime.now(APP_ZONE) : createTime);
        judgeActionEventMapper.insert(event);
    }

    private String requireAnnouncement(String announcement) {
        if (!StringUtils.hasText(announcement)) {
            throw new ApiException(4000, "请输入公告内容");
        }
        return announcement.trim();
    }

    private String normalizeJudgeMode(String judgeMode, String judgeSupportLevel) {
        String normalized = safeText(judgeMode, JUDGE_MODE_OBSERVER).toLowerCase(Locale.ROOT);
        if (!JUDGE_MODE_OBSERVER.equals(normalized) && !JUDGE_MODE_JOINED.equals(normalized)) {
            throw new ApiException(4000, "无效的法官模式");
        }
        if (JUDGE_MODE_JOINED.equals(normalized) && JudgeSupportLevels.isManualOnly(judgeSupportLevel)) {
            throw new ApiException(4002, "当前板型仅支持主持人模式");
        }
        if (JUDGE_MODE_JOINED.equals(normalized)) {
            throw new ApiException(4002, "系统执法模式将在二期开放");
        }
        return JUDGE_MODE_OBSERVER;
    }

    private String normalizeAction(String action) {
        return safeText(action, "").toLowerCase(Locale.ROOT);
    }

    private Integer normalizeSeatNo(Integer seatNo) {
        return seatNo != null && seatNo > 0 ? seatNo : null;
    }

    private List<Integer> normalizeSeatNos(Collection<Integer> seatNos) {
        if (seatNos == null || seatNos.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer seatNo : seatNos) {
            Integer value = normalizeSeatNo(seatNo);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private String requireOpenid(String authorization) {
        return userService.requireUser(authorization).openid();
    }

    /** 传进来的是纯数字（4~6 位）就当短房号找：优先未结束的房间，其次最近更新的那间。 */
    private String resolveRoomId(String roomIdOrCode) {
        if (roomIdOrCode == null || !roomIdOrCode.trim().matches("\\d{4,6}")) {
            return roomIdOrCode;
        }
        String code = roomIdOrCode.trim();
        List<JudgeRoomEntity> rooms = judgeRoomMapper.selectList(
                new LambdaQueryWrapper<JudgeRoomEntity>()
                        .eq(JudgeRoomEntity::getRoomCode, code)
                        .orderByDesc(JudgeRoomEntity::getUpdateTime)
        );
        return rooms.stream()
                .filter(room -> !ROOM_STATUS_FINISHED.equals(room.getRoomStatus()))
                .findFirst()
                .or(() -> rooms.stream().findFirst())
                .map(JudgeRoomEntity::getRoomId)
                .orElseThrow(() -> new ApiException(4004, "房间不存在"));
    }

    /** 4 位随机短房号，避开所有未结束房间正在用的号；实在撞满了就用 6 位。 */
    private String generateRoomCode() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            String code = String.valueOf(attempt < 30 ? random.nextInt(1000, 10000) : random.nextInt(100000, 1000000));
            boolean taken = judgeRoomMapper.selectCount(
                    new LambdaQueryWrapper<JudgeRoomEntity>()
                            .eq(JudgeRoomEntity::getRoomCode, code)
                            .ne(JudgeRoomEntity::getRoomStatus, ROOM_STATUS_FINISHED)
            ) > 0;
            if (!taken) {
                return code;
            }
        }
        throw new ApiException(5000, "房号分配失败，请稍后再试");
    }

    private String makeId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime(), 16);
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private Integer safeInt(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asPositiveInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0 ? number.intValue() : null;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private LocalDateTime roomUpdateTime(JudgeRoomEntity room) {
        return room.getUpdateTime() == null ? room.getCreateTime() : room.getUpdateTime();
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize judge state", exception);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object key = values[index];
            Object value = values[index + 1];
            if (!(key instanceof String stringKey) || value == null) {
                continue;
            }
            result.put(stringKey, value);
        }
        return result;
    }

}
