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
import com.wolfbook.backend.service.judge.JudgeDayClock;
import com.wolfbook.backend.service.judge.JudgeDayFlow;
import com.wolfbook.backend.service.judge.JudgeNightFlow;
import com.wolfbook.backend.service.judge.Mechanic;
import com.wolfbook.backend.service.judge.NightKind;
import com.wolfbook.backend.service.judge.NightPlan;
import com.wolfbook.backend.service.judge.NightResolver;
import com.wolfbook.backend.service.judge.RoleSkillRegistry;
import com.wolfbook.backend.service.judge.VoteResolver;
import com.wolfbook.backend.service.judge.WinJudge;
import com.wolfbook.backend.support.JudgeSupportLevels;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
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
    private static final String PHASE_SHERIFF_SIGNUP = "sheriff_signup";
    private static final String PHASE_SHERIFF_SPEECH = "sheriff_speech";
    private static final String PHASE_SHERIFF_VOTE = "sheriff_vote";
    private static final String PHASE_SHERIFF_PK = "sheriff_pk";
    private static final String PHASE_VOTE_RESULT = "vote_result";
    private static final String PHASE_VOTE_PK = "vote_pk";
    private static final String PHASE_SKILL_PENDING = "skill_pending";
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
    private static final String EVENT_SHERIFF_SIGNUP_CLOSED = "sheriff_signup_closed";
    private static final String EVENT_SHERIFF_WITHDRAW = "sheriff_withdraw";
    private static final String EVENT_SHERIFF_VOTE_SUBMIT = "sheriff_vote_submit";
    private static final String EVENT_SHERIFF_RESOLVED = "sheriff_resolved";
    private static final String EVENT_SPEECH_ORDER = "speech_order";
    private static final String EVENT_HUNTER_SHOT = "hunter_shot";
    private static final String EVENT_BADGE_PASSED = "badge_passed";
    private static final String EVENT_IDIOT_REVEALED = "idiot_revealed";
    private static final String EVENT_FORCE_OUT = "force_out";
    private static final String EVENT_JUDGE_PAUSED = "judge_paused";
    private static final String EVENT_JUDGE_RESUMED = "judge_resumed";
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
    /** 惰性推进用独立事务：推进失败（另一个请求已经推进了）整体回滚，不影响本次请求。 */
    private final TransactionTemplate tickTransaction;

    public JudgeService(
            UserService userService,
            BoardService boardService,
            RoleMapper roleMapper,
            JudgeRoomMapper judgeRoomMapper,
            JudgeRoomPlayerMapper judgeRoomPlayerMapper,
            JudgeActionEventMapper judgeActionEventMapper,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tickTransaction = new TransactionTemplate(transactionManager);
        this.tickTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tickTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        boolean joined = JUDGE_MODE_JOINED.equals(judgeMode);
        // 无法官房间只能走新流程（系统推进）
        boolean autoJudge = joined || Boolean.TRUE.equals(request.autoJudge());
        JudgeRules boardRules = JudgeRules.fromBoard(boardService.getBoard(board.id()));
        Boolean sheriffRequested = request.sheriffEnabled() != null ? request.sheriffEnabled()
                : request.rules() == null ? null : request.rules().sheriffEnabled();
        JudgeRules rules = applyRoomRules(boardRules, request.rules(), sheriffRequested);
        room.setAutoJudgeEnabled(autoJudge);
        room.setSheriffEnabled(autoJudge && Boolean.TRUE.equals(rules.sheriffDefault()));
        room.setRulesJson(writeJson(rules));
        room.setCanRollback(true);
        room.setWinnerCamp(null);
        room.setLatestAnnouncement("房间已创建，等待玩家入座");
        room.setCreateTime(now);
        room.setUpdateTime(now);
        judgeRoomMapper.insert(room);

        // 有法官：房主是法官，不入座；无法官：房主自己坐 1 号拿牌
        JudgeRoomPlayerEntity judgeObserver = new JudgeRoomPlayerEntity();
        judgeObserver.setPlayerId(makeId("player"));
        judgeObserver.setRoomId(room.getRoomId());
        judgeObserver.setUserId(user.openid());
        judgeObserver.setNickname(user.nickname());
        judgeObserver.setAvatar(user.avatar());
        judgeObserver.setSeatNo(joined ? 1 : null);
        judgeObserver.setRoomOwner(true);
        judgeObserver.setJudgeObserver(!joined);
        judgeObserver.setPlaying(joined);
        judgeObserver.setReady(!joined);
        judgeObserver.setAlive(true);
        judgeObserver.setCreateTime(now);
        judgeObserver.setUpdateTime(now);
        judgeRoomPlayerMapper.insert(judgeObserver);
        if (joined) {
            room.setOwnerPlayerId(judgeObserver.getPlayerId());
            room.setOwnerSeatNo(1);
            judgeRoomMapper.updateById(room);
        }

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
        String openid = requireOpenid(authorization);
        String resolved = resolveRoomId(roomId);
        tick(resolved);
        return buildSnapshot(openid, resolved);
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView startRoom(String authorization, String roomId) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        lockRoom(roomId);
        JudgeRoomEntity room = requireHost(roomId, openid);
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
            player.setMechanic(role.getMechanic());
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView advanceRoom(String authorization, String roomId, WolfbookDtos.JudgeRoomAdvanceRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        lockRoom(roomId);
        if ("next_speaker".equals(normalizeAction(request.action()))) {
            // 发言者自己点「结束发言」也走这里，不要求是法官
            return nextSpeaker(openid, roomId);
        }
        JudgeRoomEntity room = requireAdvancePermission(roomId, openid, normalizeAction(request.action()));
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            throw new ApiException(4002, ROOM_STATUS_FINISHED.equals(room.getRoomStatus()) ? "对局已结束" : "对局尚未开始");
        }
        rejectDictatedResults(room, normalizeAction(request.action()), request);

        if (!Set.of("resume", "force_out").contains(normalizeAction(request.action()))) {
            requireNotPaused(room);
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
            case "close_sheriff_signup" -> handleCloseSheriffSignup(room, players, request, now);
            case "start_sheriff_vote" -> handleStartSheriffVote(room, players, now);
            case "resolve_sheriff_vote" -> handleResolveSheriffVote(room, players, now);
            case "start_speech" -> handleStartSpeech(room, players, request, now);
            case "continue" -> handleContinue(room, now);
            case "force_out" -> handleForceOut(room, players, request, now);
            case "pause" -> handlePause(room, now);
            case "resume" -> handleResume(room, now);
            default -> throw new ApiException(4000, "不支持的推进动作");
        }
        return buildSnapshot(openid, roomId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitNightAction(String authorization, String roomId, WolfbookDtos.JudgeNightActionRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        tick(roomId);
        lockRoom(roomId);
        JudgeRoomEntity room = getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间行动阶段");
        }

        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity player;
        if (request.actorSeatNo() != null) {
            // 线下面杀：玩家闭眼指人，由法官替这个座位记下夜间行动（只有旧流程房间；新流程玩家自己操作）
            requireJudgeRoom(roomId, openid);
            if (Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
                throw new ApiException(4003, "新流程里请玩家自己操作");
            }
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
        touchRoom(roomId, now);
        return buildSnapshot(openid, roomId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitVote(String authorization, String roomId, WolfbookDtos.JudgeVoteRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        tick(roomId);
        lockRoom(roomId);
        JudgeRoomEntity room = getRoom(roomId);
        requireNotPaused(room);
        boolean pk = PHASE_VOTE_PK.equals(room.getCurrentPhase());
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || (!PHASE_EXILE_VOTE.equals(room.getCurrentPhase()) && !pk)) {
            throw new ApiException(4002, "当前不是投票阶段");
        }

        JudgeRoomPlayerEntity player = requireAlivePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        if (Boolean.FALSE.equals(player.getCanVote())) {
            throw new ApiException(4003, "你已经没有投票权");
        }
        if (pk && pkSeats(room).contains(player.getSeatNo())) {
            throw new ApiException(4003, "平票的玩家这一轮不能投票");
        }
        // targetSeatNo 为空是弃票
        JudgeRoomPlayerEntity targetPlayer = request.targetSeatNo() == null ? null : requireAliveSeatPlayer(players, request.targetSeatNo());
        if (pk && targetPlayer != null && !pkSeats(room).contains(targetPlayer.getSeatNo())) {
            throw new ApiException(4000, "这一轮只能投给平票的玩家");
        }
        deletePhaseEvent(roomId, room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), EVENT_VOTE_SUBMIT);

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                targetPlayer == null ? List.of() : List.of(targetPlayer.getPlayerId()),
                EVENT_VOTE_SUBMIT,
                mapOf("targetSeatNo", targetPlayer == null ? null : targetPlayer.getSeatNo()),
                Map.of(),
                VISIBILITY_JUDGE_ONLY,
                now
        );
        touchRoom(roomId, now);
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
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) && openid.equals(room.getOwnerUserId())
                        && joinedCount == room.getPlayerCount() && readyCount == room.getPlayerCount(),
                ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && openid.equals(room.getOwnerUserId()),
                joinedViewer && Boolean.TRUE.equals(self.getAlive()) && PHASE_EXILE_VOTE.equals(room.getCurrentPhase()) && ROOM_STATUS_PLAYING.equals(room.getRoomStatus()),
                joinedViewer && Boolean.TRUE.equals(self.getAlive()) && PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) && ROOM_STATUS_PLAYING.equals(room.getRoomStatus()),
                joinedCount,
                readyCount,
                buildRoomTip(room, joinedCount, readyCount, judgeViewer),
                ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) ? "" : buildWinnerSuggestion(room, players),
                self == null ? null : toPlayerView(self, revealAllRoles || Boolean.TRUE.equals(self.getPlaying()), true),
                playerViews,
                timeline,
                (judgeViewer || roomFinished || PHASE_VOTE_RESULT.equals(room.getCurrentPhase()))
                        ? buildVoteTallies(room, players, events, votePhase(room)) : List.of(),
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
                        : null,
                players.stream().filter(player -> Boolean.TRUE.equals(player.getSheriff())).map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null),
                Set.of(PHASE_SHERIFF_SPEECH, PHASE_SHERIFF_VOTE, PHASE_SHERIFF_PK).contains(safeText(room.getCurrentPhase(), "")) ? onStage(players) : List.of(),
                speechView(room),
                Set.of(PHASE_VOTE_RESULT, PHASE_VOTE_PK, PHASE_SHERIFF_PK).contains(safeText(room.getCurrentPhase(), "")) ? pkSeats(room) : List.of(),
                buildRounds(events),
                pendingView(room, players, self, judgeViewer),
                isPaused(room),
                room.getStepDeadline(),
                buildNightInfo(room, players, self, judgeViewer)
        );
    }

    /** 查看者本人的夜间私密信息（只在新流程房间）。法官有 nightActions 全量，不给。 */
    private WolfbookDtos.JudgeNightInfoView buildNightInfo(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                          JudgeRoomPlayerEntity self, boolean judgeViewer) {
        if (judgeViewer || self == null || !Boolean.TRUE.equals(self.getPlaying()) || self.getSeatNo() == null
                || !Boolean.TRUE.equals(room.getAutoJudgeEnabled()) || ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            return null;
        }
        Mechanic mechanic = mechanicOf(self).orElse(null);
        boolean wolf = RoleSkillRegistry.isWolfTeam(mechanic, self.getRoleName(), self.getFaction());
        boolean alive = Boolean.TRUE.equals(self.getAlive());
        List<Integer> teammates = wolf ? players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && player.getSeatNo() != null
                        && !player.getSeatNo().equals(self.getSeatNo()) && nightKindOf(player) == NightKind.WOLF)
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .sorted()
                .toList() : List.of();

        List<WolfbookDtos.JudgeWolfPickView> picks = List.of();
        Integer knifeSeat = null;
        Integer checkedSeat = null;
        String checkResult = null;
        boolean night = ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())
                && room.getNightStep() != null;
        if (night && alive) {
            List<NightPlan.NightStep> steps = nightSteps(room, safeInt(room.getCurrentDay(), 1));
            NightPlan.NightStep step = room.getNightStep() < steps.size() ? steps.get(room.getNightStep()) : null;
            List<NightResolver.Submission> submissions = tonightSubmissions(room, players);
            if (step != null && step.kind() == NightKind.WOLF && wolf) {
                Map<Integer, Integer> chosen = new HashMap<>();
                submissions.stream()
                        .filter(submission -> submission.kind() == NightKind.WOLF && NightResolver.KILL.equals(submission.action()))
                        .forEach(submission -> chosen.put(submission.actorSeat(), submission.targetSeat()));
                picks = players.stream()
                        .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive())
                                && player.getSeatNo() != null && nightKindOf(player) == NightKind.WOLF)
                        .sorted(Comparator.comparing(JudgeRoomPlayerEntity::getSeatNo))
                        .map(player -> new WolfbookDtos.JudgeWolfPickView(player.getSeatNo(), chosen.get(player.getSeatNo())))
                        .toList();
            }
            if (step != null && step.kind() == NightKind.WITCH && mechanic == Mechanic.WITCH) {
                Map<String, Object> phaseData = readMap(room.getPhaseData());
                knifeSeat = Boolean.TRUE.equals(phaseData.get("knifeDecided")) ? asPositiveInteger(phaseData.get("knifeSeat")) : null;
            }
            if (mechanic == Mechanic.SEER) {
                NightResolver.Submission check = submissions.stream()
                        .filter(submission -> submission.actorSeat() == self.getSeatNo() && NightResolver.CHECK.equals(submission.action()))
                        .findFirst().orElse(null);
                if (check != null && check.targetSeat() != null) {
                    JudgeRoomPlayerEntity target = findSeatPlayer(players, check.targetSeat());
                    checkedSeat = check.targetSeat();
                    checkResult = target != null && "狼人".equals(target.getFaction()) ? "狼人" : "好人";
                }
            }
        }
        if (teammates.isEmpty() && picks.isEmpty() && knifeSeat == null && checkedSeat == null) {
            return null;
        }
        return new WolfbookDtos.JudgeNightInfoView(teammates, picks, knifeSeat, checkedSeat, checkResult);
    }

    private WolfbookDtos.JudgeSpeechView speechView(JudgeRoomEntity room) {
        String phase = safeText(room.getCurrentPhase(), "");
        if (!PHASE_DAY_SPEECH.equals(phase) && !PHASE_SHERIFF_SPEECH.equals(phase)) {
            return null;
        }
        Object speech = readMap(room.getPhaseData()).get("speech");
        if (!(speech instanceof Map<?, ?> map)) {
            return null;
        }
        return new WolfbookDtos.JudgeSpeechView(asIntegerList(map.get("order")), asPositiveIntegerOrZero(map.get("index")));
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
        judgeRoomMapper.update(null, new LambdaUpdateWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, room.getRoomId())
                .set(JudgeRoomEntity::getLatestAnnouncement, announcement)
                .set(JudgeRoomEntity::getUpdateTime, now));
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
        setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
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
        if (Boolean.TRUE.equals(room.getAutoJudgeEnabled()) && dayNo == 1 && Boolean.TRUE.equals(room.getSheriffEnabled())) {
            // 第一天先竞选警长，昨夜死讯等警长选完再公布（死者也能上警）
            room.setLatestAnnouncement(StringUtils.hasText(requestedAnnouncement) ? requestedAnnouncement.trim() : "天亮了，现在开始警长竞选，要上警的玩家请选择");
            room.setCurrentPhase(PHASE_SHERIFF_SIGNUP);
            room.setNightStep(null);
            room.setStepDeadline(now.plusSeconds(JudgeDayClock.SHERIFF_SIGNUP_SECONDS));
            room.setPhaseData(writeJson(mapOf("pendingDeaths", deadSeatNos)));
            room.setUpdateTime(now);
            updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, expectedNightStep);
            return;
        }
        markPlayersDead(players, deadSeatNos, dayNo, PHASE_NIGHT_ACTION, now);

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
        room.setPhaseData(verdict.map(value -> writeJson(mapOf("verdictReason", value.reason())))
                .orElse(writeJson(mapOf("nightDeaths", deadSeatNos))));
        if (!finished && Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            startPending(room, players, deadSeatNos, now);
        }
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
        String votePhase = safeText(room.getCurrentPhase(), "");
        boolean pkRound = PHASE_VOTE_PK.equals(votePhase);
        if (!PHASE_EXILE_VOTE.equals(votePhase) && !pkRound) {
            throw new ApiException(4002, "当前不是投票结算阶段");
        }
        List<JudgeActionEventEntity> voteEvents = loadEvents(room.getRoomId()).stream()
                .filter(event -> Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .filter(event -> votePhase.equals(event.getPhase()))
                .filter(event -> EVENT_VOTE_SUBMIT.equals(event.getActionType()))
                .toList();
        List<WolfbookDtos.JudgeVoteTallyView> tallies = buildVoteTallies(room, players, voteEvents, votePhase);
        VoteResolver.Outcome voteOutcome = VoteResolver.resolve(ballotsOf(players, voteEvents),
                pkRound ? new LinkedHashSet<>(pkSeats(room)) : aliveSeats(players));
        // 弃票记录：新流程房间里有投票权却没投的人也算弃票（超时 = 弃票），对局记录里能看到
        Set<Integer> abstainSet = new TreeSet<>(voteOutcome.abstainSeats());
        if (Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            Set<Integer> voted = votedSeats(room, players, votePhase);
            eligibleVoters(room, players, votePhase).stream().filter(seat -> !voted.contains(seat)).forEach(abstainSet::add);
        }
        List<Integer> abstainSeats = List.copyOf(abstainSet);
        Integer eliminatedSeatNo = normalizeSeatNo(request.eliminatedSeatNo());
        if (eliminatedSeatNo != null) {
            requireAliveSeat(players, eliminatedSeatNo);
        } else {
            eliminatedSeatNo = voteOutcome.eliminatedSeat();
        }
        boolean idiotRevealed = Boolean.TRUE.equals(room.getAutoJudgeEnabled()) && revealIdiot(room, players, eliminatedSeatNo, now);
        if (eliminatedSeatNo != null && !idiotRevealed) {
            markPlayersDead(players, List.of(eliminatedSeatNo), room.getCurrentDay(), PHASE_EXILE_VOTE, now);
        }

        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, request.winnerCamp());
        boolean finished = verdict.isPresent();
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : idiotRevealed
                ? eliminatedSeatNo + "号是白痴，翻牌免死，之后不能投票"
                : buildVoteAnnouncement(eliminatedSeatNo, tallies) + verdict.map(value -> "。" + value.reason()).orElse("");

        int resolvedDay = safeInt(room.getCurrentDay(), 1);
        boolean autoJudge = Boolean.TRUE.equals(room.getAutoJudgeEnabled());
        // 新阶段机：第一轮平票进 PK；PK 再平无人出局
        List<Integer> nextPk = autoJudge && !pkRound && eliminatedSeatNo == null ? voteOutcome.tiedSeats() : List.of();
        room.setLatestAnnouncement(nextPk.isEmpty() ? announcement : seatsText(nextPk) + "平票，进入 PK");
        room.setWinnerCamp(verdict.map(WinJudge.Verdict::camp).orElse(null));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        if (finished) {
            room.setCurrentPhase(PHASE_RESULT);
            room.setPhaseData(writeJson(mapOf("verdictReason", verdict.get().reason())));
        } else if (autoJudge) {
            room.setStepDeadline(null);
            room.setCurrentPhase(PHASE_VOTE_RESULT);
            room.setPhaseData(writeJson(mapOf("pkSeats", nextPk, "resolvedFrom", votePhase)));
            if (eliminatedSeatNo != null && !idiotRevealed) {
                startPending(room, players, List.of(eliminatedSeatNo), now);
            }
        } else {
            room.setCurrentDay(resolvedDay + 1);
            room.setCurrentPhase(PHASE_NIGHT_ACTION);
            openNight(room, resolvedDay + 1, now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, votePhase);

        addEvent(
                room.getRoomId(),
                resolvedDay,
                room.getCurrentPhase(),
                null,
                resolveTargetPlayerIds(players, eliminatedSeatNo == null ? List.of() : List.of(eliminatedSeatNo)),
                EVENT_VOTE_RESOLVED,
                mapOf("eliminatedSeatNo", eliminatedSeatNo, "round", pkRound ? "pk" : "exile"),
                mapOf("announcement", announcement, "winnerCamp", room.getWinnerCamp(),
                        "tallies", summarizeTallies(voteOutcome), "abstainSeatNos", abstainSeats, "pkSeatNos", nextPk),
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
        return NightPlan.build(board, loadBoardRoleMap(room.getBoardId()), dayNo, roomRules(room));
    }

    /**
     * 把房间规则（节奏项）合并到底稿上：为空的项保持原值，超范围 4000。上警开关记在 sheriffDefault 里（快照里能看到本局开没开）。
     * 胜负、女巫自救、一晚一瓶是玩法规则，永远取底稿（板子）的。
     */
    private JudgeRules applyRoomRules(JudgeRules base, WolfbookDtos.JudgeRoomRulesInput input, Boolean sheriffEnabled) {
        JudgeRules rules = base.withDefaults();
        if (input != null) {
            requireRange(input.speechSeconds(), 30, 300);
            requireRange(input.sheriffSpeechSeconds(), 30, 300);
            requireRange(input.wolfSeconds(), 30, 120);
            requireRange(input.roleSeconds(), 15, 60);
        }
        return new JudgeRules(
                rules.winCondition(),
                sheriffEnabled != null ? sheriffEnabled : rules.sheriffDefault(),
                rules.witchSelfSave(),
                rules.witchOnePotionPerNight(),
                input != null && input.speechSeconds() != null ? input.speechSeconds() : rules.speechSeconds(),
                input != null && input.sheriffSpeechSeconds() != null ? input.sheriffSpeechSeconds() : rules.sheriffSpeechSeconds(),
                input != null && input.wolfSeconds() != null ? input.wolfSeconds() : rules.wolfSeconds(),
                input != null && input.roleSeconds() != null ? input.roleSeconds() : rules.roleSeconds()
        );
    }

    private void requireRange(Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new ApiException(4000, "房间规则超出范围");
        }
    }

    /** 大厅里改房间规则（节奏项）；开局后锁定。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView updateRoomRules(String authorization, String roomId, WolfbookDtos.JudgeRoomRulesInput request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        lockRoom(roomId);
        JudgeRoomEntity room = requireHost(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "开局后规则已锁定");
        }
        JudgeRules rules = applyRoomRules(roomRules(room), request, request == null ? null : request.sheriffEnabled());
        room.setRulesJson(writeJson(rules));
        room.setSheriffEnabled(Boolean.TRUE.equals(room.getAutoJudgeEnabled()) && Boolean.TRUE.equals(rules.sheriffDefault()));
        room.setUpdateTime(LocalDateTime.now(APP_ZONE));
        updateRoomGuarded(room, ROOM_STATUS_LOBBY, null);
        return buildSnapshot(openid, roomId);
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
        Mechanic mechanic = mechanicOf(player).orElse(null);
        if (RoleSkillRegistry.isWolfTeam(mechanic, player.getRoleName(), player.getFaction())) {
            return NightKind.WOLF;
        }
        return mechanic == null ? NightKind.GENERIC : mechanic.nightKind();
    }

    /** 座位上的技能机制：发身份时拷进来的；升级前开局的座位没有，按角色名兜底。 */
    private Optional<Mechanic> mechanicOf(JudgeRoomPlayerEntity player) {
        return Mechanic.fromCode(player.getMechanic()).or(() -> RoleSkillRegistry.legacyMechanic(player.getRoleName()));
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
                            mechanicOf(player).orElse(null),
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
                    : mechanicOf(actor)
                            .map(mechanic -> mechanic.actions().contains(action))
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

    // ---------- 新阶段机：上警、公布死讯、发言、票型公布、PK（只对 auto_judge_enabled 的房间）----------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitSheriff(String authorization, String roomId, WolfbookDtos.JudgeSheriffRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        tick(roomId);
        lockRoom(roomId);
        JudgeRoomEntity room = getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局不在进行中");
        }
        JudgeRoomPlayerEntity self = requireAlivePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        String phase = safeText(room.getCurrentPhase(), "");
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        if (request.candidate() != null) {
            if (!PHASE_SHERIFF_SIGNUP.equals(phase)) {
                throw new ApiException(4002, "现在不是上警报名");
            }
            self.setSheriffCandidate(request.candidate());
            self.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(self);
        } else if (Boolean.TRUE.equals(request.withdraw())) {
            if (!PHASE_SHERIFF_SPEECH.equals(phase)) {
                throw new ApiException(4002, "现在不能退水");
            }
            if (!Boolean.TRUE.equals(self.getSheriffCandidate()) || Boolean.TRUE.equals(self.getSheriffWithdrew())) {
                throw new ApiException(4002, "你不在警上");
            }
            self.setSheriffWithdrew(true);
            self.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(self);
            addEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), List.of(), EVENT_SHERIFF_WITHDRAW,
                    mapOf("seatNo", self.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
        } else if (request.targetSeatNo() != null) {
            if (!PHASE_SHERIFF_VOTE.equals(phase) && !PHASE_SHERIFF_PK.equals(phase)) {
                throw new ApiException(4002, "现在不是警长投票");
            }
            if (Boolean.TRUE.equals(self.getSheriffCandidate())) {
                throw new ApiException(4003, "上过警的玩家不能投警长");
            }
            Set<Integer> allowed = PHASE_SHERIFF_PK.equals(phase) ? new LinkedHashSet<>(pkSeats(room)) : new LinkedHashSet<>(onStage(players));
            if (!allowed.contains(request.targetSeatNo())) {
                throw new ApiException(4000, "只能投给警上的玩家");
            }
            deletePhaseEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), EVENT_SHERIFF_VOTE_SUBMIT);
            addEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), List.of(), EVENT_SHERIFF_VOTE_SUBMIT,
                    mapOf("targetSeatNo", request.targetSeatNo()), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        } else if (StringUtils.hasText(request.speechOrder())) {
            if (!PHASE_DAY_ANNOUNCE.equals(phase)) {
                throw new ApiException(4002, "现在不能选发言顺序");
            }
            if (!Boolean.TRUE.equals(self.getSheriff())) {
                throw new ApiException(4003, "只有警长能决定发言顺序");
            }
            String choice = request.speechOrder().trim();
            if (!Set.of(JudgeDayFlow.LEFT, JudgeDayFlow.RIGHT, JudgeDayFlow.DEAD_LEFT, JudgeDayFlow.DEAD_RIGHT).contains(choice)) {
                throw new ApiException(4000, "发言顺序只能是警左、警右、死左、死右");
            }
            Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
            phaseData.put("sheriffOrder", choice);
            room.setPhaseData(writeJson(phaseData));
            room.setUpdateTime(now);
            updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_ANNOUNCE);
            return buildSnapshot(openid, roomId);
        } else {
            throw new ApiException(4000, "请求格式错误");
        }
        touchRoom(roomId, now);
        return buildSnapshot(openid, roomId);
    }

    /** 报名截止：没人上或全员上 → 警徽流失；只有一人 → 直接当选；否则进警上发言。法官可以用 sheriffCandidates 代勾。 */
    private void handleCloseSheriffSignup(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                          WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        requirePhase(room, PHASE_SHERIFF_SIGNUP);
        if (request.sheriffCandidates() != null) {
            throw new ApiException(4000, "上警由玩家自己选");
        }
        List<Integer> candidates = onStage(players);
        int alive = aliveSeats(players).size();
        addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_SHERIFF_SIGNUP, null, List.of(), EVENT_SHERIFF_SIGNUP_CLOSED,
                mapOf("candidates", candidates), Map.of(), VISIBILITY_PUBLIC, now);
        if (candidates.isEmpty() || candidates.size() == alive) {
            finishSheriff(room, players, candidates, null, List.of(), now);
        } else if (candidates.size() == 1) {
            finishSheriff(room, players, candidates, candidates.get(0), List.of(), now);
        } else {
            Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
            phaseData.put("speech", mapOf("order", JudgeDayFlow.speechOrder(candidates, room.getPlayerCount(), null, null, List.of(), random), "index", 0));
            room.setPhaseData(writeJson(phaseData));
            room.setCurrentPhase(PHASE_SHERIFF_SPEECH);
            room.setLatestAnnouncement("上警玩家有：" + seatsText(candidates));
            setPhaseClock(room, roomRules(room).sheriffSpeechSeconds(), now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SHERIFF_SIGNUP);
    }

    private void handleStartSheriffVote(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, LocalDateTime now) {
        requirePhase(room, PHASE_SHERIFF_SPEECH);
        List<Integer> remaining = onStage(players);
        if (remaining.isEmpty()) {
            finishSheriff(room, players, remaining, null, List.of(), now);
        } else if (remaining.size() == 1) {
            finishSheriff(room, players, remaining, remaining.get(0), List.of(), now);
        } else {
            room.setCurrentPhase(PHASE_SHERIFF_VOTE);
            room.setLatestAnnouncement("请警下的玩家投票选警长");
            setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SHERIFF_SPEECH);
    }

    /** 警长投票结算：唯一最高票当选；第一轮平票进 PK；PK 再平或没人投 → 警徽流失。 */
    private void handleResolveSheriffVote(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, LocalDateTime now) {
        String phase = safeText(room.getCurrentPhase(), "");
        if (!PHASE_SHERIFF_VOTE.equals(phase) && !PHASE_SHERIFF_PK.equals(phase)) {
            throw new ApiException(4002, "现在不是警长投票");
        }
        Map<Integer, JudgeRoomPlayerEntity> bySeat = seatMap(players);
        List<VoteResolver.Ballot> ballots = loadEvents(room.getRoomId()).stream()
                .filter(event -> EVENT_SHERIFF_VOTE_SUBMIT.equals(event.getActionType()) && phase.equals(event.getPhase())
                        && Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .map(event -> {
                    JudgeRoomPlayerEntity voter = players.stream().filter(player -> player.getPlayerId().equals(event.getActorPlayerId())).findFirst().orElse(null);
                    return voter == null || voter.getSeatNo() == null ? null
                            : new VoteResolver.Ballot(voter.getSeatNo(), asPositiveInteger(readMap(event.getPayloadJson()).get("targetSeatNo")), false);
                })
                .filter(Objects::nonNull)
                .toList();
        List<Integer> allowed = PHASE_SHERIFF_PK.equals(phase) ? pkSeats(room) : onStage(players);
        VoteResolver.Outcome outcome = VoteResolver.resolve(ballots, new LinkedHashSet<>(allowed));
        if (outcome.eliminatedSeat() != null && bySeat.containsKey(outcome.eliminatedSeat())) {
            finishSheriff(room, players, onStage(players), outcome.eliminatedSeat(), outcome.tallies(), now);
        } else if (PHASE_SHERIFF_VOTE.equals(phase) && !outcome.tiedSeats().isEmpty()) {
            Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
            phaseData.put("pkSeats", outcome.tiedSeats());
            room.setPhaseData(writeJson(phaseData));
            room.setCurrentPhase(PHASE_SHERIFF_PK);
            room.setLatestAnnouncement(seatsText(outcome.tiedSeats()) + "平票，PK 发言后警下再投一次");
            setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
            addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_SHERIFF_PK, null, List.of(), EVENT_SHERIFF_RESOLVED,
                    mapOf("candidates", onStage(players)),
                    mapOf("tallies", summarizeTallies(outcome), "pkSeatNos", outcome.tiedSeats()), VISIBILITY_PUBLIC, now);
        } else {
            finishSheriff(room, players, onStage(players), null, outcome.tallies(), now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
    }

    /** 警长竞选结束（当选或流失），接着公布昨夜死讯。调用方负责带条件写回房间。 */
    private void finishSheriff(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> candidates, Integer electedSeat,
                               List<VoteResolver.Tally> tallies, LocalDateTime now) {
        room.setStepDeadline(null);
        if (electedSeat != null) {
            JudgeRoomPlayerEntity sheriff = findSeatPlayer(players, electedSeat);
            sheriff.setSheriff(true);
            sheriff.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(sheriff);
        }
        addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_DAY_ANNOUNCE, null, List.of(), EVENT_SHERIFF_RESOLVED,
                mapOf("candidates", candidates),
                mapOf("tallies", summarizeTallies(new VoteResolver.Outcome(electedSeat, List.of(), tallies, List.of())),
                        "electedSeatNo", electedSeat, "lost", electedSeat == null),
                VISIBILITY_PUBLIC, now);
        enterDayAnnounce(room, players, (electedSeat == null ? "警徽流失" : electedSeat + "号当选警长") + "。", now);
    }

    /** 进入公布死讯：把上警前压着的昨夜出局写进去、判一次胜负。调用方负责带条件写回房间。 */
    private void enterDayAnnounce(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String prefix, LocalDateTime now) {
        Map<String, Object> phaseData = readMap(room.getPhaseData());
        List<Integer> deaths = normalizeSeatNos(asIntegerList(phaseData.get("pendingDeaths")));
        int dayNo = safeInt(room.getCurrentDay(), 1);
        markPlayersDead(players, deaths, dayNo, PHASE_NIGHT_ACTION, now);
        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
        String announcement = prefix + buildNightAnnouncement(deaths) + verdict.map(value -> "。" + value.reason()).orElse("");
        String nextPhase = verdict.isPresent() ? PHASE_RESULT : PHASE_DAY_ANNOUNCE;
        addEvent(room.getRoomId(), dayNo, nextPhase, null, resolveTargetPlayerIds(players, deaths), EVENT_NIGHT_RESOLVED,
                mapOf("deadSeatNos", deaths), mapOf("announcement", announcement), VISIBILITY_PUBLIC, now);
        room.setLatestAnnouncement(announcement);
        room.setCurrentPhase(nextPhase);
        if (verdict.isPresent()) {
            room.setRoomStatus(ROOM_STATUS_FINISHED);
            room.setWinnerCamp(verdict.get().camp());
            room.setPhaseData(writeJson(mapOf("verdictReason", verdict.get().reason())));
            addFinishedEvent(room, verdict.get(), null, announcement, now);
        } else {
            room.setPhaseData(writeJson(mapOf("nightDeaths", deaths)));
            startPending(room, players, deaths, now);
        }
    }

    /** 公布死讯后开始发言：法官给的起点 / 警长选的顺序 / 默认（有死者死左，没有随机）。 */
    private void handleStartSpeech(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                   WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        requirePhase(room, PHASE_DAY_ANNOUNCE);
        Map<String, Object> phaseData = readMap(room.getPhaseData());
        List<Integer> alive = new ArrayList<>(aliveSeats(players));
        alive.sort(Integer::compareTo);
        List<Integer> nightDeaths = asIntegerList(phaseData.get("nightDeaths"));
        Integer sheriffSeat = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getSheriff()) && Boolean.TRUE.equals(player.getAlive()))
                .map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null);
        String choice = trimToNull(request.speechDirection());
        if (choice == null) {
            choice = trimToNull(asString(phaseData.get("sheriffOrder")));
        }
        List<Integer> order;
        Integer startSeat = normalizeSeatNo(request.speechStartSeat());
        if (startSeat != null && alive.contains(startSeat)) {
            order = JudgeDayFlow.speechOrderFrom(alive, room.getPlayerCount(), startSeat, !JudgeDayFlow.RIGHT.equals(choice));
        } else {
            order = JudgeDayFlow.speechOrder(alive, room.getPlayerCount(), choice, sheriffSeat, nightDeaths, random);
        }
        room.setPhaseData(writeJson(mapOf("speech", mapOf("order", order, "index", 0))));
        room.setCurrentPhase(PHASE_DAY_SPEECH);
        setPhaseClock(room, order.isEmpty() ? null : speechSeconds(room, PHASE_DAY_SPEECH, order.get(0), sheriffSeat), now);
        room.setLatestAnnouncement(order.isEmpty() ? "开始发言" : "请" + order.get(0) + "号开始发言");
        room.setUpdateTime(now);
        addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_DAY_SPEECH, null, List.of(), EVENT_SPEECH_ORDER,
                mapOf("order", order, "startSeat", order.isEmpty() ? null : order.get(0), "direction", choice), Map.of(), VISIBILITY_PUBLIC, now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_ANNOUNCE);
    }

    /** 下一位发言：当前发言者自己点「结束发言」，或者房主跳过。 */
    private WolfbookDtos.JudgeRoomSnapshotView nextSpeaker(String openid, String roomId) {
        JudgeRoomEntity room = getRoom(roomId);
        requireNotPaused(room);
        String phase = safeText(room.getCurrentPhase(), "");
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || (!PHASE_DAY_SPEECH.equals(phase) && !PHASE_SHERIFF_SPEECH.equals(phase))) {
            throw new ApiException(4002, "现在不是发言阶段");
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        Map<String, Object> speech = phaseData.get("speech") instanceof Map<?, ?> map ? new LinkedHashMap<>(castMap(map)) : new LinkedHashMap<>();
        List<Integer> order = asIntegerList(speech.get("order"));
        int index = safeInt(asPositiveIntegerOrZero(speech.get("index")), 0);
        JudgeRoomPlayerEntity self = findPlayer(roomId, openid);
        boolean host = openid.equals(room.getOwnerUserId());
        boolean speaker = self != null && index < order.size() && Objects.equals(self.getSeatNo(), order.get(index));
        if (!host && !speaker) {
            throw new ApiException(4003, "只有正在发言的人或房主能切到下一位");
        }
        int next = Math.min(index + 1, order.size());
        speech.put("index", next);
        phaseData.put("speech", speech);
        room.setPhaseData(writeJson(phaseData));
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        Integer sheriffSeat = loadPlayers(roomId).stream()
                .filter(player -> Boolean.TRUE.equals(player.getSheriff()) && Boolean.TRUE.equals(player.getAlive()))
                .map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null);
        setPhaseClock(room, next < order.size() ? speechSeconds(room, phase, order.get(next), sheriffSeat) : null, now);
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
        return buildSnapshot(openid, roomId);
    }

    /** 票型公布之后：有平票进 PK，否则进下一夜。 */
    private void handleContinue(JudgeRoomEntity room, LocalDateTime now) {
        requirePhase(room, PHASE_VOTE_RESULT);
        List<Integer> pk = pkSeats(room);
        if (!pk.isEmpty()) {
            room.setCurrentPhase(PHASE_VOTE_PK);
            room.setLatestAnnouncement(seatsText(pk) + "PK，其余玩家只能投给他们");
            setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
        } else {
            int nextDay = safeInt(room.getCurrentDay(), 1) + 1;
            room.setCurrentDay(nextDay);
            room.setCurrentPhase(PHASE_NIGHT_ACTION);
            room.setLatestAnnouncement("天黑请闭眼");
            openNight(room, nextDay, now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_VOTE_RESULT);
    }

    private void requirePhase(JudgeRoomEntity room, String phase) {
        if (!phase.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "房间不在这个阶段");
        }
    }

    /** 还在警上的人：报了名、没退水、活着。 */
    private List<Integer> onStage(List<JudgeRoomPlayerEntity> players) {
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()) && player.getSeatNo() != null)
                .filter(player -> Boolean.TRUE.equals(player.getSheriffCandidate()) && !Boolean.TRUE.equals(player.getSheriffWithdrew()))
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .sorted()
                .toList();
    }

    private List<Integer> pkSeats(JudgeRoomEntity room) {
        return asIntegerList(readMap(room.getPhaseData()).get("pkSeats"));
    }

    private Map<Integer, JudgeRoomPlayerEntity> seatMap(List<JudgeRoomPlayerEntity> players) {
        Map<Integer, JudgeRoomPlayerEntity> map = new LinkedHashMap<>();
        players.stream().filter(player -> player.getSeatNo() != null).forEach(player -> map.put(player.getSeatNo(), player));
        return map;
    }

    private String seatsText(List<Integer> seats) {
        return seats.stream().map(seat -> seat + "号").collect(Collectors.joining("、"));
    }

    private List<Integer> asIntegerList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::asPositiveInteger).filter(Objects::nonNull).toList();
    }

    private Integer asPositiveIntegerOrZero(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 快照里的对局记录：公开事件按天折叠。 */
    private List<WolfbookDtos.JudgeRoundView> buildRounds(List<JudgeActionEventEntity> events) {
        return JudgeDayFlow.buildRounds(events.stream()
                .filter(event -> VISIBILITY_PUBLIC.equals(event.getVisibility()) || VISIBILITY_SYSTEM.equals(event.getVisibility()))
                .map(event -> new JudgeDayFlow.EventRecord(safeInt(event.getDayNo(), 1), safeText(event.getActionType(), ""),
                        readMap(event.getPayloadJson()), readMap(event.getResultPayloadJson())))
                .toList());
    }

    /** 当前要展示的那轮投票：票型公布时是刚结算的那轮，PK 时是 PK，其余是放逐投票。 */
    private String votePhase(JudgeRoomEntity room) {
        String phase = safeText(room.getCurrentPhase(), "");
        if (PHASE_VOTE_RESULT.equals(phase)) {
            return safeText(asString(readMap(room.getPhaseData()).get("resolvedFrom")), PHASE_EXILE_VOTE);
        }
        return PHASE_VOTE_PK.equals(phase) ? PHASE_VOTE_PK : PHASE_EXILE_VOTE;
    }

    // ---------- 出局待办：技能询问、猎人开枪、移交警徽（只对 auto_judge_enabled 的房间）----------

    private static final int SKILL_ASK_SECONDS = 10;
    private static final int HUNTER_SHOT_SECONDS = 20;
    private static final int BADGE_SECONDS = 30;
    private static final String PENDING_SKILL_ASK = "skill_ask";
    private static final String PENDING_HUNTER_SHOT = "hunter_shot";
    private static final String PENDING_BADGE = "badge";

    /**
     * 有人出局后压入待办，先把所有人的技能问完再回到原来的阶段。每个出局者都有一条技能询问（没技能的人只有「确认」，
     * 倒计时一样长，别人看不出谁有技能）；警长出局再加一条移交警徽。已经在待办里时，新的出局者插在当前这条后面（连锁）。
     */
    private void startPending(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> outSeats, LocalDateTime now) {
        if (outSeats.isEmpty()) {
            return;
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = pendingList(phaseData);
        List<Map<String, Object>> added = new ArrayList<>();
        for (Integer seat : outSeats.stream().sorted().toList()) {
            added.add(pendingEntry(seat, PENDING_SKILL_ASK, now.plusSeconds(SKILL_ASK_SECONDS)));
            JudgeRoomPlayerEntity player = findSeatPlayer(players, seat);
            if (player != null && Boolean.TRUE.equals(player.getSheriff())) {
                added.add(pendingEntry(seat, PENDING_BADGE, now.plusSeconds(BADGE_SECONDS)));
            }
        }
        if (PHASE_SKILL_PENDING.equals(room.getCurrentPhase()) && !pending.isEmpty()) {
            pending.addAll(1, added);
        } else {
            pending = added;
            phaseData.put("resumePhase", room.getCurrentPhase());
            // 夜里点杀之类：待办处理完回到原阶段时，原来的倒计时照常继续
            if (room.getStepDeadline() != null) {
                phaseData.put("resumeDeadline", room.getStepDeadline().toString());
            }
        }
        phaseData.put("pending", pending);
        room.setPhaseData(writeJson(phaseData));
        room.setCurrentPhase(PHASE_SKILL_PENDING);
        room.setStepDeadline(parseDeadline(pending.get(0)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitSkill(String authorization, String roomId, WolfbookDtos.JudgeSkillRequest request) {
        roomId = resolveRoomId(roomId);
        String openid = requireOpenid(authorization);
        tick(roomId);
        lockRoom(roomId);
        JudgeRoomEntity room = getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_SKILL_PENDING.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "现在没有待处理的技能");
        }
        List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
        JudgeRoomPlayerEntity self = findPlayer(roomId, openid);
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = pendingList(phaseData);
        Map<String, Object> current = pending.get(0);
        int seat = safeInt(asPositiveInteger(current.get("seat")), 0);
        if (self == null || !Objects.equals(self.getSeatNo(), seat)) {
            throw new ApiException(4003, "现在不是你的技能");
        }
        resolvePending(room, players, safeText(trimToNull(request.decision()), "skip"), request.targetSeatNo(), LocalDateTime.now(APP_ZONE));
        return buildSnapshot(openid, roomId);
    }

    /** 处理当前这条待办（玩家 / 法官提交，或者超时当作 skip），处理完写回房间。 */
    private void resolvePending(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String decision, Integer targetSeatNo, LocalDateTime now) {
        String roomId = room.getRoomId();
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = pendingList(phaseData);
        if (pending.isEmpty()) {
            throw new ApiException(4002, "现在没有待处理的技能");
        }
        Map<String, Object> current = pending.get(0);
        int seat = safeInt(asPositiveInteger(current.get("seat")), 0);
        JudgeRoomPlayerEntity actor = findSeatPlayer(players, seat);
        int dayNo = safeInt(room.getCurrentDay(), 1);
        String kind = asString(current.get("kind"));
        pending.remove(0);
        if (PENDING_SKILL_ASK.equals(kind)) {
            if ("use".equals(decision)) {
                if (!skillOptions(actor).contains("use")) {
                    throw new ApiException(4000, "你没有可以发动的技能");
                }
                pending.add(0, pendingEntry(seat, PENDING_HUNTER_SHOT, now.plusSeconds(HUNTER_SHOT_SECONDS)));
            }
        } else if (PENDING_HUNTER_SHOT.equals(kind) && "use".equals(decision)) {
            JudgeRoomPlayerEntity target = requireAliveSeat(players, targetSeatNo);
            markPlayersDead(players, List.of(target.getSeatNo()), dayNo, PHASE_SKILL_PENDING, now);
            addEvent(roomId, dayNo, PHASE_SKILL_PENDING, actor.getPlayerId(), List.of(target.getPlayerId()), EVENT_HUNTER_SHOT,
                    mapOf("actorSeatNo", seat, "targetSeatNo", target.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
            room.setLatestAnnouncement(seat + "号开枪带走了" + target.getSeatNo() + "号");
            Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
            if (verdict.isPresent()) {
                finishByVerdict(room, verdict.get(), room.getLatestAnnouncement() + "。" + verdict.get().reason(), now);
                room.setUpdateTime(now);
                updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SKILL_PENDING);
                return;
            }
            List<Map<String, Object>> chained = new ArrayList<>();
            chained.add(pendingEntry(target.getSeatNo(), PENDING_SKILL_ASK, now.plusSeconds(SKILL_ASK_SECONDS)));
            if (Boolean.TRUE.equals(target.getSheriff())) {
                chained.add(pendingEntry(target.getSeatNo(), PENDING_BADGE, now.plusSeconds(BADGE_SECONDS)));
            }
            pending.addAll(0, chained);
        } else if (PENDING_BADGE.equals(kind)) {
            JudgeRoomPlayerEntity heir = "use".equals(decision) ? requireAliveSeat(players, targetSeatNo) : null;
            actor.setSheriff(false);
            actor.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(actor);
            if (heir != null) {
                heir.setSheriff(true);
                heir.setUpdateTime(now);
                judgeRoomPlayerMapper.updateById(heir);
            }
            addEvent(roomId, dayNo, PHASE_SKILL_PENDING, actor.getPlayerId(), heir == null ? List.of() : List.of(heir.getPlayerId()), EVENT_BADGE_PASSED,
                    mapOf("actorSeatNo", seat, "targetSeatNo", heir == null ? null : heir.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
            room.setLatestAnnouncement(heir == null ? seat + "号撕掉了警徽" : seat + "号把警徽移交给" + heir.getSeatNo() + "号");
        }
        if (pending.isEmpty()) {
            room.setCurrentPhase(safeText(asString(phaseData.get("resumePhase")), PHASE_DAY_ANNOUNCE));
            room.setStepDeadline(parseDeadline(Map.of("deadline", safeText(asString(phaseData.get("resumeDeadline")), ""))));
            phaseData.remove("pending");
            phaseData.remove("resumePhase");
            phaseData.remove("resumeDeadline");
        } else {
            phaseData.put("pending", pending);
            room.setStepDeadline(parseDeadline(pending.get(0)));
        }
        room.setPhaseData(writeJson(phaseData));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SKILL_PENDING);
    }

    // ---------- 暂停 / 继续（只认法官，只在新流程房间）----------

    private boolean isPaused(JudgeRoomEntity room) {
        return readMap(room.getPhaseData()).containsKey("pausedAt");
    }

    private void requireNotPaused(JudgeRoomEntity room) {
        if (ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && isPaused(room)) {
            throw new ApiException(4002, "法官暂停中");
        }
    }

    /** 暂停：记下暂停时刻；惰性推进跳过暂停中的房间，所有提交都先等着。 */
    private void handlePause(JudgeRoomEntity room, LocalDateTime now) {
        if (!Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            throw new ApiException(4002, "旧流程房间没有计时，不用暂停");
        }
        if (isPaused(room)) {
            throw new ApiException(4002, "已经暂停了");
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        phaseData.put("pausedAt", now.toString());
        room.setPhaseData(writeJson(phaseData));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, room.getCurrentPhase(), room.getNightStep());
        addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), null, List.of(), EVENT_JUDGE_PAUSED,
                Map.of(), Map.of(), VISIBILITY_PUBLIC, now);
    }

    /** 继续：当前截止时间、每条待办的截止时间、待办结束后要恢复的截止时间，全部顺延暂停了多久。 */
    private void handleResume(JudgeRoomEntity room, LocalDateTime now) {
        Map<String, Object> phaseData = new LinkedHashMap<>(readMap(room.getPhaseData()));
        LocalDateTime pausedAt = parseDeadline(Map.of("deadline", safeText(asString(phaseData.get("pausedAt")), "")));
        if (pausedAt == null) {
            throw new ApiException(4002, "现在没有暂停");
        }
        java.time.Duration paused = java.time.Duration.between(pausedAt, now);
        if (room.getStepDeadline() != null) {
            room.setStepDeadline(room.getStepDeadline().plus(paused));
        }
        List<Map<String, Object>> pending = pendingList(phaseData);
        for (Map<String, Object> entry : pending) {
            LocalDateTime deadline = parseDeadline(entry);
            if (deadline != null) {
                entry.put("deadline", deadline.plus(paused).toString());
            }
        }
        if (!pending.isEmpty()) {
            phaseData.put("pending", pending);
        }
        LocalDateTime resumeDeadline = parseDeadline(Map.of("deadline", safeText(asString(phaseData.get("resumeDeadline")), "")));
        if (resumeDeadline != null) {
            phaseData.put("resumeDeadline", resumeDeadline.plus(paused).toString());
        }
        phaseData.remove("pausedAt");
        room.setPhaseData(writeJson(phaseData));
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, room.getCurrentPhase(), room.getNightStep());
        addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), null, List.of(), EVENT_JUDGE_RESUMED,
                Map.of(), Map.of(), VISIBILITY_PUBLIC, now);
    }

    /** 法官点杀：违规的玩家直接出局，公告注明法官判罚，照常问技能（点杀的猎人不能开枪）。 */
    private void handleForceOut(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        JudgeRoomPlayerEntity target = requireAliveSeat(players, request.forceOutSeatNo());
        String phase = safeText(room.getCurrentPhase(), "");
        int dayNo = safeInt(room.getCurrentDay(), 1);
        Map<String, Object> status = new LinkedHashMap<>(readMap(target.getStatusJson()));
        status.put("canShoot", false);
        target.setStatusJson(writeJson(status));
        markPlayersDead(players, List.of(target.getSeatNo()), dayNo, phase, now);
        addEvent(room.getRoomId(), dayNo, phase, null, List.of(target.getPlayerId()), EVENT_FORCE_OUT,
                mapOf("targetSeatNo", target.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
        String announcement = target.getSeatNo() + "号被法官判罚出局";
        room.setLatestAnnouncement(announcement);
        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
        if (verdict.isPresent()) {
            finishByVerdict(room, verdict.get(), announcement + "。" + verdict.get().reason(), now);
        } else if (Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            startPending(room, players, List.of(target.getSeatNo()), now);
        }
        room.setUpdateTime(now);
        updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
    }

    /** 自动判出胜负：直接结束，待办作废。调用方负责带条件写回房间。 */
    private void finishByVerdict(JudgeRoomEntity room, WinJudge.Verdict verdict, String announcement, LocalDateTime now) {
        room.setRoomStatus(ROOM_STATUS_FINISHED);
        room.setCurrentPhase(PHASE_RESULT);
        room.setWinnerCamp(verdict.camp());
        room.setLatestAnnouncement(announcement);
        room.setNightStep(null);
        room.setStepDeadline(null);
        room.setPhaseData(writeJson(mapOf("verdictReason", verdict.reason())));
        addFinishedEvent(room, verdict, null, announcement, now);
    }

    /** 技能询问的选项：没被毒的猎人可以开枪，其余人只有「确认」。 */
    private List<String> skillOptions(JudgeRoomPlayerEntity player) {
        if (player == null) {
            return List.of("skip");
        }
        boolean hunter = mechanicOf(player).map(mechanic -> mechanic == Mechanic.HUNTER).orElse(false);
        boolean canShoot = !Boolean.FALSE.equals(readMap(player.getStatusJson()).get("canShoot"));
        return hunter && canShoot ? List.of("use", "skip") : List.of("skip");
    }

    /** 当前待办只发给当事人和法官。 */
    private WolfbookDtos.JudgePendingView pendingView(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                      JudgeRoomPlayerEntity self, boolean judgeViewer) {
        if (!PHASE_SKILL_PENDING.equals(room.getCurrentPhase()) || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            return null;
        }
        List<Map<String, Object>> pending = pendingList(readMap(room.getPhaseData()));
        if (pending.isEmpty()) {
            return null;
        }
        Map<String, Object> current = pending.get(0);
        int seat = safeInt(asPositiveInteger(current.get("seat")), 0);
        if (!judgeViewer && (self == null || !Objects.equals(self.getSeatNo(), seat))) {
            return null;
        }
        String kind = asString(current.get("kind"));
        List<String> options = PENDING_SKILL_ASK.equals(kind) ? skillOptions(findSeatPlayer(players, seat)) : List.of("use", "skip");
        return new WolfbookDtos.JudgePendingView(kind, seat, options, parseDeadline(current));
    }

    private List<Map<String, Object>> pendingList(Map<String, Object> phaseData) {
        List<Map<String, Object>> pending = new ArrayList<>();
        if (phaseData.get("pending") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    pending.add(new LinkedHashMap<>(castMap(map)));
                }
            }
        }
        return pending;
    }

    private Map<String, Object> pendingEntry(int seat, String kind, LocalDateTime deadline) {
        return mapOf("seat", seat, "kind", kind, "deadline", deadline.toString());
    }

    private LocalDateTime parseDeadline(Map<String, Object> entry) {
        try {
            return LocalDateTime.parse(asString(entry.get("deadline")));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 被放逐的是还没翻牌的白痴：翻牌免死，从此没有投票权。返回 true 表示翻了牌、不出局。 */
    private boolean revealIdiot(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, Integer seat, LocalDateTime now) {
        JudgeRoomPlayerEntity player = seat == null ? null : findSeatPlayer(players, seat);
        if (player == null || mechanicOf(player).filter(mechanic -> mechanic == Mechanic.IDIOT).isEmpty()) {
            return false;
        }
        Map<String, Object> status = new LinkedHashMap<>(readMap(player.getStatusJson()));
        if (Boolean.TRUE.equals(status.get("idiotRevealed"))) {
            return false;
        }
        status.put("idiotRevealed", true);
        player.setStatusJson(writeJson(status));
        player.setCanVote(false);
        player.setUpdateTime(now);
        judgeRoomPlayerMapper.updateById(player);
        addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), List.of(), EVENT_IDIOT_REVEALED,
                mapOf("actorSeatNo", seat), Map.of(), VISIBILITY_PUBLIC, now);
        return true;
    }


    // ---------- 惰性推进：没有定时器，任何一次轮询或提交进来时把到期的步骤推过去 ----------

    /** 推进到期的夜间步骤和出局待办。独立事务，另一个请求先推进了就整体回滚、静默忽略。 */
    private void tick(String roomId) {
        try {
            tickTransaction.executeWithoutResult(status -> tickInTransaction(roomId));
        } catch (ApiException ignored) {
            // 并发下别的请求已经推进过这一步
        }
    }

    private void tickInTransaction(String roomId) {
        JudgeRoomEntity room = lockRoom(roomId);
        if (room == null || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !Boolean.TRUE.equals(room.getAutoJudgeEnabled())
                || isPaused(room)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        String phase = safeText(room.getCurrentPhase(), "");
        if (PHASE_NIGHT_ACTION.equals(phase) && room.getNightStep() != null) {
            List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
            List<NightPlan.NightStep> steps = nightSteps(room, safeInt(room.getCurrentDay(), 1));
            if (room.getNightStep() >= steps.size()) {
                return;
            }
            NightPlan.NightStep step = steps.get(room.getNightStep());
            List<Integer> aliveHolders = players.stream()
                    .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()))
                    .filter(player -> player.getRoleId() != null && step.roleIds().contains(player.getRoleId()) && player.getSeatNo() != null)
                    .map(JudgeRoomPlayerEntity::getSeatNo)
                    .toList();
            Set<Integer> submitted = tonightSubmissions(room, players).stream()
                    .map(NightResolver.Submission::actorSeat)
                    .collect(Collectors.toSet());
            if (JudgeNightFlow.shouldClose(step.kind(), aliveHolders, submitted, room.getStepDeadline(), now)) {
                handleNextStep(room, players, new WolfbookDtos.JudgeRoomAdvanceRequest("next_step", null, null, null, null,
                        room.getNightStep(), null, null, null, null), now);
            }
        } else if (PHASE_SKILL_PENDING.equals(phase) && room.getStepDeadline() != null && now.isAfter(room.getStepDeadline())) {
            resolvePending(room, loadPlayers(roomId), "skip", null, now);
        } else if (Set.of(PHASE_EXILE_VOTE, PHASE_VOTE_PK, PHASE_SHERIFF_VOTE, PHASE_SHERIFF_PK).contains(phase)) {
            // 全员投完或到点：没投的人算弃票
            List<JudgeRoomPlayerEntity> players = loadPlayers(roomId);
            if (JudgeDayClock.shouldCloseVote(eligibleVoters(room, players, phase), votedSeats(room, players, phase),
                    room.getStepDeadline(), now)) {
                if (PHASE_EXILE_VOTE.equals(phase) || PHASE_VOTE_PK.equals(phase)) {
                    handleResolveVote(room, players, systemRequest("resolve_vote"), now);
                } else {
                    handleResolveSheriffVote(room, players, now);
                }
            }
        } else if (PHASE_SHERIFF_SIGNUP.equals(phase) && room.getStepDeadline() != null && now.isAfter(room.getStepDeadline())) {
            // 报名到点：没选的人算不上警
            handleCloseSheriffSignup(room, loadPlayers(roomId), systemRequest("close_sheriff_signup"), now);
        }
    }


    /** 房主：有法官时就是法官，无法官时是坐 1 号的玩家。白天推进、开局、改规则归房主。 */
    private JudgeRoomEntity requireHost(String roomId, String openid) {
        JudgeRoomEntity room = getRoom(roomId);
        if (!openid.equals(room.getOwnerUserId())) {
            throw new ApiException(4003, "只有房主可以执行此操作");
        }
        return room;
    }

    /**
     * 推进动作的权限：点杀、暂停 / 继续只认法官；旧流程房间其余动作也只认法官（线上旧版小程序的主持台）；
     * 新流程房间其余动作认房主，但系统负责的夜间推进（手动切步、改判）不开放，有法官时也不能流局。
     */
    private JudgeRoomEntity requireAdvancePermission(String roomId, String openid, String action) {
        if (Set.of("force_out", "pause", "resume").contains(action)) {
            return requireJudgeRoom(roomId, openid);
        }
        JudgeRoomEntity room = getRoom(roomId);
        if (!Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            return requireJudgeRoom(roomId, openid);
        }
        requireHost(roomId, openid);
        if ("next_step".equals(action) || "resolve_night".equals(action)) {
            throw new ApiException(4003, "新流程由系统推进");
        }
        if ("finish".equals(action) && JUDGE_MODE_OBSERVER.equals(room.getJudgeMode())) {
            throw new ApiException(4003, "有法官模式不能流局");
        }
        return room;
    }

    // ---------- 白天计时 ----------

    /** 新流程房间才计时；seconds 为空表示这一阶段不计时。 */
    private void setPhaseClock(JudgeRoomEntity room, Integer seconds, LocalDateTime now) {
        room.setStepDeadline(Boolean.TRUE.equals(room.getAutoJudgeEnabled()) && seconds != null ? now.plusSeconds(seconds) : null);
    }

    /** 这位发言者的时长：警上发言和警长发言用警长时长，其余用每人发言时长。 */
    private int speechSeconds(JudgeRoomEntity room, String phase, Integer seat, Integer sheriffSeat) {
        JudgeRules rules = roomRules(room);
        boolean sheriffTime = PHASE_SHERIFF_SPEECH.equals(phase) || (seat != null && seat.equals(sheriffSeat));
        return sheriffTime ? rules.sheriffSpeechSeconds() : rules.speechSeconds();
    }

    /** 这一轮能投票的座位：放逐看投票权（翻牌白痴没有），PK 去掉平票者；警长投票只有没上过警的人。 */
    private Set<Integer> eligibleVoters(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String phase) {
        boolean sheriffVote = PHASE_SHERIFF_VOTE.equals(phase) || PHASE_SHERIFF_PK.equals(phase);
        Set<Integer> pk = new HashSet<>(PHASE_VOTE_PK.equals(phase) ? pkSeats(room) : List.of());
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()) && player.getSeatNo() != null)
                .filter(player -> sheriffVote ? !Boolean.TRUE.equals(player.getSheriffCandidate()) : !Boolean.FALSE.equals(player.getCanVote()))
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .filter(seat -> !pk.contains(seat))
                .collect(Collectors.toSet());
    }

    /** 这一轮已经投了的座位（弃票也算）。 */
    private Set<Integer> votedSeats(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String phase) {
        String type = PHASE_SHERIFF_VOTE.equals(phase) || PHASE_SHERIFF_PK.equals(phase) ? EVENT_SHERIFF_VOTE_SUBMIT : EVENT_VOTE_SUBMIT;
        Map<String, Integer> seatById = new HashMap<>();
        players.stream().filter(player -> player.getSeatNo() != null).forEach(player -> seatById.put(player.getPlayerId(), player.getSeatNo()));
        return loadEvents(room.getRoomId()).stream()
                .filter(event -> type.equals(event.getActionType()) && phase.equals(event.getPhase())
                        && Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .map(event -> seatById.get(event.getActorPlayerId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 惰性推进替房主调用结算时用的空请求。 */
    private WolfbookDtos.JudgeRoomAdvanceRequest systemRequest(String action) {
        return new WolfbookDtos.JudgeRoomAdvanceRequest(action, null, null, null, null, null, null, null, null, null);
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

    /** 只更新 update_time：玩家提交不改房间状态，别把这次请求读到的整行写回去（惰性推进可能刚推进过）。 */
    private void touchRoom(String roomId, LocalDateTime now) {
        judgeRoomMapper.update(null, new LambdaUpdateWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, roomId)
                .set(JudgeRoomEntity::getUpdateTime, now));
    }

    private JudgeRoomPlayerEntity requireAliveSeatPlayer(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        JudgeRoomPlayerEntity player = findSeatPlayer(players, seatNo);
        if (player == null || !Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4002, "目标玩家当前不可选");
        }
        return player;
    }

    /**
     * 锁住房间这一行（SELECT … FOR UPDATE），同一间房的推进、提交、惰性推进排队执行。
     * 必须在读房间、座位、事件之前调用，配合 READ COMMITTED，锁到之后读到的都是最新提交的数据，
     * 不会出现「两个请求都读了旧房间，后写的把先写的覆盖掉」（比如暂停被关步骤的惰性推进冲掉）。
     */
    private JudgeRoomEntity lockRoom(String roomId) {
        return judgeRoomMapper.selectOne(new LambdaQueryWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, roomId)
                .last("FOR UPDATE"));
    }

    /** 新流程房间按票型和规则结算：不收房主指定的出局者、胜方、发言起点和方向（无法官房主只是普通玩家，有法官也不改判）。 */
    private void rejectDictatedResults(JudgeRoomEntity room, String action, WolfbookDtos.JudgeRoomAdvanceRequest request) {
        if (!Boolean.TRUE.equals(room.getAutoJudgeEnabled())) {
            return;
        }
        if ("resolve_vote".equals(action) && (request.eliminatedSeatNo() != null || trimToNull(request.winnerCamp()) != null)) {
            throw new ApiException(4000, "新流程按票型结算，不能指定结果");
        }
        if ("finish".equals(action) && trimToNull(request.winnerCamp()) != null) {
            throw new ApiException(4000, "只能强制结束（记流局），胜负由系统判定");
        }
        if ("start_speech".equals(action) && (request.speechStartSeat() != null || trimToNull(request.speechDirection()) != null)) {
            throw new ApiException(4000, "发言顺序由系统和警长决定");
        }
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
            List<JudgeActionEventEntity> events,
            String votePhase
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
            if (!votePhase.equals(event.getPhase())) {
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
        if (JUDGE_MODE_JOINED.equals(normalized) && !JudgeSupportLevels.FULL.equals(judgeSupportLevel)) {
            throw new ApiException(4000, "这个板子只能开有法官");
        }
        return normalized;
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
