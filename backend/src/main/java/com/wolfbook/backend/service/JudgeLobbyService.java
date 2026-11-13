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

/** 法官房间的大厅：建房、最近的房间、按顺序入座、换座、准备、开局前改本局规则。开局之后的流程在 JudgeService。 */
@Service
public class JudgeLobbyService {

    private final JudgeRoomSupport support;
    private final JudgeSnapshotAssembler assembler;
    private final UserService userService;
    private final BoardService boardService;
    private final JudgeRoomMapper judgeRoomMapper;
    private final JudgeRoomPlayerMapper judgeRoomPlayerMapper;
    private final Random random = new Random();

    public JudgeLobbyService(
            JudgeRoomSupport support,
            JudgeSnapshotAssembler assembler,
            UserService userService,
            BoardService boardService,
            JudgeRoomMapper judgeRoomMapper,
            JudgeRoomPlayerMapper judgeRoomPlayerMapper
    ) {
        this.support = support;
        this.assembler = assembler;
        this.userService = userService;
        this.boardService = boardService;
        this.judgeRoomMapper = judgeRoomMapper;
        this.judgeRoomPlayerMapper = judgeRoomPlayerMapper;
    }

    public List<WolfbookDtos.JudgeRoomSummaryView> listRecentRooms(String authorization) {
        String openid = support.requireOpenid(authorization);
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
                .sorted(Comparator.comparing(support::roomUpdateTime).reversed())
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
        room.setRoomId(support.makeId("room"));
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
        // 旧流程已下掉：所有房间都走新流程（建房请求里的 autoJudge 不再看）
        boolean autoJudge = true;
        JudgeRules boardRules = JudgeRules.fromBoard(boardService.getBoard(board.id()));
        Boolean sheriffRequested = request.sheriffEnabled() != null ? request.sheriffEnabled()
                : request.rules() == null ? null : request.rules().sheriffEnabled();
        JudgeRules rules = applyRoomRules(boardRules, request.rules(), sheriffRequested);
        room.setAutoJudgeEnabled(autoJudge);
        room.setSheriffEnabled(autoJudge && Boolean.TRUE.equals(rules.sheriffDefault()));
        room.setRulesJson(support.writeJson(rules));
        room.setCanRollback(true);
        room.setWinnerCamp(null);
        room.setLatestAnnouncement("房间已创建，等待玩家入座");
        room.setCreateTime(now);
        room.setUpdateTime(now);
        judgeRoomMapper.insert(room);

        // 有法官：房主是法官，不入座；无法官：房主自己坐 1 号拿牌
        JudgeRoomPlayerEntity judgeObserver = new JudgeRoomPlayerEntity();
        judgeObserver.setPlayerId(support.makeId("player"));
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

        support.addEvent(
                room.getRoomId(),
                room.getCurrentDay(),
                room.getCurrentPhase(),
                judgeObserver.getPlayerId(),
                List.of(),
                EVENT_ROOM_CREATED,
                support.mapOf("judgeMode", judgeMode, "boardName", board.name()),
                support.mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return assembler.buildSnapshot(user.openid(), room.getRoomId());
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView joinRoom(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        UserProfile user = userService.requireUser(authorization);
        JudgeRoomEntity room = support.getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，当前不能加入");
        }

        support.requireNotRemoved(room, user.openid());
        JudgeRoomPlayerEntity existing = support.findPlayer(roomId, user.openid());
        if (existing != null) {
            return assembler.buildSnapshot(user.openid(), roomId);
        }

        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        if (support.countJoinedPlayers(players) >= room.getPlayerCount()) {
            throw new ApiException(4002, "房间已满");
        }

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        JudgeRoomPlayerEntity player = new JudgeRoomPlayerEntity();
        player.setPlayerId(support.makeId("player"));
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
        support.addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                List.of(),
                EVENT_PLAYER_JOINED,
                support.mapOf("seatNo", player.getSeatNo(), "nickname", player.getNickname()),
                support.mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return assembler.buildSnapshot(user.openid(), roomId);
    }

    /**
     * 大厅换座（用户给定规则）：空位直接换；有人的座位发申请，对方 30 秒内同意才互换；换过座的人变回未准备。
     * 申请存在大厅的 phase_data.swapRequests（{from, to, deadline}，按玩家记），开局时 phase_data 整个重置。
     */
    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView changeSeat(String authorization, String roomId, WolfbookDtos.JudgeSeatRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，不能换座");
        }
        JudgeRoomPlayerEntity me = support.requirePlayingPlayer(roomId, openid);
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        String action = support.safeText(request == null ? null : request.action(), "");
        Integer seatNo = request == null ? null : request.seatNo();
        if (seatNo == null || seatNo < 1 || seatNo > room.getPlayerCount()) {
            throw new ApiException(4000, "没有这个座位");
        }
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        List<Map<String, Object>> requests = support.liveSwapRequests(room, now);
        JudgeRoomPlayerEntity other = support.findSeatPlayer(players, seatNo);
        switch (action) {
            case "move" -> {
                if (other != null) {
                    throw new ApiException(4002, other.getPlayerId().equals(me.getPlayerId()) ? "你已经在这个座位上了" : "这个座位有人了，可以申请换座");
                }
                int from = me.getSeatNo();
                moveSeat(me, seatNo, now);
                dropSwapRequests(requests, me.getPlayerId());
                announceSeat(room, me, me.getNickname() + " 从 " + from + " 号换到 " + seatNo + " 号", now);
            }
            case "request" -> {
                if (other == null) {
                    throw new ApiException(4002, "这个座位没人，直接入座就行");
                }
                if (other.getPlayerId().equals(me.getPlayerId())) {
                    throw new ApiException(4000, "不能和自己换座");
                }
                boolean busy = requests.stream().anyMatch(item -> other.getPlayerId().equals(item.get("to"))
                        && !me.getPlayerId().equals(item.get("from")));
                if (busy) {
                    throw new ApiException(4002, "对方正在处理别人的换座申请，稍后再试");
                }
                requests.removeIf(item -> me.getPlayerId().equals(item.get("from")));
                requests.add(support.mapOf("from", me.getPlayerId(), "to", other.getPlayerId(),
                        "deadline", now.plusSeconds(SEAT_SWAP_SECONDS).toString()));
            }
            case "accept" -> {
                if (other == null || requests.stream().noneMatch(item -> other.getPlayerId().equals(item.get("from"))
                        && me.getPlayerId().equals(item.get("to")))) {
                    throw new ApiException(4002, "换座申请已经失效");
                }
                int mySeat = me.getSeatNo();
                // 座位号有唯一键：先把对方挪到临时座位，再互换
                moveSeat(other, -1, now);
                moveSeat(me, seatNo, now);
                moveSeat(other, mySeat, now);
                dropSwapRequests(requests, me.getPlayerId());
                dropSwapRequests(requests, other.getPlayerId());
                announceSeat(room, me, other.getNickname() + " 和 " + me.getNickname() + " 换了座位（" + seatNo + " 号 ↔ " + mySeat + " 号）", now);
            }
            case "reject" -> requests.removeIf(item -> other != null && other.getPlayerId().equals(item.get("from"))
                    && me.getPlayerId().equals(item.get("to")));
            case "cancel" -> requests.removeIf(item -> me.getPlayerId().equals(item.get("from")));
            default -> throw new ApiException(4000, "不支持的换座操作");
        }
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        phaseData.put(SWAP_REQUESTS, requests);
        room.setPhaseData(support.writeJson(phaseData));
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        return assembler.buildSnapshot(openid, roomId);
    }

    /** 房主解散房间（用户给定：只在大厅里）：房间、座位、事件全部删掉，系统里不再有这个房间。 */
    @Transactional
    public void dissolveRoom(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.requireHost(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，不能解散房间");
        }
        support.deleteRoom(roomId);
    }

    /** 玩家在大厅里自己离开（用户给定）：座位空出来，可以再进；房主不能离开，只能解散。 */
    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView leaveRoom(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，不能离开房间");
        }
        if (openid.equals(room.getOwnerUserId())) {
            throw new ApiException(4003, "房主不能离开房间，可以解散房间");
        }
        JudgeRoomPlayerEntity me = support.requirePlayingPlayer(roomId, openid);
        removeSeat(room, me, me.getNickname() + " 离开了房间", null, LocalDateTime.now(APP_ZONE));
        return assembler.buildSnapshot(openid, roomId);
    }

    /** 房主在大厅里请人离开（用户给定）：座位空出来，被请离的人不能再进这个房间。 */
    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView removePlayer(String authorization, String roomId, WolfbookDtos.JudgeSeatRequest request) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.requireHost(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，不能请人离开");
        }
        Integer seatNo = request == null ? null : request.seatNo();
        if (seatNo == null || seatNo < 1 || seatNo > room.getPlayerCount()) {
            throw new ApiException(4000, "没有这个座位");
        }
        JudgeRoomPlayerEntity target = support.findSeatPlayer(support.loadPlayers(roomId), seatNo);
        if (target == null) {
            throw new ApiException(4002, "这个座位没人");
        }
        if (openid.equals(target.getUserId())) {
            throw new ApiException(4000, "不能请自己离开");
        }
        removeSeat(room, target, seatNo + " 号 " + target.getNickname() + " 被房主请离了房间", target.getUserId(), LocalDateTime.now(APP_ZONE));
        return assembler.buildSnapshot(openid, roomId);
    }

    /** 把这个人从座位上拿掉：他相关的换座申请作废；被请离的记进 removedPlayers。 */
    private void removeSeat(JudgeRoomEntity room, JudgeRoomPlayerEntity player, String announcement, String removedUserId, LocalDateTime now) {
        List<Map<String, Object>> requests = support.liveSwapRequests(room, now);
        dropSwapRequests(requests, player.getPlayerId());
        Map<String, Object> phaseData = new LinkedHashMap<>(support.readMap(room.getPhaseData()));
        phaseData.put(SWAP_REQUESTS, requests);
        if (removedUserId != null) {
            List<String> removed = support.removedPlayers(room);
            if (!removed.contains(removedUserId)) {
                removed.add(removedUserId);
            }
            phaseData.put(REMOVED_PLAYERS, removed);
        }
        judgeRoomPlayerMapper.deleteById(player.getPlayerId());
        room.setPhaseData(support.writeJson(phaseData));
        room.setLatestAnnouncement(announcement);
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        support.addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), null, List.of(), EVENT_PLAYER_LEFT,
                support.mapOf("seatNo", player.getSeatNo(), "nickname", player.getNickname(), "removed", removedUserId != null),
                support.mapOf("announcement", announcement), VISIBILITY_SYSTEM, now);
    }

    /** 换了座位的人：他发出和收到的申请都作废（申请是冲着那个座位去的）。 */
    private void dropSwapRequests(List<Map<String, Object>> requests, String playerId) {
        requests.removeIf(item -> playerId.equals(item.get("from")) || playerId.equals(item.get("to")));
    }

    private void moveSeat(JudgeRoomPlayerEntity player, int seatNo, LocalDateTime now) {
        player.setSeatNo(seatNo);
        player.setReady(false);
        player.setUpdateTime(now);
        try {
            judgeRoomPlayerMapper.updateById(player);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(4002, "座位刚被其他玩家占用，请重试");
        }
    }

    private void announceSeat(JudgeRoomEntity room, JudgeRoomPlayerEntity actor, String announcement, LocalDateTime now) {
        room.setLatestAnnouncement(announcement);
        support.addEvent(room.getRoomId(), room.getCurrentDay(), room.getCurrentPhase(), actor.getPlayerId(), List.of(),
                EVENT_SEAT_CHANGED, support.mapOf("seatNo", actor.getSeatNo()), support.mapOf("announcement", announcement), VISIBILITY_SYSTEM, now);
    }

    @Transactional
    public WolfbookDtos.JudgeRoomSnapshotView toggleReady(String authorization, String roomId) {
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        JudgeRoomEntity room = support.getRoom(roomId);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "对局已经开始，无法修改准备状态");
        }

        JudgeRoomPlayerEntity player = support.requirePlayingPlayer(roomId, openid);
        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        player.setReady(!Boolean.TRUE.equals(player.getReady()));
        player.setUpdateTime(now);
        judgeRoomPlayerMapper.updateById(player);

        room.setLatestAnnouncement(player.getNickname() + (Boolean.TRUE.equals(player.getReady()) ? " 已准备" : " 取消准备"));
        room.setUpdateTime(now);
        judgeRoomMapper.updateById(room);
        support.addEvent(
                roomId,
                room.getCurrentDay(),
                room.getCurrentPhase(),
                player.getPlayerId(),
                List.of(),
                EVENT_READY_CHANGED,
                support.mapOf("seatNo", player.getSeatNo(), "ready", Boolean.TRUE.equals(player.getReady())),
                support.mapOf("announcement", room.getLatestAnnouncement()),
                VISIBILITY_SYSTEM,
                now
        );
        return assembler.buildSnapshot(openid, roomId);
    }

    /**
     * 把房间规则（节奏项）合并到底稿上：为空的项保持原值，超范围 4000。上警开关记在 sheriffDefault 里（快照里能看到本局开没开）。
     * 胜负、女巫自救、一晚一瓶、弹毒是玩法规则，永远取底稿（板子）的。
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
                input != null && input.roleSeconds() != null ? input.roleSeconds() : rules.roleSeconds(),
                rules.poisonBounce()
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
        roomId = support.resolveRoomId(roomId);
        String openid = support.requireOpenid(authorization);
        support.lockRoom(roomId);
        JudgeRoomEntity room = support.requireHost(roomId, openid);
        if (!ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4002, "开局后规则已锁定");
        }
        JudgeRules rules = applyRoomRules(support.roomRules(room), request, request == null ? null : request.sheriffEnabled());
        room.setRulesJson(support.writeJson(rules));
        room.setSheriffEnabled(Boolean.TRUE.equals(rules.sheriffDefault()));
        room.setUpdateTime(LocalDateTime.now(APP_ZONE));
        support.updateRoomGuarded(room, ROOM_STATUS_LOBBY, null);
        return assembler.buildSnapshot(openid, roomId);
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
                support.safeText(room.getRoomStatus(), ROOM_STATUS_LOBBY),
                support.safeInt(room.getCurrentDay(), 1),
                support.safeText(room.getCurrentPhase(), PHASE_LOBBY),
                support.safeText(room.getWinnerCamp(), ""),
                support.countJoinedPlayers(players),
                support.countReadyPlayers(players),
                openid.equals(room.getOwnerUserId()),
                self != null,
                support.safeText(room.getLatestAnnouncement(), ""),
                room.getCreateTime(),
                support.roomUpdateTime(room)
        );
    }

    private String normalizeJudgeMode(String judgeMode, String judgeSupportLevel) {
        String normalized = support.safeText(judgeMode, JUDGE_MODE_OBSERVER).toLowerCase(Locale.ROOT);
        if (!JUDGE_MODE_OBSERVER.equals(normalized) && !JUDGE_MODE_JOINED.equals(normalized)) {
            throw new ApiException(4000, "无效的法官模式");
        }
        if (JUDGE_MODE_JOINED.equals(normalized) && !JudgeSupportLevels.FULL.equals(judgeSupportLevel)) {
            throw new ApiException(4000, "这个板子只能开有法官");
        }
        return normalized;
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
}
