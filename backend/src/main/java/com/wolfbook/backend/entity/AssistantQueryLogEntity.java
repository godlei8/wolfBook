package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("assistant_query_logs")
public class AssistantQueryLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String openid;
    private String sessionId;
    private String userMessage;
    private String answerType;
    private String hitSources;
    private Integer usedWebSearch;
    private Long latencyMs;
    private Long firstTokenMs;
    private Long embeddingMs;
    private Long retrievalMs;
    private Long modelMs;
    private Long webSearchMs;
    private Integer cacheHit;
    private String fallbackMode;
    private String streamMode;
    private String retrievalMetaJson;
    private Integer success;
    private String failureType;
    private String traceId;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAnswerType() {
        return answerType;
    }

    public void setAnswerType(String answerType) {
        this.answerType = answerType;
    }

    public String getHitSources() {
        return hitSources;
    }

    public void setHitSources(String hitSources) {
        this.hitSources = hitSources;
    }

    public Integer getUsedWebSearch() {
        return usedWebSearch;
    }

    public void setUsedWebSearch(Integer usedWebSearch) {
        this.usedWebSearch = usedWebSearch;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Long getFirstTokenMs() {
        return firstTokenMs;
    }

    public void setFirstTokenMs(Long firstTokenMs) {
        this.firstTokenMs = firstTokenMs;
    }

    public Long getEmbeddingMs() {
        return embeddingMs;
    }

    public void setEmbeddingMs(Long embeddingMs) {
        this.embeddingMs = embeddingMs;
    }

    public Long getRetrievalMs() {
        return retrievalMs;
    }

    public void setRetrievalMs(Long retrievalMs) {
        this.retrievalMs = retrievalMs;
    }

    public Long getModelMs() {
        return modelMs;
    }

    public void setModelMs(Long modelMs) {
        this.modelMs = modelMs;
    }

    public Long getWebSearchMs() {
        return webSearchMs;
    }

    public void setWebSearchMs(Long webSearchMs) {
        this.webSearchMs = webSearchMs;
    }

    public Integer getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Integer cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String getFallbackMode() {
        return fallbackMode;
    }

    public void setFallbackMode(String fallbackMode) {
        this.fallbackMode = fallbackMode;
    }

    public String getStreamMode() {
        return streamMode;
    }

    public void setStreamMode(String streamMode) {
        this.streamMode = streamMode;
    }

    public String getRetrievalMetaJson() {
        return retrievalMetaJson;
    }

    public void setRetrievalMetaJson(String retrievalMetaJson) {
        this.retrievalMetaJson = retrievalMetaJson;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
