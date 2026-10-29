package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** AI 助手可切换的对话模型，接口都按 OpenAI 兼容格式（{baseUrl}/chat/completions）调用。 */
@TableName("assistant_models")
public class AssistantModelEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String baseUrl;
    private String model;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String apiKey;
    private Integer enabled;
    private Integer active;
    private Integer disableThinking;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getActive() { return active; }
    public void setActive(Integer active) { this.active = active; }
    public Integer getDisableThinking() { return disableThinking; }
    public void setDisableThinking(Integer disableThinking) { this.disableThinking = disableThinking; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
