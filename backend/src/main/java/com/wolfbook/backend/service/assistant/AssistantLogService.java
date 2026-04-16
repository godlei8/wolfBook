package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantQueryLogEntity;
import com.wolfbook.backend.mapper.AssistantQueryLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AI 助手查询日志服务。
 *
 * <p>后台用它查看每次提问的耗时、命中来源、是否联网、缓存状态和失败类型，
 * 方便排查“回答不准/检索不准/模型失败”等问题。</p>
 */
@Service
public class AssistantLogService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
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
                        entity.getFirstTokenMs() == null ? 0L : entity.getFirstTokenMs(),
                        entity.getEmbeddingMs() == null ? 0L : entity.getEmbeddingMs(),
                        entity.getRetrievalMs() == null ? 0L : entity.getRetrievalMs(),
                        entity.getModelMs() == null ? 0L : entity.getModelMs(),
                        entity.getWebSearchMs() == null ? 0L : entity.getWebSearchMs(),
                        entity.getCacheHit() != null && entity.getCacheHit() == 1,
                        entity.getFallbackMode(),
                        entity.getStreamMode(),
                        entity.getSuccess() != null && entity.getSuccess() == 1,
                        entity.getFailureType(),
                        entity.getTraceId(),
                        entity.getCreateTime()
                ))
                .toList();
    }

    public AssistantDtos.AdminAiPerformanceView getPerformanceView() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<AssistantQueryLogEntity> logs = assistantQueryLogMapper.selectList(
                new LambdaQueryWrapper<AssistantQueryLogEntity>()
                        .ge(AssistantQueryLogEntity::getCreateTime, since)
                        .orderByDesc(AssistantQueryLogEntity::getCreateTime)
        );
        if (logs.isEmpty()) {
            return new AssistantDtos.AdminAiPerformanceView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long queryCount = logs.size();
        long avgFirstTokenMs = Math.round(logs.stream().mapToLong(item -> valueOf(item.getFirstTokenMs())).average().orElse(0));
        long avgLatencyMs = Math.round(logs.stream().mapToLong(item -> valueOf(item.getLatencyMs())).average().orElse(0));
        long avgRetrievalMs = Math.round(logs.stream().mapToLong(item -> valueOf(item.getRetrievalMs())).average().orElse(0));
        long avgModelMs = Math.round(logs.stream().mapToLong(item -> valueOf(item.getModelMs())).average().orElse(0));
        List<Long> sortedLatency = logs.stream()
                .map(item -> valueOf(item.getLatencyMs()))
                .sorted(Comparator.naturalOrder())
                .toList();
        long p95LatencyMs = sortedLatency.get((int) Math.min(sortedLatency.size() - 1, Math.floor(sortedLatency.size() * 0.95)));

        double ragHitRate = ratio(logs.stream().filter(item -> AssistantConstants.ANSWER_RAG.equals(item.getAnswerType())).count(), queryCount);
        double structuredHitRate = ratio(logs.stream().filter(item -> AssistantConstants.ANSWER_STRUCTURED.equals(item.getAnswerType())).count(), queryCount);
        double webSearchRate = ratio(logs.stream().filter(item -> item.getUsedWebSearch() != null && item.getUsedWebSearch() == 1).count(), queryCount);
        double cacheHitRate = ratio(logs.stream().filter(item -> item.getCacheHit() != null && item.getCacheHit() == 1).count(), queryCount);
        double failureRate = ratio(logs.stream().filter(item -> item.getSuccess() == null || item.getSuccess() != 1).count(), queryCount);
        List<AssistantQueryLogEntity> clarified = logs.stream()
                .filter(item -> readBooleanMeta(item.getRetrievalMetaJson(), "clarificationResolved"))
                .toList();
        double clarifiedAccuracyRate = ratio(
                clarified.stream().filter(item -> item.getSuccess() != null && item.getSuccess() == 1).count(),
                clarified.size()
        );

        return new AssistantDtos.AdminAiPerformanceView(
                queryCount,
                avgFirstTokenMs,
                avgLatencyMs,
                avgRetrievalMs,
                avgModelMs,
                p95LatencyMs,
                ragHitRate,
                structuredHitRate,
                webSearchRate,
                cacheHitRate,
                failureRate,
                clarifiedAccuracyRate
        );
    }

    public int clearLogs() {
        return assistantQueryLogMapper.delete(new LambdaQueryWrapper<>());
    }

    private long valueOf(Long value) {
        return value == null ? 0L : value;
    }

    private double ratio(long matched, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round((matched * 10000.0d) / total) / 100.0d;
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

    private boolean readBooleanMeta(String value, String key) {
        if (value == null || value.isBlank() || key == null || key.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> data = objectMapper.readValue(value, MAP_TYPE);
            Object result = data.get(key);
            if (result instanceof Boolean bool) {
                return bool;
            }
            if (result instanceof String string) {
                return Boolean.parseBoolean(string);
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }
}
