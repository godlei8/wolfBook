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

/** 拼法官房间快照：按查看者是法官、玩家本人还是别人裁剪（零场外的唯一裁剪点）。只读，不改库。 */
@Service
public class JudgeSnapshotAssembler {

    private final JudgeRoomSupport support;
    private final ObjectMapper objectMapper;

    public JudgeSnapshotAssembler(JudgeRoomSupport support, ObjectMapper objectMapper) {
        this.support = support;
        this.objectMapper = objectMapper;
    }

    /** 快照里本人的换座申请，只给申请双方。 */
    WolfbookDtos.JudgeSeatSwapView seatSwapView(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                       JudgeRoomPlayerEntity self) {
        if (self == null || !ROOM_STATUS_LOBBY.equals(room.getRoomStatus()) || !Boolean.TRUE.equals(self.getPlaying())) {
            return null;
        }
        Map<String, JudgeRoomPlayerEntity> byId = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        WolfbookDtos.JudgeSeatSwapItem outgoing = null;
        WolfbookDtos.JudgeSeatSwapItem incoming = null;
        for (Map<String, Object> item : support.liveSwapRequests(room, LocalDateTime.now(APP_ZONE))) {
            JudgeRoomPlayerEntity from = byId.get(support.asString(item.get("from")));
            JudgeRoomPlayerEntity to = byId.get(support.asString(item.get("to")));
            if (from == null || to == null || from.getSeatNo() == null || to.getSeatNo() == null) {
                continue;
            }
            LocalDateTime deadline = support.parseDeadline(item);
            if (from.getPlayerId().equals(self.getPlayerId())) {
                outgoing = new WolfbookDtos.JudgeSeatSwapItem(to.getSeatNo(), support.safeText(to.getNickname(), ""), deadline);
            } else if (to.getPlayerId().equals(self.getPlayerId()) && incoming == null) {
                incoming = new WolfbookDtos.JudgeSeatSwapItem(from.getSeatNo(), support.safeText(from.getNickname(), ""), deadline);
            }
        }
        return outgoing == null && incoming == null ? null : new WolfbookDtos.JudgeSeatSwapView(outgoing, incoming);
    }

    WolfbookDtos.JudgeRoomSnapshotView buildSnapshot(String openid, String roomId) {
        JudgeRoomEntity room = support.getRoom(roomId);
        List<JudgeRoomPlayerEntity> players = support.loadPlayers(roomId);
        JudgeRoomPlayerEntity self = players.stream()
                .filter(player -> openid.equals(player.getUserId()))
                .findFirst()
                .orElse(null);
        boolean judgeViewer = self != null && Boolean.TRUE.equals(self.getJudgeObserver());
        boolean joinedViewer = self != null && Boolean.TRUE.equals(self.getPlaying()) && !judgeViewer;
        if (self == null && !ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            throw new ApiException(4003, "对局已开始，当前无法旁观");
        }

        List<JudgeActionEventEntity> events = support.loadEvents(roomId);
        int joinedCount = support.countJoinedPlayers(players);
        int readyCount = support.countReadyPlayers(players);
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
                support.safeInt(room.getCurrentDay(), 1),
                support.safeText(room.getCurrentPhase(), PHASE_LOBBY),
                support.safeText(room.getWinnerCamp(), ""),
                support.safeText(room.getLatestAnnouncement(), ""),
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
                        ? support.buildVoteTallies(room, players, events, votePhase(room)) : List.of(),
                judgeViewer ? buildNightActions(room, players, events) : List.of(),
                (judgeViewer || roomFinished) ? buildNightHistory(players, events) : List.of(),
                self == null ? new WolfbookDtos.JudgePendingActionView(false, null, "") : buildPendingNightAction(room, self, events, playersById),
                room.getCreateTime(),
                support.roomUpdateTime(room),
                buildStepView(room, players, self, judgeViewer),
                support.roomRules(room),
                openid.equals(room.getOwnerUserId()),
                roomFinished && StringUtils.hasText(room.getWinnerCamp())
                        ? new WolfbookDtos.JudgeVerdictView(room.getWinnerCamp(), support.safeText(support.asString(support.readMap(room.getPhaseData()).get("verdictReason")), ""))
                        : null,
                players.stream().filter(player -> Boolean.TRUE.equals(player.getSheriff())).map(JudgeRoomPlayerEntity::getSeatNo).findFirst().orElse(null),
                Set.of(PHASE_SHERIFF_SPEECH, PHASE_SHERIFF_VOTE, PHASE_SHERIFF_PK).contains(support.safeText(room.getCurrentPhase(), "")) ? support.onStage(players) : List.of(),
                speechView(room),
                Set.of(PHASE_VOTE_RESULT, PHASE_VOTE_PK, PHASE_SHERIFF_PK).contains(support.safeText(room.getCurrentPhase(), "")) ? support.pkSeats(room) : List.of(),
                buildRounds(events, judgeViewer || roomFinished),
                pendingView(room, players, self, judgeViewer),
                support.isPaused(room),
                room.getStepDeadline(),
                buildNightInfo(room, players, self, judgeViewer, events),
                true,
                buildDayInfo(room, players, self, events),
                ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && Boolean.TRUE.equals(support.readMap(room.getPhaseData()).get("nightNext")),
                publicPendingView(room),
                seatSwapView(room, players, self),
                LocalDateTime.now(APP_ZONE),
                PHASE_DAY_ANNOUNCE.equals(room.getCurrentPhase())
                        ? support.trimToNull(support.asString(support.readMap(room.getPhaseData()).get("sheriffOrder"))) : null
        );
    }

    /** 查看者本人白天的上警报名和投票状态（只在新流程房间）。 */
    WolfbookDtos.JudgeDayInfoView buildDayInfo(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                      JudgeRoomPlayerEntity self, List<JudgeActionEventEntity> events) {
        if (self == null || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            return null;
        }
        String phase = support.safeText(room.getCurrentPhase(), "");
        Integer selfSeat = Boolean.TRUE.equals(self.getPlaying()) ? self.getSeatNo() : null;
        if (PHASE_SHERIFF_SIGNUP.equals(phase)) {
            // 没选是 null，选了不上警是 false（本人这天在报名阶段交过选择才算选过）
            boolean decided = selfSeat != null && events.stream().anyMatch(event -> EVENT_SHERIFF_SIGNUP.equals(event.getActionType())
                    && self.getPlayerId().equals(event.getActorPlayerId())
                    && java.util.Objects.equals(event.getDayNo(), room.getCurrentDay()));
            return new WolfbookDtos.JudgeDayInfoView(decided ? Boolean.valueOf(Boolean.TRUE.equals(self.getSheriffCandidate())) : null,
                    false, false, null, 0, 0, false);
        }
        if (SELF_DESTRUCT_PHASES.contains(phase)) {
            return new WolfbookDtos.JudgeDayInfoView(null, false, false, null, 0, 0, support.canSelfDestruct(room, self));
        }
        if (!Set.of(PHASE_EXILE_VOTE, PHASE_VOTE_PK, PHASE_SHERIFF_VOTE, PHASE_SHERIFF_PK).contains(phase)) {
            return null;
        }
        Set<Integer> eligible = support.eligibleVoters(room, players, phase);
        Set<Integer> voted = support.votedSeats(room, players, phase, events);
        String type = PHASE_SHERIFF_VOTE.equals(phase) || PHASE_SHERIFF_PK.equals(phase) ? EVENT_SHERIFF_VOTE_SUBMIT : EVENT_VOTE_SUBMIT;
        Integer myVote = events.stream()
                .filter(event -> type.equals(event.getActionType()) && phase.equals(event.getPhase())
                        && Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1))
                        && Objects.equals(event.getActorPlayerId(), self.getPlayerId()))
                .reduce((left, right) -> right)
                .map(event -> support.asPositiveInteger(support.readMap(event.getPayloadJson()).get("targetSeatNo")))
                .orElse(null);
        int votedCount = (int) voted.stream().filter(eligible::contains).count();
        return new WolfbookDtos.JudgeDayInfoView(null, selfSeat != null && eligible.contains(selfSeat),
                selfSeat != null && voted.contains(selfSeat), myVote, votedCount, eligible.size(), false);
    }

    /** 查看者本人的夜间私密信息（只在新流程房间）。法官有 nightActions 全量，不给。 */
    WolfbookDtos.JudgeNightInfoView buildNightInfo(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                          JudgeRoomPlayerEntity self, boolean judgeViewer,
                                                          List<JudgeActionEventEntity> events) {
        if (judgeViewer || self == null || !Boolean.TRUE.equals(self.getPlaying()) || self.getSeatNo() == null
                || ROOM_STATUS_LOBBY.equals(room.getRoomStatus())) {
            return null;
        }
        Mechanic mechanic = support.mechanicOf(self).orElse(null);
        boolean wolf = RoleSkillRegistry.isWolfTeam(mechanic, self.getRoleName(), self.getFaction());
        boolean alive = Boolean.TRUE.equals(self.getAlive());
        List<Integer> teammates = wolf ? players.stream()
                .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && player.getSeatNo() != null
                        && !player.getSeatNo().equals(self.getSeatNo()) && support.nightKindOf(player) == NightKind.WOLF)
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .sorted()
                .toList() : List.of();

        List<WolfbookDtos.JudgeWolfPickView> picks = List.of();
        Integer knifeSeat = null;
        Integer checkedSeat = null;
        String checkResult = null;
        Boolean antidote = null;
        Boolean poison = null;
        Integer lastGuardSeat = null;
        String myAction = null;
        Integer myTarget = null;
        String identityResult = null;
        Boolean armed = null;
        String learned = null;
        String learnedRole = null;
        Boolean learnedEffective = null;
        Boolean mechPoison = null;
        List<WolfbookDtos.JudgeNightActionItem> myActions = null;
        Boolean myConfirmed = null;
        Boolean canShoot = null;
        boolean night = ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) && PHASE_NIGHT_ACTION.equals(room.getCurrentPhase())
                && room.getNightStep() != null;
        if (night && alive) {
            Map<String, Object> status = support.readMap(self.getStatusJson());
            if (mechanic == Mechanic.WITCH) {
                antidote = !Boolean.FALSE.equals(status.get("antidote"));
                poison = !Boolean.FALSE.equals(status.get("poison"));
            }
            if (mechanic == Mechanic.GUARD) {
                lastGuardSeat = support.asPositiveInteger(status.get("lastGuardSeat"));
            }
            List<NightPlan.NightStep> steps = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1));
            NightPlan.NightStep step = room.getNightStep() < steps.size() ? steps.get(room.getNightStep()) : null;
            List<NightResolver.Submission> submissions = support.tonightSubmissions(room, players, events);
            if (step != null && step.kind() == NightKind.WOLF && wolf) {
                Map<Integer, Integer> chosen = new HashMap<>();
                submissions.stream()
                        .filter(submission -> submission.kind() == NightKind.WOLF
                                && (NightResolver.KILL.equals(submission.action()) || NightResolver.NONE.equals(submission.action())))
                        .forEach(submission -> chosen.put(submission.actorSeat(), submission.targetSeat()));
                Set<Integer> confirmed = support.confirmedSeats(room, players, events);
                picks = players.stream()
                        .filter(player -> Boolean.TRUE.equals(player.getPlaying()) && Boolean.TRUE.equals(player.getAlive())
                                && player.getSeatNo() != null && support.nightKindOf(player) == NightKind.WOLF)
                        .sorted(Comparator.comparing(JudgeRoomPlayerEntity::getSeatNo))
                        .map(player -> new WolfbookDtos.JudgeWolfPickView(player.getSeatNo(), chosen.get(player.getSeatNo()),
                                chosen.containsKey(player.getSeatNo()), confirmed.contains(player.getSeatNo())))
                        .toList();
            }
            if (step != null && step.kind() == NightKind.WITCH && mechanic == Mechanic.WITCH && Boolean.TRUE.equals(antidote)) {
                // roles/女巫.md：只在还有解药时告知刀口，解药用完后不再告知
                Map<String, Object> phaseData = support.readMap(room.getPhaseData());
                knifeSeat = Boolean.TRUE.equals(phaseData.get("knifeDecided")) ? support.asPositiveInteger(phaseData.get("knifeSeat")) : null;
            }
            // 本人这一步的提交；这一步不归自己时看今晚最后一次提交（狼人那一步的带刀机械狼不能拿自己那一步的技能当刀口）
            JudgeActionEventEntity stepEvent = support.stepSubmitEvent(room, events, self.getPlayerId());
            NightResolver.Submission mine = stepEvent != null ? lastSubmissionOf(self.getSeatNo(), support.readMap(stepEvent.getPayloadJson()))
                    : step != null && support.stepHolderSeats(players, step).contains(self.getSeatNo()) ? null
                    : submissions.stream()
                            .filter(submission -> submission.actorSeat() == self.getSeatNo())
                            .reduce((left, right) -> right)
                            .orElse(null);
            if (mine != null) {
                myAction = mine.action();
                myTarget = mine.targetSeat();
            }
            if (step != null && step.kind() == NightKind.WOLF && stepEvent != null) {
                myConfirmed = Boolean.TRUE.equals(support.readMap(stepEvent.getPayloadJson()).get("confirmed"));
            }
            if (mechanic == Mechanic.PSYCHIC || mechanic == Mechanic.MECH_WOLF) {
                // 通灵师查验、机械狼学习 / 学到通灵师后的查验：提交后立刻看到对方的具体身份
                Map<Integer, NightResolver.SeatState> seats = support.seatStates(players);
                NightResolver.SeatState me = seats.get(self.getSeatNo());
                int dayNo = support.safeInt(room.getCurrentDay(), 1);
                NightResolver.Submission look = submissions.stream()
                        .filter(submission -> submission.actorSeat() == self.getSeatNo() && submission.targetSeat() != null
                                && (NightResolver.LEARN.equals(submission.action())
                                || (NightResolver.CHECK.equals(submission.action())
                                && (mechanic == Mechanic.PSYCHIC || (me != null && me.learned() == Mechanic.PSYCHIC
                                && NightResolver.learnedEffective(me, dayNo))))))
                        .findFirst().orElse(null);
                if (look != null) {
                    checkedSeat = look.targetSeat();
                    NightResolver.SeatState target = seats.get(look.targetSeat());
                    identityResult = NightResolver.LEARN.equals(look.action())
                            ? (target == null ? "" : target.roleName())
                            : NightResolver.identityOf(target, submissions, seats);
                }
                if (mechanic == Mechanic.MECH_WOLF && me != null) {
                    armed = NightResolver.armed(seats);
                    learned = me.learned() == null ? null : me.learned().code();
                    learnedRole = me.learnedRoleName();
                    learnedEffective = NightResolver.learnedEffective(me, dayNo);
                    mechPoison = me.learned() == Mechanic.WITCH ? me.mechPoisonLeft() : null;
                    if (me.learned() == Mechanic.GUARD) {
                        lastGuardSeat = me.lastGuardSeat();
                    }
                    myActions = submissions.stream()
                            .filter(submission -> submission.actorSeat() == self.getSeatNo())
                            .map(submission -> new WolfbookDtos.JudgeNightActionItem(submission.action(), submission.targetSeat()))
                            .toList();
                }
            }
            if (mechanic == Mechanic.HUNTER && step != null && step.kind() == NightKind.HUNTER) {
                // 猎人那一步告知开枪状态：预演今晚的结算，被毒（含学到女巫的机械狼的毒）就不能开枪
                NightResolver.Outcome preview = NightResolver.resolve(support.knifeFixedSubmissions(room, players),
                        support.seatStates(players), support.roomRules(room), support.safeInt(room.getCurrentDay(), 1), new java.util.Random());
                canShoot = !preview.cannotShoot().contains(self.getSeatNo());
            }
            if (mechanic == Mechanic.SEER) {
                NightResolver.Submission check = submissions.stream()
                        .filter(submission -> submission.actorSeat() == self.getSeatNo() && NightResolver.CHECK.equals(submission.action()))
                        .findFirst().orElse(null);
                if (check != null && check.targetSeat() != null) {
                    JudgeRoomPlayerEntity target = support.findSeatPlayer(players, check.targetSeat());
                    checkedSeat = check.targetSeat();
                    checkResult = target != null && "狼人".equals(target.getFaction()) ? "狼人" : "好人";
                }
            }
        }
        if (teammates.isEmpty() && picks.isEmpty() && knifeSeat == null && checkedSeat == null
                && antidote == null && lastGuardSeat == null && myAction == null && armed == null && canShoot == null) {
            return null;
        }
        return new WolfbookDtos.JudgeNightInfoView(teammates, picks, knifeSeat, checkedSeat, checkResult,
                antidote, poison, lastGuardSeat, myAction, myTarget, identityResult, armed, learned, learnedRole,
                learnedEffective, mechPoison, myActions, myConfirmed, canShoot);
    }

    WolfbookDtos.JudgeSpeechView speechView(JudgeRoomEntity room) {
        String phase = support.safeText(room.getCurrentPhase(), "");
        if (!PHASE_DAY_SPEECH.equals(phase) && !PHASE_SHERIFF_SPEECH.equals(phase)) {
            return null;
        }
        Object speech = support.readMap(room.getPhaseData()).get("speech");
        if (!(speech instanceof Map<?, ?> map)) {
            return null;
        }
        return new WolfbookDtos.JudgeSpeechView(support.asIntegerList(map.get("order")), support.asPositiveIntegerOrZero(map.get("index")),
                support.trimToNull(support.asString(map.get("direction"))));
    }

    /** 夜里当前这一步：持有者和法官看得到台词，其他人只知道是第几步、哪个角色（播报里本来就会念）。 */
    WolfbookDtos.JudgeStepView buildStepView(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                     JudgeRoomPlayerEntity self, boolean judgeViewer) {
        if (!ROOM_STATUS_PLAYING.equals(room.getRoomStatus()) || !PHASE_NIGHT_ACTION.equals(room.getCurrentPhase()) || room.getNightStep() == null) {
            return null;
        }
        List<NightPlan.NightStep> steps = support.nightSteps(room, support.safeInt(room.getCurrentDay(), 1));
        if (room.getNightStep() >= steps.size()) {
            return null;
        }
        NightPlan.NightStep step = steps.get(room.getNightStep());
        // 狼队全出局后带刀的机械狼也是狼人那一步的持有者（用户给定规则：刀口在狼人那一步选）
        boolean holder = self != null && Boolean.TRUE.equals(self.getPlaying()) && self.getRoleId() != null
                && (step.roleIds().contains(self.getRoleId())
                || (self.getSeatNo() != null && support.stepHolderSeats(players, step).contains(self.getSeatNo())));
        return new WolfbookDtos.JudgeStepView(
                step.index(),
                steps.size(),
                step.kind().name().toLowerCase(Locale.ROOT),
                step.roleName(),
                judgeViewer || holder ? step.line() : "",
                room.getStepDeadline()
        );
    }

    /** 快照里的对局记录：公开事件按天折叠；revealCauses（法官或已结束）时带上只给法官的死因。 */
    List<WolfbookDtos.JudgeRoundView> buildRounds(List<JudgeActionEventEntity> events, boolean revealCauses) {
        return JudgeDayFlow.buildRounds(events.stream()
                .filter(event -> VISIBILITY_PUBLIC.equals(event.getVisibility()) || VISIBILITY_SYSTEM.equals(event.getVisibility())
                        || (revealCauses && EVENT_NIGHT_CAUSES.equals(event.getActionType())))
                .map(event -> new JudgeDayFlow.EventRecord(support.safeInt(event.getDayNo(), 1), support.safeText(event.getActionType(), ""),
                        support.readMap(event.getPayloadJson()), support.readMap(event.getResultPayloadJson())))
                .toList());
    }

    /** 当前要展示的那轮投票：票型公布时是刚结算的那轮，PK 时是 PK，其余是放逐投票。 */
    String votePhase(JudgeRoomEntity room) {
        String phase = support.safeText(room.getCurrentPhase(), "");
        if (PHASE_VOTE_RESULT.equals(phase)) {
            return support.safeText(support.asString(support.readMap(room.getPhaseData()).get("resolvedFrom")), PHASE_EXILE_VOTE);
        }
        return PHASE_VOTE_PK.equals(phase) ? PHASE_VOTE_PK : PHASE_EXILE_VOTE;
    }

    /** 当前待办只发给当事人和法官。 */
    WolfbookDtos.JudgePendingView pendingView(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players,
                                                      JudgeRoomPlayerEntity self, boolean judgeViewer) {
        if (!PHASE_SKILL_PENDING.equals(room.getCurrentPhase()) || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            return null;
        }
        List<Map<String, Object>> pending = support.pendingList(support.readMap(room.getPhaseData()));
        if (pending.isEmpty()) {
            return null;
        }
        Map<String, Object> current = pending.get(0);
        int seat = support.safeInt(support.asPositiveInteger(current.get("seat")), 0);
        if (!judgeViewer && (self == null || !Objects.equals(self.getSeatNo(), seat))) {
            return null;
        }
        String kind = support.asString(current.get("kind"));
        List<String> options = PENDING_SKILL_ASK.equals(kind) ? support.skillOptions(support.findSeatPlayer(players, seat))
                : PENDING_LAST_WORDS.equals(kind) ? List.of("skip") : List.of("use", "skip");
        return new WolfbookDtos.JudgePendingView(kind, seat, options, support.parseDeadline(current));
    }

    /** 全场可见的当前待办：只有遗言公开（谁在说、到几点），其他待办不公开。 */
    WolfbookDtos.JudgePendingView publicPendingView(JudgeRoomEntity room) {
        if (!PHASE_SKILL_PENDING.equals(room.getCurrentPhase()) || !ROOM_STATUS_PLAYING.equals(room.getRoomStatus())) {
            return null;
        }
        List<Map<String, Object>> pending = support.pendingList(support.readMap(room.getPhaseData()));
        if (pending.isEmpty() || !PENDING_LAST_WORDS.equals(support.asString(pending.get(0).get("kind")))) {
            return null;
        }
        Map<String, Object> head = pending.get(0);
        return new WolfbookDtos.JudgePendingView(PENDING_LAST_WORDS, support.safeInt(support.asPositiveInteger(head.get("seat")), 0), List.of("skip"),
                support.parseDeadline(head));
    }

    WolfbookDtos.JudgePlayerView toPlayerView(JudgeRoomPlayerEntity player, boolean revealRole, boolean self) {
        return new WolfbookDtos.JudgePlayerView(
                player.getPlayerId(),
                self,
                support.safeText(player.getNickname(), "未命名玩家"),
                support.safeText(player.getAvatar(), ""),
                player.getSeatNo(),
                Boolean.TRUE.equals(player.getRoomOwner()),
                Boolean.TRUE.equals(player.getJudgeObserver()),
                Boolean.TRUE.equals(player.getPlaying()),
                Boolean.TRUE.equals(player.getReady()),
                Boolean.TRUE.equals(player.getAlive()),
                revealRole ? player.getRoleId() : null,
                revealRole ? support.trimToNull(player.getRoleName()) : null,
                revealRole ? support.trimToNull(player.getFaction()) : null,
                player.getDeathDay(),
                support.trimToNull(player.getDeathPhase()),
                revealRole ? support.mechanicOf(player).map(Mechanic::code).orElse(null) : null
        );
    }

    WolfbookDtos.JudgeEventView toEventView(JudgeActionEventEntity event, Map<String, JudgeRoomPlayerEntity> playersById) {
        JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
        List<Integer> targetSeatNos = readStringList(event.getTargetPlayerIdsJson()).stream()
                .map(playersById::get)
                .filter(Objects::nonNull)
                .map(JudgeRoomPlayerEntity::getSeatNo)
                .filter(Objects::nonNull)
                .toList();
        return new WolfbookDtos.JudgeEventView(
                event.getEventId(),
                support.safeInt(event.getDayNo(), 1),
                support.safeText(event.getPhase(), PHASE_LOBBY),
                event.getActorPlayerId(),
                actor == null ? null : actor.getSeatNo(),
                targetSeatNos,
                support.safeText(event.getActionType(), ""),
                support.readMap(event.getPayloadJson()),
                support.readMap(event.getResultPayloadJson()),
                support.safeText(event.getVisibility(), VISIBILITY_PUBLIC),
                event.getCreateTime()
        );
    }

    boolean canViewEvent(JudgeActionEventEntity event, boolean judgeViewer, String selfPlayerId) {
        return switch (support.safeText(event.getVisibility(), VISIBILITY_PUBLIC)) {
            case VISIBILITY_PUBLIC, VISIBILITY_SYSTEM -> true;
            case VISIBILITY_JUDGE_ONLY -> judgeViewer;
            case VISIBILITY_ACTOR_ONLY -> StringUtils.hasText(selfPlayerId) && selfPlayerId.equals(event.getActorPlayerId());
            default -> judgeViewer;
        };
    }

    List<WolfbookDtos.JudgeNightActionView> buildNightActions(
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
                .filter(event -> Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1)))
                .filter(event -> PHASE_NIGHT_ACTION.equals(event.getPhase()))
                .flatMap(event -> toNightActionViews(event, playersById).stream())
                .toList();
    }

    /** 历次夜晚的全部夜间行动：对局中只给法官（主持台据此判断女巫的药是否已用过等），结束后全场可见，结果页据此写战绩。 */
    List<WolfbookDtos.JudgeNightActionView> buildNightHistory(
            List<JudgeRoomPlayerEntity> players,
            List<JudgeActionEventEntity> events
    ) {
        Map<String, JudgeRoomPlayerEntity> playersById = players.stream()
                .collect(Collectors.toMap(JudgeRoomPlayerEntity::getPlayerId, player -> player));
        return events.stream()
                .filter(event -> EVENT_NIGHT_SUBMIT.equals(event.getActionType()))
                .sorted(Comparator.comparing(JudgeActionEventEntity::getCreateTime))
                .flatMap(event -> toNightActionViews(event, playersById).stream())
                .toList();
    }

    /** 一条夜间提交展开成法官 / 对局记录里的行：机械狼一次提交的多件事各占一行。 */
    List<WolfbookDtos.JudgeNightActionView> toNightActionViews(
            JudgeActionEventEntity event,
            Map<String, JudgeRoomPlayerEntity> playersById
    ) {
        Map<String, Object> payload = support.readMap(event.getPayloadJson());
        if (!(payload.get("actions") instanceof List<?> items)) {
            return List.of(toNightActionView(event, playersById));
        }
        JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
        List<WolfbookDtos.JudgeNightActionView> views = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                views.add(new WolfbookDtos.JudgeNightActionView(
                        event.getActorPlayerId(),
                        actor == null ? null : actor.getSeatNo(),
                        actor == null ? "未知玩家" : support.safeText(actor.getNickname(), "未知玩家"),
                        support.asPositiveInteger(map.get("targetSeatNo")),
                        support.safeText(support.asString(map.get("action")), NightResolver.NONE),
                        "",
                        support.safeInt(event.getDayNo(), 1),
                        event.getCreateTime()
                ));
            }
        }
        return views;
    }

    WolfbookDtos.JudgeNightActionView toNightActionView(
            JudgeActionEventEntity event,
            Map<String, JudgeRoomPlayerEntity> playersById
    ) {
        JudgeRoomPlayerEntity actor = playersById.get(event.getActorPlayerId());
        Map<String, Object> payload = support.readMap(event.getPayloadJson());
        return new WolfbookDtos.JudgeNightActionView(
                event.getActorPlayerId(),
                actor == null ? null : actor.getSeatNo(),
                actor == null ? "未知玩家" : support.safeText(actor.getNickname(), "未知玩家"),
                support.asPositiveInteger(payload.get("targetSeatNo")),
                support.safeText(support.asString(payload.get("actionType")), "night_action"),
                support.safeText(support.asString(payload.get("note")), ""),
                support.safeInt(event.getDayNo(), 1),
                event.getCreateTime()
        );
    }

    WolfbookDtos.JudgePendingActionView buildPendingNightAction(
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
                .filter(event -> Objects.equals(support.safeInt(event.getDayNo(), 1), support.safeInt(room.getCurrentDay(), 1)))
                .filter(event -> PHASE_NIGHT_ACTION.equals(event.getPhase()))
                .reduce((left, right) -> right)
                .orElse(null);
        if (latest == null) {
            return new WolfbookDtos.JudgePendingActionView(false, null, "");
        }
        Map<String, Object> payload = support.readMap(latest.getPayloadJson());
        Integer targetSeatNo = support.asPositiveInteger(payload.get("targetSeatNo"));
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
                support.safeText(support.asString(payload.get("note")), "")
        );
    }

    NightResolver.Submission lastSubmissionOf(int seat, Map<String, Object> payload) {
        if (payload.get("actions") instanceof List<?> items && !items.isEmpty() && items.get(items.size() - 1) instanceof Map<?, ?> map) {
            return new NightResolver.Submission(seat, null, support.safeText(support.asString(map.get("action")), NightResolver.NONE),
                    support.asPositiveInteger(map.get("targetSeatNo")));
        }
        String label = support.safeText(support.asString(payload.get("actionType")), "");
        return new NightResolver.Submission(seat, null,
                support.safeText(support.asString(payload.get("action")), RoleSkillRegistry.LEGACY_ACTIONS.getOrDefault(label, label)),
                support.asPositiveInteger(payload.get("targetSeatNo")));
    }

    /** 胜负提示：只有达成了板子的胜利条件才有（对局会随之自动结束），其余时候是空串。 */
    String buildWinnerSuggestion(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players) {
        return WinJudge.judge(support.winSeats(players), support.roomRules(room).winCondition()).map(WinJudge.Verdict::reason).orElse("");
    }

    String buildRoomTip(JudgeRoomEntity room, int joinedCount, int readyCount, boolean judgeViewer) {
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
        return switch (support.safeText(room.getCurrentPhase(), PHASE_LOBBY)) {
            case PHASE_NIGHT_ACTION -> judgeViewer ? "收齐夜间提交后，点击结算夜晚" : "当前为夜间阶段，提交你的夜间动作";
            case PHASE_DAY_SPEECH -> judgeViewer ? "发言阶段可广播信息，准备好后开启投票" : "当前为白天发言阶段，等待法官开启放逐投票";
            case PHASE_EXILE_VOTE -> judgeViewer ? "查看票型后结算放逐结果" : "当前为放逐投票阶段，请尽快提交投票";
            default -> "房间运行中";
        };
    }

    Comparator<JudgeRoomPlayerEntity> playerComparator() {
        return Comparator
                .comparing((JudgeRoomPlayerEntity player) -> player.getSeatNo() == null ? Integer.MAX_VALUE : player.getSeatNo())
                .thenComparing(JudgeRoomPlayerEntity::getCreateTime);
    }

    List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
