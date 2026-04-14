package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
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
import java.util.Set;
import java.util.stream.Collectors;

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
        String judgeSupportLevel = normalizeJudgeSupportLevel(board.judgeSupportLevel());
        String judgeMode = normalizeJudgeMode(request.judgeMode(), judgeSupportLevel);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);

        JudgeRoomEntity room = new JudgeRoomEntity();
        room.setRoomId(makeId("room"));
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
        room.setAutoJudgeEnabled(false);
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
        return buildSnapshot(requireOpenid(authorization), roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView joinRoom(String authorization, String roomId) {
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
        judgeRoomPlayerMapper.insert(player);

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

        room.setRoomStatus(ROOM_STATUS_PLAYING);
        room.setCurrentDay(1);
        room.setCurrentPhase(PHASE_NIGHT_ACTION);
        room.setLatestAnnouncement("身份已发放，进入第一夜");
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
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
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = requireJudgeRoom(roomId, openid);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && !"finish".equals(normalizeAction(request.action()))) {
            throw new ApiException(4002, "当前房间不支持此操作");
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        switch (normalizeAction(request.action())) {
            case "broadcast" -> handleBroadcast(room, request, now);
            case "start_vote" -> handleStartVote(room, request, now);
            case "resolve_night" -> handleResolveNight(room, players, request, now);
            case "resolve_vote" -> handleResolveVote(room, players, request, now);
            case "finish" -> handleFinish(room, players, request, now);
            default -> throw new ApiException(4000, "不支持的推进动作");
        }
        return buildSnapshot(openid, roomId);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView submitNightAction(String authorization, String roomId, WolfbookDtos.JudgeNightActionRequest request) {
        String openid = requireOpenid(authorization);
        JudgeRoomEntity room = getRoom(roomId);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间行动阶段");
        }

        JudgeRoomPlayerEntity player = requireAlivePlayingPlayer(roomId, openid);
        Integer targetSeatNo = normalizeSeatNo(request.targetSeatNo());
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity targetPlayer = targetSeatNo == null ? null : findSeatPlayer(players, targetSeatNo);
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
                        "actionType", safeText(request.actionType(), "night_action"),
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
                        revealAllRoles || (self != null && Objects.equals(self.getPlayerId(), player.getPlayerId()))
                ))
                .toList();
        List<WolfbookDtos.JudgeEventView> timeline = events.stream()
                .filter(event -> canViewEvent(event, judgeViewer, self == null ? null : self.getPlayerId()))
                .sorted(Comparator.comparing(JudgeActionEventEntity::getCreateTime))
                .map(event -> toEventView(event, playersById))
                .toList();

        return new WolfbookDtos.JudgeRoomSnapshotView(
                room.getRoomId(),
                room.getBoardId(),
                room.getBoardName(),
                room.getPlayerCount(),
                room.getJudgeMode(),
                normalizeJudgeSupportLevel(room.getJudgeSupportLevel()),
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
                buildWinnerSuggestion(players),
                self == null ? null : toPlayerView(self, revealAllRoles || Boolean.TRUE.equals(self.getPlaying())),
                playerViews,
                timeline,
                (judgeViewer || roomFinished) ? buildVoteTallies(room, players, events) : List.of(),
                judgeViewer ? buildNightActions(room, players, events) : List.of(),
                self == null ? new WolfbookDtos.JudgePendingActionView(false, null, "") : buildPendingNightAction(room, self, events, playersById),
                room.getCreateTime(),
                roomUpdateTime(room)
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
        judgeRoomMapper.updateById(room);
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

    private void handleResolveNight(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        if (!PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间结算阶段");
        }
        List<Integer> deadSeatNos = normalizeSeatNos(request.deadSeatNos());
        markPlayersDead(players, deadSeatNos, room.getCurrentDay(), PHASE_NIGHT_ACTION, now);
        String winnerCamp = resolveWinnerCamp(request.winnerCamp(), players);
        boolean finished = StringUtils.hasText(winnerCamp);
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : buildNightAnnouncement(deadSeatNos);

        room.setLatestAnnouncement(announcement);
        room.setWinnerCamp(trimToNull(winnerCamp));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        room.setCurrentPhase(finished ? PHASE_RESULT : PHASE_DAY_SPEECH);
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                finished ? PHASE_RESULT : PHASE_DAY_SPEECH,
                null,
                resolveTargetPlayerIds(players, deadSeatNos),
                EVENT_NIGHT_RESOLVED,
                mapOf("deadSeatNos", deadSeatNos),
                mapOf("announcement", announcement, "winnerCamp", winnerCamp),
                VISIBILITY_PUBLIC,
                now
        );
        if (finished) {
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
        Integer eliminatedSeatNo = normalizeSeatNo(request.eliminatedSeatNo());
        if (eliminatedSeatNo == null) {
            eliminatedSeatNo = inferEliminatedSeat(tallies);
        }
        if (eliminatedSeatNo != null) {
            markPlayersDead(players, List.of(eliminatedSeatNo), room.getCurrentDay(), PHASE_EXILE_VOTE, now);
        }

        String winnerCamp = resolveWinnerCamp(request.winnerCamp(), players);
        boolean finished = StringUtils.hasText(winnerCamp);
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : buildVoteAnnouncement(eliminatedSeatNo, tallies);

        int resolvedDay = safeInt(room.getCurrentDay(), 1);
        room.setLatestAnnouncement(announcement);
        room.setWinnerCamp(trimToNull(winnerCamp));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        room.setCurrentDay(finished ? resolvedDay : resolvedDay + 1);
        room.setCurrentPhase(finished ? PHASE_RESULT : PHASE_NIGHT_ACTION);
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);

        addEvent(
                room.getRoomId(),
                resolvedDay,
                finished ? PHASE_RESULT : PHASE_NIGHT_ACTION,
                null,
                resolveTargetPlayerIds(players, eliminatedSeatNo == null ? List.of() : List.of(eliminatedSeatNo)),
                EVENT_VOTE_RESOLVED,
                mapOf("eliminatedSeatNo", eliminatedSeatNo),
                mapOf("announcement", announcement, "winnerCamp", winnerCamp, "tallies", summarizeTallies(tallies)),
                VISIBILITY_PUBLIC,
                now
        );
        if (finished) {
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
    }

    private void handleFinish(
            JudgeRoomEntity room,
            List<JudgeRoomPlayerEntity> players,
            WolfbookDtos.JudgeRoomAdvanceRequest request,
            LocalDateTime now
    ) {
        String winnerCamp = resolveWinnerCamp(request.winnerCamp(), players);
        if (!StringUtils.hasText(winnerCamp)) {
            winnerCamp = "待法官判定";
        }
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : "对局已结束";
        room.setWinnerCamp(winnerCamp);
        room.setLatestAnnouncement(announcement);
        room.setRoomStatus(ROOM_STATUS_FINISHED);
        room.setCurrentPhase(PHASE_RESULT);
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
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
                room.getBoardId(),
                room.getBoardName(),
                room.getPlayerCount(),
                room.getJudgeMode(),
                normalizeJudgeSupportLevel(room.getJudgeSupportLevel()),
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

    private WolfbookDtos.JudgePlayerView toPlayerView(JudgeRoomPlayerEntity player, boolean revealRole) {
        return new WolfbookDtos.JudgePlayerView(
                player.getPlayerId(),
                player.getUserId(),
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
                .map(event -> {
                    JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
                    Map<String, Object> payload = readMap(event.getPayloadJson());
                    return new WolfbookDtos.JudgeNightActionView(
                            event.getActorPlayerId(),
                            actor == null ? null : actor.getSeatNo(),
                            actor == null ? "未知玩家" : safeText(actor.getNickname(), "未知玩家"),
                            asPositiveInteger(payload.get("targetSeatNo")),
                            safeText(asString(payload.get("actionType")), "night_action"),
                            safeText(asString(payload.get("note")), ""),
                            event.getCreateTime()
                    );
                })
                .toList();
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

    private String buildWinnerSuggestion(List<JudgeRoomPlayerEntity> players) {
        String winnerCamp = inferWinnerCamp(players);
        if (StringUtils.hasText(winnerCamp)) {
            return "当前自动判断：" + winnerCamp + "胜利";
        }
        int aliveWolf = countAliveFaction(players, FactionType.WOLF);
        int aliveGood = countAliveFaction(players, FactionType.GOOD);
        int aliveThird = countAliveFaction(players, FactionType.THIRD);
        return "当前存活：狼人 " + aliveWolf + " / 好人 " + aliveGood + (aliveThird > 0 ? " / 第三方 " + aliveThird : "");
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

    private Integer inferEliminatedSeat(List<WolfbookDtos.JudgeVoteTallyView> tallies) {
        if (tallies == null || tallies.isEmpty()) {
            return null;
        }
        WolfbookDtos.JudgeVoteTallyView first = tallies.get(0);
        if (tallies.size() > 1 && Objects.equals(first.voteCount(), tallies.get(1).voteCount())) {
            return null;
        }
        return first.targetSeatNo();
    }

    private List<Map<String, Object>> summarizeTallies(List<WolfbookDtos.JudgeVoteTallyView> tallies) {
        if (tallies == null || tallies.isEmpty()) {
            return List.of();
        }
        return tallies.stream()
                .map(tally -> mapOf("targetSeatNo", tally.targetSeatNo(), "voteCount", tally.voteCount(), "voterSeatNos", tally.voterSeatNos()))
                .toList();
    }

    private String resolveWinnerCamp(String requested, List<JudgeRoomPlayerEntity> players) {
        if (StringUtils.hasText(requested)) {
            return requested.trim();
        }
        return inferWinnerCamp(players);
    }

    private String inferWinnerCamp(List<JudgeRoomPlayerEntity> players) {
        int aliveWolf = countAliveFaction(players, FactionType.WOLF);
        int aliveGood = countAliveFaction(players, FactionType.GOOD);
        int aliveThird = countAliveFaction(players, FactionType.THIRD);
        if (aliveThird > 0 && aliveWolf == 0 && aliveGood == 0) {
            return "第三方";
        }
        if (aliveWolf == 0) {
            return "好人";
        }
        if (aliveGood == 0 && aliveThird == 0) {
            return "狼人";
        }
        if (aliveWolf >= aliveGood && aliveThird == 0) {
            return "狼人";
        }
        return null;
    }

    private int countAliveFaction(List<JudgeRoomPlayerEntity> players, FactionType targetType) {
        return (int) players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .filter(player -> Boolean.TRUE.equals(player.getAlive()))
                .filter(player -> resolveFactionType(player.getFaction()) == targetType)
                .count();
    }

    private FactionType resolveFactionType(String faction) {
        String value = trimToNull(faction);
        if (!StringUtils.hasText(value)) {
            return FactionType.GOOD;
        }
        if (value.contains("狼")) {
            return FactionType.WOLF;
        }
        if (value.contains("第三")) {
            return FactionType.THIRD;
        }
        return FactionType.GOOD;
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
        if (JUDGE_MODE_JOINED.equals(normalized) && "manual_only".equals(judgeSupportLevel)) {
            throw new ApiException(4002, "当前板型仅支持主持人模式");
        }
        if (JUDGE_MODE_JOINED.equals(normalized)) {
            throw new ApiException(4002, "系统执法模式将在二期开放");
        }
        return JUDGE_MODE_OBSERVER;
    }

    private String normalizeJudgeSupportLevel(String judgeSupportLevel) {
        if ("full".equalsIgnoreCase(judgeSupportLevel) || "partial".equalsIgnoreCase(judgeSupportLevel)) {
            return judgeSupportLevel.toLowerCase(Locale.ROOT);
        }
        return "manual_only";
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

    private enum FactionType {
        GOOD,
        WOLF,
        THIRD
    }
}
