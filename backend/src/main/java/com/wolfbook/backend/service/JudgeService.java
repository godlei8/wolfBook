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
import com.wolfbook.backend.service.judge.JudgeLastWords;
import com.wolfbook.backend.service.judge.JudgeNightFlow;
import com.wolfbook.backend.service.judge.Mechanic;
import com.wolfbook.backend.service.judge.NightKind;
import com.wolfbook.backend.service.judge.NightPlan;
import com.wolfbook.backend.service.judge.NightResolver;
import com.wolfbook.backend.service.judge.NightStepRules;
import com.wolfbook.backend.service.judge.RoleSkillRegistry;
import com.wolfbook.backend.service.judge.VoteResolver;
import com.wolfbook.backend.service.judge.WinJudge;
import com.wolfbook.backend.support.JudgeSupportLevels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import static com.wolfbook.backend.service.JudgeRoomSupport.*;

/**
 * 法官房间服务。
 *
 * <p>负责从板子创建房间、玩家座位、身份分配、夜晚/白天流程、事件记录和房间快照。
 * 这是交互最重的服务，读代码时可先找“创建房间、加入房间、开始游戏、提交行动”几个入口方法。</p>
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    private final JudgeRoomSupport support;
    private final JudgeSnapshotAssembler assembler;
    private final JudgeLobbyService lobby;
    private final UserService userService;
    private final BoardService boardService;
    private final JudgeRoomMapper judgeRoomMapper;
    private final JudgeRoomPlayerMapper judgeRoomPlayerMapper;
    private final JudgeActionEventMapper judgeActionEventMapper;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();
    /** 惰性推进用独立事务：推进失败（另一个请求已经推进了）整体回滚，不影响本次请求。 */
    private final TransactionTemplate tickTransaction;

    public JudgeService(
            JudgeRoomSupport support,
            JudgeSnapshotAssembler assembler,
            JudgeLobbyService lobby,
            UserService userService,
            BoardService boardService,
            JudgeRoomMapper judgeRoomMapper,
            JudgeRoomPlayerMapper judgeRoomPlayerMapper,
            JudgeActionEventMapper judgeActionEventMapper,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tickTransaction = new TransactionTemplate(transactionManager);
        this.tickTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tickTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.support = support;
        this.assembler = assembler;
        this.lobby = lobby;
        this.userService = userService;
        this.boardService = boardService;
        this.judgeRoomMapper = judgeRoomMapper;
        this.judgeRoomPlayerMapper = judgeRoomPlayerMapper;
        this.judgeActionEventMapper = judgeActionEventMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 大厅（在 JudgeLobbyService）----------

    public List<WolfbookDtos.JudgeRoomSummaryView> listRecentRooms(String authorization) {
        return lobby.listRecentRooms(authorization);
    }

    public WolfbookDtos.JudgeRoomSnapshotView createRoom(String authorization, WolfbookDtos.JudgeRoomCreateRequest request) {
        return lobby.createRoom(authorization, request);
    }

    public WolfbookDtos.JudgeRoomSnapshotView joinRoom(String authorization, String roomId) {
        return lobby.joinRoom(authorization, roomId);
    }

    public void dissolveRoom(String authorization, String roomId) {
        lobby.dissolveRoom(authorization, roomId);
    }

    public WolfbookDtos.JudgeRoomSnapshotView leaveRoom(String authorization, String roomId) {
        return lobby.leaveRoom(authorization, roomId);
    }

    public WolfbookDtos.JudgeRoomSnapshotView removePlayer(String authorization, String roomId, WolfbookDtos.JudgeSeatRequest request) {
        return lobby.removePlayer(authorization, roomId, request);
    }

    public WolfbookDtos.JudgeRoomSnapshotView changeSeat(String authorization, String roomId, WolfbookDtos.JudgeSeatRequest request) {
        return lobby.changeSeat(authorization, roomId, request);
    }

    public WolfbookDtos.JudgeRoomSnapshotView toggleReady(String authorization, String roomId) {
        return lobby.toggleReady(authorization, roomId);
    }

    public WolfbookDtos.JudgeRoomSnapshotView updateRoomRules(String authorization, String roomId, WolfbookDtos.JudgeRoomRulesInput request) {
        return lobby.updateRoomRules(authorization, roomId, request);
    }

    public WolfbookDtos.JudgeRoomSnapshotView getRoomSnapshot(String authorization, String roomId) {
        String openid = support.requireOpenid(authorization);
        String resolved = support.resolveRoomId(roomId);
        support.requireNotRemoved(support.getRoom(resolved), openid);
        tick(resolved);
        return assembler.buildSnapshot(openid, resolved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView startRoom(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.requireHost(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "房间不在可开局状态");
        }

        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
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

        Map<Integer, RoleEntity> roleMap = support.loadBoardRoleMap(room.getBoardId());
        Board startBoard = boardService.getBoard(room.getBoardId());
        List<RoleEntity> shuffledRoles = buildShuffledRoles(startBoard, roleMap);
        if (shuffledRoles.size() != playingPlayers.size()) {
            throw new ApiException(4002, "板子角色配置与人数不匹配");
        }

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        room.setRoomStatus(ROOM_STATUS_PLAYING);
        room.setCurrentDay(1);
        room.setCurrentPhase(PHASE_NIGHT_ACTION);
        room.setLatestAnnouncement("身份已发放，进入第一夜");
        room.setUpdateTime(now);
        room.setBoardSnapshot(support.writeJson(new BoardSnapshot(startBoard, new ArrayList<>(roleMap.values()))));
        openNight(room, 1, now);
        support.updateRoomGuarded(room, ROOM_STATUS_LOBBY, null);

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

        support.addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_GAME_STARTED,
                support.mapOf("playerCount", room.getPlayerCount()),
                support.mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return assembler.buildSnapshot(openid, roomId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView advanceRoom(String authorization, String roomId, WolfbookDtos.JudgeRoomAdvanceRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
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
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        switch (normalizeAction(request.action())) {
            case "broadcast" -> handleBroadcast(room, request, now);
            case "start_vote" -> handleStartVote(room, request, now);
            case "resolve_vote" -> handleResolveVote(room, players, request, now);
            case "finish" -> handleFinish(room, players, request, now);
            case "close_sheriff_signup" -> handleCloseSheriffSignup(room, players, request, now);
            case "start_sheriff_vote" -> handleStartSheriffVote(room, players, now);
            case "resolve_sheriff_vote" -> handleResolveSheriffVote(room, players, now);
            case "start_speech" -> handleStartSpeech(room, players, request, now);
            case "continue" -> handleContinue(room, now);
            case "start_night" -> handleStartNight(room, now);
            case "force_out" -> handleForceOut(room, players, request, now);
            case "pause" -> handlePause(room, now);
            case "resume" -> handleResume(room, now);
            default -> throw new ApiException(4000, "不支持的推进动作");
        }
        return assembler.buildSnapshot(openid, roomId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitNightAction(String authorization, String roomId, WolfbookDtos.JudgeNightActionRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        tick(roomId);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是夜间行动阶段");
        }

        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        JudgeRoomPlayerEntity player;
        if (request.actorSeatNo() != null) {
            // 法官代记是旧流程的功能，已下掉：夜里都是玩家自己操作
            throw new ApiException(4003, "请玩家自己操作");
        }
        player = requireAlivePlayingPlayer(roomId, openid);
        JudgeRoomPlayerEntity targetPlayer = null;
        if (request.targetSeatNo() != null) {
            targetPlayer = requireAliveSeat(players, request.targetSeatNo());
        }
        Integer targetSeatNo = targetPlayer == null ? null : targetPlayer.getSeatNo();
        String actionLabel = support.safeText(request.actionType(), "night_action");
        String action = RoleSkillRegistry.LEGACY_ACTIONS.getOrDefault(actionLabel, actionLabel);
        if (request.actorSeatNo() == null && (WOLF_CONFIRM.equals(action) || WOLF_RESELECT.equals(action))
                && currentStepKind(room) == NightKind.WOLF) {
            confirmWolfPick(room, players, player, WOLF_CONFIRM.equals(action));
            return assembler.buildSnapshot(openid, roomId);
        }
        validateNightAction(room, players, player, targetPlayer, action);
        deleteStepSubmission(room, player);

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        support.addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                targetPlayer == null ? List.of() : List.of(targetPlayer.getPlayerId()),
                EVENT_NIGHT_SUBMIT,
                support.mapOf(
                        // actionType 原样保留（旧版小程序按中文名识别刀口、用药），结算读英文的 action
                        "actionType", actionLabel,
                        "action", action,
                        "targetSeatNo", targetSeatNo,
                        "note", support.trimToNull(request.note()),
                        "stepIndex", stepIndexForStorage(room)
                ),
                Map.of(),
                VISIBILITY_JUDGE_ONLY,
                now
        );
        touchRoom(roomId, now);
        syncWolfConfirm(room, players, now);
        return assembler.buildSnapshot(openid, roomId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitVote(String authorization, String roomId, WolfbookDtos.JudgeVoteRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        tick(roomId);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        boolean pk = PHASE_VOTE_PK.equals(room.getCurrentPhase());
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || (!PHASE_EXILE_VOTE.equals(room.getCurrentPhase()) && !pk)) {
            throw new ApiException(4002, "当前不是投票阶段");
        }

        JudgeRoomPlayerEntity player = requireAlivePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        if (Boolean.FALSE.equals(player.getCanVote())) {
            throw new ApiException(4003, "你已经没有投票权");
        }
        if (pk && support.pkSeats(room).contains(player.getSeatNo())) {
            throw new ApiException(4003, "平票的玩家这一轮不能投票");
        }
        // targetSeatNo 为空是弃票
        JudgeRoomPlayerEntity targetPlayer = request.targetSeatNo() == null ? null : requireAliveSeatPlayer(players, request.targetSeatNo());
        if (pk && targetPlayer != null && !support.pkSeats(room).contains(targetPlayer.getSeatNo())) {
            throw new ApiException(4000, "这一轮只能投给平票的玩家");
        }
        deletePhaseEvent(roomId, room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), EVENT_VOTE_SUBMIT);

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        support.addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                targetPlayer == null ? List.of() : List.of(targetPlayer.getPlayerId()),
                EVENT_VOTE_SUBMIT,
                support.mapOf("targetSeatNo", targetPlayer == null ? null : targetPlayer.getSeatNo()),
                Map.of(),
                VISIBILITY_JUDGE_ONLY,
                now
        );
        touchRoom(roomId, now);
        return assembler.buildSnapshot(openid, roomId);
    }

    private void handleBroadcast(JudgeRoomEntity room, WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        String announcement = requireAnnouncement(request.announcement());
        judgeRoomMapper.update(null, new LambdaUpdateWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, room.getRoomId())
                .set(JudgeRoomEntity::getLatestAnnouncement, announcement)
                .set(JudgeRoomEntity::getUpdateTime, now));
        support.addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_BROADCAST,
                support.mapOf("announcement", announcement),
                Map.of(),
                VISIBILITY_SYSTEM,
                now
        );
    }

    private void handleStartVote(JudgeRoomEntity room, WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        if (!PHASE_DAY_SPEECH.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "当前不是发言阶段");
        }
        if (pkSpeech(room)) {
            openPkVote(room, PHASE_DAY_SPEECH, PHASE_VOTE_PK, "PK 发言结束，其余玩家只能投给他们", now);
            return;
        }
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : "进入放逐投票阶段";
        room.setCurrentPhase(PHASE_EXILE_VOTE);
        room.setLatestAnnouncement(announcement);
        setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_SPEECH);
        support.addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                null,
                List.of(),
                EVENT_VOTE_OPENED,
                support.mapOf("announcement", announcement),
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
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        List<NightPlan.NightStep> steps = support.nightSteps(room, dayNo);
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        if (current < steps.size() && steps.get(current).kind() == NightKind.WOLF) {
            List<NightResolver.Submission> tonight = support.tonightSubmissions(room, players);
            // 交了的狼每人一项：刀人是目标座位，明确空刀是 null；没交的不算（控刀）
            List<Integer> picks = tonight.stream()
                    .filter(submission -> submission.kind() == NightKind.WOLF
                            && (NightResolver.KILL.equals(submission.action()) || NightResolver.NONE.equals(submission.action())))
                    .map(submission -> NightResolver.KILL.equals(submission.action()) ? submission.targetSeat() : null)
                    .toList();
            if (picks.isEmpty() && NightResolver.armed(support.seatStates(players))) {
                // 狼队全出局：普通刀口是带刀机械狼在狼人那一步选的
                picks = tonight.stream()
                        .filter(submission -> submission.kind() == NightKind.MECH_WOLF && NightResolver.KILL.equals(submission.action()))
                        .map(NightResolver.Submission::targetSeat)
                        .toList();
            }
            phaseData.put("knifeDecided", true);
            phaseData.put("knifeSeat", NightResolver.tallyKnife(picks, random));
            phaseData.remove(WOLF_FULL_DEADLINE);
        }
        support.addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, null, List.of(), EVENT_STEP_CLOSED,
                support.mapOf("stepIndex", current), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        room.setPhaseData(support.writeJson(phaseData));
        if (current + 1 >= steps.size()) {
            room.setUpdateTime(now);
            resolveNight(room, players, null, null, null, now, current);
            return;
        }
        room.setNightStep(current + 1);
        room.setStepDeadline(now.plusSeconds(steps.get(current + 1).durationSeconds()));
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, current);
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
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        JudgeRules rules = support.roomRules(room);
        List<NightResolver.Submission> submissions = support.knifeFixedSubmissions(room, players);
        Map<Integer, NightResolver.SeatState> seats = support.seatStates(players);
        NightResolver.Outcome outcome = NightResolver.resolve(submissions, seats, rules, dayNo, random);

        List<Integer> deadSeatNos;
        boolean overridden = requestedDeadSeatNos != null;
        if (overridden) {
            deadSeatNos = normalizeSeatNos(requestedDeadSeatNos);
            requireAliveSeats(players, deadSeatNos);
        } else {
            deadSeatNos = outcome.deadSeats();
        }
        applyNightStatus(players, submissions, outcome, dayNo, now);
        for (Map.Entry<Integer, String> entry : outcome.feedback().entrySet()) {
            JudgeRoomPlayerEntity actor = support.findSeatPlayer(players, entry.getKey());
            if (actor != null) {
                support.addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, actor.getPlayerId(), List.of(), EVENT_NIGHT_FEEDBACK,
                        support.mapOf("feedback", entry.getValue()), Map.of(), VISIBILITY_ACTOR_ONLY, now);
            }
        }
        if (!overridden && !outcome.causes().isEmpty()) {
            Map<String, Object> causes = new LinkedHashMap<>();
            outcome.causes().forEach((seat, list) -> causes.put(String.valueOf(seat), list));
            support.addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, null, List.of(), EVENT_NIGHT_CAUSES,
                    support.mapOf("causes", causes), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        }
        if (overridden) {
            support.addEvent(room.getRoomId(), dayNo, PHASE_NIGHT_ACTION, null, List.of(), EVENT_NIGHT_OVERRIDE,
                    support.mapOf("deadSeatNos", deadSeatNos, "engineDeadSeatNos", outcome.deadSeats()), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        }
        if (Boolean.TRUE.equals(room.getSheriffEnabled()) && dayNo > 1
                && sheriffDeferred(room.getRoomId())) {
            // 昨天警上有人自爆：先接着竞选（不再报名），这一夜的死讯照样等竞选完再公布
            room.setNightStep(null);
            room.setStepDeadline(null);
            room.setPhaseData(support.writeJson(support.mapOf("pendingDeaths", deadSeatNos)));
            openSheriffSpeech(room, players, support.onStage(players), "天亮了，昨天的警长竞选继续。", now);
            room.setUpdateTime(now);
            support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, expectedNightStep);
            return;
        }
        if (dayNo == 1 && Boolean.TRUE.equals(room.getSheriffEnabled())) {
            // 第一天先竞选警长，昨夜死讯等警长选完再公布（死者也能上警）
            room.setLatestAnnouncement(StringUtils.hasText(requestedAnnouncement) ? requestedAnnouncement.trim() : "天亮了，现在开始警长竞选，要上警的玩家请选择");
            room.setCurrentPhase(PHASE_SHERIFF_SIGNUP);
            room.setNightStep(null);
            room.setStepDeadline(now.plusSeconds(JudgeDayClock.SHERIFF_SIGNUP_SECONDS));
            room.setPhaseData(support.writeJson(support.mapOf("pendingDeaths", deadSeatNos)));
            room.setUpdateTime(now);
            support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, expectedNightStep);
            return;
        }
        markPlayersDead(players, deadSeatNos, dayNo, PHASE_NIGHT_ACTION, now);

        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, requestedWinnerCamp);
        boolean finished = verdict.isPresent();
        String announcement = StringUtils.hasText(requestedAnnouncement)
                ? requestedAnnouncement.trim()
                : buildNightAnnouncement(deadSeatNos) + verdict.map(value -> "。" + value.reason()).orElse("");
        String nextPhase = finished ? PHASE_RESULT : PHASE_DAY_ANNOUNCE;
        room.setLatestAnnouncement(announcement);
        room.setWinnerCamp(verdict.map(WinJudge.Verdict::camp).orElse(null));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        room.setCurrentPhase(nextPhase);
        room.setNightStep(null);
        room.setStepDeadline(null);
        room.setPhaseData(verdict.map(value -> support.writeJson(support.mapOf("verdictReason", value.reason())))
                .orElse(support.writeJson(support.mapOf("nightDeaths", deadSeatNos))));
        if (!finished) {
            startPending(room, players, deadSeatNos, JudgeLastWords.Cause.NIGHT, now);
            if (PHASE_DAY_ANNOUNCE.equals(room.getCurrentPhase())) {
                // 用户给定：公布死讯后停 5 秒，自动开始发言
                setPhaseClock(room, JudgeDayClock.ANNOUNCE_SECONDS, now);
            }
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, expectedNightStep);
        support.addEvent(
                room.getRoomId(),
                dayNo,
                nextPhase,
                null,
                resolveTargetPlayerIds(players, deadSeatNos),
                EVENT_NIGHT_RESOLVED,
                support.mapOf("deadSeatNos", deadSeatNos),
                support.mapOf("announcement", announcement, "winnerCamp", room.getWinnerCamp()),
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
        String votePhase = support.safeText(room.getCurrentPhase(), "");
        boolean pkRound = PHASE_VOTE_PK.equals(votePhase);
        if (!PHASE_EXILE_VOTE.equals(votePhase) && !pkRound) {
            throw new ApiException(4002, "当前不是投票结算阶段");
        }
        List<JudgeActionEventEntity> voteEvents = support.loadEvents(room.getRoomId()).stream()
                .filter(event -> Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1)))
                .filter(event -> votePhase.equals(event.getPhase()))
                .filter(event -> EVENT_VOTE_SUBMIT.equals(event.getActionType()))
                .toList();
        List<WolfbookDtos.JudgeVoteTallyView> tallies = support.buildVoteTallies(room, players, voteEvents, votePhase);
        VoteResolver.Outcome voteOutcome = VoteResolver.resolve(ballotsOf(players, voteEvents),
                pkRound ? new LinkedHashSet<>(support.pkSeats(room)) : aliveSeats(players));
        // 弃票记录：新流程房间里有投票权却没投的人也算弃票（超时 = 弃票），对局记录里能看到
        Set<Integer> abstainSet = new TreeSet<>(voteOutcome.abstainSeats());
        Set<Integer> voted = support.votedSeats(room, players, votePhase);
        support.eligibleVoters(room, players, votePhase).stream().filter(seat -> !voted.contains(seat)).forEach(abstainSet::add);
        List<Integer> abstainSeats = List.copyOf(abstainSet);
        Integer eliminatedSeatNo = support.normalizeSeatNo(request.eliminatedSeatNo());
        if (eliminatedSeatNo != null) {
            requireAliveSeat(players, eliminatedSeatNo);
        } else {
            eliminatedSeatNo = voteOutcome.eliminatedSeat();
        }
        // 白痴被放逐照样出局，出局时翻牌公开身份
        boolean idiotRevealed = revealIdiot(room, players, eliminatedSeatNo, now);
        if (eliminatedSeatNo != null) {
            markPlayersDead(players, List.of(eliminatedSeatNo), room.getCurrentDay(), PHASE_EXILE_VOTE, now);
        }

        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, request.winnerCamp());
        boolean finished = verdict.isPresent();
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : (idiotRevealed ? eliminatedSeatNo + "号是白痴，被放逐出局" : buildVoteAnnouncement(eliminatedSeatNo, tallies))
                + verdict.map(value -> "。" + value.reason()).orElse("");

        int resolvedDay = support.safeInt(room.getCurrentDay(), 1);
        // 第一轮平票进 PK；PK 再平无人出局
        List<Integer> nextPk = !pkRound && eliminatedSeatNo == null ? voteOutcome.tiedSeats() : List.of();
        room.setLatestAnnouncement(nextPk.isEmpty() ? announcement : seatsText(nextPk) + "平票，进入 PK");
        room.setWinnerCamp(verdict.map(WinJudge.Verdict::camp).orElse(null));
        room.setRoomStatus(finished ? ROOM_STATUS_FINISHED : ROOM_STATUS_PLAYING);
        if (finished) {
            room.setCurrentPhase(PHASE_RESULT);
            room.setPhaseData(support.writeJson(support.mapOf("verdictReason", verdict.get().reason())));
        } else {
            // 用户给定：票型公布停 10 秒自动进入下一步（被放逐的人先处理技能和遗言，回来后重新计时）
            setPhaseClock(room, JudgeDayClock.RESULT_SECONDS, now);
            room.setCurrentPhase(PHASE_VOTE_RESULT);
            room.setPhaseData(support.writeJson(support.mapOf("pkSeats", nextPk, "resolvedFrom", votePhase)));
            if (eliminatedSeatNo != null) {
                startPending(room, players, List.of(eliminatedSeatNo), JudgeLastWords.Cause.EXILE, now);
            }
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, votePhase);

        support.addEvent(
                room.getRoomId(),
                resolvedDay,
                room.getCurrentPhase(),
                null,
                resolveTargetPlayerIds(players, eliminatedSeatNo == null ? List.of() : List.of(eliminatedSeatNo)),
                EVENT_VOTE_RESOLVED,
                support.mapOf("eliminatedSeatNo", eliminatedSeatNo, "round", pkRound ? "pk" : "exile"),
                support.mapOf("announcement", announcement, "winnerCamp", room.getWinnerCamp(),
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
        String winnerCamp = support.safeText(support.trimToNull(request.winnerCamp()), DRAW);
        String announcement = StringUtils.hasText(request.announcement())
                ? request.announcement().trim()
                : "对局已结束";
        room.setWinnerCamp(winnerCamp);
        room.setLatestAnnouncement(announcement);
        room.setRoomStatus(ROOM_STATUS_FINISHED);
        room.setCurrentPhase(PHASE_RESULT);
        room.setNightStep(null);
        room.setStepDeadline(null);
        String ender = JUDGE_MODE_OBSERVER.equals(room.getJudgeMode()) ? "法官" : "房主";
        room.setPhaseData(support.writeJson(support.mapOf("verdictReason", DRAW.equals(winnerCamp) ? ender + "提前结束了对局（流局）" : ender + "判定" + winnerCamp + "胜利")));
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, null);
        support.addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                PHASE_RESULT,
                null,
                List.of(),
                EVENT_GAME_FINISHED,
                support.mapOf("winnerCamp", winnerCamp),
                support.mapOf("announcement", announcement),
                VISIBILITY_SYSTEM,
                now
        );
    }

    /** 开一个新的夜晚：从第 0 步开始，截止时间按这一步的时长，清空上一阶段的临时数据。 */
    private void openNight(JudgeRoomEntity room, int dayNo, LocalDateTime now) {
        List<NightPlan.NightStep> steps = support.nightSteps(room, dayNo);
        room.setNightStep(steps.isEmpty() ? null : 0);
        room.setStepDeadline(steps.isEmpty() ? null : now.plusSeconds(steps.get(0).durationSeconds()));
        room.setPhaseData(support.writeJson(Map.of()));
    }

    /** 把今晚的用药、守护、猎人能否开枪写回座位状态。 */
    private void applyNightStatus(List<JudgeRoomPlayerEntity> players, List<NightResolver.Submission> submissions,
                                  NightResolver.Outcome outcome, int dayNo, LocalDateTime now) {
        for (JudgeRoomPlayerEntity player : players) {
            if (player.getSeatNo() == null || !Boolean.TRUE.equals(player.getPlaying())) {
                continue;
            }
            Map<String, Object> status = new LinkedHashMap<>(support.readMap(player.getStatusJson()));
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
            NightResolver.Learned learned = outcome.learnedTonight().get(seat);
            if (learned != null) {
                status.put("mechLearned", learned.mechanic().code());
                status.put("mechLearnedRole", learned.roleName());
                status.put("mechLearnedDay", dayNo);
                changed = true;
            }
            if (outcome.mechPoisonUsedBy().contains(seat)) {
                status.put("mechPoison", false);
                changed = true;
            }
            if (support.nightKindOf(player) == NightKind.MECH_WOLF && "guard".equals(support.asString(status.get("mechLearned")))
                    && dayNo > support.safeInt(support.asPositiveInteger(status.get("mechLearnedDay")), dayNo)) {
                // 学到守卫的机械狼：和守卫一样记上一夜守了谁（不能连守）
                Integer guarded = submissions.stream()
                        .filter(submission -> submission.actorSeat() == seat && NightResolver.GUARD.equals(submission.action()))
                        .map(NightResolver.Submission::targetSeat)
                        .findFirst()
                        .orElse(null);
                status.put("lastGuardSeat", guarded);
                changed = true;
            }
            if (support.nightKindOf(player) == NightKind.GUARD) {
                Integer guarded = submissions.stream()
                        .filter(submission -> submission.actorSeat() == seat && NightResolver.GUARD.equals(submission.action()))
                        .map(NightResolver.Submission::targetSeat)
                        .findFirst()
                        .orElse(null);
                status.put("lastGuardSeat", guarded);
                changed = true;
            }
            if (changed) {
                player.setStatusJson(support.writeJson(status));
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
        NightKind kind = support.nightKindOf(actor);
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        if (room.getNightStep() != null) {
            List<NightPlan.NightStep> steps = support.nightSteps(room, dayNo);
            NightPlan.NightStep step = room.getNightStep() < steps.size() ? steps.get(room.getNightStep()) : null;
            if (step == null || !support.stepHolderSeats(players, step).contains(actor.getSeatNo())) {
                throw new ApiException(4003, "现在还没轮到你行动");
            }
            switch (NightStepRules.check(step, support.mechanicOf(actor).orElse(null), action)) {
                case NOT_ALLOWED -> throw new ApiException(4000, "这个角色不能这样行动");
                // 带刀的机械狼：技能在自己那一步用，普通刀口到狼人那一步选（用户给定规则）
                case KNIFE_IN_WOLF_STEP -> throw new ApiException(4000, "刀口请到狼人那一步再选");
                default -> {
                }
            }
            if (target == null && !NightResolver.NONE.equals(action) && !NightResolver.CONFIRM.equals(action)) {
                throw new ApiException(4000, "请选择目标");
            }
        }
        if (!NightResolver.NONE.equals(action) && target == null) {
            return;
        }
        Map<Integer, NightResolver.SeatState> seats = support.seatStates(players);
        NightResolver.SeatState actorState = seats.get(actor.getSeatNo());
        NightResolver.SeatState targetState = target == null ? null : seats.get(target.getSeatNo());
        if (actorState == null) {
            return;
        }
        NightResolver.validate(new NightResolver.Submission(actor.getSeatNo(), kind, action, target == null ? null : target.getSeatNo()),
                actorState, targetState, support.roomRules(room), dayNo, knifeForValidation(room, players), seats);
    }

    /** 女巫校验解药用的刀口：狼队那一步已经定了就用定下的，否则看当前狼队的唯一多数（还没定 / 平票时为空）。 */
    private Integer knifeForValidation(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        Map<String, Object> phaseData = support.readMap(room.getPhaseData());
        if (Boolean.TRUE.equals(phaseData.get("knifeDecided"))) {
            return support.asPositiveInteger(phaseData.get("knifeSeat"));
        }
        Map<Integer, Long> counts = support.tonightSubmissions(room, players).stream()
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
            ballots.add(new VoteResolver.Ballot(voter.getSeatNo(), support.asPositiveInteger(support.readMap(event.getPayloadJson()).get("targetSeatNo")),
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
        String explicit = support.trimToNull(requestedWinnerCamp);
        if (explicit != null) {
            return Optional.of(new WinJudge.Verdict(explicit, "法官判定" + explicit + "胜利"));
        }
        return WinJudge.judge(support.winSeats(players), support.roomRules(room).winCondition());
    }

    private void addFinishedEvent(JudgeRoomEntity room, WinJudge.Verdict verdict, String requestedWinnerCamp, String announcement, LocalDateTime now) {
        boolean automatic = support.trimToNull(requestedWinnerCamp) == null;
        support.addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                PHASE_RESULT,
                null,
                List.of(),
                automatic ? EVENT_GAME_FINISHED_AUTO : EVENT_GAME_FINISHED,
                support.mapOf("winnerCamp", verdict.camp()),
                support.mapOf("announcement", announcement, "reason", verdict.reason()),
                VISIBILITY_SYSTEM,
                now
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitSheriff(String authorization, String roomId, WolfbookDtos.JudgeSheriffRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        tick(roomId);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局不在进行中");
        }
        JudgeRoomPlayerEntity self = requireAlivePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        String phase = support.safeText(room.getCurrentPhase(), "");
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        if (request.candidate() != null) {
            if (!PHASE_SHERIFF_SIGNUP.equals(phase)) {
                throw new ApiException(4002, "现在不是上警报名");
            }
            self.setSheriffCandidate(request.candidate());
            self.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(self);
            // 记下本人选过了（实测问题 2：没选和选了不上警要分得开）
            deletePhaseEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), EVENT_SHERIFF_SIGNUP);
            support.addEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), List.of(), EVENT_SHERIFF_SIGNUP,
                    support.mapOf("candidate", request.candidate()), Map.of(), VISIBILITY_JUDGE_ONLY, now);
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
            support.addEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), List.of(), EVENT_SHERIFF_WITHDRAW,
                    support.mapOf("seatNo", self.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
        } else if (request.targetSeatNo() != null) {
            if (!PHASE_SHERIFF_VOTE.equals(phase) && !PHASE_SHERIFF_PK.equals(phase)) {
                throw new ApiException(4002, "现在不是警长投票");
            }
            if (!support.eligibleVoters(room, players, phase).contains(self.getSeatNo())) {
                throw new ApiException(4003, PHASE_SHERIFF_PK.equals(phase) ? "PK 的玩家和退水的玩家不能投警长" : "上过警的玩家不能投警长");
            }
            Set<Integer> allowed = PHASE_SHERIFF_PK.equals(phase) ? new LinkedHashSet<>(support.pkSeats(room)) : new LinkedHashSet<>(support.onStage(players));
            if (!allowed.contains(request.targetSeatNo())) {
                throw new ApiException(4000, "只能投给警上的玩家");
            }
            deletePhaseEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), EVENT_SHERIFF_VOTE_SUBMIT);
            support.addEvent(roomId, room.getCurrentDay(), phase, self.getPlayerId(), List.of(), EVENT_SHERIFF_VOTE_SUBMIT,
                    support.mapOf("targetSeatNo", request.targetSeatNo()), Map.of(), VISIBILITY_JUDGE_ONLY, now);
        } else if (StringUtils.hasText(request.speechOrder())) {
            // 用户给定：警长不选发言顺序，系统随机警左或警右
            throw new ApiException(4003, "发言顺序由系统随机");
        } else {
            throw new ApiException(4000, "请求格式错误");
        }
        touchRoom(roomId, now);
        return assembler.buildSnapshot(openid, roomId);
    }

    /** 报名截止：没人上或全员上 → 警徽流失；只有一人 → 直接当选；否则进警上发言。法官可以用 sheriffCandidates 代勾。 */
    private void handleCloseSheriffSignup(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                          WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        requirePhase(room, PHASE_SHERIFF_SIGNUP);
        if (request.sheriffCandidates() != null) {
            throw new ApiException(4000, "上警由玩家自己选");
        }
        List<Integer> candidates = support.onStage(players);
        support.addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_SHERIFF_SIGNUP, null, List.of(), EVENT_SHERIFF_SIGNUP_CLOSED,
                support.mapOf("candidates", candidates), Map.of(), VISIBILITY_PUBLIC, now);
        openSheriffSpeech(room, players, candidates, "", now);
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SHERIFF_SIGNUP);
    }

    /** 开始警上发言：没人在警上或全员在警上 → 警徽流失；只剩一人 → 直接当选；否则排发言顺序。调用方负责带条件写回房间。 */
    private void openSheriffSpeech(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> candidates,
                                   String opening, LocalDateTime now) {
        int alive = aliveSeats(players).size();
        if (candidates.isEmpty() || candidates.size() == alive) {
            finishSheriff(room, players, candidates, null, List.of(), now);
        } else if (candidates.size() == 1) {
            finishSheriff(room, players, candidates, candidates.get(0), List.of(), now);
        } else {
            Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
            phaseData.put("speech", support.mapOf("order", JudgeDayFlow.speechOrder(candidates, room.getPlayerCount(), null, null, List.of(), random), "index", 0));
            room.setPhaseData(support.writeJson(phaseData));
            room.setCurrentPhase(PHASE_SHERIFF_SPEECH);
            room.setLatestAnnouncement(opening + "上警玩家有：" + seatsText(candidates));
            setPhaseClock(room, support.roomRules(room).sheriffSpeechSeconds(), now);
        }
    }

    private void handleStartSheriffVote(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, LocalDateTime now) {
        requirePhase(room, PHASE_SHERIFF_SPEECH);
        if (pkSpeech(room)) {
            openPkVote(room, PHASE_SHERIFF_SPEECH, PHASE_SHERIFF_PK, "PK 发言结束，警下再投一次", now);
            return;
        }
        List<Integer> remaining = support.onStage(players);
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
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SHERIFF_SPEECH);
    }

    /** 警长投票结算：唯一最高票当选；第一轮平票进 PK；PK 再平或没人投 → 警徽流失。 */
    private void handleResolveSheriffVote(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, LocalDateTime now) {
        String phase = support.safeText(room.getCurrentPhase(), "");
        if (!PHASE_SHERIFF_VOTE.equals(phase) && !PHASE_SHERIFF_PK.equals(phase)) {
            throw new ApiException(4002, "现在不是警长投票");
        }
        Map<Integer, JudgeRoomPlayerEntity> bySeat = seatMap(players);
        List<VoteResolver.Ballot> ballots = support.loadEvents(room.getRoomId()).stream()
                .filter(event -> EVENT_SHERIFF_VOTE_SUBMIT.equals(event.getActionType()) && phase.equals(event.getPhase())
                        && Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1)))
                .map(event -> {
                    JudgeRoomPlayerEntity voter = players.stream().filter(player -> player.getPlayerId().equals(event.getActorPlayerId())).findFirst().orElse(null);
                    return voter == null || voter.getSeatNo() == null ? null
                            : new VoteResolver.Ballot(voter.getSeatNo(), support.asPositiveInteger(support.readMap(event.getPayloadJson()).get("targetSeatNo")), false);
                })
                .filter(Objects::nonNull)
                .toList();
        List<Integer> allowed = PHASE_SHERIFF_PK.equals(phase) ? support.pkSeats(room) : support.onStage(players);
        VoteResolver.Outcome outcome = VoteResolver.resolve(ballots, new LinkedHashSet<>(allowed));
        if (outcome.eliminatedSeat() != null && bySeat.containsKey(outcome.eliminatedSeat())) {
            finishSheriff(room, players, support.onStage(players), outcome.eliminatedSeat(), outcome.tallies(), now);
        } else if (PHASE_SHERIFF_VOTE.equals(phase) && !outcome.tiedSeats().isEmpty()) {
            // 通用流程 4.4：平票者再各说一轮（先发言的后发言），说完自动 PK 投票
            Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
            List<Integer> spoke = phaseData.get("speech") instanceof Map<?, ?> speech
                    ? support.asIntegerList(support.castMap(speech).get("order")) : List.of();
            List<Integer> pkOrder = JudgeDayFlow.pkSpeechOrder(outcome.tiedSeats(), spoke);
            phaseData.put("pkSeats", outcome.tiedSeats());
            phaseData.put("speech", support.mapOf("order", pkOrder, "index", 0, "pk", true));
            room.setPhaseData(support.writeJson(phaseData));
            room.setCurrentPhase(PHASE_SHERIFF_SPEECH);
            room.setLatestAnnouncement(seatsText(outcome.tiedSeats()) + "平票，PK 发言后警下再投一次");
            setPhaseClock(room, support.roomRules(room).sheriffSpeechSeconds(), now);
            support.addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_SHERIFF_PK, null, List.of(), EVENT_SHERIFF_RESOLVED,
                    support.mapOf("candidates", support.onStage(players)),
                    support.mapOf("tallies", summarizeTallies(outcome), "pkSeatNos", outcome.tiedSeats()), VISIBILITY_PUBLIC, now);
        } else {
            finishSheriff(room, players, support.onStage(players), null, outcome.tallies(), now);
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
    }

    /** 警长竞选结束（当选或流失），接着公布昨夜死讯。调用方负责带条件写回房间。 */
    private void finishSheriff(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> candidates, Integer electedSeat,
                               List<VoteResolver.Tally> tallies, LocalDateTime now) {
        room.setStepDeadline(null);
        if (electedSeat != null) {
            JudgeRoomPlayerEntity sheriff = support.findSeatPlayer(players, electedSeat);
            sheriff.setSheriff(true);
            sheriff.setUpdateTime(now);
            judgeRoomPlayerMapper.updateById(sheriff);
        }
        support.addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_DAY_ANNOUNCE, null, List.of(), EVENT_SHERIFF_RESOLVED,
                support.mapOf("candidates", candidates),
                support.mapOf("tallies", summarizeTallies(new VoteResolver.Outcome(electedSeat, List.of(), tallies, List.of())),
                        "electedSeatNo", electedSeat, "lost", electedSeat == null),
                VISIBILITY_PUBLIC, now);
        enterDayAnnounce(room, players, (electedSeat == null ? "警徽流失" : electedSeat + "号当选警长") + "。", now);
    }

    /** 进入公布死讯：把上警前压着的昨夜出局写进去、判一次胜负。调用方负责带条件写回房间。 */
    private void enterDayAnnounce(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String prefix, LocalDateTime now) {
        enterDayAnnounce(room, players, prefix, now, false);
    }

    /** nightNext：今天有人警上自爆，公布死讯（和死者的待办）之后直接天黑，不进发言。 */
    private void enterDayAnnounce(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String prefix, LocalDateTime now,
                                  boolean nightNext) {
        Map<String, Object> phaseData = support.readMap(room.getPhaseData());
        List<Integer> deaths = normalizeSeatNos(support.asIntegerList(phaseData.get("pendingDeaths")));
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        markPlayersDead(players, deaths, dayNo, PHASE_NIGHT_ACTION, now);
        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
        String announcement = prefix + buildNightAnnouncement(deaths) + verdict.map(value -> "。" + value.reason()).orElse("");
        String nextPhase = verdict.isPresent() ? PHASE_RESULT : PHASE_DAY_ANNOUNCE;
        support.addEvent(room.getRoomId(), dayNo, nextPhase, null, resolveTargetPlayerIds(players, deaths), EVENT_NIGHT_RESOLVED,
                support.mapOf("deadSeatNos", deaths), support.mapOf("announcement", announcement), VISIBILITY_PUBLIC, now);
        room.setLatestAnnouncement(announcement);
        room.setCurrentPhase(nextPhase);
        if (verdict.isPresent()) {
            room.setRoomStatus(ROOM_STATUS_FINISHED);
            room.setWinnerCamp(verdict.get().camp());
            room.setPhaseData(support.writeJson(support.mapOf("verdictReason", verdict.get().reason())));
            addFinishedEvent(room, verdict.get(), null, announcement, now);
        } else {
            room.setPhaseData(support.writeJson(nightNext ? support.mapOf("nightDeaths", deaths, "nightNext", true) : support.mapOf("nightDeaths", deaths)));
            room.setStepDeadline(null);
            startPending(room, players, deaths, JudgeLastWords.Cause.NIGHT, now);
            if (PHASE_DAY_ANNOUNCE.equals(room.getCurrentPhase())) {
                // 用户给定：公布死讯后停 5 秒，自动开始发言（警上自爆那天自动天黑）
                setPhaseClock(room, JudgeDayClock.ANNOUNCE_SECONDS, now);
            }
        }
    }

    /** 公布死讯后开始发言：法官给的起点 / 警长选的顺序 / 默认（有死者死左，没有随机）。 */
    private void handleStartSpeech(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                   WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        requirePhase(room, PHASE_DAY_ANNOUNCE);
        Map<String, Object> phaseData = support.readMap(room.getPhaseData());
        if (Boolean.TRUE.equals(phaseData.get("nightNext"))) {
            // 今天有人警上自爆，直接天黑。旧版小程序的房主只有「开始发言」，当作「天黑」，免得房间卡住
            handleStartNight(room, now);
            return;
        }
        List<Integer> alive = new ArrayList<>(aliveSeats(players));
        alive.sort(Integer::compareTo);
        List<Integer> nightDeaths = support.asIntegerList(phaseData.get("nightDeaths"));
        Integer sheriffSeat = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getSheriff()) && Boolean.TRUE.equals(player.getAlive()))
                .map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null);
        // 用户给定：警长不选发言顺序，有警长时系统随机警左或警右（警长最后发言）；没警长有死者死左，否则随机
        String choice = sheriffSeat == null ? null : JudgeDayFlow.sheriffDirection(random);
        List<Integer> order = JudgeDayFlow.speechOrder(alive, room.getPlayerCount(), choice, sheriffSeat, nightDeaths, random);
        String direction = JudgeDayFlow.speechMode(choice, sheriffSeat, nightDeaths);
        room.setPhaseData(support.writeJson(support.mapOf("speech", support.mapOf("order", order, "index", 0, "direction", direction))));
        room.setCurrentPhase(PHASE_DAY_SPEECH);
        setPhaseClock(room, order.isEmpty() ? null : speechSeconds(room, PHASE_DAY_SPEECH, order.get(0), sheriffSeat), now);
        room.setLatestAnnouncement(order.isEmpty() ? "开始发言" : "请" + order.get(0) + "号开始发言");
        room.setUpdateTime(now);
        support.addEvent(room.getRoomId(), room.getCurrentDay(), PHASE_DAY_SPEECH, null, List.of(), EVENT_SPEECH_ORDER,
                support.mapOf("order", order, "startSeat", order.isEmpty() ? null : order.get(0), "direction", direction), Map.of(), VISIBILITY_PUBLIC, now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_ANNOUNCE);
    }

    /** 下一位发言：当前发言者自己点「结束发言」，或者法官跳过（无法官房主不能跳）。到点由惰性推进换人。 */
    private WolfbookDtos.JudgeRoomSnapshotView nextSpeaker(String openid, String roomId) {
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        String phase = support.safeText(room.getCurrentPhase(), "");
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || (!PHASE_DAY_SPEECH.equals(phase) && !PHASE_SHERIFF_SPEECH.equals(phase))) {
            throw new ApiException(4002, "现在不是发言阶段");
        }
        Map<String, Object> speech = speechOf(room);
        List<Integer> order = support.asIntegerList(speech.get("order"));
        int index = support.safeInt(support.asPositiveIntegerOrZero(speech.get("index")), 0);
        JudgeRoomPlayerEntity self = support.findPlayer(roomId, openid);
        boolean judge = JUDGE_MODE_OBSERVER.equals(room.getJudgeMode()) && self != null && Boolean.TRUE.equals(self.getJudgeObserver());
        boolean speaker = self != null && index < order.size() && Objects.equals(self.getSeatNo(), order.get(index));
        if (!judge && !speaker) {
            throw new ApiException(4003, "只有正在发言的人或法官能切到下一位");
        }
        advanceSpeaker(room, LocalDateTime.now(APP_ZONE));
        return assembler.buildSnapshot(openid, roomId);
    }

    private Map<String, Object> speechOf(JudgeRoomEntity room) {
        return support.readMap(room.getPhaseData()).get("speech") instanceof Map<?, ?> map
                ? new LinkedHashMap<>(support.castMap(map)) : new LinkedHashMap<>();
    }

    /** 这一轮发言是不是平票 PK 发言（说完进 PK 投票）。 */
    private boolean pkSpeech(JudgeRoomEntity room) {
        return Boolean.TRUE.equals(speechOf(room).get("pk"));
    }

    /**
     * 换下一位发言人（发言人点结束、法官跳过、到点）。所有人说完：白天进放逐投票，警上进警长投票（通用流程 4、6），
     * PK 发言说完进 PK 投票。写回房间。
     */
    private void advanceSpeaker(JudgeRoomEntity room, LocalDateTime now) {
        String phase = support.safeText(room.getCurrentPhase(), "");
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        Map<String, Object> speech = speechOf(room);
        List<Integer> order = support.asIntegerList(speech.get("order"));
        int index = support.safeInt(support.asPositiveIntegerOrZero(speech.get("index")), 0);
        int next = Math.min(index + 1, order.size());
        speech.put("index", next);
        phaseData.put("speech", speech);
        room.setPhaseData(support.writeJson(phaseData));
        if (next >= order.size()) {
            if (PHASE_DAY_SPEECH.equals(phase)) {
                handleStartVote(room, systemRequest("start_vote"), now);
            } else {
                handleStartSheriffVote(room, support.loadPlayers(room.getRoomId()), now);
            }
            return;
        }
        Integer sheriffSeat = support.loadPlayers(room.getRoomId()).stream()
                .filter(player -> Boolean.TRUE.equals(player.getSheriff()) && Boolean.TRUE.equals(player.getAlive()))
                .map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null);
        setPhaseClock(room, speechSeconds(room, phase, order.get(next), sheriffSeat), now);
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
    }

    /** PK 发言说完：进 PK 投票（平票的人留在 phase_data.pkSeats）。写回房间。 */
    private void openPkVote(JudgeRoomEntity room, String fromPhase, String votePhase, String line, LocalDateTime now) {
        room.setCurrentPhase(votePhase);
        room.setLatestAnnouncement(seatsText(support.pkSeats(room)) + line);
        setPhaseClock(room, JudgeDayClock.VOTE_SECONDS, now);
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, fromPhase);
    }

    /** 警上自爆那天公布完死讯：房主点「天黑」。 */
    private void handleStartNight(JudgeRoomEntity room, LocalDateTime now) {
        requirePhase(room, PHASE_DAY_ANNOUNCE);
        if (!Boolean.TRUE.equals(support.readMap(room.getPhaseData()).get("nightNext"))) {
            throw new ApiException(4002, "现在不能直接天黑");
        }
        enterNight(room, "天黑请闭眼", now);
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_DAY_ANNOUNCE);
    }

    /** 票型公布之后：有平票先 PK 发言（通用流程 7.4，先发言的后发言），否则进下一夜。 */
    private void handleContinue(JudgeRoomEntity room, LocalDateTime now) {
        requirePhase(room, PHASE_VOTE_RESULT);
        List<Integer> pk = support.pkSeats(room);
        if (!pk.isEmpty()) {
            List<Integer> spoke = support.loadEvents(room.getRoomId()).stream()
                    .filter(event -> EVENT_SPEECH_ORDER.equals(event.getActionType())
                            && Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1)))
                    .reduce((first, second) -> second)
                    .map(event -> support.asIntegerList(support.readMap(event.getPayloadJson()).get("order")))
                    .orElse(List.of());
            List<Integer> pkOrder = JudgeDayFlow.pkSpeechOrder(pk, spoke);
            Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
            phaseData.put("speech", support.mapOf("order", pkOrder, "index", 0, "pk", true));
            room.setPhaseData(support.writeJson(phaseData));
            room.setCurrentPhase(PHASE_DAY_SPEECH);
            room.setLatestAnnouncement(seatsText(pk) + "PK 发言，请" + pkOrder.get(0) + "号开始");
            Integer sheriffSeat = support.loadPlayers(room.getRoomId()).stream()
                    .filter(player -> Boolean.TRUE.equals(player.getSheriff()) && Boolean.TRUE.equals(player.getAlive()))
                    .map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null);
            setPhaseClock(room, speechSeconds(room, PHASE_DAY_SPEECH, pkOrder.get(0), sheriffSeat), now);
        } else {
            enterNight(room, "天黑请闭眼", now);
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_VOTE_RESULT);
    }

    /** 进入下一夜：天数 +1、写公告、排好夜间步骤。调用方负责带条件写回房间。 */
    private void enterNight(JudgeRoomEntity room, String announcement, LocalDateTime now) {
        int nextDay = support.safeInt(room.getCurrentDay(), 1) + 1;
        room.setCurrentDay(nextDay);
        room.setCurrentPhase(PHASE_NIGHT_ACTION);
        room.setLatestAnnouncement(announcement);
        openNight(room, nextDay, now);
    }

    private void requirePhase(JudgeRoomEntity room, String phase) {
        if (!phase.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "房间不在这个阶段");
        }
    }

    private Map<Integer, JudgeRoomPlayerEntity> seatMap(List<JudgeRoomPlayerEntity> players) {
        Map<Integer, JudgeRoomPlayerEntity> map = new LinkedHashMap<>();
        players.stream().filter(player -> player.getSeatNo() != null).forEach(player -> map.put(player.getSeatNo(), player));
        return map;
    }

    private String seatsText(List<Integer> seats) {
        return seats.stream().map(seat -> seat + "号").collect(Collectors.joining("、"));
    }

    /**
     * 有人出局后压入待办，处理完回到原来的阶段。先后由 {@link JudgeLastWords#arrange} 排：技能（每个出局者一条询问，
     * 没技能的人只有「确认」，倒计时一样长，别人看不出谁有技能）→ 遗言（多人随机）→ 移交警徽。
     * 已经在待办里时，当前这条不动，新的出局者按三段插进去（连锁）。每条轮到它时才开始计时。
     */
    private void startPending(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> outSeats,
                              JudgeLastWords.Cause cause, LocalDateTime now) {
        if (outSeats.isEmpty()) {
            return;
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = support.pendingList(phaseData);
        boolean inPending = PHASE_SKILL_PENDING.equals(room.getCurrentPhase()) && !pending.isEmpty();
        if (!inPending) {
            pending = new ArrayList<>();
        }
        List<Map<String, Object>> arranged = arrangePending(pending, inPending ? 1 : 0, players, outSeats, cause, room);
        if (arranged.isEmpty()) {
            return;
        }
        if (!inPending) {
            phaseData.put("resumePhase", room.getCurrentPhase());
            // 夜里点杀之类：待办处理完回到原阶段时，原来的倒计时照常继续
            if (room.getStepDeadline() != null) {
                phaseData.put("resumeDeadline", room.getStepDeadline().toString());
            }
        }
        room.setCurrentPhase(PHASE_SKILL_PENDING);
        beginPendingHead(room, phaseData, arranged, now);
    }

    /** 按 JudgeLastWords 的三段把出局者排进待办；前 keep 条（正在进行的那条，带着截止时间）原样保留。 */
    private List<Map<String, Object>> arrangePending(List<Map<String, Object>> pending, int keep, List<JudgeRoomPlayerEntity> players,
                                                     List<Integer> outSeats, JudgeLastWords.Cause cause, JudgeRoomEntity room) {
        List<JudgeLastWords.Out> outs = outSeats.stream()
                .map(seat -> {
                    JudgeRoomPlayerEntity player = support.findSeatPlayer(players, seat);
                    return new JudgeLastWords.Out(seat, cause, player != null && Boolean.TRUE.equals(player.getSheriff()));
                })
                .toList();
        List<JudgeLastWords.Item> items = pending.stream().map(this::pendingItem).toList();
        List<JudgeLastWords.Item> arranged = JudgeLastWords.arrange(items, keep, outs, support.safeInt(room.getCurrentDay(), 1), random);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < arranged.size(); i++) {
            result.add(i < keep && i < pending.size() ? pending.get(i) : pendingEntry(arranged.get(i)));
        }
        return result;
    }

    /** 队首这条开始计时（入队时只记时长，轮到才写截止时间）；轮到遗言时公告「请 X 号留遗言」。写回 phase_data 和截止时间。 */
    private void beginPendingHead(JudgeRoomEntity room, Map<String, Object> phaseData, List<Map<String, Object>> pending, LocalDateTime now) {
        Map<String, Object> head = pending.get(0);
        if (support.parseDeadline(head) == null) {
            int seconds = support.safeInt(support.asPositiveInteger(head.get("seconds")), JudgeLastWords.SKILL_ASK_SECONDS);
            head.put("deadline", now.plusSeconds(seconds).toString());
            if (PENDING_LAST_WORDS.equals(support.asString(head.get("kind")))) {
                // 记下原来的公告（死讯、自爆、开枪），遗言结束后恢复
                head.put("announcementBefore", support.safeText(room.getLatestAnnouncement(), ""));
                room.setLatestAnnouncement("请" + support.safeInt(support.asPositiveInteger(head.get("seat")), 0) + "号留遗言");
            }
        }
        phaseData.put("pending", pending);
        room.setPhaseData(support.writeJson(phaseData));
        room.setStepDeadline(support.parseDeadline(head));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView submitSkill(String authorization, String roomId, WolfbookDtos.JudgeSkillRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        tick(roomId);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_SKILL_PENDING.equals(room.getCurrentPhase())) {
            throw new ApiException(4002, "现在没有待处理的技能");
        }
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        JudgeRoomPlayerEntity self = support.findPlayer(roomId, openid);
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = support.pendingList(phaseData);
        Map<String, Object> current = pending.get(0);
        int seat = support.safeInt(support.asPositiveInteger(current.get("seat")), 0);
        // 跳过别人的遗言只有法官能做（无法官房主只有暂停和提前结束）
        boolean judgeSkipsLastWords = PENDING_LAST_WORDS.equals(support.asString(current.get("kind")))
                && JUDGE_MODE_OBSERVER.equals(room.getJudgeMode()) && self != null && Boolean.TRUE.equals(self.getJudgeObserver());
        if (!judgeSkipsLastWords && (self == null || !Objects.equals(self.getSeatNo(), seat))) {
            throw new ApiException(4003, "现在不是你的技能");
        }
        resolvePending(room, players, support.safeText(support.trimToNull(request.decision()), "skip"), request.targetSeatNo(), LocalDateTime.now(APP_ZONE));
        return assembler.buildSnapshot(openid, roomId);
    }

    /** 处理当前这条待办（玩家 / 法官提交，或者超时当作 skip），处理完写回房间。 */
    private void resolvePending(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String decision, Integer targetSeatNo, LocalDateTime now) {
        String roomId = room.getRoomId();
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        List<Map<String, Object>> pending = support.pendingList(phaseData);
        if (pending.isEmpty()) {
            throw new ApiException(4002, "现在没有待处理的技能");
        }
        Map<String, Object> current = pending.get(0);
        int seat = support.safeInt(support.asPositiveInteger(current.get("seat")), 0);
        JudgeRoomPlayerEntity actor = support.findSeatPlayer(players, seat);
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        String kind = support.asString(current.get("kind"));
        pending.remove(0);
        if (PENDING_SKILL_ASK.equals(kind)) {
            if ("use".equals(decision)) {
                if (!support.skillOptions(actor).contains("use")) {
                    throw new ApiException(4000, "你没有可以发动的技能");
                }
                pending = new ArrayList<>(JudgeLastWords.withShot(pending.stream().map(this::pendingItem).toList(), seat).stream()
                        .map(this::pendingEntry).toList());
            }
        } else if (PENDING_HUNTER_SHOT.equals(kind) && "use".equals(decision)) {
            JudgeRoomPlayerEntity target = requireAliveSeat(players, targetSeatNo);
            markPlayersDead(players, List.of(target.getSeatNo()), dayNo, PHASE_SKILL_PENDING, now);
            support.addEvent(roomId, dayNo, PHASE_SKILL_PENDING, actor.getPlayerId(), List.of(target.getPlayerId()), EVENT_HUNTER_SHOT,
                    support.mapOf("actorSeatNo", seat, "targetSeatNo", target.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
            // 猎人、狼王同一句公告：场上只能从发言判断是谁开的枪
            room.setLatestAnnouncement(seat + "号发动技能，带走了" + target.getSeatNo() + "号");
            Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
            if (verdict.isPresent()) {
                finishByVerdict(room, verdict.get(), room.getLatestAnnouncement() + "。" + verdict.get().reason(), now);
                room.setUpdateTime(now);
                support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SKILL_PENDING);
                return;
            }
            // 被带走的人：技能询问排在技能段最前面（连锁），白天被带走有遗言，是警长最后移交警徽
            pending = arrangePending(pending, 0, players, List.of(target.getSeatNo()), JudgeLastWords.Cause.SHOT, room);
        } else if (PENDING_LAST_WORDS.equals(kind)) {
            // 本人点「结束遗言」、房主跳过或到点：公告恢复成遗言开始前的那句
            String before = support.asString(current.get("announcementBefore"));
            if (before != null) {
                room.setLatestAnnouncement(before);
            }
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
            support.addEvent(roomId, dayNo, PHASE_SKILL_PENDING, actor.getPlayerId(), heir == null ? List.of() : List.of(heir.getPlayerId()), EVENT_BADGE_PASSED,
                    support.mapOf("actorSeatNo", seat, "targetSeatNo", heir == null ? null : heir.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
            room.setLatestAnnouncement(heir == null ? seat + "号撕掉了警徽" : seat + "号把警徽移交给" + heir.getSeatNo() + "号");
        }
        if (pending.isEmpty() && Boolean.TRUE.equals(phaseData.get("nightAfterPending"))) {
            // 白天发言时自爆的警长移交完警徽：直接天黑
            enterNight(room, support.safeText(room.getLatestAnnouncement(), "") + "，天黑请闭眼", now);
            room.setUpdateTime(now);
            support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SKILL_PENDING);
            return;
        }
        if (pending.isEmpty()) {
            String resume = support.safeText(support.asString(phaseData.get("resumePhase")), PHASE_DAY_ANNOUNCE);
            room.setCurrentPhase(resume);
            if (PHASE_DAY_ANNOUNCE.equals(resume)) {
                setPhaseClock(room, JudgeDayClock.ANNOUNCE_SECONDS, now);
            } else if (PHASE_VOTE_RESULT.equals(resume)) {
                setPhaseClock(room, JudgeDayClock.RESULT_SECONDS, now);
            } else {
                room.setStepDeadline(support.parseDeadline(Map.of("deadline", support.safeText(support.asString(phaseData.get("resumeDeadline")), ""))));
            }
            phaseData.remove("pending");
            phaseData.remove("resumePhase");
            phaseData.remove("resumeDeadline");
            room.setPhaseData(support.writeJson(phaseData));
        } else {
            beginPendingHead(room, phaseData, pending, now);
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_SKILL_PENDING);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WolfbookDtos.JudgeRoomSnapshotView selfDestruct(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        tick(roomId);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        requireNotPaused(room);
        JudgeRoomPlayerEntity self = support.findPlayer(roomId, openid);
        if (!support.canSelfDestruct(room, self)) {
            throw new ApiException(4003, "你现在不能自爆");
        }
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        JudgeRoomPlayerEntity actor = support.findSeatPlayer(players, self.getSeatNo());
        String phase = room.getCurrentPhase();
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        int seat = actor.getSeatNo();
        if (PHASE_SHERIFF_SPEECH.equals(phase)) {
            explodeOnStage(room, players, actor, now);
            room.setUpdateTime(now);
            support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
            return assembler.buildSnapshot(openid, roomId);
        }
        // 自爆的狼当场出局（2026-11-12 用户改定），不参与当晚点刀；最后一只狼自爆当场结束
        markPlayersDead(players, List.of(seat), dayNo, phase, now);
        support.addEvent(roomId, dayNo, phase, actor.getPlayerId(), List.of(), EVENT_SELF_DESTRUCT,
                support.mapOf("actorSeatNo", seat, "duringSheriff", false), Map.of(), VISIBILITY_PUBLIC, now);
        String announcement = seat + "号自爆";
        room.setStepDeadline(null);
        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
        if (verdict.isPresent()) {
            finishByVerdict(room, verdict.get(), announcement + "。" + verdict.get().reason(), now);
        } else {
            // 自爆不问技能：60 秒遗言（通用流程 12），是警长再移交警徽，处理完直接天黑
            room.setLatestAnnouncement(announcement);
            room.setPhaseData(support.writeJson(support.mapOf("nightAfterPending", true)));
            startPending(room, players, List.of(seat), JudgeLastWords.Cause.SELF_DESTRUCT, now);
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
        return assembler.buildSnapshot(openid, roomId);
    }

    /**
     * 警上发言时自爆（双爆吞警徽）：第一次 → 竞选推迟到明天；延期的那次竞选里再爆 → 警徽流失。
     * 都是先公布昨夜死讯（死者照常处理技能、遗言），停在公布死讯，房主点「天黑」入夜。
     * 昨夜死讯没公布前死者在库里还活着，自爆的人要是在昨夜死者里，只算他自爆这一次出局。
     */
    private void explodeOnStage(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, JudgeRoomPlayerEntity actor, LocalDateTime now) {
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        int seat = actor.getSeatNo();
        boolean second = sheriffDeferred(room.getRoomId());
        markPlayersDead(players, List.of(seat), dayNo, PHASE_SHERIFF_SPEECH, now);
        support.addEvent(room.getRoomId(), dayNo, PHASE_SHERIFF_SPEECH, actor.getPlayerId(), List.of(), EVENT_SELF_DESTRUCT,
                support.mapOf("actorSeatNo", seat, "duringSheriff", true), Map.of(), VISIBILITY_PUBLIC, now);
        String prefix;
        if (second) {
            support.addEvent(room.getRoomId(), dayNo, PHASE_DAY_ANNOUNCE, null, List.of(), EVENT_SHERIFF_RESOLVED,
                    support.mapOf("candidates", support.onStage(players)),
                    support.mapOf("tallies", List.of(), "electedSeatNo", null, "lost", true),
                    VISIBILITY_PUBLIC, now);
            prefix = seat + "号自爆，警徽流失。";
        } else {
            prefix = seat + "号自爆，警长竞选推迟到明天。";
        }
        // 昨夜死讯没公布前死者在库里还活着：自爆的人要是在昨夜死者里，只算他自爆这一次出局
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        List<Integer> pendingDeaths = new ArrayList<>(support.asIntegerList(phaseData.get("pendingDeaths")));
        pendingDeaths.remove(Integer.valueOf(seat));
        phaseData.put("pendingDeaths", pendingDeaths);
        room.setPhaseData(support.writeJson(phaseData));
        room.setStepDeadline(null);
        enterDayAnnounce(room, players, prefix, now, true);
        if (ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            // 自爆的狼 60 秒遗言，和昨夜死者的待办排在同一条队列里（技能 → 遗言随机 → 警徽）
            startPending(room, players, List.of(seat), JudgeLastWords.Cause.SELF_DESTRUCT, now);
        }
    }

    /** 竞选延到了今天：有过警上自爆、还没有竞选结果（当选或流失）。 */
    private boolean sheriffDeferred(String roomId) {
        List<JudgeActionEventEntity> events = support.loadEvents(roomId);
        boolean explodedOnStage = events.stream().anyMatch(event -> EVENT_SELF_DESTRUCT.equals(event.getActionType())
                && Boolean.TRUE.equals(support.readMap(event.getPayloadJson()).get("duringSheriff")));
        boolean resolved = events.stream().anyMatch(event -> EVENT_SHERIFF_RESOLVED.equals(event.getActionType()));
        return explodedOnStage && !resolved;
    }

    private void requireNotPaused(JudgeRoomEntity room) {
        if (ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && support.isPaused(room)) {
            throw new ApiException(4002, "对局暂停中");
        }
    }

    /** 暂停：记下暂停时刻；惰性推进跳过暂停中的房间，所有提交都先等着。 */
    private void handlePause(JudgeRoomEntity room, LocalDateTime now) {
        if (support.isPaused(room)) {
            throw new ApiException(4002, "已经暂停了");
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        phaseData.put("pausedAt", now.toString());
        room.setPhaseData(support.writeJson(phaseData));
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, room.getCurrentPhase(), room.getNightStep());
        support.addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), null, List.of(), EVENT_JUDGE_PAUSED,
                Map.of(), Map.of(), VISIBILITY_PUBLIC, now);
    }

    /** 继续：当前截止时间、每条待办的截止时间、待办结束后要恢复的截止时间，全部顺延暂停了多久。 */
    private void handleResume(JudgeRoomEntity room, LocalDateTime now) {
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        LocalDateTime pausedAt = support.parseDeadline(Map.of("deadline", support.safeText(support.asString(phaseData.get("pausedAt")), "")));
        if (pausedAt == null) {
            throw new ApiException(4002, "现在没有暂停");
        }
        java.time.Duration paused = java.time.Duration.between(pausedAt, now);
        if (room.getStepDeadline() != null) {
            room.setStepDeadline(room.getStepDeadline().plus(paused));
        }
        List<Map<String, Object>> pending = support.pendingList(phaseData);
        for (Map<String, Object> entry : pending) {
            LocalDateTime deadline = support.parseDeadline(entry);
            if (deadline != null) {
                entry.put("deadline", deadline.plus(paused).toString());
            }
        }
        if (!pending.isEmpty()) {
            phaseData.put("pending", pending);
        }
        LocalDateTime resumeDeadline = support.parseDeadline(Map.of("deadline", support.safeText(support.asString(phaseData.get("resumeDeadline")), "")));
        if (resumeDeadline != null) {
            phaseData.put("resumeDeadline", resumeDeadline.plus(paused).toString());
        }
        LocalDateTime wolfFull = support.parseDeadline(Map.of("deadline", support.safeText(support.asString(phaseData.get(WOLF_FULL_DEADLINE)), "")));
        if (wolfFull != null) {
            phaseData.put(WOLF_FULL_DEADLINE, wolfFull.plus(paused).toString());
        }
        phaseData.remove("pausedAt");
        room.setPhaseData(support.writeJson(phaseData));
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, room.getCurrentPhase(), room.getNightStep());
        support.addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), null, List.of(), EVENT_JUDGE_RESUMED,
                Map.of(), Map.of(), VISIBILITY_PUBLIC, now);
    }

    /** 法官点杀：违规的玩家直接出局，公告注明法官判罚，照常问技能（点杀的猎人 / 狼王不能开枪）。 */
    private void handleForceOut(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                WolfbookDtos.JudgeRoomAdvanceRequest request, LocalDateTime now) {
        JudgeRoomPlayerEntity target = requireAliveSeat(players, request.forceOutSeatNo());
        String phase = support.safeText(room.getCurrentPhase(), "");
        int dayNo = support.safeInt(room.getCurrentDay(), 1);
        Map<String, Object> status = new LinkedHashMap<>(support.readMap(target.getStatusJson()));
        status.put("canShoot", false);
        target.setStatusJson(support.writeJson(status));
        markPlayersDead(players, List.of(target.getSeatNo()), dayNo, phase, now);
        support.addEvent(room.getRoomId(), dayNo, phase, null, List.of(target.getPlayerId()), EVENT_FORCE_OUT,
                support.mapOf("targetSeatNo", target.getSeatNo()), Map.of(), VISIBILITY_PUBLIC, now);
        String announcement = target.getSeatNo() + "号被法官判罚出局";
        room.setLatestAnnouncement(announcement);
        Optional<WinJudge.Verdict> verdict = verdictOf(room, players, null);
        if (verdict.isPresent()) {
            finishByVerdict(room, verdict.get(), announcement + "。" + verdict.get().reason(), now);
        } else {
            startPending(room, players, List.of(target.getSeatNo()), JudgeLastWords.Cause.FORCE_OUT, now);
        }
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
    }

    /** 自动判出胜负：直接结束，待办作废。调用方负责带条件写回房间。 */
    private void finishByVerdict(JudgeRoomEntity room, WinJudge.Verdict verdict, String announcement, LocalDateTime now) {
        room.setRoomStatus(ROOM_STATUS_FINISHED);
        room.setCurrentPhase(PHASE_RESULT);
        room.setWinnerCamp(verdict.camp());
        room.setLatestAnnouncement(announcement);
        room.setNightStep(null);
        room.setStepDeadline(null);
        room.setPhaseData(support.writeJson(support.mapOf("verdictReason", verdict.reason())));
        addFinishedEvent(room, verdict, null, announcement, now);
    }

    /** 新入队的一条：只记时长，轮到它时由 beginPendingHead 写截止时间。 */
    private Map<String, Object> pendingEntry(JudgeLastWords.Item item) {
        return support.mapOf("seat", item.seat(), "kind", item.kind(), "seconds", item.seconds());
    }

    /** phase_data 里的一条转成纯逻辑用的 Item（升级前入队的条目没有 seconds，按种类补）。 */
    private JudgeLastWords.Item pendingItem(Map<String, Object> entry) {
        String kind = support.asString(entry.get("kind"));
        Integer seconds = support.asPositiveInteger(entry.get("seconds"));
        int fallback = PENDING_HUNTER_SHOT.equals(kind) ? JudgeLastWords.HUNTER_SHOT_SECONDS
                : PENDING_BADGE.equals(kind) ? JudgeLastWords.BADGE_SECONDS
                : PENDING_LAST_WORDS.equals(kind) ? JudgeLastWords.LAST_WORDS_SECONDS : JudgeLastWords.SKILL_ASK_SECONDS;
        return new JudgeLastWords.Item(support.safeInt(support.asPositiveInteger(entry.get("seat")), 0), kind, seconds == null ? fallback : seconds);
    }

    /** 被放逐的是白痴：翻牌公开身份（照样出局，出局由调用方处理）。返回 true 表示他是白痴、翻了牌。 */
    private boolean revealIdiot(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, Integer seat, LocalDateTime now) {
        JudgeRoomPlayerEntity player = seat == null ? null : support.findSeatPlayer(players, seat);
        if (player == null || support.mechanicOf(player).filter(mechanic -> mechanic == Mechanic.IDIOT).isEmpty()) {
            return false;
        }
        support.addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), player.getPlayerId(), List.of(), EVENT_IDIOT_REVEALED,
                support.mapOf("actorSeatNo", seat), Map.of(), VISIBILITY_PUBLIC, now);
        return true;
    }

    /** 推进到期的夜间步骤和出局待办。独立事务，另一个请求先推进了就整体回滚、静默忽略。 */
    private void tick(String roomId) {
        try {
            tickTransaction.executeWithoutResult(status -> tickInTransaction(roomId));
        } catch (ApiException exception) {
            if (exception.getCode() == 4002) {
                // 并发下别的请求已经推进过这一步
                return;
            }
            logTickFailure(roomId, exception);
        } catch (RuntimeException exception) {
            logTickFailure(roomId, exception);
        }
    }

    /** 推进失败不打断这次请求（玩家照样拿到快照），但要留下能查的日志：房间、阶段、夜间步骤。 */
    private void logTickFailure(String roomId, RuntimeException exception) {
        JudgeRoomEntity room = judgeRoomMapper.selectById(roomId);
        log.error("电子法官推进失败 room={} status={} phase={} day={} nightStep={}: {}", roomId,
                room == null ? null : room.getRoomStatus(), room == null ? null : room.getCurrentPhase(),
                room == null ? null : room.getCurrentDay(), room == null ? null : room.getNightStep(), exception.getMessage(), exception);
    }

    private void tickInTransaction(String roomId) {
        JudgeRoomEntity room = support.lockRoom(roomId);
        if (room == null || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus())
                || support.isPaused(room)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        String phase = support.safeText(room.getCurrentPhase(), "");
        if (PHASE_NIGHT_ACTION.equals(phase) && room.getNightStep() != null) {
            List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
            List<NightPlan.NightStep> steps = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1));
            if (room.getNightStep() >= steps.size()) {
                return;
            }
            // 交了也不提前切步；狼人那一步全员确定后截止时间已经缩到 5 秒后（syncWolfConfirm）
            if (JudgeNightFlow.shouldClose(room.getStepDeadline(), now)) {
                handleNextStep(room, players, new WolfbookDtos.JudgeRoomAdvanceRequest("next_step", null, null, null, null,
                        room.getNightStep(), null, null, null, null), now);
            }
        } else if (PHASE_SKILL_PENDING.equals(phase) && room.getStepDeadline() != null && now.isAfter(room.getStepDeadline())) {
            resolvePending(room, support.loadPlayers(roomId), "skip", null, now);
        } else if (Set.of(PHASE_EXILE_VOTE, PHASE_VOTE_PK, PHASE_SHERIFF_VOTE, PHASE_SHERIFF_PK).contains(phase)) {
            // 全员投完或到点：没投的人算弃票
            List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
            if (JudgeDayClock.shouldCloseVote(support.eligibleVoters(room, players, phase), support.votedSeats(room, players, phase),
                    room.getStepDeadline(), now)) {
                if (PHASE_EXILE_VOTE.equals(phase) || PHASE_VOTE_PK.equals(phase)) {
                    handleResolveVote(room, players, systemRequest("resolve_vote"), now);
                } else {
                    handleResolveSheriffVote(room, players, now);
                }
            }
        } else if (PHASE_SHERIFF_SIGNUP.equals(phase) && room.getStepDeadline() != null && now.isAfter(room.getStepDeadline())) {
            // 报名到点：没选的人算不上警
            handleCloseSheriffSignup(room, support.loadPlayers(roomId), systemRequest("close_sheriff_signup"), now);
        } else if (PHASE_DAY_SPEECH.equals(phase) || PHASE_SHERIFF_SPEECH.equals(phase)) {
            // 发言按人计时，到点自动换下一位（用户给定）
            if (room.getStepDeadline() != null && now.isAfter(room.getStepDeadline())) {
                advanceSpeaker(room, now);
            }
        } else if (PHASE_DAY_ANNOUNCE.equals(phase) || PHASE_VOTE_RESULT.equals(phase)) {
            if (room.getStepDeadline() == null) {
                // 升级前停在这里的房间没有截止时间：从现在开始计
                setPhaseClock(room, PHASE_DAY_ANNOUNCE.equals(phase) ? JudgeDayClock.ANNOUNCE_SECONDS : JudgeDayClock.RESULT_SECONDS, now);
                room.setUpdateTime(now);
                support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, phase);
            } else if (now.isAfter(room.getStepDeadline())) {
                if (PHASE_VOTE_RESULT.equals(phase)) {
                    handleContinue(room, now);
                } else if (Boolean.TRUE.equals(support.readMap(room.getPhaseData()).get("nightNext"))) {
                    handleStartNight(room, now);
                } else {
                    handleStartSpeech(room, support.loadPlayers(roomId), systemRequest("start_speech"), now);
                }
            }
        }
    }

    /**
     * 推进动作的权限（用户给定：白天黑夜都由系统按倒计时推进）：
     * 无法官房间的房主只有暂停 / 继续和提前结束（记流局）；有法官时法官有暂停 / 继续、点杀、提前结束，
     * 还可以不等倒计时提前推进白天的每一步。夜里的步骤谁都不能手动推进。
     */
    private JudgeRoomEntity requireAdvancePermission(String roomId, String openid, String action) {
        JudgeRoomEntity room = support.getRoom(roomId);
        if ("next_step".equals(action) || "resolve_night".equals(action)) {
            throw new ApiException(4003, "夜里由系统推进");
        }
        boolean observer = JUDGE_MODE_OBSERVER.equals(room.getJudgeMode());
        if (observer) {
            return requireJudgeRoom(roomId, openid);
        }
        if ("force_out".equals(action)) {
            throw new ApiException(4003, "当前只有法官可以执行此操作");
        }
        support.requireHost(roomId, openid);
        if (!Set.of("pause", "resume", "finish").contains(action)) {
            throw new ApiException(4003, "无法官房间由系统自动推进");
        }
        return room;
    }

    /** 新流程房间才计时；seconds 为空表示这一阶段不计时。 */
    private void setPhaseClock(JudgeRoomEntity room, Integer seconds, LocalDateTime now) {
        room.setStepDeadline(seconds != null ? now.plusSeconds(seconds) : null);
    }

    /** 这位发言者的时长：警上发言和警长发言用警长时长，其余用每人发言时长。 */
    private int speechSeconds(JudgeRoomEntity room, String phase, Integer seat, Integer sheriffSeat) {
        JudgeRules rules = support.roomRules(room);
        boolean sheriffTime = PHASE_SHERIFF_SPEECH.equals(phase) || (seat != null && seat.equals(sheriffSeat));
        return sheriffTime ? rules.sheriffSpeechSeconds() : rules.speechSeconds();
    }

    /** 惰性推进替房主调用结算时用的空请求。 */
    private WolfbookDtos.JudgeRoomAdvanceRequest systemRequest(String action) {
        return new WolfbookDtos.JudgeRoomAdvanceRequest(action, null, null, null, null, null, null, null, null, null);
    }

    private JudgeRoomEntity requireJudgeRoom(String roomId, String openid) {
        JudgeRoomEntity room = support.getRoom(roomId);
        JudgeRoomPlayerEntity self = support.findPlayer(roomId, openid);
        if (self == null || !Boolean.TRUE.equals(self.getJudgeObserver())) {
            throw new ApiException(4003, "当前只有法官可以执行此操作");
        }
        return room;
    }

    private JudgeRoomPlayerEntity requireAlivePlayingPlayer(String roomId, String openid) {
        JudgeRoomPlayerEntity player = support.requirePlayingPlayer(roomId, openid);
        if (!Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4002, "你当前已出局，无法继续操作");
        }
        return player;
    }

    /** 法官/夜间操作引用的座位必须是在座且存活的玩家，否则直接拒绝，避免公告里出现不存在的座位。 */
    private JudgeRoomPlayerEntity requireAliveSeat(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        JudgeRoomPlayerEntity player = support.findSeatPlayer(players, seatNo);
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

    /** 只更新 update_time：玩家提交不改房间状态，别把这次请求读到的整行写回去（惰性推进可能刚推进过）。 */
    private void touchRoom(String roomId, LocalDateTime now) {
        judgeRoomMapper.update(null, new LambdaUpdateWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, roomId)
                .set(JudgeRoomEntity::getUpdateTime, now));
    }

    private JudgeRoomPlayerEntity requireAliveSeatPlayer(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
        JudgeRoomPlayerEntity player = support.findSeatPlayer(players, seatNo);
        if (player == null || !Boolean.TRUE.equals(player.getAlive())) {
            throw new ApiException(4002, "目标玩家当前不可选");
        }
        return player;
    }

    /** 新流程房间按票型和规则结算：不收房主指定的出局者、胜方、发言起点和方向（无法官房主只是普通玩家，有法官也不改判）。 */
    private void rejectDictatedResults(JudgeRoomEntity room, String action, WolfbookDtos.JudgeRoomAdvanceRequest request) {
        if ("resolve_vote".equals(action) && (request.eliminatedSeatNo() != null || support.trimToNull(request.winnerCamp()) != null)) {
            throw new ApiException(4000, "新流程按票型结算，不能指定结果");
        }
        if ("finish".equals(action) && support.trimToNull(request.winnerCamp()) != null) {
            throw new ApiException(4000, "只能强制结束（记流局），胜负由系统判定");
        }
        if ("start_speech".equals(action) && (request.speechStartSeat() != null || support.trimToNull(request.speechDirection()) != null)) {
            throw new ApiException(4000, "发言顺序由系统和警长决定");
        }
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

    private NightKind currentStepKind(JudgeRoomEntity room) {
        if (room.getNightStep() == null) {
            return null;
        }
        List<NightPlan.NightStep> steps = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1));
        return room.getNightStep() < steps.size() ? steps.get(room.getNightStep()).kind() : null;
    }

    /** 夜间提交记下是哪一步交的（带刀机械狼一晚有两步的提交）。 */
    private Integer stepIndexForStorage(JudgeRoomEntity room) {
        return room.getNightStep();
    }

    /** 换掉本人这一步之前的提交（只换同一步的；没记步骤的老数据也换掉）。 */
    private void deleteStepSubmission(JudgeRoomEntity room, JudgeRoomPlayerEntity player) {
        judgeActionEventMapper.selectList(new LambdaQueryWrapper<JudgeActionEventEntity>()
                        .eq(JudgeActionEventEntity::getRoomId, room.getRoomId())
                        .eq(JudgeActionEventEntity::getDayNo, room.getCurrentDay())
                        .eq(JudgeActionEventEntity::getPhase, room.getCurrentPhase())
                        .eq(JudgeActionEventEntity::getActorPlayerId, player.getPlayerId())
                        .eq(JudgeActionEventEntity::getActionType, EVENT_NIGHT_SUBMIT))
                .stream()
                .filter(event -> {
                    Integer index = support.stepIndexOf(support.readMap(event.getPayloadJson()));
                    return index == null || index.equals(room.getNightStep());
                })
                .forEach(event -> judgeActionEventMapper.deleteById(event.getEventId()));
    }

    /** 狼人点「确定」（要先选了刀口或空刀）或「重新选择」。 */
    private void confirmWolfPick(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, JudgeRoomPlayerEntity player, boolean confirm) {
        NightPlan.NightStep step = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1)).get(room.getNightStep());
        if (!support.stepHolderSeats(players, step).contains(player.getSeatNo())) {
            throw new ApiException(4003, "现在还没轮到你行动");
        }
        JudgeActionEventEntity mine = support.stepSubmitEvent(room, support.loadEvents(room.getRoomId()), player.getPlayerId());
        if (mine == null) {
            if (confirm) {
                throw new ApiException(4000, "请先选择刀口或空刀");
            }
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(support.readMap(mine.getPayloadJson()));
        payload.put("confirmed", confirm);
        mine.setPayloadJson(support.writeJson(payload));
        judgeActionEventMapper.updateById(mine);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        touchRoom(room.getRoomId(), now);
        syncWolfConfirm(room, players, now);
    }

    /** 狼人那一步：所有存活持有者都确定了就把截止时间缩到 5 秒后；有人重新选择 / 改了刀口就恢复原来的总时长。 */
    private void syncWolfConfirm(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, LocalDateTime now) {
        if (currentStepKind(room) != NightKind.WOLF || room.getStepDeadline() == null) {
            return;
        }
        NightPlan.NightStep step = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1)).get(room.getNightStep());
        boolean all = JudgeNightFlow.allConfirmed(support.stepHolderSeats(players, step), support.confirmedSeats(room, players, support.loadEvents(room.getRoomId())));
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        LocalDateTime full = support.parseDeadline(Map.of("deadline", support.safeText(support.asString(phaseData.get(WOLF_FULL_DEADLINE)), "")));
        if (all && full == null) {
            phaseData.put(WOLF_FULL_DEADLINE, room.getStepDeadline().toString());
            room.setStepDeadline(JudgeNightFlow.confirmedDeadline(room.getStepDeadline(), now));
        } else if (!all && full != null) {
            phaseData.remove(WOLF_FULL_DEADLINE);
            room.setStepDeadline(full);
        } else {
            return;
        }
        room.setPhaseData(support.writeJson(phaseData));
        room.setUpdateTime(now);
        support.updateRoomGuarded(room, ROOM_STATUS_PLAYING, PHASE_NIGHT_ACTION, room.getNightStep());
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
                .map(tally -> support.mapOf("targetSeatNo", tally.targetSeat(), "voteCount", tally.voterSeats().size(),
                        "weight", tally.weight(), "voterSeatNos", tally.voterSeats()))
                .toList();
    }

    private List<Map<String, Object>> summarizeTallies(List<WolfbookDtos.JudgeVoteTallyView> tallies) {
        if (tallies == null || tallies.isEmpty()) {
            return List.of();
        }
        return tallies.stream()
                .map(tally -> support.mapOf("targetSeatNo", tally.targetSeatNo(), "voteCount", tally.voteCount(), "voterSeatNos", tally.voterSeatNos()))
                .toList();
    }

    private String requireAnnouncement(String announcement) {
        if (!StringUtils.hasText(announcement)) {
            throw new ApiException(4000, "请输入公告内容");
        }
        return announcement.trim();
    }

    private String normalizeAction(String action) {
        return support.safeText(action, "").toLowerCase(Locale.ROOT);
    }

    private List<Integer> normalizeSeatNos(Collection<Integer> seatNos) {
        if (seatNos == null || seatNos.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer seatNo : seatNos) {
            Integer value = support.normalizeSeatNo(seatNo);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

}
