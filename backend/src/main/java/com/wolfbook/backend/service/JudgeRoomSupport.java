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

/**
 * 电子法官共用的底层：常量、读写库、JSON、座位和夜间步骤的现算（JudgeService 推进流程、JudgeSnapshotAssembler 拼快照都用）。
 * 只放两边都要的东西；只有一边用的留在那一边。
 */
@Service
public class JudgeRoomSupport {

    static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    static final String ROOM_STATUS_LOBBY = "lobby";
    static final String ROOM_STATUS_PLAYING = "playing";
    static final String ROOM_STATUS_FINISHED = "finished";
    static final String JUDGE_MODE_OBSERVER = "observer";
    static final String JUDGE_MODE_JOINED = "joined";
    static final String PHASE_LOBBY = "lobby";
    static final String PHASE_NIGHT_ACTION = "night_action";
    static final String PHASE_DAY_SPEECH = "day_speech";
    static final String PHASE_EXILE_VOTE = "exile_vote";
    static final String PHASE_RESULT = "result";
    /** 新阶段机（auto_judge_enabled 的房间）：天亮后先公布死讯 / 上警，旧房间直接进发言。 */
    static final String PHASE_DAY_ANNOUNCE = "day_announce";
    static final String PHASE_SHERIFF_SIGNUP = "sheriff_signup";
    static final String PHASE_SHERIFF_SPEECH = "sheriff_speech";
    static final String PHASE_SHERIFF_VOTE = "sheriff_vote";
    static final String PHASE_SHERIFF_PK = "sheriff_pk";
    static final String PHASE_VOTE_RESULT = "vote_result";
    static final String PHASE_VOTE_PK = "vote_pk";
    static final String PHASE_SKILL_PENDING = "skill_pending";
    static final String VISIBILITY_PUBLIC = "public";
    static final String VISIBILITY_SYSTEM = "system";
    static final String VISIBILITY_JUDGE_ONLY = "judge_only";
    static final String VISIBILITY_ACTOR_ONLY = "actor_only";
    static final String EVENT_ROOM_CREATED = "room_created";
    static final String EVENT_PLAYER_JOINED = "player_joined";
    static final String EVENT_SEAT_CHANGED = "seat_changed";
    static final String EVENT_READY_CHANGED = "ready_changed";
    static final String EVENT_GAME_STARTED = "game_started";
    static final String EVENT_BROADCAST = "broadcast";
    static final String EVENT_NIGHT_SUBMIT = "night_action_submit";
    static final String EVENT_VOTE_SUBMIT = "vote_submit";
    static final String EVENT_NIGHT_RESOLVED = "night_resolved";
    static final String EVENT_VOTE_OPENED = "vote_opened";
    static final String EVENT_VOTE_RESOLVED = "vote_resolved";
    static final String EVENT_GAME_FINISHED = "game_finished";
    static final String EVENT_NIGHT_OVERRIDE = "night_override";
    /** 一夜的死因（judge_only，结束后进对局记录）：{causes: {座位号: [knife / guard_save / pierce / poison / bounce / mech_poison]}}。 */
    static final String EVENT_NIGHT_CAUSES = "night_causes";
    static final String EVENT_NIGHT_FEEDBACK = "night_feedback";
    static final String EVENT_STEP_CLOSED = "step_closed";
    static final String EVENT_GAME_FINISHED_AUTO = "game_finished_auto";
    static final String DRAW = "流局";
    static final String EVENT_SHERIFF_SIGNUP_CLOSED = "sheriff_signup_closed";
    /** 本人这次报名的选择（上警 / 不上警），只给法官；快照里据此区分「还没选」和「选了不上警」。 */
    static final String EVENT_SHERIFF_SIGNUP = "sheriff_signup";
    static final String EVENT_SHERIFF_WITHDRAW = "sheriff_withdraw";
    static final String EVENT_SHERIFF_VOTE_SUBMIT = "sheriff_vote_submit";
    static final String EVENT_SHERIFF_RESOLVED = "sheriff_resolved";
    static final String EVENT_SPEECH_ORDER = "speech_order";
    static final String EVENT_HUNTER_SHOT = "hunter_shot";
    static final String EVENT_BADGE_PASSED = "badge_passed";
    static final String EVENT_IDIOT_REVEALED = "idiot_revealed";
    static final String EVENT_FORCE_OUT = "force_out";
    static final String EVENT_JUDGE_PAUSED = "judge_paused";
    static final String EVENT_JUDGE_RESUMED = "judge_resumed";
    static final String EVENT_SELF_DESTRUCT = "self_destruct";
    static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    /** 换座申请多久不理算拒绝（用户给定：30 秒）。 */
    static final int SEAT_SWAP_SECONDS = 30;
    static final String SWAP_REQUESTS = "swapRequests";
    static final String PENDING_SKILL_ASK = JudgeLastWords.SKILL_ASK;
    static final String PENDING_HUNTER_SHOT = JudgeLastWords.HUNTER_SHOT;
    static final String PENDING_LAST_WORDS = JudgeLastWords.LAST_WORDS;
    static final String PENDING_BADGE = JudgeLastWords.BADGE;
    /** 能自爆的阶段：警上发言（双爆吞警徽）和白天发言。上警报名、投票、公布死讯、技能询问都不能。 */
    static final Set<String> SELF_DESTRUCT_PHASES = Set.of(PHASE_SHERIFF_SPEECH, PHASE_DAY_SPEECH);
    /** 狼人那一步的「确定」「重新选择」（用户给定规则：所有存活狼人都确定后 5 秒结束）。 */
    static final String WOLF_CONFIRM = "confirm";
    static final String WOLF_RESELECT = "reselect";
    /** 狼人全员确定、截止时间缩短后，原来的总时长存在 phase_data 这一项里（有人重新选择时恢复）。 */
    static final String WOLF_FULL_DEADLINE = "wolfFullDeadline";

    private static final Logger log = LoggerFactory.getLogger(JudgeRoomSupport.class);

    private final UserService userService;
    private final BoardService boardService;
    private final RoleMapper roleMapper;
    private final JudgeRoomMapper judgeRoomMapper;
    private final JudgeRoomPlayerMapper judgeRoomPlayerMapper;
    private final JudgeActionEventMapper judgeActionEventMapper;
    private final ObjectMapper objectMapper;

    public JudgeRoomSupport(
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

    /** 开局时存进房间的板子（阵容、夜间顺序、规则）和阵容里各角色（机制、阵营）。 */
    public record BoardSnapshot(Board board, List<RoleEntity> roles) {
    }

    /** 还没过期的换座申请。 */
    List<Map<String, Object>> liveSwapRequests(JudgeRoomEntity room, LocalDateTime now) {
        List<Map<String, Object>> live = new ArrayList<>();
        if (readMap(room.getPhaseData()).get(SWAP_REQUESTS) instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    LocalDateTime deadline = parseDeadline(castMap(map));
                    if (deadline != null && deadline.isAfter(now)) {
                        live.add(new LinkedHashMap<>(castMap(map)));
                    }
                }
            }
        }
        return live;
    }

    List<NightPlan.NightStep> nightSteps(JudgeRoomEntity room, int dayNo) {
        BoardSnapshot snapshot = boardSnapshotOf(room);
        if (snapshot != null) {
            Map<Integer, RoleEntity> roles = snapshot.roles().stream()
                    .collect(Collectors.toMap(RoleEntity::getId, role -> role, (left, right) -> left));
            return NightPlan.build(snapshot.board(), roles, dayNo, roomRules(room));
        }
        Board board = boardService.getBoard(room.getBoardId());
        return NightPlan.build(board, loadBoardRoleMap(room.getBoardId()), dayNo, roomRules(room));
    }

    /** 读房间里的板子快照；升级前开的局没有（或读不出来）返回 null，调用方退回现读。 */
    BoardSnapshot boardSnapshotOf(JudgeRoomEntity room) {
        if (!StringUtils.hasText(room.getBoardSnapshot())) {
            return null;
        }
        try {
            BoardSnapshot snapshot = objectMapper.readValue(room.getBoardSnapshot(), BoardSnapshot.class);
            return snapshot.board() == null || snapshot.roles() == null ? null : snapshot;
        } catch (Exception exception) {
            log.warn("房间 {} 的板子快照读不出来，退回现读板子：{}", room.getRoomId(), exception.getMessage());
            return null;
        }
    }

    /** 本局规则：开局时拷进房间的；老房间没有就现取板子的。 */
    JudgeRules roomRules(JudgeRoomEntity room) {
        if (StringUtils.hasText(room.getRulesJson())) {
            try {
                return objectMapper.readValue(room.getRulesJson(), JudgeRules.class).withDefaults();
            } catch (Exception ignored) {
                // 读不出来就按板子现取
            }
        }
        return JudgeRules.fromBoard(boardService.getBoard(room.getBoardId()));
    }

    NightKind nightKindOf(JudgeRoomPlayerEntity player) {
        Mechanic mechanic = mechanicOf(player).orElse(null);
        if (RoleSkillRegistry.isWolfTeam(mechanic, player.getRoleName(), player.getFaction())) {
            return NightKind.WOLF;
        }
        return mechanic == null ? NightKind.GENERIC : mechanic.nightKind();
    }

    /** 座位上的技能机制：发身份时拷进来的；升级前开局的座位没有，按角色名兜底。 */
    Optional<Mechanic> mechanicOf(JudgeRoomPlayerEntity player) {
        return Mechanic.fromCode(player.getMechanic()).or(() -> RoleSkillRegistry.legacyMechanic(player.getRoleName()));
    }

    /** 今晚的夜间提交（每个座位最后一条），旧事件没有 action 字段时按中文 actionType 换算。 */
    List<NightResolver.Submission> tonightSubmissions(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        return tonightSubmissions(room, players, loadEvents(room.getRoomId()));
    }

    /** 同上，用已经读出来的事件（拼快照时整间房只读一次）。 */
    List<NightResolver.Submission> tonightSubmissions(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                              List<JudgeActionEventEntity> events) {
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        // 每个座位每一步留最后一条（带刀机械狼一晚有两步：自己那一步的技能、狼人那一步的刀口）
        Map<String, List<NightResolver.Submission>> bySeat = new LinkedHashMap<>();
        for (JudgeActionEventEntity event : events) {
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
            NightKind kind = nightKindOf(actor);
            List<NightResolver.Submission> mine = new ArrayList<>();
            if (payload.get("actions") instanceof List<?> items) {
                // 机械狼一次提交的多个行动
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map) {
                        mine.add(new NightResolver.Submission(actor.getSeatNo(), kind, safeText(asString(map.get("action")), NightResolver.NONE),
                                asPositiveInteger(map.get("targetSeatNo"))));
                    }
                }
            } else {
                String label = safeText(asString(payload.get("actionType")), "");
                String action = safeText(asString(payload.get("action")), RoleSkillRegistry.LEGACY_ACTIONS.getOrDefault(label, label));
                mine.add(new NightResolver.Submission(actor.getSeatNo(), kind, action, asPositiveInteger(payload.get("targetSeatNo"))));
            }
            bySeat.put(actor.getSeatNo() + ":" + stepIndexOf(payload), mine);
        }
        return bySeat.values().stream().flatMap(List::stream).toList();
    }

    Map<Integer, NightResolver.SeatState> seatStates(List<JudgeRoomPlayerEntity> players) {
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
                            asPositiveInteger(status.get("lastGuardSeat")),
                            Mechanic.fromCode(asString(status.get("mechLearned"))).orElse(null),
                            trimToNull(asString(status.get("mechLearnedRole"))),
                            asPositiveInteger(status.get("mechLearnedDay")),
                            !Boolean.FALSE.equals(status.get("mechPoison"))
                    ));
                });
        return seats;
    }

    List<WinJudge.Seat> winSeats(List<JudgeRoomPlayerEntity> players) {
        Set<Integer> roleIds = players.stream().map(JudgeRoomPlayerEntity::getRoleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, String> roleTypes = roleIds.isEmpty() ? Map.of() : roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(RoleEntity::getId, role -> safeText(role.getRoleType(), "")));
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .map(player -> new WinJudge.Seat(Boolean.TRUE.equals(player.getAlive()), player.getFaction(),
                        roleTypes.getOrDefault(player.getRoleId(), "")))
                .toList();
    }

    /** 还在警上的人：报了名、没退水、活着。 */
    List<Integer> onStage(List<JudgeRoomPlayerEntity> players) {
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()) && player.getSeatNo() != null)
                .filter(player -> Boolean.TRUE.equals(player.getSheriffCandidate()) && !Boolean.TRUE.equals(player.getSheriffWithdrew()))
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .sorted()
                .toList();
    }

    List<Integer> pkSeats(JudgeRoomEntity room) {
        return asIntegerList(readMap(room.getPhaseData()).get("pkSeats"));
    }

    List<Integer> asIntegerList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::asPositiveInteger).filter(Objects::nonNull).toList();
    }

    Integer asPositiveIntegerOrZero(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 本人现在能不能自爆：新流程、对局中、入座且活着、机制带自爆、在能自爆的阶段、没暂停。快照和提交共用。 */
    boolean canSelfDestruct(JudgeRoomEntity room, JudgeRoomPlayerEntity self) {
        return self != null
                && ROOM_STATUS_PLAYING.equals(room.getRoomStatus())
                && Boolean.TRUE.equals(self.getPlaying()) && self.getSeatNo() != null && Boolean.TRUE.equals(self.getAlive())
                && mechanicOf(self).map(Mechanic::canSelfDestruct).orElse(false)
                && SELF_DESTRUCT_PHASES.contains(safeText(room.getCurrentPhase(), ""))
                && !isPaused(room);
    }

    boolean isPaused(JudgeRoomEntity room) {
        return readMap(room.getPhaseData()).containsKey("pausedAt");
    }

    /** 技能询问的选项：没被毒、没被点杀的猎人 / 狼王可以开枪，其余人只有「确认」。 */
    List<String> skillOptions(JudgeRoomPlayerEntity player) {
        if (player == null) {
            return List.of("skip");
        }
        Map<String, Object> status = readMap(player.getStatusJson());
        boolean shooter = mechanicOf(player).map(Mechanic::canShoot).orElse(false)
                || (mechanicOf(player).orElse(null) == Mechanic.MECH_WOLF && "hunter".equals(asString(status.get("mechLearned"))));
        boolean canShoot = !Boolean.FALSE.equals(status.get("canShoot"));
        return shooter && canShoot ? List.of("use", "skip") : List.of("skip");
    }

    List<Map<String, Object>> pendingList(Map<String, Object> phaseData) {
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

    LocalDateTime parseDeadline(Map<String, Object> entry) {
        try {
            return LocalDateTime.parse(asString(entry.get("deadline")));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 这一轮能投票的座位：放逐看投票权（翻牌白痴没有），PK 去掉平票者；警长投票只有没上过警的人。 */
    /**
     * 这一轮能投票的座位。警长投票只有警下的人；警长 PK 时还在警上、不在 PK 里的候选人也能投（退水的始终不能投）。
     * 放逐投票看 canVote（翻牌白痴不能投），PK 的人不能投。
     */
    Set<Integer> eligibleVoters(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String phase) {
        boolean sheriffVote = PHASE_SHERIFF_VOTE.equals(phase) || PHASE_SHERIFF_PK.equals(phase);
        boolean sheriffPk = PHASE_SHERIFF_PK.equals(phase);
        Set<Integer> pk = new HashSet<>(PHASE_VOTE_PK.equals(phase) || sheriffPk ? pkSeats(room) : List.of());
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive()) && player.getSeatNo() != null)
                .filter(player -> !sheriffVote ? !Boolean.FALSE.equals(player.getCanVote())
                        : !Boolean.TRUE.equals(player.getSheriffCandidate())
                        || (sheriffPk && !Boolean.TRUE.equals(player.getSheriffWithdrew())))
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .filter(seat -> !pk.contains(seat))
                .collect(Collectors.toSet());
    }

    /** 这一轮已经投了的座位（弃票也算）。 */
    Set<Integer> votedSeats(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String phase) {
        return votedSeats(room, players, phase, loadEvents(room.getRoomId()));
    }

    /** 同上，用已经读出来的事件。 */
    Set<Integer> votedSeats(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, String phase,
                                    List<JudgeActionEventEntity> events) {
        String type = PHASE_SHERIFF_VOTE.equals(phase) || PHASE_SHERIFF_PK.equals(phase) ? EVENT_SHERIFF_VOTE_SUBMIT : EVENT_VOTE_SUBMIT;
        Map<String, Integer> seatById = new HashMap<>();
        players.stream().filter(player -> player.getSeatNo() != null).forEach(player -> seatById.put(player.getPlayerId(), player.getSeatNo()));
        return events.stream()
                .filter(event -> type.equals(event.getActionType()) && phase.equals(event.getPhase())
                        && Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1)))
                .map(event -> seatById.get(event.getActorPlayerId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    JudgeRoomEntity getRoom(String roomId) {
        JudgeRoomEntity room = judgeRoomMapper.selectById(roomId);
        if (room == null) {
            throw new ApiException(4004, "房间不存在");
        }
        return room;
    }

    List<JudgeRoomPlayerEntity> loadPlayers(String roomId) {
        return judgeRoomPlayerMapper.selectList(
                new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                        .eq(JudgeRoomPlayerEntity::getRoomId, roomId)
                        .orderByAsc(JudgeRoomPlayerEntity::getSeatNo)
                        .orderByAsc(JudgeRoomPlayerEntity::getCreateTime)
        );
    }

    List<JudgeActionEventEntity> loadEvents(String roomId) {
        return judgeActionEventMapper.selectList(
                new LambdaQueryWrapper<JudgeActionEventEntity>()
                        .eq(JudgeActionEventEntity::getRoomId, roomId)
                        .orderByAsc(JudgeActionEventEntity::getCreateTime)
                        .orderByAsc(JudgeActionEventEntity::getSeq)
        );
    }

    int countJoinedPlayers(List<JudgeRoomPlayerEntity> players) {
        return (int) players.stream().filter(player -> Boolean.TRUE.equals(player.getPlaying())).count();
    }

    int countReadyPlayers(List<JudgeRoomPlayerEntity> players) {
        return (int) players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()))
                .filter(player -> Boolean.TRUE.equals(player.getReady()))
                .count();
    }

    Map<Integer, RoleEntity> loadBoardRoleMap(Integer boardId) {
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

    List<WolfbookDtos.JudgeVoteTallyView> buildVoteTallies(
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

    /** 这一步该谁行动（活着的持有者）：狼人那一步除了狼队，还有狼队全出局后带刀的机械狼。 */
    List<Integer> stepHolderSeats(List<JudgeRoomPlayerEntity> players, NightPlan.NightStep step) {
        List<NightStepRules.Seat> seats = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && player.getSeatNo() != null)
                .map(player -> new NightStepRules.Seat(player.getSeatNo(), player.getRoleId(), Boolean.TRUE.equals(player.getAlive()),
                        mechanicOf(player).orElse(null)))
                .toList();
        boolean armed = step.kind() == NightKind.WOLF && NightResolver.armed(seatStates(players));
        return NightStepRules.holders(step, seats, armed);
    }

    Integer stepIndexOf(Map<String, Object> payload) {
        Object value = payload.get("stepIndex");
        return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
    }

    /** 本人当前这一步的提交（最后一条）；没记步骤的老数据当作当前这一步。 */
    JudgeActionEventEntity stepSubmitEvent(JudgeRoomEntity room, List<JudgeActionEventEntity> events, String playerId) {
        JudgeActionEventEntity found = null;
        for (JudgeActionEventEntity event : events) {
            if (!EVENT_NIGHT_SUBMIT.equals(event.getActionType()) || !Objects.equals(event.getActorPlayerId(), playerId)
                    || !Objects.equals(safeInt(event.getDayNo(), 1), safeInt(room.getCurrentDay(), 1))
                    || !PHASE_NIGHT_ACTION.equals(event.getPhase())) {
                continue;
            }
            Integer index = stepIndexOf(readMap(event.getPayloadJson()));
            if (index == null || index.equals(room.getNightStep())) {
                found = event;
            }
        }
        return found;
    }

    /** 狼人那一步已经点了确定的座位。 */
    Set<Integer> confirmedSeats(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<JudgeActionEventEntity> events) {
        Set<Integer> seats = new HashSet<>();
        for (JudgeRoomPlayerEntity player : players) {
            if (player.getSeatNo() == null) {
                continue;
            }
            JudgeActionEventEntity event = stepSubmitEvent(room, events, player.getPlayerId());
            if (event != null && Boolean.TRUE.equals(readMap(event.getPayloadJson()).get("confirmed"))) {
                seats.add(player.getSeatNo());
            }
        }
        return seats;
    }

    JudgeRoomPlayerEntity findSeatPlayer(List<JudgeRoomPlayerEntity> players, Integer seatNo) {
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

    Integer normalizeSeatNo(Integer seatNo) {
        return seatNo != null && seatNo > 0 ? seatNo : null;
    }

    String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    Integer safeInt(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    Integer asPositiveInteger(Object value) {
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

    LocalDateTime roomUpdateTime(JudgeRoomEntity room) {
        return room.getUpdateTime() == null ? room.getCreateTime() : room.getUpdateTime();
    }

    Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /** 房主：有法官时就是法官，无法官时是坐 1 号的玩家。白天推进、开局、改规则归房主。 */
    JudgeRoomEntity requireHost(String roomId, String openid) {
        JudgeRoomEntity room = getRoom(roomId);
        if (!openid.equals(room.getOwnerUserId())) {
            throw new ApiException(4003, "只有房主可以执行此操作");
        }
        return room;
    }

    JudgeRoomPlayerEntity requirePlayingPlayer(String roomId, String openid) {
        JudgeRoomPlayerEntity player = findPlayer(roomId, openid);
        if (player == null || !Boolean.TRUE.equals(player.getPlaying())) {
            throw new ApiException(4003, "你尚未作为玩家加入当前房间");
        }
        return player;
    }

    /**
     * 带条件的房间更新：只有房间仍处于预期状态/阶段时才写入，否则说明有并发操作先完成了，
     * 抛错让当前事务回滚（例如法官连点两次“开局”或“结算”）。
     */
    void updateRoomGuarded(JudgeRoomEntity room, String expectedStatus, String expectedPhase) {
        updateRoomGuarded(room, expectedStatus, expectedPhase, null);
    }

    /** 同上，另外要求夜里还停在 expectedNightStep 这一步（两台手机同时推进同一步时，后到的那个失败）。 */
    void updateRoomGuarded(JudgeRoomEntity room, String expectedStatus, String expectedPhase, Integer expectedNightStep) {
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

    /**
     * 锁住房间这一行（SELECT … FOR UPDATE），同一间房的推进、提交、惰性推进排队执行。
     * 必须在读房间、座位、事件之前调用，配合 READ COMMITTED，锁到之后读到的都是最新提交的数据，
     * 不会出现「两个请求都读了旧房间，后写的把先写的覆盖掉」（比如暂停被关步骤的惰性推进冲掉）。
     */
    JudgeRoomEntity lockRoom(String roomId) {
        return judgeRoomMapper.selectOne(new LambdaQueryWrapper<JudgeRoomEntity>()
                .eq(JudgeRoomEntity::getRoomId, roomId)
                .last("FOR UPDATE"));
    }

    JudgeRoomPlayerEntity findPlayer(String roomId, String openid) {
        return judgeRoomPlayerMapper.selectOne(
                new LambdaQueryWrapper<JudgeRoomPlayerEntity>()
                        .eq(JudgeRoomPlayerEntity::getRoomId, roomId)
                        .eq(JudgeRoomPlayerEntity::getUserId, openid)
                        .last("limit 1")
        );
    }

    void addEvent(
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

    String requireOpenid(String authorization) {
        return userService.requireUser(authorization).openid();
    }

    /** 传进来的是纯数字（4~6 位）就当短房号找：优先未结束的房间，其次最近更新的那间。 */
    String resolveRoomId(String roomIdOrCode) {
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

    String makeId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime(), 16);
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize judge state", exception);
        }
    }

    Map<String, Object> mapOf(Object... values) {
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

    /** 今晚已交的夜间行动；狼队那一步已经定了刀口（平票随机过一次）就用定下的刀口，结算和预演都不再重抽。 */
    List<NightResolver.Submission> knifeFixedSubmissions(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        List<NightResolver.Submission> submissions = tonightSubmissions(room, players);
        Map<String, Object> phaseData = readMap(room.getPhaseData());
        if (!Boolean.TRUE.equals(phaseData.get("knifeDecided"))) {
            return submissions;
        }
        Integer knife = asPositiveInteger(phaseData.get("knifeSeat"));
        List<NightResolver.Submission> fixed = new ArrayList<>(submissions.stream()
                .filter(submission -> submission.kind() != NightKind.WOLF)
                .toList());
        if (knife != null) {
            fixed.add(new NightResolver.Submission(0, NightKind.WOLF, NightResolver.KILL, knife));
        }
        return fixed;
    }
}
