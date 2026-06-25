package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.config.AssistantProperties;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantMessageEntity;
import com.wolfbook.backend.entity.AssistantSessionEntity;
import com.wolfbook.backend.mapper.AssistantMessageMapper;
import com.wolfbook.backend.mapper.AssistantSessionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssistantConversationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantDtos.AssistantCitation>> CITATION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantDtos.RecommendedBoardCard>> BOARD_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AssistantSessionMapper assistantSessionMapper;
    private final AssistantMessageMapper assistantMessageMapper;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;

    public AssistantConversationService(
            AssistantSessionMapper assistantSessionMapper,
            AssistantMessageMapper assistantMessageMapper,
            AssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.assistantSessionMapper = assistantSessionMapper;
        this.assistantMessageMapper = assistantMessageMapper;
        this.assistantProperties = assistantProperties;
        this.objectMapper = objectMapper;
    }

    public List<AssistantDtos.AssistantSessionView> listSessions(String openid) {
        return assistantSessionMapper.selectList(
                        new LambdaQueryWrapper<AssistantSessionEntity>()
                                .eq(AssistantSessionEntity::getOpenid, openid)
                                .orderByDesc(AssistantSessionEntity::getUpdateTime)
                ).stream()
                .map(this::toSessionView)
                .toList();
    }

    public List<AssistantDtos.AssistantMessageView> listMessages(String openid, String sessionId) {
        AssistantSessionEntity session = requireOwnedSession(openid, sessionId);
        return assistantMessageMapper.selectList(
                        new LambdaQueryWrapper<AssistantMessageEntity>()
                                .eq(AssistantMessageEntity::getSessionId, session.getSessionId())
                                .orderByAsc(AssistantMessageEntity::getCreateTime)
                                .orderByAsc(AssistantMessageEntity::getId)
                ).stream()
                .map(this::toMessageView)
                .toList();
    }

    public AssistantSessionEntity resolveSession(String openid, String sessionId, String scene, Map<String, Object> pageContext, String firstMessage) {
        AssistantSessionEntity session = null;
        if (sessionId != null && !sessionId.isBlank()) {
            session = requireOwnedSession(openid, sessionId);
        }
        if (session == null) {
            session = new AssistantSessionEntity();
            session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
            session.setOpenid(openid);
            session.setTitle(titleFrom(firstMessage));
            session.setScene(scene);
            session.setPageContext(write(pageContext == null ? Map.of() : pageContext));
            session.setCreateTime(LocalDateTime.now());
            session.setUpdateTime(LocalDateTime.now());
            assistantSessionMapper.insert(session);
            trimSessions(openid);
            return session;
        }
        session.setScene(scene == null || scene.isBlank() ? session.getScene() : scene);
        if (pageContext != null && !pageContext.isEmpty()) {
            session.setPageContext(write(pageContext));
        }
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle(titleFrom(firstMessage));
        }
        session.setUpdateTime(LocalDateTime.now());
        assistantSessionMapper.updateById(session);
        return session;
    }

    public AssistantMessageEntity saveUserMessage(String sessionId, String content) {
        return saveMessage(sessionId, "USER", content, null, List.of(), List.of(), List.of(), false, null);
    }

    public AssistantMessageEntity saveAssistantMessage(
            String sessionId,
            String content,
            String answerType,
            List<AssistantDtos.AssistantCitation> citations,
            List<AssistantDtos.RecommendedBoardCard> boards,
            List<String> suggestedQuestions,
            boolean usedWebSearch,
            String traceId
    ) {
        return saveMessage(sessionId, "ASSISTANT", content, answerType, citations, boards, suggestedQuestions, usedWebSearch, traceId);
    }

    public void resetSession(String openid, String sessionId) {
        requireOwnedSession(openid, sessionId);
        assistantMessageMapper.delete(new LambdaQueryWrapper<AssistantMessageEntity>().eq(AssistantMessageEntity::getSessionId, sessionId));
        assistantSessionMapper.deleteById(sessionId);
    }

    public List<String> recentContextMessages(String openid, String sessionId) {
        requireOwnedSession(openid, sessionId);
        return assistantMessageMapper.selectList(
                        new LambdaQueryWrapper<AssistantMessageEntity>()
                                .eq(AssistantMessageEntity::getSessionId, sessionId)
                                .orderByDesc(AssistantMessageEntity::getCreateTime)
                                .orderByDesc(AssistantMessageEntity::getId)
                                .last("LIMIT " + assistantProperties.getHistoryWindow())
                ).stream()
                .sorted(Comparator.comparing(AssistantMessageEntity::getCreateTime).thenComparing(AssistantMessageEntity::getId))
                .map(message -> message.getRole() + ": " + message.getContent())
                .toList();
    }

    private AssistantMessageEntity saveMessage(
            String sessionId,
            String role,
            String content,
            String answerType,
            List<AssistantDtos.AssistantCitation> citations,
            List<AssistantDtos.RecommendedBoardCard> boards,
            List<String> suggestedQuestions,
            boolean usedWebSearch,
            String traceId
    ) {
        AssistantMessageEntity entity = new AssistantMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setAnswerType(answerType);
        entity.setCitations(write(citations));
        entity.setRecommendedBoards(write(boards));
        entity.setSuggestedQuestions(write(suggestedQuestions));
        entity.setUsedWebSearch(usedWebSearch ? 1 : 0);
        entity.setTraceId(traceId);
        entity.setCreateTime(LocalDateTime.now());
        assistantMessageMapper.insert(entity);

        assistantSessionMapper.update(null, new LambdaUpdateWrapper<AssistantSessionEntity>()
                .set(AssistantSessionEntity::getUpdateTime, LocalDateTime.now())
                .eq(AssistantSessionEntity::getSessionId, sessionId));
        return entity;
    }

    private void trimSessions(String openid) {
        List<AssistantSessionEntity> sessions = assistantSessionMapper.selectList(
                new LambdaQueryWrapper<AssistantSessionEntity>()
                        .eq(AssistantSessionEntity::getOpenid, openid)
                        .orderByDesc(AssistantSessionEntity::getUpdateTime)
        );
        if (sessions.size() <= assistantProperties.getMaxSessionsPerUser()) {
            return;
        }
        sessions.stream()
                .skip(assistantProperties.getMaxSessionsPerUser())
                .forEach(session -> {
                    assistantMessageMapper.delete(new LambdaQueryWrapper<AssistantMessageEntity>().eq(AssistantMessageEntity::getSessionId, session.getSessionId()));
                    assistantSessionMapper.deleteById(session.getSessionId());
                });
    }

    private AssistantSessionEntity requireOwnedSession(String openid, String sessionId) {
        AssistantSessionEntity session = assistantSessionMapper.selectById(sessionId);
        if (session == null || !openid.equals(session.getOpenid())) {
            throw new ApiException(4004, "Assistant session not found");
        }
        return session;
    }

    private AssistantDtos.AssistantSessionView toSessionView(AssistantSessionEntity entity) {
        return new AssistantDtos.AssistantSessionView(
                entity.getSessionId(),
                entity.getTitle(),
                entity.getScene(),
                readMap(entity.getPageContext()),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }

    private AssistantDtos.AssistantMessageView toMessageView(AssistantMessageEntity entity) {
        return new AssistantDtos.AssistantMessageView(
                entity.getId(),
                entity.getRole(),
                entity.getContent(),
                "ASSISTANT".equals(entity.getRole()) ? AssistantConstants.CONTENT_MARKDOWN : AssistantConstants.CONTENT_PLAIN,
                entity.getAnswerType(),
                entity.getTraceId(),
                entity.getCreateTime()
        );
    }

    private String titleFrom(String message) {
        String normalized = message == null ? "" : message.trim();
        if (normalized.isEmpty()) {
            return "新对话";
        }
        return normalized.length() > 20 ? normalized.substring(0, 20) : normalized;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(5000, "Failed to write assistant json");
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
