package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("judge_action_events")
public class JudgeActionEventEntity {

    @TableId(type = IdType.INPUT)
    private String eventId;
    private String roomId;
    private Integer dayNo;
    private String phase;
    private String actorPlayerId;
    private String targetPlayerIdsJson;
    private String actionType;
    private String payloadJson;
    private String resultPayloadJson;
    private String visibility;
    private LocalDateTime createTime;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Integer getDayNo() {
        return dayNo;
    }

    public void setDayNo(Integer dayNo) {
        this.dayNo = dayNo;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getActorPlayerId() {
        return actorPlayerId;
    }

    public void setActorPlayerId(String actorPlayerId) {
        this.actorPlayerId = actorPlayerId;
    }

    public String getTargetPlayerIdsJson() {
        return targetPlayerIdsJson;
    }

    public void setTargetPlayerIdsJson(String targetPlayerIdsJson) {
        this.targetPlayerIdsJson = targetPlayerIdsJson;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getResultPayloadJson() {
        return resultPayloadJson;
    }

    public void setResultPayloadJson(String resultPayloadJson) {
        this.resultPayloadJson = resultPayloadJson;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
