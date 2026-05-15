package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("assistant_config")
public class AssistantConfigEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String baseConfig;
    private String providerConfig;
    private String volcengineConfig;
    private String promptConfig;
    private String retrievalConfig;
    private String searchConfig;
    private String safetyConfig;
    private String uiConfig;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getBaseConfig() {
        return baseConfig;
    }

    public void setBaseConfig(String baseConfig) {
        this.baseConfig = baseConfig;
    }

    public String getPromptConfig() {
        return promptConfig;
    }

    public String getProviderConfig() {
        return providerConfig;
    }

    public void setProviderConfig(String providerConfig) {
        this.providerConfig = providerConfig;
    }

    public String getVolcengineConfig() {
        return volcengineConfig;
    }

    public void setVolcengineConfig(String volcengineConfig) {
        this.volcengineConfig = volcengineConfig;
    }

    public void setPromptConfig(String promptConfig) {
        this.promptConfig = promptConfig;
    }

    public String getRetrievalConfig() {
        return retrievalConfig;
    }

    public void setRetrievalConfig(String retrievalConfig) {
        this.retrievalConfig = retrievalConfig;
    }

    public String getSearchConfig() {
        return searchConfig;
    }

    public void setSearchConfig(String searchConfig) {
        this.searchConfig = searchConfig;
    }

    public String getSafetyConfig() {
        return safetyConfig;
    }

    public void setSafetyConfig(String safetyConfig) {
        this.safetyConfig = safetyConfig;
    }

    public String getUiConfig() {
        return uiConfig;
    }

    public void setUiConfig(String uiConfig) {
        this.uiConfig = uiConfig;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
