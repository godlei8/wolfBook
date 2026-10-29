package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantMessageEntity;
import com.wolfbook.backend.entity.AssistantQueryLogEntity;
import com.wolfbook.backend.mapper.AssistantMessageMapper;
import com.wolfbook.backend.mapper.AssistantQueryLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AssistantLogService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /** 后台一次最多看最近这么多条，日志表会一直涨 */
    static final int MAX_LOGS = 500;

    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final AssistantMessageMapper assistantMessageMapper;
    private final ObjectMapper objectMapper;

    public AssistantLogService(AssistantQueryLogMapper assistantQueryLogMapper, AssistantMessageMapper assistantMessageMapper, ObjectMapper objectMapper) {
        this.assistantQueryLogMapper = assistantQueryLogMapper;
        this.assistantMessageMapper = assistantMessageMapper;
        this.objectMapper = objectMapper;
    }

    public List<AssistantDtos.AdminQueryLogView> listLogs() {
        List<AssistantQueryLogEntity> logs = assistantQueryLogMapper.selectList(new LambdaQueryWrapper<AssistantQueryLogEntity>()
                .orderByDesc(AssistantQueryLogEntity::getCreateTime)
                .orderByDesc(AssistantQueryLogEntity::getId)
                .last("LIMIT " + MAX_LOGS));
        List<String> traceIds = logs.stream().map(AssistantQueryLogEntity::getTraceId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> answers = traceIds.isEmpty() ? Map.of() : assistantMessageMapper.selectList(new LambdaQueryWrapper<AssistantMessageEntity>()
                        .in(AssistantMessageEntity::getTraceId, traceIds)
                        .eq(AssistantMessageEntity::getRole, "ASSISTANT"))
                .stream()
                .filter(message -> message.getContent() != null)
                .collect(Collectors.toMap(AssistantMessageEntity::getTraceId, AssistantMessageEntity::getContent, (left, right) -> left));
        return logs.stream()
                .map(entity -> new AssistantDtos.AdminQueryLogView(
                        entity.getId(),
                        entity.getOpenid(),
                        entity.getSessionId(),
                        entity.getUserMessage(),
                        entity.getAnswerType(),
                        readHitSources(entity.getHitSources()),
                        entity.getUsedWebSearch() != null && entity.getUsedWebSearch() == 1,
                        entity.getLatencyMs() == null ? 0L : entity.getLatencyMs(),
                        entity.getSuccess() != null && entity.getSuccess() == 1,
                        entity.getFailureType(),
                        entity.getTraceId(),
                        entity.getCreateTime(),
                        entity.getTraceId() == null ? null : answers.get(entity.getTraceId())
                ))
                .toList();
    }

    public int clearLogs() {
        return assistantQueryLogMapper.delete(new LambdaQueryWrapper<>());
    }

    private List<String> readHitSources(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
