package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantQueryLogEntity;
import com.wolfbook.backend.mapper.AssistantQueryLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssistantLogService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AssistantQueryLogMapper assistantQueryLogMapper;
    private final ObjectMapper objectMapper;

    public AssistantLogService(AssistantQueryLogMapper assistantQueryLogMapper, ObjectMapper objectMapper) {
        this.assistantQueryLogMapper = assistantQueryLogMapper;
        this.objectMapper = objectMapper;
    }

    public List<AssistantDtos.AdminQueryLogView> listLogs() {
        return assistantQueryLogMapper.selectList(new LambdaQueryWrapper<AssistantQueryLogEntity>().orderByDesc(AssistantQueryLogEntity::getCreateTime))
                .stream()
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
                        entity.getCreateTime()
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
