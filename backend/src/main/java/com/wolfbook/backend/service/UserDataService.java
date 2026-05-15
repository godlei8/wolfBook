package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserDataService {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserService userService;
    private final BoardService boardService;
    private final UserFavoriteBoardMapper userFavoriteBoardMapper;
    private final UserNoteSessionMapper userNoteSessionMapper;
    private final UserNoteRecordMapper userNoteRecordMapper;

    public UserDataService(
            UserService userService,
            BoardService boardService,
            UserFavoriteBoardMapper userFavoriteBoardMapper,
            UserNoteSessionMapper userNoteSessionMapper,
            UserNoteRecordMapper userNoteRecordMapper
    ) {
        this.userService = userService;
        this.boardService = boardService;
        this.userFavoriteBoardMapper = userFavoriteBoardMapper;
        this.userNoteSessionMapper = userNoteSessionMapper;
        this.userNoteRecordMapper = userNoteRecordMapper;
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
            entity.setCreateTime(LocalDateTime.now());
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
        String boardName = request.boardName().trim();
        if ("library".equals(boardMode)) {
            validateBoardId(request.boardId());
            boardId = request.boardId();
            boardName = boardService.getBoard(boardId).name();
        }

        UserNoteSessionEntity existing = userNoteSessionMapper.selectById(resolvedSessionId);
        if (existing != null && !openid.equals(existing.getOpenid())) {
            throw new ApiException(4004, "对局不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        UserNoteSessionEntity entity = existing == null ? new UserNoteSessionEntity() : existing;
        entity.setSessionId(resolvedSessionId);
        entity.setOpenid(openid);
        entity.setBoardMode(boardMode);
        entity.setBoardId(boardId);
        entity.setBoardName(boardName);
        entity.setPlayerCount(Math.max(1, request.playerCount()));
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

        List<WolfbookDtos.NoteRecordInput> recordInputs = request.records() == null ? List.of() : request.records();
        for (WolfbookDtos.NoteRecordInput recordInput : recordInputs) {
            UserNoteRecordEntity recordEntity = new UserNoteRecordEntity();
            recordEntity.setRecordId(recordInput.id());
            recordEntity.setSessionId(resolvedSessionId);
            recordEntity.setOpenid(openid);
            recordEntity.setRecordType(recordInput.type());
            recordEntity.setDayNo(Math.max(1, recordInput.round()));
            recordEntity.setContent(recordInput.content().trim());
            recordEntity.setPlayer(recordInput.player());
            recordEntity.setCreateTime(parseDateTime(recordInput.timestamp(), now));
            userNoteRecordMapper.insert(recordEntity);
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
                .sorted(Comparator.comparing(UserNoteRecordEntity::getCreateTime))
                .map(record -> new WolfbookDtos.NoteRecordView(
                        record.getRecordId(),
                        record.getRecordType(),
                        record.getDayNo(),
                        record.getContent(),
                        record.getPlayer(),
                        record.getCreateTime()
                ))
                .toList();
        return new WolfbookDtos.NoteSessionView(
                session.getSessionId(),
                session.getBoardMode(),
                session.getBoardId(),
                session.getBoardName(),
                session.getPlayerCount(),
                session.getCreateTime(),
                session.getUpdateTime(),
                recordViews
        );
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
}
