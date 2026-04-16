package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.UserFavoriteBoardEntity;
import com.wolfbook.backend.entity.UserNoteRecordEntity;
import com.wolfbook.backend.entity.UserNoteSessionEntity;
import com.wolfbook.backend.mapper.UserFavoriteBoardMapper;
import com.wolfbook.backend.mapper.UserNoteRecordMapper;
import com.wolfbook.backend.mapper.UserNoteSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户个人数据服务。
 *
 * <p>管理用户收藏、笔记局、笔记记录、复盘时间线和统计摘要。
 * 这部分数据和具体用户强绑定，所有入口都会围绕 openid 做权限隔离。</p>
 */
@Service
public class UserDataService {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_OFFSET_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern SEAT_PATTERN = Pattern.compile("(\\d{1,2})号");
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<WolfbookDtos.NotePlayerView>> NOTE_PLAYER_LIST = new TypeReference<>() {
    };
    private static final TypeReference<WolfbookDtos.NoteSessionSummaryView> NOTE_SUMMARY = new TypeReference<>() {
    };

    private final UserService userService;
    private final BoardService boardService;
    private final UserFavoriteBoardMapper userFavoriteBoardMapper;
    private final UserNoteSessionMapper userNoteSessionMapper;
    private final UserNoteRecordMapper userNoteRecordMapper;
    private final ObjectMapper objectMapper;

    public UserDataService(
            UserService userService,
            BoardService boardService,
            UserFavoriteBoardMapper userFavoriteBoardMapper,
            UserNoteSessionMapper userNoteSessionMapper,
            UserNoteRecordMapper userNoteRecordMapper,
            ObjectMapper objectMapper
    ) {
        this.userService = userService;
        this.boardService = boardService;
        this.userFavoriteBoardMapper = userFavoriteBoardMapper;
        this.userNoteSessionMapper = userNoteSessionMapper;
        this.userNoteRecordMapper = userNoteRecordMapper;
        this.objectMapper = objectMapper;
    }

    public WolfbookDtos.FavoriteBoardsView getFavoriteBoards(String authorization) {
        String openid = requireOpenid(authorization);
        return buildFavoriteBoardsView(openid);
    }

    @Transactional
    public WolfbookDtos.FavoriteBoardsView addFavoriteBoard(String authorization, Integer boardId) {
        String openid = requireOpenid(authorization);
        validateBoardId(boardId);
        UserFavoriteBoardEntity existing = userFavoriteBoardMapper.selectOne(
                new LambdaQueryWrapper<UserFavoriteBoardEntity>()
                        .eq(UserFavoriteBoardEntity::getOpenid, openid)
                        .eq(UserFavoriteBoardEntity::getBoardId, boardId)
                        .last("limit 1")
        );
        if (existing == null) {
            UserFavoriteBoardEntity entity = new UserFavoriteBoardEntity();
            entity.setOpenid(openid);
            entity.setBoardId(boardId);
            entity.setCreateTime(LocalDateTime.now(APP_ZONE));
            userFavoriteBoardMapper.insert(entity);
        }
        return buildFavoriteBoardsView(openid);
    }

    @Transactional
    public WolfbookDtos.FavoriteBoardsView removeFavoriteBoard(String authorization, Integer boardId) {
        String openid = requireOpenid(authorization);
        userFavoriteBoardMapper.delete(
                new LambdaQueryWrapper<UserFavoriteBoardEntity>()
                        .eq(UserFavoriteBoardEntity::getOpenid, openid)
                        .eq(UserFavoriteBoardEntity::getBoardId, boardId)
        );
        return buildFavoriteBoardsView(openid);
    }

    public List<WolfbookDtos.NoteSessionView> listNoteSessions(String authorization) {
        String openid = requireOpenid(authorization);
        List<UserNoteSessionEntity> sessions = userNoteSessionMapper.selectList(
                new LambdaQueryWrapper<UserNoteSessionEntity>()
                        .eq(UserNoteSessionEntity::getOpenid, openid)
                        .orderByDesc(UserNoteSessionEntity::getUpdateTime)
        );
        return mapSessions(sessions, loadRecordsBySessionIds(openid, sessions.stream().map(UserNoteSessionEntity::getSessionId).toList()));
    }

    public WolfbookDtos.NoteSessionView getNoteSession(String authorization, String sessionId) {
        String openid = requireOpenid(authorization);
        UserNoteSessionEntity session = getOwnedSession(openid, sessionId);
        Map<String, List<UserNoteRecordEntity>> recordMap = loadRecordsBySessionIds(openid, List.of(sessionId));
        return toNoteSessionView(session, recordMap.getOrDefault(sessionId, List.of()));
    }

    @Transactional
    public WolfbookDtos.NoteSessionView saveNoteSession(String authorization, String sessionId, WolfbookDtos.NoteSessionSaveRequest request) {
        String openid = requireOpenid(authorization);
        String resolvedSessionId = StringUtils.hasText(sessionId) ? sessionId : request.sessionId();
        if (!StringUtils.hasText(resolvedSessionId)) {
            throw new ApiException(4000, "对局 id 不能为空");
        }

        String boardMode = normalizeBoardMode(request.boardMode());
        Integer boardId = null;
        String boardName = safeText(request.boardName());
        if ("library".equals(boardMode)) {
            validateBoardId(request.boardId());
            boardId = request.boardId();
            boardName = boardService.getBoard(boardId).name();
        } else if (!StringUtils.hasText(boardName)) {
            throw new ApiException(4000, "板子名称不能为空");
        }

        UserNoteSessionEntity existing = userNoteSessionMapper.selectById(resolvedSessionId);
        if (existing != null && !openid.equals(existing.getOpenid())) {
            throw new ApiException(4004, "对局不存在");
        }

        LocalDateTime now = LocalDateTime.now(APP_ZONE);
        List<WolfbookDtos.NoteRecordView> normalizedRecords = normalizeRecordInputs(request.records(), now);
        List<WolfbookDtos.NotePlayerView> normalizedPlayers = normalizePlayers(request.players(), request.playerCount(), request.sheriffSeat(), now);
        Integer sheriffSeat = normalizeSheriffSeat(request.sheriffSeat(), normalizedPlayers);
        normalizedPlayers = applySheriffSeat(normalizedPlayers, sheriffSeat);
        Integer currentDay = normalizeCurrentDay(request.currentDay(), normalizedRecords);
        String currentPhase = normalizeCurrentPhase(request.currentPhase(), normalizedRecords);
        WolfbookDtos.NoteSessionSummaryView normalizedSummary = normalizeSummary(request.summary(), normalizedPlayers, normalizedRecords, currentDay);

        UserNoteSessionEntity entity = existing == null ? new UserNoteSessionEntity() : existing;
        entity.setSessionId(resolvedSessionId);
        entity.setVersion(normalizeVersion(request.version()));
        entity.setOpenid(openid);
        entity.setBoardMode(boardMode);
        entity.setBoardId(boardId);
        entity.setBoardName(boardName);
        entity.setPlayerCount(Math.max(1, request.playerCount()));
        entity.setStatus(normalizeStatus(request.status()));
        entity.setCurrentDay(currentDay);
        entity.setCurrentPhase(currentPhase);
        entity.setResultCamp(safeText(request.resultCamp()));
        entity.setSheriffSeat(sheriffSeat);
        entity.setPlayersJson(writeJson(normalizedPlayers));
        entity.setSummaryJson(writeJson(normalizedSummary));
        entity.setCreateTime(existing == null ? parseDateTime(request.createTime(), now) : existing.getCreateTime());
        entity.setUpdateTime(parseDateTime(request.updateTime(), now));

        if (existing == null) {
            userNoteSessionMapper.insert(entity);
        } else {
            userNoteSessionMapper.updateById(entity);
        }

        userNoteRecordMapper.delete(
                new LambdaQueryWrapper<UserNoteRecordEntity>()
                        .eq(UserNoteRecordEntity::getSessionId, resolvedSessionId)
                        .eq(UserNoteRecordEntity::getOpenid, openid)
        );

        for (WolfbookDtos.NoteRecordView record : normalizedRecords) {
            userNoteRecordMapper.insert(toRecordEntity(resolvedSessionId, openid, record, now));
        }

        return getNoteSession(authorization, resolvedSessionId);
    }

    @Transactional
    public void deleteNoteSession(String authorization, String sessionId) {
        String openid = requireOpenid(authorization);
        getOwnedSession(openid, sessionId);
        userNoteRecordMapper.delete(
                new LambdaQueryWrapper<UserNoteRecordEntity>()
                        .eq(UserNoteRecordEntity::getSessionId, sessionId)
                        .eq(UserNoteRecordEntity::getOpenid, openid)
        );
        userNoteSessionMapper.delete(
                new LambdaQueryWrapper<UserNoteSessionEntity>()
                        .eq(UserNoteSessionEntity::getSessionId, sessionId)
                        .eq(UserNoteSessionEntity::getOpenid, openid)
        );
    }

    private String requireOpenid(String authorization) {
        UserProfile user = userService.requireUser(authorization);
        return user.openid();
    }

    private void validateBoardId(Integer boardId) {
        if (boardId == null || boardId <= 0) {
            throw new ApiException(4000, "板子不存在");
        }
        boardService.getBoard(boardId);
    }

    private String normalizeBoardMode(String boardMode) {
        if ("library".equals(boardMode) || "custom".equals(boardMode)) {
            return boardMode;
        }
        throw new ApiException(4000, "无效的板子模式");
    }

    private String normalizeBoardModeForView(String boardMode, Integer boardId) {
        if ("custom".equals(boardMode)) {
            return "custom";
        }
        return boardId != null && boardId > 0 ? "library" : "custom";
    }

    private String normalizeStatus(String status) {
        if ("finished".equals(status) || "archived".equals(status)) {
            return status;
        }
        return "active";
    }

    private Integer normalizeVersion(Integer version) {
        return version != null && version > 0 ? version : 2;
    }

    private Integer normalizeCurrentDay(Integer currentDay, List<WolfbookDtos.NoteRecordView> records) {
        int inferred = inferCurrentDay(records);
        int value = currentDay != null && currentDay > 0 ? currentDay : inferred;
        return Math.max(1, value);
    }

    private String normalizeCurrentPhase(String currentPhase, List<WolfbookDtos.NoteRecordView> records) {
        if (StringUtils.hasText(currentPhase)) {
            return currentPhase.trim();
        }
        return inferCurrentPhase(records);
    }

    private WolfbookDtos.FavoriteBoardsView buildFavoriteBoardsView(String openid) {
        List<Integer> boardIds = userFavoriteBoardMapper.selectList(
                        new LambdaQueryWrapper<UserFavoriteBoardEntity>()
                                .eq(UserFavoriteBoardEntity::getOpenid, openid)
                                .orderByDesc(UserFavoriteBoardEntity::getCreateTime)
                                .orderByDesc(UserFavoriteBoardEntity::getId)
                ).stream()
                .map(UserFavoriteBoardEntity::getBoardId)
                .filter(Objects::nonNull)
                .toList();

        List<WolfbookDtos.BoardCardView> boards = boardService.listBoardCardsByIds(boardIds);
        List<Integer> normalizedIds = boards.stream().map(WolfbookDtos.BoardCardView::id).toList();
        return new WolfbookDtos.FavoriteBoardsView(normalizedIds, boards);
    }

    private UserNoteSessionEntity getOwnedSession(String openid, String sessionId) {
        UserNoteSessionEntity session = userNoteSessionMapper.selectById(sessionId);
        if (session == null || !openid.equals(session.getOpenid())) {
            throw new ApiException(4004, "对局不存在");
        }
        return session;
    }

    private Map<String, List<UserNoteRecordEntity>> loadRecordsBySessionIds(String openid, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        return userNoteRecordMapper.selectList(
                        new LambdaQueryWrapper<UserNoteRecordEntity>()
                                .eq(UserNoteRecordEntity::getOpenid, openid)
                                .in(UserNoteRecordEntity::getSessionId, sessionIds)
                                .orderByAsc(UserNoteRecordEntity::getCreateTime)
                                .orderByAsc(UserNoteRecordEntity::getUpdateTime)
                ).stream()
                .collect(Collectors.groupingBy(UserNoteRecordEntity::getSessionId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<WolfbookDtos.NoteSessionView> mapSessions(List<UserNoteSessionEntity> sessions, Map<String, List<UserNoteRecordEntity>> recordMap) {
        return sessions.stream()
                .map(session -> toNoteSessionView(session, recordMap.getOrDefault(session.getSessionId(), List.of())))
                .toList();
    }

    private WolfbookDtos.NoteSessionView toNoteSessionView(UserNoteSessionEntity session, List<UserNoteRecordEntity> records) {
        List<WolfbookDtos.NoteRecordView> recordViews = records.stream()
                .sorted(Comparator.comparing(this::recordEntityTime))
                .map(this::toNoteRecordView)
                .toList();

        List<WolfbookDtos.NotePlayerView> players = readJson(session.getPlayersJson(), NOTE_PLAYER_LIST, List.of());
        if (players.isEmpty()) {
            players = buildDefaultPlayers(session.getPlayerCount(), session.getSheriffSeat(), session.getCreateTime());
        } else {
            players = applySheriffSeat(players, normalizeSheriffSeat(session.getSheriffSeat(), players));
        }

        Integer sheriffSeat = normalizeSheriffSeat(session.getSheriffSeat(), players);
        Integer currentDay = normalizeCurrentDay(session.getCurrentDay(), recordViews);
        String currentPhase = normalizeCurrentPhase(session.getCurrentPhase(), recordViews);
        WolfbookDtos.NoteSessionSummaryView summary = mergeSummary(
                readJson(session.getSummaryJson(), NOTE_SUMMARY, null),
                players,
                recordViews,
                currentDay
        );

        return new WolfbookDtos.NoteSessionView(
                session.getSessionId(),
                normalizeVersion(session.getVersion()),
                normalizeBoardModeForView(session.getBoardMode(), session.getBoardId()),
                session.getBoardId(),
                safeText(session.getBoardName()),
                Math.max(1, session.getPlayerCount() == null ? 12 : session.getPlayerCount()),
                normalizeStatus(session.getStatus()),
                currentDay,
                currentPhase,
                safeText(session.getResultCamp()),
                sheriffSeat,
                players,
                summary,
                toIso(session.getCreateTime()),
                toIso(session.getUpdateTime()),
                recordViews
        );
    }

    private UserNoteRecordEntity toRecordEntity(String sessionId, String openid, WolfbookDtos.NoteRecordView record, LocalDateTime fallbackNow) {
        UserNoteRecordEntity entity = new UserNoteRecordEntity();
        entity.setRecordId(StringUtils.hasText(record.id()) ? record.id() : makeId("rec"));
        entity.setSessionId(sessionId);
        entity.setOpenid(openid);
        entity.setRecordType(StringUtils.hasText(record.type()) ? record.type() : "speech");
        entity.setScene(StringUtils.hasText(record.scene()) ? record.scene() : inferSceneByType(record.type()));
        entity.setDayNo(Math.max(1, record.day() == null ? 1 : record.day()));
        entity.setPhase(StringUtils.hasText(record.phase()) ? record.phase() : inferPhaseByType(record.type(), record.scene()));
        entity.setActorSeatsJson(writeJson(normalizeIntegerList(record.actorSeats())));
        entity.setTargetSeatsJson(writeJson(normalizeIntegerList(record.targetSeats())));
        entity.setContent(StringUtils.hasText(record.content()) ? record.content().trim() : buildFallbackContent(record));
        entity.setPlayer(safeText(record.player()));
        entity.setPayloadJson(writeJson(normalizePayload(record.payload(), record.type(), record.content(), record.actorSeats(), record.targetSeats())));
        entity.setTagsJson(writeJson(normalizeStringList(record.tags())));
        entity.setEditable(record.editable() == null || record.editable());
        entity.setCreateTime(parseDateTime(firstNonBlank(record.createTime(), record.timestamp(), record.updateTime()), fallbackNow));
        entity.setUpdateTime(parseDateTime(firstNonBlank(record.updateTime(), record.timestamp(), record.createTime()), entity.getCreateTime()));
        return entity;
    }

    private WolfbookDtos.NoteRecordView toNoteRecordView(UserNoteRecordEntity record) {
        String type = StringUtils.hasText(record.getRecordType()) ? record.getRecordType().trim() : "speech";
        String scene = StringUtils.hasText(record.getScene()) ? record.getScene().trim() : inferSceneByType(type);
        Integer day = Math.max(1, record.getDayNo() == null ? 1 : record.getDayNo());
        List<Integer> actorSeats = normalizeIntegerList(readJson(record.getActorSeatsJson(), INTEGER_LIST, List.of()));
        if (actorSeats.isEmpty()) {
            actorSeats = extractSeatNos(record.getPlayer());
        }
        List<Integer> targetSeats = normalizeIntegerList(readJson(record.getTargetSeatsJson(), INTEGER_LIST, List.of()));
        if (targetSeats.isEmpty()) {
            targetSeats = inferTargetSeats(type, record.getContent(), actorSeats, readJson(record.getPayloadJson(), OBJECT_MAP, Map.of()));
        }
        Map<String, Object> payload = normalizePayload(
                readJson(record.getPayloadJson(), OBJECT_MAP, Map.of()),
                type,
                record.getContent(),
                actorSeats,
                targetSeats
        );
        List<String> tags = normalizeStringList(readJson(record.getTagsJson(), STRING_LIST, List.of()));
        LocalDateTime createTime = record.getCreateTime();
        LocalDateTime updateTime = record.getUpdateTime() == null ? createTime : record.getUpdateTime();

        return new WolfbookDtos.NoteRecordView(
                record.getRecordId(),
                type,
                scene,
                day,
                day,
                StringUtils.hasText(record.getPhase()) ? record.getPhase().trim() : inferPhaseByType(type, scene),
                actorSeats,
                targetSeats,
                StringUtils.hasText(record.getContent()) ? record.getContent().trim() : buildFallbackContent(type, actorSeats, targetSeats, payload, record.getPlayer()),
                safeText(record.getPlayer()),
                payload,
                tags,
                record.getEditable() == null || record.getEditable(),
                toIso(updateTime),
                toIso(createTime),
                toIso(updateTime)
        );
    }

    private List<WolfbookDtos.NoteRecordView> normalizeRecordInputs(List<WolfbookDtos.NoteRecordInput> records, LocalDateTime now) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(record -> normalizeRecordInput(record, now))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::recordViewTime))
                .toList();
    }

    private WolfbookDtos.NoteRecordView normalizeRecordInput(WolfbookDtos.NoteRecordInput record, LocalDateTime now) {
        if (record == null) {
            return null;
        }
        String type = StringUtils.hasText(record.type()) ? record.type().trim() : "speech";
        String scene = StringUtils.hasText(record.scene()) ? record.scene().trim() : inferSceneByType(type);
        Integer day = Math.max(1, firstPositive(record.day(), record.round(), 1));
        List<Integer> actorSeats = normalizeIntegerList(record.actorSeats());
        if (actorSeats.isEmpty()) {
            actorSeats = extractSeatNos(record.player());
        }
        List<Integer> targetSeats = normalizeIntegerList(record.targetSeats());
        if (targetSeats.isEmpty()) {
            targetSeats = inferTargetSeats(type, record.content(), actorSeats, record.payload());
        }
        Map<String, Object> payload = normalizePayload(record.payload(), type, record.content(), actorSeats, targetSeats);
        List<String> tags = normalizeStringList(record.tags());
        LocalDateTime createTime = parseDateTime(firstNonBlank(record.createTime(), record.timestamp(), record.updateTime()), now);
        LocalDateTime updateTime = parseDateTime(firstNonBlank(record.updateTime(), record.timestamp(), record.createTime()), createTime);
        String player = safeText(record.player());
        String content = StringUtils.hasText(record.content())
                ? record.content().trim()
                : buildFallbackContent(type, actorSeats, targetSeats, payload, player);

        return new WolfbookDtos.NoteRecordView(
                StringUtils.hasText(record.id()) ? record.id() : makeId("rec"),
                type,
                scene,
                day,
                Math.max(1, firstPositive(record.round(), record.day(), 1)),
                StringUtils.hasText(record.phase()) ? record.phase().trim() : inferPhaseByType(type, scene),
                actorSeats,
                targetSeats,
                content,
                player,
                payload,
                tags,
                record.editable() == null || record.editable(),
                toIso(updateTime),
                toIso(createTime),
                toIso(updateTime)
        );
    }

    private List<WolfbookDtos.NotePlayerView> normalizePlayers(
            List<WolfbookDtos.NotePlayerInput> players,
            Integer playerCount,
            Integer sheriffSeat,
            LocalDateTime now
    ) {
        int count = Math.max(1, Math.max(playerCount == null ? 12 : playerCount, maxSeatNo(players)));
        if (players == null || players.isEmpty()) {
            return buildDefaultPlayers(count, sheriffSeat, now);
        }
        Map<Integer, WolfbookDtos.NotePlayerInput> playerMap = players.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.seatNo() != null && item.seatNo() > 0)
                .collect(Collectors.toMap(WolfbookDtos.NotePlayerInput::seatNo, item -> item, (left, right) -> right, LinkedHashMap::new));

        List<WolfbookDtos.NotePlayerView> result = new ArrayList<>();
        for (int seatNo = 1; seatNo <= count; seatNo++) {
            WolfbookDtos.NotePlayerInput source = playerMap.get(seatNo);
            result.add(normalizePlayer(source, seatNo, sheriffSeat, now));
        }
        return result;
    }

    private int maxSeatNo(List<WolfbookDtos.NotePlayerInput> players) {
        if (players == null || players.isEmpty()) {
            return 0;
        }
        return players.stream()
                .map(WolfbookDtos.NotePlayerInput::seatNo)
                .filter(Objects::nonNull)
                .filter(item -> item > 0)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private WolfbookDtos.NotePlayerView normalizePlayer(
            WolfbookDtos.NotePlayerInput player,
            int seatNo,
            Integer sheriffSeat,
            LocalDateTime now
    ) {
        LocalDateTime createTime = parseDateTime(player == null ? null : player.createTime(), now);
        LocalDateTime updateTime = parseDateTime(player == null ? null : player.updateTime(), createTime);
        return new WolfbookDtos.NotePlayerView(
                seatNo,
                player == null ? "" : safeText(player.nickname()),
                player == null || player.alive() == null || player.alive(),
                sheriffSeat != null ? sheriffSeat == seatNo : player != null && Boolean.TRUE.equals(player.isSheriff()),
                player == null ? "" : safeText(player.claimedRole()),
                player == null ? "" : safeText(player.realRole()),
                player == null ? "" : safeText(player.factionHint()),
                clamp(player == null ? null : player.suspicionLevel(), 0, 3),
                normalizeStringList(player == null ? null : player.tags()),
                player == null ? null : normalizeNullablePositive(player.deathDay()),
                player == null ? "" : safeText(player.deathPhase()),
                player == null ? "" : safeText(player.deathReason()),
                player != null && Boolean.TRUE.equals(player.isFocus()),
                player == null ? "" : safeText(player.note()),
                toIso(createTime),
                toIso(updateTime)
        );
    }

    private List<WolfbookDtos.NotePlayerView> buildDefaultPlayers(Integer playerCount, Integer sheriffSeat, LocalDateTime now) {
        int count = Math.max(1, playerCount == null ? 12 : playerCount);
        List<WolfbookDtos.NotePlayerView> players = new ArrayList<>();
        for (int seatNo = 1; seatNo <= count; seatNo++) {
            players.add(new WolfbookDtos.NotePlayerView(
                    seatNo,
                    "",
                    true,
                    sheriffSeat != null && sheriffSeat == seatNo,
                    "",
                    "",
                    "",
                    0,
                    List.of(),
                    null,
                    "",
                    "",
                    false,
                    "",
                    toIso(now),
                    toIso(now)
            ));
        }
        return players;
    }

    private List<WolfbookDtos.NotePlayerView> applySheriffSeat(List<WolfbookDtos.NotePlayerView> players, Integer sheriffSeat) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        return players.stream()
                .map(player -> new WolfbookDtos.NotePlayerView(
                        player.seatNo(),
                        player.nickname(),
                        player.alive(),
                        sheriffSeat != null && sheriffSeat.equals(player.seatNo()),
                        player.claimedRole(),
                        player.realRole(),
                        player.factionHint(),
                        player.suspicionLevel(),
                        player.tags(),
                        player.deathDay(),
                        player.deathPhase(),
                        player.deathReason(),
                        player.isFocus(),
                        player.note(),
                        player.createTime(),
                        player.updateTime()
                ))
                .toList();
    }

    private Integer normalizeSheriffSeat(Integer sheriffSeat, List<WolfbookDtos.NotePlayerView> players) {
        if (sheriffSeat != null && sheriffSeat > 0) {
            return sheriffSeat;
        }
        if (players == null || players.isEmpty()) {
            return null;
        }
        return players.stream()
                .filter(player -> Boolean.TRUE.equals(player.isSheriff()))
                .map(WolfbookDtos.NotePlayerView::seatNo)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private WolfbookDtos.NoteSessionSummaryView normalizeSummary(
            WolfbookDtos.NoteSessionSummaryInput summary,
            List<WolfbookDtos.NotePlayerView> players,
            List<WolfbookDtos.NoteRecordView> records,
            Integer currentDay
    ) {
        WolfbookDtos.NoteSessionSummaryView existing = summary == null ? null : new WolfbookDtos.NoteSessionSummaryView(
                summary.aliveCount(),
                summary.deadCount(),
                summary.todayOutSeat(),
                normalizeIntegerList(summary.latestWolfPackSeats()),
                safeText(summary.latestWolfPackText()),
                summary.keyEventCount(),
                safeText(summary.latestRecordType()),
                safeText(summary.latestRecordPreview())
        );
        return mergeSummary(existing, players, records, currentDay);
    }

    private WolfbookDtos.NoteSessionSummaryView mergeSummary(
            WolfbookDtos.NoteSessionSummaryView summary,
            List<WolfbookDtos.NotePlayerView> players,
            List<WolfbookDtos.NoteRecordView> records,
            Integer currentDay
    ) {
        WolfbookDtos.NoteSessionSummaryView rebuilt = rebuildSummary(players, records, currentDay);
        if (summary == null) {
            return rebuilt;
        }
        return new WolfbookDtos.NoteSessionSummaryView(
                positiveOrDefault(summary.aliveCount(), rebuilt.aliveCount()),
                positiveOrDefault(summary.deadCount(), rebuilt.deadCount()),
                summary.todayOutSeat() != null && summary.todayOutSeat() > 0 ? summary.todayOutSeat() : rebuilt.todayOutSeat(),
                normalizeIntegerList(summary.latestWolfPackSeats()).isEmpty() ? rebuilt.latestWolfPackSeats() : normalizeIntegerList(summary.latestWolfPackSeats()),
                StringUtils.hasText(summary.latestWolfPackText()) ? summary.latestWolfPackText() : rebuilt.latestWolfPackText(),
                positiveOrDefault(summary.keyEventCount(), rebuilt.keyEventCount()),
                StringUtils.hasText(summary.latestRecordType()) ? summary.latestRecordType() : rebuilt.latestRecordType(),
                StringUtils.hasText(summary.latestRecordPreview()) ? summary.latestRecordPreview() : rebuilt.latestRecordPreview()
        );
    }

    private WolfbookDtos.NoteSessionSummaryView rebuildSummary(
            List<WolfbookDtos.NotePlayerView> players,
            List<WolfbookDtos.NoteRecordView> records,
            Integer currentDay
    ) {
        int aliveCount = (int) players.stream().filter(player -> player.alive() == null || player.alive()).count();
        int deadCount = Math.max(0, players.size() - aliveCount);
        List<Integer> latestWolfPackSeats = inferLatestWolfPackSeats(records);
        WolfbookDtos.NoteRecordView latestRecord = records.isEmpty() ? null : records.get(records.size() - 1);

        return new WolfbookDtos.NoteSessionSummaryView(
                aliveCount,
                deadCount,
                inferTodayOutSeat(records, currentDay),
                latestWolfPackSeats,
                seatListToText(latestWolfPackSeats),
                (int) records.stream().filter(record -> !normalizeStringList(record.tags()).isEmpty()).count(),
                latestRecord == null ? "" : safeText(latestRecord.type()),
                latestRecord == null ? "" : safeText(latestRecord.content())
        );
    }

    private Integer inferCurrentDay(List<WolfbookDtos.NoteRecordView> records) {
        if (records == null || records.isEmpty()) {
            return 1;
        }
        return records.stream()
                .map(WolfbookDtos.NoteRecordView::day)
                .filter(Objects::nonNull)
                .filter(day -> day > 0)
                .max(Integer::compareTo)
                .orElse(1);
    }

    private String inferCurrentPhase(List<WolfbookDtos.NoteRecordView> records) {
        if (records == null || records.isEmpty()) {
            return "day_speech";
        }
        WolfbookDtos.NoteRecordView latest = records.get(records.size() - 1);
        if (StringUtils.hasText(latest.phase())) {
            return latest.phase();
        }
        return inferPhaseByType(latest.type(), latest.scene());
    }

    private Integer inferTodayOutSeat(List<WolfbookDtos.NoteRecordView> records, Integer currentDay) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        int day = currentDay != null && currentDay > 0 ? currentDay : inferCurrentDay(records);
        for (int index = records.size() - 1; index >= 0; index--) {
            WolfbookDtos.NoteRecordView record = records.get(index);
            if (!Objects.equals(record.day(), day)) {
                continue;
            }
            if (!"vote".equals(record.type())) {
                continue;
            }
            if (!"exile_vote".equals(record.phase()) && !"sheriff_race".equals(record.phase())) {
                continue;
            }
            List<Integer> targetSeats = normalizeIntegerList(record.targetSeats());
            if (!targetSeats.isEmpty()) {
                return targetSeats.get(0);
            }
        }
        return null;
    }

    private List<Integer> inferLatestWolfPackSeats(List<WolfbookDtos.NoteRecordView> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            WolfbookDtos.NoteRecordView record = records.get(index);
            if ("wolfPack".equals(record.type())) {
                List<Integer> seats = normalizeIntegerList(record.targetSeats());
                if (!seats.isEmpty()) {
                    return seats;
                }
                return extractSeatNos(record.content());
            }
            Object subtype = record.payload() == null ? null : record.payload().get("subtype");
            if ("note".equals(record.type()) && "wolf_pack".equals(String.valueOf(subtype))) {
                List<Integer> seats = normalizeIntegerList(record.targetSeats());
                if (!seats.isEmpty()) {
                    return seats;
                }
                return asIntegerList(record.payload().get("targetSeats"));
            }
        }
        return List.of();
    }

    private LocalDateTime recordEntityTime(UserNoteRecordEntity record) {
        return record.getUpdateTime() == null ? record.getCreateTime() : record.getUpdateTime();
    }

    private LocalDateTime recordViewTime(WolfbookDtos.NoteRecordView record) {
        return parseDateTime(firstNonBlank(record.updateTime(), record.timestamp(), record.createTime()), LocalDateTime.now(APP_ZONE));
    }

    private String inferSceneByType(String type) {
        if ("seer".equals(type) || "nightAction".equals(type)) {
            return "night";
        }
        if ("vote".equals(type)) {
            return "vote";
        }
        if ("speech".equals(type)) {
            return "speech";
        }
        if ("identity".equals(type)) {
            return "identity";
        }
        return "note";
    }

    private String inferPhaseByType(String type, String scene) {
        if ("night".equals(scene) || "seer".equals(type) || "nightAction".equals(type)) {
            return "night";
        }
        if ("vote".equals(type) || "vote".equals(scene)) {
            return "exile_vote";
        }
        return "day_speech";
    }

    private List<Integer> inferTargetSeats(String type, String content, List<Integer> actorSeats, Map<String, Object> payload) {
        List<Integer> fromPayload = extractTargetSeatsFromPayload(payload);
        if (!fromPayload.isEmpty()) {
            return fromPayload;
        }
        List<Integer> seats = extractSeatNos(content);
        if (seats.isEmpty()) {
            return List.of();
        }
        if ("vote".equals(type) && !actorSeats.isEmpty()) {
            List<Integer> filtered = seats.stream()
                    .filter(seat -> !actorSeats.contains(seat))
                    .toList();
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }
        if (("wolfPack".equals(type) || "note".equals(type)) && seats.size() > 1) {
            return seats;
        }
        return seats.size() > 1 ? List.of(seats.get(seats.size() - 1)) : seats;
    }

    private List<Integer> extractTargetSeatsFromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        List<Integer> directTargets = asIntegerList(payload.get("targetSeats"));
        if (!directTargets.isEmpty()) {
            return directTargets;
        }
        directTargets = asIntegerList(payload.get("targets"));
        if (!directTargets.isEmpty()) {
            return directTargets;
        }
        Integer singleTarget = asPositiveInteger(payload.get("targetSeat"));
        if (singleTarget != null) {
            return List.of(singleTarget);
        }
        singleTarget = asPositiveInteger(payload.get("target"));
        if (singleTarget != null) {
            return List.of(singleTarget);
        }
        return List.of();
    }

    private Map<String, Object> normalizePayload(
            Map<String, Object> payload,
            String type,
            String content,
            List<Integer> actorSeats,
            List<Integer> targetSeats
    ) {
        if (payload != null && !payload.isEmpty()) {
            return new LinkedHashMap<>(payload);
        }
        return buildLegacyPayload(type, content, actorSeats, targetSeats);
    }

    private Map<String, Object> buildLegacyPayload(String type, String content, List<Integer> actorSeats, List<Integer> targetSeats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("seer".equals(type) || "nightAction".equals(type)) {
            payload.put("subtype", "legacy");
            payload.put("checkerSeat", actorSeats.isEmpty() ? null : actorSeats.get(0));
            payload.put("targetSeat", targetSeats.isEmpty() ? null : targetSeats.get(0));
            payload.put("rawText", safeText(content));
            return payload;
        }
        if ("vote".equals(type)) {
            payload.put("template", "legacy");
            payload.put("voters", actorSeats);
            payload.put("target", targetSeats.isEmpty() ? null : targetSeats.get(0));
            payload.put("targets", targetSeats);
            payload.put("remark", "");
            payload.put("rawText", safeText(content));
            return payload;
        }
        if ("speech".equals(type)) {
            payload.put("speakerSeat", actorSeats.isEmpty() ? null : actorSeats.get(0));
            payload.put("speechTags", List.of());
            payload.put("remark", safeText(content));
            return payload;
        }
        if ("identity".equals(type)) {
            payload.put("action", "legacy");
            payload.put("seat", actorSeats.isEmpty() ? null : actorSeats.get(0));
            payload.put("rawText", safeText(content));
            return payload;
        }
        if ("wolfPack".equals(type)) {
            payload.put("subtype", "wolf_pack");
            payload.put("targetSeats", targetSeats.isEmpty() ? actorSeats : targetSeats);
            payload.put("rawText", safeText(content));
            return payload;
        }
        payload.put("rawText", safeText(content));
        return payload;
    }

    private String buildFallbackContent(WolfbookDtos.NoteRecordView record) {
        return buildFallbackContent(record.type(), record.actorSeats(), record.targetSeats(), record.payload(), record.player());
    }

    private String buildFallbackContent(String type, List<Integer> actorSeats, List<Integer> targetSeats, Map<String, Object> payload, String player) {
        if ("vote".equals(type) && !targetSeats.isEmpty()) {
            String voters = seatListToText(actorSeats);
            if (StringUtils.hasText(voters)) {
                return voters + " 投给 " + targetSeats.get(0) + "号";
            }
            return "投票给 " + targetSeats.get(0) + "号";
        }
        if (("seer".equals(type) || "nightAction".equals(type)) && !targetSeats.isEmpty()) {
            Integer actor = actorSeats.isEmpty() ? null : actorSeats.get(0);
            return actor == null ? "夜间行动 -> " + targetSeats.get(0) + "号" : actor + "号夜间行动 -> " + targetSeats.get(0) + "号";
        }
        if ("wolfPack".equals(type) || ("note".equals(type) && "wolf_pack".equals(String.valueOf(payload == null ? null : payload.get("subtype"))))) {
            return "狼坑：" + seatListToText(targetSeats);
        }
        if (StringUtils.hasText(player)) {
            return player + " 的局内记录";
        }
        return "局内记录";
    }

    private List<Integer> extractSeatNos(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        LinkedHashSet<Integer> seats = new LinkedHashSet<>();
        Matcher matcher = SEAT_PATTERN.matcher(text);
        while (matcher.find()) {
            Integer seatNo = asPositiveInteger(matcher.group(1));
            if (seatNo != null) {
                seats.add(seatNo);
            }
        }
        return List.copyOf(seats);
    }

    private List<Integer> normalizeIntegerList(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Object value : values) {
            Integer number = asPositiveInteger(value);
            if (number != null) {
                result.add(number);
            }
        }
        return List.copyOf(result);
    }

    private List<String> normalizeStringList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private List<Integer> asIntegerList(Object value) {
        if (value instanceof List<?> list) {
            return normalizeIntegerList(list);
        }
        Integer number = asPositiveInteger(value);
        if (number != null) {
            return List.of(number);
        }
        return List.of();
    }

    private Integer asPositiveInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
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

    private Integer normalizeNullablePositive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private int positiveOrDefault(Integer value, Integer fallback) {
        return value != null && value >= 0 ? value : (fallback == null ? 0 : fallback);
    }

    private int clamp(Integer value, int min, int max) {
        int current = value == null ? min : value;
        return Math.max(min, Math.min(max, current));
    }

    private int firstPositive(Integer first, Integer second, int fallback) {
        if (first != null && first > 0) {
            return first;
        }
        if (second != null && second > 0) {
            return second;
        }
        return fallback;
    }

    private String seatListToText(List<Integer> seats) {
        if (seats == null || seats.isEmpty()) {
            return "";
        }
        return seats.stream().map(seat -> seat + "号").collect(Collectors.joining("、"));
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String makeId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + Long.toString(System.nanoTime(), 16);
    }

    private String toIso(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(APP_ZONE).format(ISO_OFFSET_FORMATTER);
    }

    private LocalDateTime parseDateTime(String value, LocalDateTime fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(APP_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        return fallback;
    }

    private <T> T readJson(String json, TypeReference<T> typeReference, T fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write note json", exception);
        }
    }
}
